package com.ogba.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.ogba.app.data.AppDb
import com.ogba.app.data.Message
import com.ogba.app.data.Routine
import com.ogba.app.llm.LlmEngine
import java.util.concurrent.TimeUnit

object RoutineScheduler {
    fun schedule(ctx: Context, r: Routine) {
        val req = PeriodicWorkRequestBuilder<RoutineWorker>(maxOf(15L, r.intervalMinutes), TimeUnit.MINUTES)
            .setInputData(workDataOf("id" to r.id)).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("routine-${r.id}", ExistingPeriodicWorkPolicy.UPDATE, req)
    }
    fun runNow(ctx: Context, r: Routine) {
        WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<RoutineWorker>().setInputData(workDataOf("id" to r.id)).build())
    }
    fun cancel(ctx: Context, r: Routine) = WorkManager.getInstance(ctx).cancelUniqueWork("routine-${r.id}")
}

/** Runs a routine prompt against the local model, stores the result in the bot's chat and notifies. */
class RoutineWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val dao = AppDb.get(applicationContext).dao()
        val r = dao.routine(inputData.getLong("id", -1)) ?: return Result.success()
        val bot = dao.bot(r.botId) ?: return Result.success()
        val model = Models.selected(applicationContext) ?: return Result.success()
        val mem = dao.memoriesNow(bot.id).joinToString("\n") { "- " + it.text }
        val sys = bot.systemPrompt + (if (mem.isNotEmpty()) "\nKnown facts about the user:\n$mem" else "")
        val out = try {
            LlmEngine.generate(model, listOf("system" to sys, "user" to r.prompt), 384)
        } catch (e: Exception) { return Result.retry() }
        dao.insertMessage(Message(botId = bot.id, role = "assistant", content = "⏰ ${r.name}\n\n$out"))
        notify(applicationContext, "${bot.name} · ${r.name}", out)
        return Result.success()
    }

    companion object {
        const val CHANNEL = "routines"
        fun createChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Routines", NotificationManager.IMPORTANCE_DEFAULT))
        }
        fun notify(ctx: Context, title: String, text: String) {
            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title).setContentText(text.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).build()
            NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % 100000).toInt(), n)
        }
    }
}
