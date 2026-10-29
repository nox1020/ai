package com.ogba.app

import android.app.Application
import com.ogba.app.data.AppDb
import com.ogba.app.data.Bot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Default roster ported 1:1 from open-grok-bot storage_service._ensure_defaults (model swapped to local GGUF). */
val DEFAULT_BOTS = listOf(
    Bot(name = "Grok 4.5 Analyst", role = "Real-time Reasoning & Intelligence",
        description = "Runs on the on-device model. Deep analysis & precise technical problem solving.",
        avatar = "🚀", accentColor = "#3b82f6", pinned = true, tools = "calc,time,notes",
        systemPrompt = "You are Grok 4.5, an advanced AI analyst. Provide direct, highly accurate, and helpful answers with real-time insight."),
    Bot(name = "Claude Architect", role = "Full-Stack Code & Refactoring",
        description = "Specialized in software design, clean architectural patterns, and elegant code.",
        avatar = "⚡", accentColor = "#d97757", pinned = true, tools = "workspace",
        systemPrompt = "You are Claude Architect, an expert software engineer. Write clean, modular, modern code."),
    Bot(name = "Codex Builder", role = "Automated Execution & Workflows",
        description = "Manages the local sandboxed workspace through approval-gated actions.",
        avatar = "💻", accentColor = "#10b981", tools = "workspace,approval_broker",
        systemPrompt = "You are Codex Builder, a devops and execution specialist. Help run commands safely."),
    Bot(name = "Grok Connector Assistant", role = "General Assistant & App Connector",
        description = "Friendly assistant using local tools (notes, time, calculator, workspace).",
        avatar = "🐭", accentColor = "#a855f7", tools = "notes,dictation",
        systemPrompt = "You are a super-powered assistant. Be friendly, fast, and proactive."),
)

/** Casual, spoken-style companion used by voice mode by default. */
val VOICE_BOT = Bot(name = "OGBA Voice", role = "Hands-free voice companion", avatar = "🎙️", accentColor = "#f43f5e", pinned = true,
    description = "Talk naturally; short spoken replies in Persian or English.",
    systemPrompt = "You are OGBA, a witty, warm voice companion running entirely on the user's phone. " +
        "You are talking out loud: keep replies short (one to three sentences), casual and natural, like a friend chatting. " +
        "No markdown, no lists, no emojis, no code unless asked. Always answer in the same language the user speaks (Persian or English). " +
        "Ask a quick follow-up question when it keeps the conversation going.")

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        RoutineWorker.createChannel(this)
        DownloadWorker.createChannel(this)
        com.ogba.app.llm.LlmEngine.configure(this)
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDb.get(this@App).dao()
            if (dao.botCount() == 0) DEFAULT_BOTS.forEach { dao.insertBot(it) }
            if (dao.botByName(VOICE_BOT.name) == null) dao.insertBot(VOICE_BOT)
        }
    }
}
