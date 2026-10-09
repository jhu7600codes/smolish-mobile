package com.smolish

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.core.content.getSystemService
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream

/**
 * The offline pack: downloaded smols + a manifest, and a tiny "web server" that hands them to the
 * webview under a fake https origin (so the offline feed page is a normal page, no file:// access).
 */
object OfflineStore {
    // .invalid is reserved and never resolves, so nothing can ever reach the real internet here
    const val HOST = "smolish-offline.invalid"
    const val URL = "https://$HOST/"
    private const val PREFS = "offline"

    fun dir(ctx: Context) = File(ctx.filesDir, "offline")
    fun stagingDir(ctx: Context) = File(ctx.filesDir, "offline_new")
    private fun manifest(ctx: Context) = File(dir(ctx), "manifest.json")

    fun count(ctx: Context): Int =
        runCatching { JSONArray(manifest(ctx).readText()).length() }.getOrDefault(0)

    fun hasPack(ctx: Context) = count(ctx) > 0

    fun sizeBytes(ctx: Context) = dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    fun clear(ctx: Context) {
        dir(ctx).deleteRecursively()
        stagingDir(ctx).deleteRecursively()
    }

    /** Swaps a finished download in, so a failed or cancelled download never kills the old pack. */
    fun commitStaging(ctx: Context, manifestJson: String) {
        val staging = stagingDir(ctx)
        File(staging, "manifest.json").writeText(manifestJson)
        dir(ctx).deleteRecursively()
        staging.renameTo(dir(ctx))
    }

    // "always use offline mode" from settings
    fun isForced(ctx: Context) = ctx.getSharedPreferences(PREFS, 0).getBoolean("forced", false)
    fun setForced(ctx: Context, on: Boolean) = ctx.getSharedPreferences(PREFS, 0).edit().putBoolean("forced", on).apply()

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService<ConnectivityManager>() ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Answers requests to the offline origin: the feed page from assets, everything else from the pack. */
    fun serve(ctx: Context, request: WebResourceRequest): WebResourceResponse {
        val path = request.url.path.orEmpty()
        return when {
            path == "/" || path == "/index.html" ->
                WebResourceResponse("text/html", "utf-8", ctx.assets.open("offline.html"))
            path.startsWith("/media/") || path == "/manifest.json" -> {
                // only plain file names, never a path out of the pack
                val name = path.substringAfterLast('/')
                val file = File(dir(ctx), name)
                if (name.isEmpty() || name.startsWith(".") || !file.isFile) notFound()
                else serveFile(file, mimeOf(name), request.requestHeaders["Range"] ?: request.requestHeaders["range"])
            }
            else -> notFound()
        }
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())

    private fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "json" -> "application/json"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }

    // video elements read in byte ranges (to seek, and to find the index at the end of an mp4),
    // so answer "Range: bytes=a-b" with a 206 and just that slice
    private fun serveFile(file: File, mime: String, range: String?): WebResourceResponse {
        val len = file.length()
        val headers = mutableMapOf("Accept-Ranges" to "bytes", "Cache-Control" to "no-store")
        val m = range?.let { Regex("""bytes=(\d*)-(\d*)""").find(it) }
        if (m != null && len > 0) {
            val (a, b) = m.destructured
            val start = (a.toLongOrNull() ?: 0L).coerceIn(0, len - 1)
            val end = (b.toLongOrNull() ?: (len - 1)).coerceIn(start, len - 1)
            val input = FileInputStream(file).also { it.channel.position(start) }
            headers["Content-Range"] = "bytes $start-$end/$len"
            headers["Content-Length"] = (end - start + 1).toString()
            return WebResourceResponse(mime, null, 206, "Partial Content", headers, Limited(input, end - start + 1))
        }
        headers["Content-Length"] = len.toString()
        return WebResourceResponse(mime, null, 200, "OK", headers, FileInputStream(file))
    }

    /** Stops after [left] bytes, for range responses. */
    private class Limited(input: InputStream, private var left: Long) : FilterInputStream(input) {
        override fun read(): Int = if (left <= 0) -1 else super.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }
}
