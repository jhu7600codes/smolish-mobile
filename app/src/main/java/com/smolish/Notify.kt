package com.smolish

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Real android notifications for new smolish activity (the site's web push can't work in a webview). */
object Notify {
    private const val CHANNEL = "activity"
    private const val ID = 1
    const val EXTRA_URL = "open_url"

    fun createChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private var nextId = 100

    /** A push from smolish's server ({title, body, url, tag}, see the site's sw.js). */
    fun showPush(ctx: Context, title: String, body: String, url: String, tag: String?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val target = when {
            url.startsWith("https://smolish.com") -> url
            url.startsWith("/") -> "https://smolish.com$url"
            else -> "https://smolish.com/notifications"
        }
        // same tag = replaces the old one (like the site's renotify), otherwise each push is its own
        val id = tag?.takeIf { it.isNotBlank() }?.hashCode() ?: nextId++
        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_URL, target)
        val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_cube)
            .setColor(ContextCompat.getColor(ctx, R.color.brand))
            .setContentTitle(title.ifBlank { ctx.getString(R.string.app_name) })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    fun show(ctx: Context, count: Int, who: String, what: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val title = if (who.isNotBlank()) who else ctx.getString(R.string.app_name)
        val text = when {
            what.isNotBlank() -> what
            else -> ctx.resources.getQuantityString(R.plurals.notif_new, count, count)
        }
        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_URL, "https://smolish.com/notifications")
        val pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_cube)
            .setColor(ContextCompat.getColor(ctx, R.color.brand))
            .setContentTitle(title)
            .setContentText(text)
            .setNumber(count)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        // one notification that updates, not a pile of them
        runCatching { NotificationManagerCompat.from(ctx).notify(ID, n) }
    }
}
