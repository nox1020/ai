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
        "web.search" -> "Search the web (DuckDuckGo): $arg"
        "web.fetch" -> "Download and read page: $arg"
        "browser.open" -> "Open in the computer's browser: $arg"
        "browser.read" -> "Read the page open in the browser"
        "shell.run" -> "Run shell command on OGBA computer: $arg"
        "intent.open_url" -> "Open URL in another app: $arg"
        "intent.share" -> "Share text via Android share sheet"
        "clipboard.read" -> "Read the clipboard"
        "clipboard.write" -> "Copy text to clipboard"
        "alarm.set" -> "Set an alarm: $arg"
        "calendar.add" -> "Add a calendar event: $arg"
        else -> tool
    }
}
class ToolError(msg: String) : Exception(msg)

/** Deny-by-default registry: only these actions exist, every one requires user approval. */
object Tools {
    val registry = setOf("workspace.list", "workspace.read", "workspace.write", "local.calc", "local.time", "local.note", "local.notes",
        "web.search", "web.fetch", "browser.open", "browser.read", "shell.run", "intent.open_url", "intent.share",
        "clipboard.read", "clipboard.write", "alarm.set", "calendar.add")
    /** Side-effect-free actions; may auto-run when the "auto-run reads" setting is on. */
    val readOnly = setOf("workspace.list", "workspace.read", "local.calc", "local.time", "local.notes", "web.search", "web.fetch", "browser.open", "browser.read")
    private val aliases = mapOf("calc" to "local.calc", "time" to "local.time", "note" to "local.note", "notes" to "local.notes",
        "search" to "web.search", "fetch" to "web.fetch", "shell" to "shell.run", "open_url" to "intent.open_url", "share" to "intent.share")
    private val modelRe = Regex("""\[\[tool:([\w.]+)\s*(.*?)]]""", RegexOption.DOT_MATCHES_ALL)

    const val INSTRUCTIONS = """You are an agent on the user's phone. You may use tools, one per reply. To use a tool, write a one-line Plan, then on the last line ONLY the call, e.g.:
Plan: search the web for the weather.
[[tool:web.search weather in Tehran today]]
Tools: web.search QUERY | web.fetch URL | browser.open URL | browser.read | shell.run COMMAND (Alpine Linux sandbox, apk add available) | workspace.list PATH | workspace.read FILE | workspace.write FILE then the content on the next lines | calc EXPR | time | note TEXT | notes | intent.open_url URL | intent.share TEXT | clipboard.read | clipboard.write TEXT | alarm.set HH:MM LABEL | calendar.add TITLE | yyyy-MM-dd HH:mm
After each call you receive 'Observation (tool): ...'. Repeat until you can answer, then reply normally WITHOUT any [[tool:...]]. Never invent observations. If no tool is needed, just answer."""

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
        val m = Regex("^/(calc|time|note|notes|search|fetch|browse|sh)\\b\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).find(text) ?: return null
        val arg = m.groupValues[2].trim()
        return when (val c = m.groupValues[1].lowercase()) {
            "search" -> ToolCall("web.search", arg); "fetch" -> ToolCall("web.fetch", arg)
            "browse" -> ToolCall("browser.open", arg); "sh" -> ToolCall("shell.run", arg)
            else -> ToolCall("local.$c", arg)
        }
    }

    fun parseModelRequest(text: String): ToolCall? {
        val m = modelRe.find(text) ?: return null
        var name = m.groupValues[1].lowercase()
        name = aliases[name] ?: name
        if (name !in registry) return null
        val arg = m.groupValues[2].trim()
        if (name == "workspace.write") { val l = arg.split("\n", limit = 2); return ToolCall(name, l[0].trim(), l.getOrElse(1) { "" }) }
        return ToolCall(name, arg)
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
                "{\"path\":\"${call.arg}\",\"content\":\"${f.readText().take(3000)}\"}"
            }
            "workspace.write" -> {
                val f = resolve(root, call.arg); f.parentFile?.mkdirs(); f.writeText(call.content)
                "{\"path\":\"${call.arg}\",\"bytes_written\":${call.content.toByteArray().size}}"
            }
            "local.calc" -> try { "{\"expression\":\"${call.arg}\",\"value\":${fmt(Calc(call.arg).parse())}}" } catch (e: Exception) { throw ToolError("calc: ${e.message}") }
            "local.time" -> "{\"now\":\"" + SimpleDateFormat("EEEE, MMMM dd, yyyy 'at' hh:mm a", Locale.US).format(Date()) + "\",\"timezone\":\"" + TimeZone.getDefault().id + "\"}"
            "local.note" -> { if (call.arg.isBlank()) throw ToolError("Note text is empty."); dao.insertNote(Note(text = call.arg)); "{\"saved\":\"${call.arg}\"}" }
            "local.notes" -> "{\"notes\":[" + dao.notesNow().take(20).joinToString(", ") { "\"${it.text}\"" } + "]}"
            else -> Device.execute(ctx, call)
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
