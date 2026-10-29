package com.ogba.app

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class CatalogModel(val id: String, val title: String, val sizeMb: Int, val url: String) {
    val fileName get() = url.substringAfterLast('/')
}

object Models {
    val catalog = listOf(
        CatalogModel("qwen05", "Qwen2.5 0.5B Instruct (Q4_K_M)", 400,
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"),
        CatalogModel("qwen15", "Qwen2.5 1.5B Instruct (Q4_K_M)", 1100,
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"),
        CatalogModel("llama32_1b", "Llama 3.2 1B Instruct (Q4_K_M)", 810,
            "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"),
        CatalogModel("gemma3_1b", "Gemma 3 1B IT (Q4_K_M)", 810,
            "https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf"),
    )

    fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun installed(ctx: Context): List<File> = dir(ctx).listFiles { f -> f.name.endsWith(".gguf") }?.sortedBy { it.name } ?: emptyList()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ogba", Context.MODE_PRIVATE)
    fun selected(ctx: Context): String? = prefs(ctx).getString("model", null)?.takeIf { File(it).exists() }
        ?: installed(ctx).firstOrNull()?.absolutePath
    fun select(ctx: Context, path: String) = prefs(ctx).edit().putString("model", path).apply()

    /** Streams a GGUF from Hugging Face into app storage, reporting progress 0..1. */
    fun download(ctx: Context, m: CatalogModel, progress: (Float) -> Unit): File {
        val out = File(dir(ctx), m.fileName)
        val part = File(dir(ctx), m.fileName + ".part")
        var url = URL(m.url)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20000; conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "OGBA/0.1")
            val code = conn.responseCode
            if (code in 300..399 && redirects++ < 8) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
            if (code != 200) throw IllegalStateException("HTTP $code")
            break
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            part.outputStream().use { o ->
                val buf = ByteArray(1 shl 16); var done = 0L; var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    o.write(buf, 0, n); done += n
                    if (total > 0) progress(done.toFloat() / total)
                }
            }
        }
        part.renameTo(out)
        return out
    }

    fun import(ctx: Context, uri: Uri): File {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported.gguf"
        if (!name.endsWith(".gguf")) name += ".gguf"
        val out = File(dir(ctx), name)
        ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it, 1 shl 16) } }
        return out
    }
}
