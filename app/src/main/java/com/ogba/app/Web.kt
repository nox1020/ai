package com.ogba.app

import android.annotation.SuppressLint
import android.webkit.WebView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Inet6Address
import java.net.URI
import org.json.JSONObject
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.net.URLEncoder
import kotlin.coroutines.resume

/** Internet tools for the agent. The local model still does all reasoning. */
object Web {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

    private fun get(url: String, max: Int = 1_500_000): String {
        var u = safeUrl(url); var hops = 0
        while (true) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 15000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept-Language", "en,fa;q=0.8")
            val code = c.responseCode
            if (code in 300..399 && hops++ < 6) {
                val location = c.getHeaderField("Location") ?: throw ToolError("Redirect has no location")
                val next = URL(u, location).toString()
                c.disconnect(); u = safeUrl(next); continue
            }
            if (code in 300..399) { c.disconnect(); throw ToolError("Too many redirects") }
            if (code >= 400) throw ToolError("HTTP $code for $u")
            val bytes = try { c.inputStream.use { it.readNBytesCompat(max) } } finally { c.disconnect() }
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

    fun normalize(url: String): String = url.trim().let {
        if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://$it"
    }

    /**
     * The model and remote pages are untrusted. Do not turn web.fetch/browser.open
     * into an internal-network or metadata endpoint scanner (including redirects).
     */
    fun safeUrl(url: String): URL {
        val uri = try { URI(normalize(url)) } catch (_: Exception) { throw ToolError("Invalid URL") }
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) {
            throw ToolError("Only public HTTP(S) URLs are supported")
        }
        val host = uri.host.lowercase(Locale.ROOT).trimEnd('.')
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            throw ToolError("Local network addresses are not allowed")
        }
        val addresses = try { InetAddress.getAllByName(host) } catch (_: Exception) { throw ToolError("Cannot resolve host: $host") }
        if (addresses.isEmpty() || addresses.any { ip ->
                ip.isLoopbackAddress || ip.isSiteLocalAddress || ip.isLinkLocalAddress || ip.isAnyLocalAddress || ip.isMulticastAddress ||
                    (ip is Inet6Address && ip.address.isNotEmpty() && ((ip.address[0].toInt() and 0xfe) == 0xfc)) ||
                    (ip.address.size == 4 && (ip.address[0].toInt() and 255) == 100 && ((ip.address[1].toInt() and 255) in 64..127))
            }) throw ToolError("Private or restricted network address blocked")
        return uri.toURL()
    }
}

/** In-app WebView browser ("the computer's browser") the agent drives and the user can watch. */
object Browser {
    @SuppressLint("StaticFieldLeak") var webView: WebView? = null
    val url = MutableStateFlow("https://duckduckgo.com")
    val title = MutableStateFlow("")
    var pageDone: CompletableDeferred<Unit>? = null
    var showScreen: () -> Unit = {}

    suspend fun open(u: String): String {
        val target = withContext(Dispatchers.IO) { Web.safeUrl(u).toString() }
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

    private suspend fun evaluate(script: String): String {
        val wv = webView ?: throw ToolError("Browser is not open. Use browser.open first.")
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<String> { c ->
                wv.evaluateJavascript(script) { result -> if (c.isActive) c.resume(result ?: "null") }
            }
        }
    }

    /** Numbered link list also supports click @INDEX for pages without stable CSS selectors. */
    suspend fun links(): String {
        val script = """(function() {
            return JSON.stringify(Array.from(document.querySelectorAll('a,button,input[type=submit]')).slice(0,80)
                .map(function(e,i) { return {index:i, text:(e.innerText||e.value||e.getAttribute('aria-label')||'').trim().slice(0,100), href:e.href||''}; }));
        })()"""
        val raw = evaluate(script)
        val data = try { JSONArray(JSONArray("[$raw]").getString(0)) } catch (_: Exception) { return "No clickable elements found" }
        return data.toString().take(4000)
    }

    private fun selectorScript(selector: String): String {
        val s = selector.trim()
        if (s.isEmpty()) throw ToolError("Empty browser selector")
        if (s.startsWith("@")) {
            val idx = s.drop(1).toIntOrNull() ?: throw ToolError("Use @0, @1, ... or a CSS selector")
            if (idx !in 0..79) throw ToolError("Browser index out of range")
            return "document.querySelectorAll('a,button,input[type=submit]')[$idx]"
        }
        return "document.querySelector(${JSONObject.quote(s)})"
    }

    /** Click and type always require an approval from the owner. */
    suspend fun click(selector: String): String {
        val raw = evaluate("(function(){var e=${selectorScript(selector)};if(!e)return 'not found';e.click();return 'clicked';})()")
        return raw.trim('"').take(150)
    }

    suspend fun type(argument: String): String {
        val sel = argument.substringBefore("|").trim()
        val value = argument.substringAfter("|", "").trimStart()
        if (sel.isEmpty() || !argument.contains("|")) throw ToolError("Use browser.type SELECTOR | TEXT")
        if (value.length > 4000) throw ToolError("Browser input too long")
        val raw = evaluate("(function(){var e=${selectorScript(sel)};if(!e)return 'not found';" +
            "if(!('value' in e))return 'not an input';e.focus();e.value=${JSONObject.quote(value)};" +
            "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'typed';})()")
        return raw.trim('"').take(150)
    }

    suspend fun back(): String {
        withContext(Dispatchers.Main) {
            val wv = webView ?: throw ToolError("Browser is not open")
            if (!wv.canGoBack()) throw ToolError("No browser history")
            wv.goBack()
        }
        return "Back navigation requested"
    }

}
