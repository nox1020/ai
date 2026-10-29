package com.ogba.app

import java.text.SimpleDateFormat
import java.util.*

/** Same prompt assembly as open-grok-bot server/app/routers/chat.py stream_turn(). */
object Prompts {
    fun system(raw: String?): String {
        val now = SimpleDateFormat("EEEE, MMMM dd, yyyy 'at about' h a", Locale.US).format(Date())
        // Hour granularity keeps the prompt prefix stable so llama.cpp can reuse the KV cache between turns.
        return "Current Date & Time: $now.\n\n${raw ?: "You are a helpful AI assistant."}"
    }

    const val VOICE_STYLE = "VOICE MODE: your reply is spoken aloud by text-to-speech. Answer in 1-3 short, casual spoken sentences, no markdown, no lists, no emojis, same language as the user."
}
