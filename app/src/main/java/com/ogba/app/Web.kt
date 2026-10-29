package com.ogba.app

import android.annotation.SuppressLint
import android.webkit.WebView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.coroutines.resume

/** Internet tools for the agent. The local model still does all reasoning. */
object Web {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

    private fun get(url: String, max: Int = 1_500_000): String {
        var u = URL(url); var hops = 0
        while (true) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 15000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept-Language", "en,fa;q=0.8")
            val code = c.responseCode
            if (code in 300..399 && hops++ < 6) { u = URL(u, c.getHeaderField("Location")); continue }
            if (code >= 400) throw ToolError("HTTP $code for $u")
            val bytes = c.inputStream.use { it.readNBytesCompat(max) }
            return String(bytes, Charsets.UTF_8)
        }
    }
    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16384); var total = 0
        while (total < n) { val r = read(buf); if (r < 0) break; out.write(buf, 0, r); total += r }
        return out.toByteArray()
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'").replace("&nbsp;", " ")
    private fun strip(s: String) = unescape(s.replace(Regex("<[^>]+>"), "")).trim()

    /** DuckDuckGo HTML endpoint – no API key. */
    fun search(q: String): String {
        if (q.isBlank()) throw ToolError("Empty search query.")
        val html = get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8"))
        val links = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val snippets = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).map { strip(it.groupValues[1]) }.toList()
        if (links.isEmpty()) return "No results for \"$q\"."
        return links.take(6).mapIndexed { i, m ->
            var href = unescape(m.groupValues[1])
            Regex("uddg=([^&]+)").find(href)?.let { href = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (href.startsWith("//")) href = "https:$href"
            "${i + 1}. ${strip(m.groupValues[2])}\n   $href\n   ${snippets.getOrElse(i) { "" }.take(200)}"
        }.joinToString("\n")
    }

    /** Download a page and extract readable text. */
    fun fetch(url: String, limit: Int = 2500): String {
        val u = normalize(url)
        val html = get(u)
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(html)?.groupValues?.get(1)?.let(::strip) ?: ""
        var body = html.replace(Regex("(?is)<(script|style|noscript|svg|head|nav|footer|form)[^>]*>.*?</\\1>"), " ")
        body = body.replace(Regex("(?i)<(br|/p|/div|/h\\d|/li|/tr)[^>]*>"), "\n")
        val text = strip(body).lines().map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.length > 1 }.joinToString("\n")
        return "Title: $title\nURL: $u\n\n" + text.take(limit) + if (text.length > limit) "\n…(truncated)" else ""
    }

    fun normalize(url: String) = url.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
}

/** In-app WebView browser ("the computer's browser") the agent drives and the user can watch. */
object Browser {
    @SuppressLint("StaticFieldLeak") var webView: WebView? = null
    val url = MutableStateFlow("https://duckduckgo.com")
    val title = MutableStateFlow("")
    var pageDone: CompletableDeferred<Unit>? = null
    var showScreen: () -> Unit = {}

    suspend fun open(u: String): String {
        val target = Web.normalize(u)
        withContext(Dispatchers.Main) { showScreen(); url.value = target }
        val wv = withTimeoutOrNull(5000) { while (webView == null) delay(100); webView } ?: throw ToolError("Browser not available")
        val d = CompletableDeferred<Unit>(); pageDone = d
        withContext(Dispatchers.Main) { wv.loadUrl(target) }
        withTimeoutOrNull(25000) { d.await() }
        delay(800)
        return read()
    }

    suspend fun read(limit: Int = 2500): String {
        val wv = webView ?: throw ToolError("Browser is not open. Use browser.open first.")
        val raw = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<String> { c -> wv.evaluateJavascript("(document.title+'\\n\\n'+(document.body?document.body.innerText:''))") { c.resume(it ?: "\"\"") } }
        }
        val text = try { JSONArray("[$raw]").getString(0) } catch (e: Exception) { raw }
        return "URL: ${url.value}\n" + text.take(limit) + if (text.length > limit) "\n…(truncated)" else ""
    }
}
