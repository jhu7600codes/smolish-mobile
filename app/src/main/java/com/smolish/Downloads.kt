package com.smolish

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.content.getSystemService
import java.io.File

/** Everything ends up in Downloads/Smolish. */
object Downloads {
    private const val DIR = "Smolish"

    /** Normal http(s) downloads go through the system DownloadManager. */
    fun enqueue(ctx: Context, url: String, userAgent: String, disposition: String?, mime: String?, referer: String?) {
        val name = URLUtil.guessFileName(url, disposition, mime)
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$DIR/$name")
            // DownloadManager is a separate http client, so hand it the same identity as the webview
            // (session cookies + cloudflare clearance cookie + same UA) or the request gets blocked
            .addRequestHeader("User-Agent", userAgent)
        CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
        referer?.let { req.addRequestHeader("Referer", it) }
        if (!mime.isNullOrBlank()) req.setMimeType(mime)
        ctx.getSystemService<DownloadManager>()!!.enqueue(req)
    }

    /** For blob:/data: downloads, where we already have the bytes from the page. */
    fun saveBytes(ctx: Context, bytes: ByteArray, suggestedName: String, mime: String) {
        val name = suggestedName.ifBlank {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            "smolish_${System.currentTimeMillis()}.$ext"
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DIR")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("could not create download")
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR)
            dir.mkdirs()
            var file = File(dir, name)
            var i = 1
            while (file.exists()) file = File(dir, "${name.substringBeforeLast('.')} (${i++}).${name.substringAfterLast('.')}")
            file.writeBytes(bytes)
            MediaScannerConnection.scanFile(ctx, arrayOf(file.path), arrayOf(mime), null)
        }
    }
}
