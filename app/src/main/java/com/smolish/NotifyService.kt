package com.smolish

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Background notifications that survive closing the app. Two ways:
 * - web push (when you turned on push in smolish's own settings): a connection to the private
 *   ntfy.sh topic smolish's server pushes to, decrypted with WebPush. Instant and light.
 * - otherwise polling: a foreground service (so android keeps
 * it alive, with a quiet pinned notification) that keeps smolish.com open in a hidden webview and
 * asks the site's own unread counter every minute. The webview is needed because the site signs
 * its api requests in its own javascript. It only exists while the app is NOT on screen (the app's
 * own page checks then), so there are never two smolish pages eating memory at once.
 */
class NotifyService : Service() {

    companion object {
        private const val CHANNEL = "background"
        private const val ID = 2
        private const val POLL_MS = 60_000L
        private const val RELOAD_MS = 30 * 60_000L // a fresh page every half hour, keeps memory in check

        @Volatile var running = false
            private set

        fun isEnabled(ctx: Context) = ctx.getSharedPreferences("app", 0).getBoolean("bg_notifs", true)
        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences("app", 0).edit().putBoolean("bg_notifs", on).apply()
            if (on) start(ctx) else ctx.stopService(Intent(ctx, NotifyService::class.java))
        }

        private var instance: NotifyService? = null

        /** The app came to / left the screen: hand the checking over. Main thread. */
        fun appVisible(visible: Boolean) {
            instance?.onAppVisible(visible)
        }

        /** Push got turned on/off on the site: switch between push and polling. */
        fun restart(ctx: Context) {
            instance?.stopSelf()
            running = false
            Handler(Looper.getMainLooper()).postDelayed({ start(ctx) }, 500)
        }

        fun start(ctx: Context) {
            if (!isEnabled(ctx) || running) return
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, NotifyService::class.java)) }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var ready = false
    private val pollJs by lazy { assets.open("notify_poll.js").bufferedReader().use { it.readText() } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        instance = this
        CrashLog.install(this)
        val ch = NotificationChannel(CHANNEL, getString(R.string.bg_channel), NotificationManager.IMPORTANCE_MIN)
            .apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        Notify.createChannel(this)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cube)
            .setColor(ContextCompat.getColor(this, R.color.brand))
            .setContentTitle(getString(R.string.bg_title))
            .setContentText(getString(R.string.bg_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        try {
            ServiceCompat.startForeground(this, ID, n,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch (e: Exception) {
            // the system refused (rom restrictions etc): note it for settings and stay out of the way
            CrashLog.note(this, "Background notifications couldn't start:\n${e.stackTraceToString()}")
            stopSelf()
            return
        }
        if (WebPush.isActive(this)) listen()
        else if (!MainActivity.inFront) mainHandler.postDelayed(::load, 5_000)
    }

    // --- web push through ntfy.sh
    @Volatile private var listening = false
    @Volatile private var conn: HttpURLConnection? = null

    private fun listen() {
        listening = true
        thread(name = "ntfy") {
            var backoff = 2_000L
            while (listening) {
                val topic = WebPush.topic(this) ?: break
                try {
                    val since = WebPush.lastId(this)?.let { "?since=$it" } ?: ""
                    val c = URL("${WebPush.NTFY}/$topic/json$since").openConnection() as HttpURLConnection
                    conn = c
                    // ntfy only accepts pushes for "up..." topics while their subscriber says so
                    c.setRequestProperty("Rate-Topics", topic)
                    c.connectTimeout = 15_000
                    c.readTimeout = 120_000 // ntfy sends a keepalive every 45s
                    c.inputStream.bufferedReader().useLines { lines ->
                        backoff = 2_000L
                        for (line in lines) {
                            if (!listening) break
                            val j = runCatching { JSONObject(line) }.getOrNull() ?: continue
                            if (j.optString("event") != "message") continue
                            onPush(j)
                            WebPush.setLastId(this, j.getString("id"))
                        }
                    }
                } catch (_: Exception) {
                }
                if (listening) {
                    Thread.sleep(backoff)
                    backoff = minOf(backoff * 2, 60_000L)
                }
            }
        }
    }

    private fun onPush(j: JSONObject) {
        val raw = j.optString("message")
        val bytes = if (j.optString("encoding") == "base64") Base64.decode(raw, Base64.DEFAULT) else raw.toByteArray()
        val text = WebPush.decrypt(this, bytes) ?: return
        val p = runCatching { JSONObject(text) }.getOrNull()
        val title = p?.optString("title")?.ifBlank { null } ?: getString(R.string.app_name)
        val body = p?.optString("body") ?: text
        val url = p?.optString("url") ?: "/"
        val tag = p?.optString("tag")?.ifBlank { null }
        if (Translator.isOn(this) && body.isNotBlank()) {
            Translator.translate(listOf(title, body), Translator.lang(this)) { out ->
                Notify.showPush(this, out?.getOrNull(0) ?: title, out?.getOrNull(1) ?: body, url, tag)
            }
        } else Notify.showPush(this, title, body, url, tag)
    }

    private fun onAppVisible(visible: Boolean) {
        if (listening) return // push doesn't care whether the app is open
        mainHandler.removeCallbacksAndMessages(null)
        if (visible) unload() else mainHandler.postDelayed(::load, 3_000) // let the app settle first
    }

    private fun unload() {
        ready = false
        web?.let { runCatching { it.stopLoading(); it.destroy() } }
        web = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    @SuppressLint("SetJavaScriptEnabled")
    private fun load() {
        mainHandler.removeCallbacksAndMessages(null)
        if (MainActivity.inFront) return unload()
        unload()
        web = WebView(applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.blockNetworkImage = true // it only needs the page's scripts
            addJavascriptInterface(Bridge(), "SmolishNotify")
            webViewClient = object : WebViewClient() {
                // the page's renderer died (low memory): without this android kills the whole app
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    if (view == web) {
                        web = null
                        ready = false
                        mainHandler.removeCallbacksAndMessages(null)
                        mainHandler.postDelayed(::load, 60_000)
                    }
                    runCatching { view.destroy() }
                    return true
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    // give the site's scripts a moment, and skip cloudflare's check page (no next.js there)
                    mainHandler.postDelayed({
                        val last = getSharedPreferences("app", 0).getInt("last_unread", -1)
                        view.evaluateJavascript(
                            "(function(){ if (!document.querySelector('script[src*=\"/_next/\"]')) return false;" +
                                "window.__smolishLastUnread = $last; window.__smolishNativePoll = true;\n$pollJs\nreturn true; })()"
                        ) { ok -> ready = ok == "true" }
                    }, 3000)
                }
            }
            loadUrl("https://smolish.com/notifications")
        }
        mainHandler.postDelayed(::poll, 10_000)
        mainHandler.postDelayed(::load, RELOAD_MS)
    }

    private var notReadyPolls = 0

    private fun poll() {
        if (ready) {
            notReadyPolls = 0
            web?.evaluateJavascript("window.__smolishCheckNotifs && window.__smolishCheckNotifs()", null)
        } else if (++notReadyPolls >= 5) { // check page or no internet for 5 minutes: fresh page
            notReadyPolls = 0
            return load()
        }
        mainHandler.postDelayed(::poll, POLL_MS)
    }

    override fun onDestroy() {
        running = false
        listening = false
        thread { runCatching { conn?.disconnect() } }
        if (instance == this) instance = null
        mainHandler.removeCallbacksAndMessages(null)
        unload()
        super.onDestroy()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onUnread(count: Int) {
            getSharedPreferences("app", 0).edit().putInt("last_unread", count).apply()
        }

        @JavascriptInterface
        fun onNotification(count: Int, who: String, what: String) {
            if (MainActivity.inFront) return // you're looking at the site, its own bell shows it
            val ctx = this@NotifyService
            // in your language when the translator is on
            if (Translator.isOn(ctx) && what.isNotBlank()) {
                Translator.translate(listOf(what), Translator.lang(ctx)) { out -> Notify.show(ctx, count, who, out?.firstOrNull() ?: what) }
            } else Notify.show(ctx, count, who, what)
        }
    }
}

/** Starts the background notifications again after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) NotifyService.start(ctx)
    }
}
