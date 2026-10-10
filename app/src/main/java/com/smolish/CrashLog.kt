package com.smolish

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime

/** Saves the last crash's stack trace so it can be read (and copied) in Settings > Developer. */
object CrashLog {
    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file(app).writeText("${LocalDateTime.now()} on ${thread.name}, v$version\n\n$sw")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun note(ctx: Context, text: String) = runCatching { file(ctx).writeText("${LocalDateTime.now()}\n\n$text") }

    fun read(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.readText()
    fun clear(ctx: Context) = file(ctx).delete()
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")
}
