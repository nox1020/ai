package com.ogba.app

import com.ogba.app.data.AppDao
import com.ogba.app.data.Note
import java.text.SimpleDateFormat
import java.util.*

data class ToolCall(val name: String, val arg: String)

/** Local, deny-by-default tools. Every call is shown to the user for approval before running. */
object Tools {
    val names = setOf("calc", "time", "note", "notes")
    private val re = Regex("""\[\[tool:(\w+)\s*(.*?)]]""", RegexOption.DOT_MATCHES_ALL)

    const val INSTRUCTIONS = "You can use local tools. To use one, reply with ONLY one line like: " +
        "[[tool:calc 12*(3+4)]] or [[tool:time]] or [[tool:note TEXT TO SAVE]] or [[tool:notes]]. " +
        "After a tool result is given to you, answer the user normally."

    fun parse(text: String): ToolCall? {
        val m = re.find(text) ?: return null
        val n = m.groupValues[1].lowercase()
        return if (n in names) ToolCall(n, m.groupValues[2].trim()) else null
    }

    suspend fun run(call: ToolCall, dao: AppDao): String = when (call.name) {
        "calc" -> try { "calc(${call.arg}) = " + fmt(Calc(call.arg).parse()) } catch (e: Exception) { "calc error: ${e.message}" }
        "time" -> "Current local time: " + SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", Locale.US).format(Date()) + " (" + TimeZone.getDefault().id + ")"
        "note" -> { dao.insertNote(Note(text = call.arg)); "Saved note: ${call.arg}" }
        "notes" -> dao.notesNow().take(20).joinToString("\n") { "- " + it.text }.ifEmpty { "No notes." }
        else -> "Unknown tool"
    }

    private fun fmt(d: Double) = if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    private class Calc(s: String) {
        private val s = s.replace(" ", "").replace("×", "*").replace("÷", "/")
        private var i = 0
        fun parse(): Double { val v = expr(); if (i != s.length) error("unexpected '${s[i]}'"); return v }
        private fun expr(): Double { var v = term(); while (i < s.length && (s[i] == '+' || s[i] == '-')) { val o = s[i++]; val t = term(); v = if (o == '+') v + t else v - t }; return v }
        private fun term(): Double { var v = pow(); while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) { val o = s[i++]; val t = pow(); v = when (o) { '*' -> v * t; '/' -> v / t; else -> v % t } }; return v }
        private fun pow(): Double { val b = unary(); return if (i < s.length && s[i] == '^') { i++; Math.pow(b, pow()) } else b }
        private fun unary(): Double = if (i < s.length && s[i] == '-') { i++; -unary() } else if (i < s.length && s[i] == '+') { i++; unary() } else atom()
        private fun atom(): Double {
            if (i < s.length && s[i] == '(') { i++; val v = expr(); if (i >= s.length || s[i] != ')') error("missing )"); i++; return v }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (st == i) error("number expected")
            return s.substring(st, i).toDouble()
        }
    }
}
