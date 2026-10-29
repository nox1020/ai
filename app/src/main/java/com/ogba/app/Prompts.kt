package com.ogba.app

import java.text.SimpleDateFormat
import java.util.*

/** Same prompt assembly as open-grok-bot server/app/routers/chat.py stream_turn(). */
object Prompts {
    fun system(raw: String?): String {
        val now = SimpleDateFormat("EEEE, MMMM dd, yyyy 'at' hh:mm a", Locale.US).format(Date())
        return "Current Date & Time: $now.\n\n${raw ?: "You are a helpful AI assistant."}"
    }
}
