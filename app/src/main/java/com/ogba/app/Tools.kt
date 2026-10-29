package com.ogba.app

import android.content.Context
import com.ogba.app.data.AppDao
import com.ogba.app.data.Note
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** Structured action request, mirrors open-grok-bot WorkspaceToolCall / action gateway contract. */
data class ToolCall(val tool: String, val arg: String = "", val content: String = "") {
    val summary: String get() = when (tool) {
        "workspace.list" -> "List files in workspace path '${arg.ifEmpty { "." }}'"
        "workspace.read" -> "Read workspace file '$arg'"
        "workspace.write" -> "Write ${content.length} chars to workspace file '$arg'"
        "local.calc" -> "Evaluate expression: $arg"
        "local.time" -> "Read the device clock"
        "local.note" -> "Save note: $arg"
        "local.notes" -> "Read saved notes"
        else -> tool
    }
}
class ToolError(msg: String) : Exception(msg)

/** Deny-by-default registry: only these actions exist, every one requires user approval. */
object Tools {
    val registry = setOf("workspace.list", "workspace.read", "workspace.write", "local.calc", "local.time", "local.note", "local.notes")
    private val modelRe = Regex("""\[\[tool:([\w.]+)\s*(.*?)]]""", RegexOption.DOT_MATCHES_ALL)

    const val INSTRUCTIONS = "Local tools (each needs the user's approval): to request one, reply with ONLY a single line such as " +
        "[[tool:calc 12*(3+4)]], [[tool:time]], [[tool:note TEXT]], [[tool:notes]], [[tool:workspace.list .]] or [[tool:workspace.read FILE]]. " +
        "When a line starting with 'Action result' is in your instructions, use it to answer the user normally."

    /** Port of parse_workspace_command plus local slash tools. Returns null if not a command. */
    fun parseUserCommand(prompt: String): ToolCall? {
        val text = prompt.trim()
        val lower = text.lowercase()
        if (lower.startsWith("/workspace")) {
            val lines = text.split("\n", limit = 2)
            val header = lines[0].trim().split(Regex("\\s+"))
            if (header.size < 2 || header[0].lowercase() != "/workspace") throw ToolError("Use /workspace list, /workspace read, or /workspace write.")
            val action = header[1].lowercase()
            if (action !in setOf("list", "read", "write")) throw ToolError("Supported workspace actions are list, read, and write.")
            if (action == "write") {
                if (header.size != 3 || lines.size != 2 || lines[1].isEmpty()) throw ToolError("Write syntax: /workspace write <path> followed by file content on the next line.")
                return ToolCall("workspace.write", header[2], lines[1])
            }
            if (header.size > 3) throw ToolError("${action.replaceFirstChar { it.uppercase() }} accepts one path argument.")
            return ToolCall("workspace.$action", if (header.size == 3) header[2] else ".")
        }
        val m = Regex("^/(calc|time|note|notes)\\b\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).find(text) ?: return null
        return ToolCall("local." + m.groupValues[1].lowercase(), m.groupValues[2].trim())
    }

    fun parseModelRequest(text: String): ToolCall? {
        val m = modelRe.find(text) ?: return null
        var name = m.groupValues[1].lowercase()
        if (!name.contains('.')) name = "local.$name"
        return if (name in registry && name != "workspace.write") ToolCall(name, m.groupValues[2].trim()) else null
    }

    fun workspaceRoot(ctx: Context) = File(ctx.filesDir, "workspace").apply { mkdirs() }

    private fun resolve(root: File, path: String): File {
        val f = File(root, path.ifEmpty { "." }).canonicalFile
        if (!f.path.startsWith(root.canonicalPath)) throw ToolError("Path escapes the workspace root.")
        return f
    }

    suspend fun execute(ctx: Context, call: ToolCall, dao: AppDao): String {
        if (call.tool !in registry) throw ToolError("Action is not registered: ${call.tool}")
        val root = workspaceRoot(ctx)
        return when (call.tool) {
            "workspace.list" -> {
                val d = resolve(root, call.arg)
                if (!d.isDirectory) throw ToolError("Not a directory: ${call.arg}")
                val entries = d.listFiles()?.sortedBy { it.name }?.joinToString(", ") { (if (it.isDirectory) "${it.name}/" else "${it.name} (${it.length()} B)") }
                "{\"path\":\"${call.arg}\",\"entries\":[${entries ?: ""}]}"
            }
            "workspace.read" -> {
                val f = resolve(root, call.arg)
                if (!f.isFile) throw ToolError("File not found: ${call.arg}")
                if (f.length() > 64_000) throw ToolError("File is too large to read (limit 64 KB).")
                "{\"path\":\"${call.arg}\",\"content\":\"${f.readText()}\"}"
            }
            "workspace.write" -> {
                val f = resolve(root, call.arg); f.parentFile?.mkdirs(); f.writeText(call.content)
                "{\"path\":\"${call.arg}\",\"bytes_written\":${call.content.toByteArray().size}}"
            }
            "local.calc" -> try { "{\"expression\":\"${call.arg}\",\"value\":${fmt(Calc(call.arg).parse())}}" } catch (e: Exception) { throw ToolError("calc: ${e.message}") }
            "local.time" -> "{\"now\":\"" + SimpleDateFormat("EEEE, MMMM dd, yyyy 'at' hh:mm a", Locale.US).format(Date()) + "\",\"timezone\":\"" + TimeZone.getDefault().id + "\"}"
            "local.note" -> { if (call.arg.isBlank()) throw ToolError("Note text is empty."); dao.insertNote(Note(text = call.arg)); "{\"saved\":\"${call.arg}\"}" }
            "local.notes" -> "{\"notes\":[" + dao.notesNow().take(20).joinToString(", ") { "\"${it.text}\"" } + "]}"
            else -> throw ToolError("Unknown action")
        }
    }

    private fun fmt(d: Double) = if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    private class Calc(s: String) {
        private val s = s.replace(" ", "").replace("×", "*").replace("÷", "/").map { c ->
            if (c in '۰'..'۹') '0' + (c - '۰') else if (c in '٠'..'٩') '0' + (c - '٠') else c }.joinToString("")
        private var i = 0
        fun parse(): Double { val v = expr(); if (i != s.length) error("unexpected '${s[i]}'"); return v }
        private fun expr(): Double { var v = term(); while (i < s.length && (s[i] == '+' || s[i] == '-')) { val o = s[i++]; val t = term(); v = if (o == '+') v + t else v - t }; return v }
        private fun term(): Double { var v = pow(); while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) { val o = s[i++]; val t = pow(); v = when (o) { '*' -> v * t; '/' -> v / t; else -> v % t } }; return v }
        private fun pow(): Double { val b = unary(); return if (i < s.length && s[i] == '^') { i++; Math.pow(b, pow()) } else b }
        private fun unary(): Double = if (i < s.length && s[i] == '-') { i++; -unary() } else if (i < s.length && s[i] == '+') { i++; unary() } else atom()
        private fun atom(): Double {
            if (i < s.length && s[i] == '(') { i++; val v = expr(); if (i >= s.length || s[i] != ')') error("missing )"); i++; return v }
            val st = i
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '.')) i++
            if (st == i) error("number expected")
            return s.substring(st, i).toDouble()
        }
    }
}
