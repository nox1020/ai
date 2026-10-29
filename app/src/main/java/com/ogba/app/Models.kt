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
    // Q4_0 is preferred: llama.cpp repacks it at load time into Arm dotprod/i8mm-friendly layouts (fastest CPU path).
    val catalog = listOf(
        CatalogModel("qwen05", "Qwen2.5 0.5B Instruct", 398, 2, HF + "Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf", "fastest"),
        CatalogModel("gemma3_1b", "Gemma 3 1B IT", 721, 3, HF + "unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_0.gguf"),
        CatalogModel("qwen3_17b", "Qwen3 1.7B", 1056, 4, HF + "unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_0.gguf", "good Persian, tools"),
        CatalogModel("llama32_3b", "Llama 3.2 3B Instruct", 1921, 6, HF + "bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_0.gguf"),
        CatalogModel("phi4mini", "Phi-4-mini Instruct 3.8B", 2330, 6, HF + "bartowski/microsoft_Phi-4-mini-instruct-GGUF/resolve/main/microsoft_Phi-4-mini-instruct-Q4_0.gguf", "reasoning, code"),
        CatalogModel("gemma3_4b", "Gemma 3 4B IT", 2370, 6, HF + "unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_0.gguf"),
        CatalogModel("qwen3_4b", "Qwen3 4B", 2375, 6, HF + "unsloth/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_0.gguf", "best agent / size"),
        CatalogModel("gemma3n_e2b", "Gemma 3n E2B IT", 2965, 6, HF + "unsloth/gemma-3n-E2B-it-GGUF/resolve/main/gemma-3n-E2B-it-Q4_0.gguf", "mobile-first, fast"),
        CatalogModel("gemma3n_e4b", "Gemma 3n E4B IT", 4395, 8, HF + "unsloth/gemma-3n-E4B-it-GGUF/resolve/main/gemma-3n-E4B-it-Q4_0.gguf", "mobile-first, strong"),
        CatalogModel("qwen25_7b", "Qwen2.5 7B Instruct", 4444, 10, HF + "bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_0.gguf"),
        CatalogModel("qwen3_8b", "Qwen3 8B", 4790, 12, HF + "bartowski/Qwen_Qwen3-8B-GGUF/resolve/main/Qwen_Qwen3-8B-Q4_0.gguf", "strongest; 12 GB+ RAM"),
    )

    fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun installed(ctx: Context): List<File> = dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".gguf") && isGguf(f) }?.sortedBy { it.name } ?: emptyList()

    fun isGguf(f: File): Boolean = f.isFile && f.length() > 1_000_000L && try {
        f.inputStream().use { i -> ByteArray(4).also { if (i.read(it) != 4) return false }.contentEquals("GGUF".toByteArray()) }
    } catch (_: Exception) { false }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ogba", Context.MODE_PRIVATE)
    fun selected(ctx: Context): String? = prefs(ctx).getString("model", null)?.let { File(it).canonicalFile }
        ?.takeIf { it.parentFile == dir(ctx).canonicalFile && isGguf(it) }?.absolutePath
        ?: installed(ctx).firstOrNull()?.absolutePath
    fun pathFor(ctx: Context, botModel: String): String? {
        val d = dir(ctx).canonicalFile
        val candidate = if (botModel.isEmpty()) null else File(d, botModel).canonicalFile
        val local = candidate?.takeIf { it.parentFile == d && isGguf(it) }
        return local?.absolutePath ?: selected(ctx)
    }
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
        name = name.substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        val out = File(dir(ctx), name)
        val part = File(dir(ctx), name + ".part")
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: throw ToolError("Unable to open selected model")
            input.use { i -> part.outputStream().use { i.copyTo(it, 1 shl 16) } }
            if (!isGguf(part)) throw ToolError("Not a valid GGUF model")
            if (out.exists()) out.delete()
            if (!part.renameTo(out)) throw ToolError("Could not save model")
            return out
        } finally { part.delete() }
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
                if (code in 300..399 && hops++ < 8) {
                    val location = conn.getHeaderField("Location") ?: throw IllegalStateException("Missing download redirect")
                    val next = URL(url, location)
                    if (next.protocol != "https") throw IllegalStateException("Insecure download redirect")
                    url = next; conn.disconnect(); continue
                }
                if (code == 416) { conn.disconnect(); break }
                if (code == 200) have = 0 else if (code != 206) throw IllegalStateException("HTTP $code")
                if (code == 206 && !conn.getHeaderField("Content-Range").orEmpty().startsWith("bytes $have-")) throw IllegalStateException("Invalid download range")
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
                            if (n == 0) continue
                            raf.write(buf, 0, n); done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pct != lastPct) { lastPct = pct; setProgress(workDataOf("p" to pct / 100f, "id" to m.id)); try { setForeground(info(pct)) } catch (_: Exception) {} }
                        }
                    }
                }
            }
            if (!Models.isGguf(part)) throw IllegalStateException("Invalid or incomplete GGUF download")
            if (out.exists()) out.delete()
            if (!part.renameTo(out)) throw IllegalStateException("Unable to finalize model download")
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
