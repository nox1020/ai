package com.ogba.app.llm

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

/** Single on-device llama.cpp engine shared by chat and routines. */
object LlmEngine {
    private var handle = 0L
    private var loadedPath: String? = null
    private val mutex = Mutex()
    @Volatile var stopRequested = false

    private fun ensureLoaded(path: String) {
        if (loadedPath == path && handle != 0L) return
        if (handle != 0L) LlamaNative.free(handle)
        handle = 0L; loadedPath = null
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val h = LlamaNative.load(path, 2048, threads)
        if (h == 0L) throw IllegalStateException("Failed to load model: $path")
        handle = h; loadedPath = path
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
            val sb = StringBuilder()
            LlamaNative.generate(handle, messages.map { it.first }.toTypedArray(),
                messages.map { it.second }.toTypedArray(), maxTokens, 0.7f,
                object : TokenCallback {
                    override fun onBytes(b: ByteArray): Boolean {
                        sb.append(String(b, Charsets.UTF_8)); onText(sb.toString())
                        return !stopRequested
                    }
                })
            sb.toString().trim()
        }
    }
}
