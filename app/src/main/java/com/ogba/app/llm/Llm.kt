package com.ogba.app.llm

import android.app.ActivityManager
import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface TokenCallback { fun onBytes(b: ByteArray): Boolean }

object LlamaNative {
    init { System.loadLibrary("ogba_jni") }
    external fun load(path: String, nCtx: Int, nThreads: Int): Long
    external fun free(handle: Long)
    external fun generate(handle: Long, roles: Array<String>, contents: Array<String>, maxTokens: Int, temp: Float, cb: TokenCallback): Int
}

/** Single on-device llama.cpp engine shared by chat, voice and routines. Keeps one context alive (KV cache reuse). */
object LlmEngine {
    private var handle = 0L
    private var loadedPath: String? = null
    private val mutex = Mutex()
    @Volatile var stopRequested = false
    var nCtx = 2048; private set
    var nThreads = 4; private set
    @Volatile var lastTokPerSec = 0f

    fun totalRamGb(ctx: Context): Float {
        val mi = ActivityManager.MemoryInfo(); ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return mi.totalMem / 1_073_741_824f
    }

    /** Count performance cores (big/prime) from cpufreq; llama.cpp is fastest on those only. */
    fun perfCores(): Int {
        val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull {
            File("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq").takeIf { f -> f.canRead() }?.readText()?.trim()?.toLongOrNull()
        }
        if (freqs.isEmpty()) return (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        val max = freqs.max()
        return freqs.count { it >= max * 0.6 }.coerceIn(2, 8)
    }

    fun configure(ctx: Context) {
        nThreads = perfCores()
        nCtx = if (totalRamGb(ctx) >= 5.5f) 4096 else 2048
    }

    private fun ensureLoaded(path: String) {
        if (loadedPath == path && handle != 0L) return
        if (handle != 0L) LlamaNative.free(handle)
        handle = 0L; loadedPath = null
        val h = LlamaNative.load(path, nCtx, nThreads)
        if (h == 0L) throw IllegalStateException("Failed to load model: $path")
        handle = h; loadedPath = path
    }

    /** Removes Qwen3-style <think>…</think> reasoning from visible text. */
    fun visible(s: String): String {
        var t = s.replace(Regex("(?s)<think>.*?</think>"), "")
        val open = t.indexOf("<think>"); if (open >= 0) t = t.substring(0, open)
        return t.trimStart()
    }

    suspend fun generate(
        modelPath: String,
        messages: List<Pair<String, String>>,
        maxTokens: Int = 512,
        onText: (String) -> Unit = {}
    ): String = mutex.withLock {
        withContext(Dispatchers.Default) {
            ensureLoaded(modelPath)
            stopRequested = false
            val msgs = if (File(modelPath).name.lowercase().contains("qwen3"))
                messages.mapIndexed { i, m -> if (i == 0 && m.first == "system") m.first to (m.second + "\n/no_think") else m } else messages
            val sb = StringBuilder()
            val t0 = System.nanoTime()
            val n = LlamaNative.generate(handle, msgs.map { it.first }.toTypedArray(), msgs.map { it.second }.toTypedArray(), maxTokens, 0.7f,
                object : TokenCallback {
                    override fun onBytes(b: ByteArray): Boolean {
                        sb.append(String(b, Charsets.UTF_8)); onText(visible(sb.toString()))
                        return !stopRequested
                    }
                })
            val sec = (System.nanoTime() - t0) / 1e9f
            if (n > 0 && sec > 0) lastTokPerSec = n / sec
            visible(sb.toString()).trim()
        }
    }
}
