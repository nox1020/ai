package com.ogba.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

data class CatalogModel(val id: String, val title: String, val sizeMb: Int, val ramGb: Int, val url: String, val note: String = "") {
    val fileName get() = url.substringAfterLast('/')
}

object Models {
    private const val HF = "https://huggingface.co/"
    val catalog = listOf(
        CatalogModel("qwen05", "Qwen2.5 0.5B Instruct", 398, 2, HF + "Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf", "fastest"),
        CatalogModel("llama32_1b", "Llama 3.2 1B Instruct", 808, 3, HF + "bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"),
        CatalogModel("gemma3_1b", "Gemma 3 1B IT", 806, 3, HF + "ggml-org/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf"),
        CatalogModel("qwen15", "Qwen2.5 1.5B Instruct", 1117, 4, HF + "Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"),
        CatalogModel("qwen3_17b", "Qwen3 1.7B", 1107, 4, HF + "unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf", "good Persian, tools"),
        CatalogModel("llama32_3b", "Llama 3.2 3B Instruct", 2019, 6, HF + "bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf"),
        CatalogModel("qwen3b", "Qwen2.5 3B Instruct", 2105, 6, HF + "Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf"),
        CatalogModel("gemma3_4b", "Gemma 3 4B IT", 2490, 8, HF + "ggml-org/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf", "best quality"),
        CatalogModel("qwen3_4b", "Qwen3 4B", 2497, 8, HF + "unsloth/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf", "best agent"),
    )

    fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun installed(ctx: Context): List<File> = dir(ctx).listFiles { f -> f.name.endsWith(".gguf") }?.sortedBy { it.name } ?: emptyList()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ogba", Context.MODE_PRIVATE)
    fun selected(ctx: Context): String? = prefs(ctx).getString("model", null)?.takeIf { File(it).exists() }
        ?: installed(ctx).firstOrNull()?.absolutePath
    fun pathFor(ctx: Context, botModel: String): String? =
        botModel.takeIf { it.isNotEmpty() }?.let { File(dir(ctx), it) }?.takeIf { it.exists() }?.absolutePath ?: selected(ctx)
    fun select(ctx: Context, path: String) = prefs(ctx).edit().putString("model", path).apply()

    fun enqueue(ctx: Context, m: CatalogModel) {
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("id" to m.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
            .addTag("model-download").addTag("dl-id:${m.id}").build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("dl-${m.id}", ExistingWorkPolicy.KEEP, req)
    }
    fun cancel(ctx: Context, m: CatalogModel) = WorkManager.getInstance(ctx).cancelUniqueWork("dl-${m.id}")

    fun import(ctx: Context, uri: Uri): File {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported.gguf"
        if (!name.endsWith(".gguf")) name += ".gguf"
        val out = File(dir(ctx), name)
        ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it, 1 shl 16) } }
        return out
    }
}

/** Resumable (HTTP Range) background model download running as a foreground worker. */
class DownloadWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    private val m = Models.catalog.first { it.id == inputData.getString("id") }
    private val nid = 4000 + Models.catalog.indexOf(m)

    private fun info(pct: Int): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("OGBA · ${m.title}").setContentText("$pct%").setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, pct, pct == 0).build()
        return ForegroundInfo(nid, n)
    }

    override suspend fun getForegroundInfo() = info(0)

    override suspend fun doWork(): Result {
        try { setForeground(info(0)) } catch (_: Exception) {}
        val dir = Models.dir(applicationContext)
        val out = File(dir, m.fileName); val part = File(dir, m.fileName + ".part")
        return try {
            var url = URL(m.url); var hops = 0
            var have = part.length()
            var conn: HttpURLConnection
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false; conn.connectTimeout = 20000; conn.readTimeout = 60000
                conn.setRequestProperty("User-Agent", "OGBA/0.2")
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                if (code in 300..399 && hops++ < 8) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
                if (code == 416) { break }
                if (code == 200) have = 0 else if (code != 206) throw IllegalStateException("HTTP $code")
                break
            }
            if (conn.responseCode != 416) {
                val total = have + conn.contentLengthLong
                RandomAccessFile(part, "rw").use { raf ->
                    raf.setLength(have); raf.seek(have)
                    conn.inputStream.use { input ->
                        val buf = ByteArray(1 shl 16); var done = have; var lastPct = -1; var n: Int
                        while (input.read(buf).also { n = it } >= 0) {
                            if (isStopped) return Result.failure()
                            raf.write(buf, 0, n); done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pct != lastPct) { lastPct = pct; setProgress(workDataOf("p" to pct / 100f, "id" to m.id)); try { setForeground(info(pct)) } catch (_: Exception) {} }
                        }
                    }
                }
            }
            part.renameTo(out)
            if (Models.selected(applicationContext) == null) Models.select(applicationContext, out.absolutePath)
            Result.success(workDataOf("id" to m.id))
        } catch (e: Exception) {
            if (runAttemptCount < 20) Result.retry() else Result.failure(workDataOf("error" to (e.message ?: "error")))
        }
    }

    companion object {
        const val CHANNEL = "downloads"
        fun createChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        }
    }
}
