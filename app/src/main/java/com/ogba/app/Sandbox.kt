package com.ogba.app

import android.content.Context
import android.os.Build
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * OGBA's own "computer": an Alpine Linux userland run through proot inside app storage
 * (Termux/UserLAnd approach). Never falls back to the unrestricted Android host shell.
 */
object Sandbox {
    private const val PROOT_VER = "5.3.0"
    private const val ALPINE = "3.20.3"
    private val arch get() = if (Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("x86_64") == true) "x86_64" else "aarch64"

    data class Line(val kind: String, val text: String) // cmd | out | sys
    val terminal = MutableStateFlow<List<Line>>(listOf(Line("sys", "OGBA computer ready.")))
    val installing = MutableStateFlow<String?>(null)

    fun root(ctx: Context) = File(ctx.filesDir, "linux")
    private fun proot(ctx: Context) = File(ctx.filesDir, "bin/proot")
    fun linuxInstalled(ctx: Context) = File(root(ctx), "bin/busybox").exists() && proot(ctx).canExecute()

    private fun log(kind: String, text: String) { terminal.value = (terminal.value + Line(kind, text.take(3000))).takeLast(400) }
    fun clear() { terminal.value = emptyList() }

    suspend fun installLinux(ctx: Context) = withContext(Dispatchers.IO) {
        try {
            installing.value = "Downloading proot…"
            val p = proot(ctx); p.parentFile!!.mkdirs()
            URL("https://github.com/proot-me/proot/releases/download/v$PROOT_VER/proot-v$PROOT_VER-$arch-static").openStream().use { i -> p.outputStream().use { i.copyTo(it) } }
            p.setExecutable(true, false)
            installing.value = "Downloading Alpine Linux $ALPINE…"
            val r = root(ctx); r.deleteRecursively(); r.mkdirs()
            val url = "https://dl-cdn.alpinelinux.org/alpine/v${ALPINE.substringBeforeLast('.')}/releases/$arch/alpine-minirootfs-$ALPINE-$arch.tar.gz"
            TarArchiveInputStream(GZIPInputStream(URL(url).openStream().buffered())).use { tar ->
                var e = tar.nextEntry
                while (e != null) {
                    val out = File(r, e.name).canonicalFile
                    if (e.name.startsWith("/") || (out.path != r.canonicalPath && !out.path.startsWith(r.canonicalPath + File.separator))) {
                        throw ToolError("Unsafe path in Alpine archive: ${e.name}")
                    }
                    when {
                        e.isDirectory -> out.mkdirs()
                        e.isSymbolicLink -> {
                            if (e.linkName.contains("..")) throw ToolError("Unsafe archive symlink")
                            out.parentFile?.mkdirs(); out.delete()
                            Os.symlink(e.linkName, out.path)
                        }
                        e.isLink -> {
                            val src = File(r, e.linkName).canonicalFile
                            if (!src.path.startsWith(r.canonicalPath + File.separator)) throw ToolError("Unsafe archive hardlink")
                            out.parentFile?.mkdirs(); src.takeIf { it.isFile }?.copyTo(out, true)
                        }
                        else -> { out.parentFile?.mkdirs(); out.outputStream().use { tar.copyTo(it) }; if (e.mode and 0b001_001_001 != 0) out.setExecutable(true, false) }
                    }
                    e = tar.nextEntry
                }
            }
            File(r, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            File(r, "root").mkdirs()
            log("sys", "Alpine Linux installed. Try: apk add python3")
        } catch (e: Exception) {
            log("sys", "Linux install failed: ${e.message}. Installation is required before commands can run.")
        } finally { installing.value = null }
    }

    /** Runs a shell command; output streams into the terminal view. */
    suspend fun run(ctx: Context, cmd: String, timeoutSec: Long = 180): String = withContext(Dispatchers.IO) {
        val ws = Tools.workspaceRoot(ctx)
        val tmp = File(ctx.filesDir, "tmp").apply { mkdirs() }
        if (!linuxInstalled(ctx)) throw ToolError("Alpine computer is not installed. Install it in the Computer panel before running commands.")
        log("cmd", "alpine:~$ " + cmd)
        val pb = ProcessBuilder(
            proot(ctx).path, "--kill-on-exit", "-0", "-r", root(ctx).path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${ws.path}:/root/workspace", "-w", "/root/workspace",
            "/usr/bin/env", "-i", "HOME=/root", "TERM=xterm", "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "/bin/sh", "-c", cmd
        ).apply { environment()["PROOT_TMP_DIR"] = tmp.path; environment()["PROOT_NO_SECCOMP"] = "1" }
        pb.redirectErrorStream(true)
        val sb = StringBuilder()
        val proc = try { pb.start() } catch (e: Exception) { log("out", "error: ${e.message}"); return@withContext "error: ${e.message}" }
        val reader = Thread {
            proc.inputStream.bufferedReader().forEachLine { l -> synchronized(sb) { if (sb.length < 20000) sb.appendLine(l) }; log("out", l) }
        }.apply { start() }
        val done = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!done) proc.destroyForcibly()
        reader.join(2000)
        val code = if (done) proc.exitValue() else -1
        log("sys", if (done) "[exit $code]" else "[timeout after ${timeoutSec}s]")
        val out = synchronized(sb) { sb.toString() }
        "exit=$code\n" + (if (out.length > 3000) out.take(1200) + "\n…(truncated)…\n" + out.takeLast(1500) else out)
    }
}
