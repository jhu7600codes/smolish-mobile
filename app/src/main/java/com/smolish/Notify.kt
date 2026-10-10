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
