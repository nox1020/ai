package com.ogba.app

import android.app.Application
import com.ogba.app.data.AppDb
import com.ogba.app.data.Bot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        RoutineWorker.createChannel(this)
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDb.get(this@App).dao()
            if (dao.botCount() == 0) {
                dao.upsertBot(Bot(name = "OGBA", systemPrompt = "You are OGBA, a helpful, concise offline assistant running fully on the user's phone. Reply in the user's language (Persian or English)."))
                dao.upsertBot(Bot(name = "Writer", systemPrompt = "You are a creative writing assistant. Help draft, edit and improve text."))
                dao.upsertBot(Bot(name = "Coder", systemPrompt = "You are a senior software engineer. Give short, correct code answers."))
            }
        }
    }
}
