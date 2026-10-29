package com.ogba.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/** Network, computer and Android device tools. */
object Device {
    suspend fun execute(ctx: Context, call: ToolCall): String = when (call.tool) {
        "web.search" -> withContext(Dispatchers.IO) { Web.search(call.arg) }
        "web.fetch" -> withContext(Dispatchers.IO) { Web.fetch(call.arg) }
        "browser.open" -> Browser.open(call.arg)
        "browser.read" -> Browser.read()
        "browser.links" -> Browser.links()
        "browser.click" -> Browser.click(call.arg)
        "browser.type" -> Browser.type(call.arg)
        "browser.back" -> Browser.back()
        "intent.open_url" -> {
            val safe = withContext(Dispatchers.IO) { Web.safeUrl(call.arg).toString() }
            withContext(Dispatchers.Main) { intents(ctx, call.copy(arg = safe)) }
        }
        "shell.run" -> { if (call.arg.isBlank()) throw ToolError("Empty command."); Sandbox.run(ctx, call.arg) }
        else -> withContext(Dispatchers.Main) { intents(ctx, call) }
    }

    private fun start(ctx: Context, i: Intent) {
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { throw ToolError("No app can handle this: ${e.message}") }
    }

    private fun intents(ctx: Context, call: ToolCall): String = when (call.tool) {
        "intent.open_url" -> { start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(call.arg))); "Opened ${call.arg}" }
        "intent.share" -> { start(ctx, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, call.arg), "Share")); "Share sheet opened" }
        "clipboard.read" -> (ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: "").ifEmpty { "(clipboard empty)" }.take(2000)
        "clipboard.write" -> { ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OGBA", call.arg)); "Copied ${call.arg.length} chars" }
        "alarm.set" -> {
            val m = Regex("""(\d{1,2}):(\d{2})\s*(.*)""").find(call.arg) ?: throw ToolError("Use: alarm.set HH:MM label")
            val hour = m.groupValues[1].toInt(); val minute = m.groupValues[2].toInt()
            if (hour !in 0..23 || minute !in 0..59) throw ToolError("Alarm time must be 00:00 to 23:59")
            start(ctx, Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute).putExtra(AlarmClock.EXTRA_MESSAGE, m.groupValues[3].ifBlank { "OGBA" }))
            "Alarm requested for ${m.groupValues[1]}:${m.groupValues[2]}"
        }
        "calendar.add" -> {
            val parts = call.arg.split("|").map { it.trim() }
            val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, parts[0])
            parts.getOrNull(1)?.let { d ->
                val t = try { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(d)?.time } catch (e: Exception) { null } ?: throw ToolError("Date must be yyyy-MM-dd HH:mm")
                i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, t).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, t + 3600_000)
            }
            start(ctx, i); "Calendar editor opened for '${parts[0]}'"
        }
        else -> throw ToolError("Unknown action ${call.tool}")
    }
}
