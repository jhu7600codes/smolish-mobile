package com.smolish

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.text.format.Formatter
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.Intent
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Opened from the launcher icon's long-press menu. Right now it's all about offline mode. */
class SettingsActivity : ComponentActivity() {

    companion object {
        fun isSeamless(ctx: android.content.Context) = ctx.getSharedPreferences("app", 0).getBoolean("seamless", false)
    }

    private lateinit var countLabel: TextView
    private lateinit var count: SeekBar
    private lateinit var prepare: Button
    private lateinit var bar: ProgressBar
    private lateinit var status: TextView
    private lateinit var saved: TextView
    private lateinit var downloader: WebView

    private var downloading = false
    private val open = HashMap<String, FileOutputStream>()

    private val videos get() = (count.progress + 1) * 5 // 5..100

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        countLabel = findViewById(R.id.count_label)
        count = findViewById(R.id.count)
        prepare = findViewById(R.id.prepare)
        bar = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        saved = findViewById(R.id.saved)
        downloader = findViewById(R.id.downloader)

        val prefs = getSharedPreferences("offline", 0)
        count.progress = prefs.getInt("count_step", 3) // 20 videos
        count.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                showCount()
                prefs.edit().putInt("count_step", p).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        showCount()

        prepare.setOnClickListener { if (downloading) cancel() else start() }
        findViewById<Button>(R.id.delete).setOnClickListener {
            OfflineStore.clear(this)
            showSaved()
        }
        val iconKeys = listOf(Icons.AUTO, "default", "halloween", "winter", "valentine", "aprilfools", "easter", "birthday", "thanksgiving", "ramadan", "eid", "maslenitsa")
        findViewById<Spinner>(R.id.icon).apply {
            adapter = ArrayAdapter.createFromResource(this@SettingsActivity, R.array.icon_modes, android.R.layout.simple_spinner_dropdown_item)
            setSelection(iconKeys.indexOf(Icons.mode(this@SettingsActivity)).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = Icons.setMode(this@SettingsActivity, iconKeys[pos])
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        findViewById<Button>(R.id.run_setup).setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        findViewById<Switch>(R.id.seamless).apply {
            isChecked = isSeamless(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> getSharedPreferences("app", 0).edit().putBoolean("seamless", on).apply() }
        }
        val forced = findViewById<Switch>(R.id.forced).apply {
            isChecked = OfflineStore.isForcedSetting(this@SettingsActivity)
            isEnabled = OfflineStore.isEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> OfflineStore.setForced(this@SettingsActivity, on) }
        }
        findViewById<Switch>(R.id.enabled).apply {
            isChecked = OfflineStore.isEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, on ->
                OfflineStore.setEnabled(this@SettingsActivity, on)
                forced.isEnabled = on // "always offline" only means something while it's on
            }
        }
        showSaved()
        setupDev()
    }

    /** Splash tester, only for the @jhu account. */
    private fun setupDev() {
        if (!Icons.isDev(this)) return
        findViewById<View>(R.id.dev).isVisible = true
        // the icon names minus "Automatic", same order as Icons.KEYS
        val names = resources.getStringArray(R.array.icon_modes).drop(1)
        val spinner = findViewById<Spinner>(R.id.test_key).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, names)
            setSelection(Icons.KEYS.indexOf(Icons.wanted(this@SettingsActivity)).coerceAtLeast(0))
        }
        findViewById<Button>(R.id.test_splash).setOnClickListener {
            startActivity(Intent(this, SplashTestActivity::class.java).putExtra(SplashTestActivity.EXTRA_KEY, Icons.KEYS[spinner.selectedItemPosition]))
        }
    }

    private fun showCount() {
        countLabel.text = getString(R.string.offline_count, videos)
    }

    private fun showSaved() {
        val n = OfflineStore.count(this)
        saved.text = if (n == 0) getString(R.string.offline_none)
        else getString(R.string.offline_saved, n, Formatter.formatShortFileSize(this, OfflineStore.sizeBytes(this)))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start() {
        downloading = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prepare.setText(R.string.cancel)
        bar.isVisible = true
        bar.isIndeterminate = true
        setStatus(getString(R.string.offline_connecting))
        OfflineStore.stagingDir(this).run { deleteRecursively(); mkdirs() }

        downloader.settings.javaScriptEnabled = true
        downloader.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(downloader, true)
        downloader.addJavascriptInterface(Bridge(), "SmolishOffline")
        downloader.settings.mediaPlaybackRequiresUserGesture = true // the hidden page stays silent
        downloader.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (!downloading) return
                // give the site a moment to boot its scripts (request signing), then start, once.
                // a cloudflare check page has no next.js scripts, so we just wait for the real page
                view.postDelayed({
                    if (!downloading) return@postDelayed
                    val js = assets.open("offline_dl.js").bufferedReader().use { it.readText() }
                    view.evaluateJavascript(
                        "if (!window.__smolishPrepareStarted && document.querySelector('script[src*=\"/_next/\"]')) {" +
                            "window.__smolishPrepareStarted = true;\n$js\nwindow.__smolishPrepare($videos); }", null
                    )
                }, 2500)
            }
        }
        // the real site, so its own scripts sign our api requests
        downloader.loadUrl("https://smolish.com/")
    }

    private fun cancel() {
        stop()
        OfflineStore.stagingDir(this).deleteRecursively()
        setStatus(getString(R.string.offline_cancelled))
    }

    private fun stop() {
        downloading = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        downloader.stopLoading()
        downloader.loadUrl("about:blank")
        downloader.removeJavascriptInterface("SmolishOffline")
        synchronized(open) { open.values.forEach { runCatching { it.close() } }; open.clear() }
        prepare.setText(R.string.prepare_offline)
        bar.isVisible = false
    }

    private fun setStatus(text: String) {
        status.isVisible = true
        status.text = text
    }

    // the icon switches when you leave settings, not while you're picking
    override fun onStop() {
        super.onStop()
        Icons.apply(this)
    }

    override fun onDestroy() {
        if (downloading) {
            stop()
            OfflineStore.stagingDir(this).deleteRecursively()
        }
        downloader.destroy()
        super.onDestroy()
    }

    /** Called from offline_dl.js, on the webview's background bridge thread. */
    private inner class Bridge {
        private val staging get() = OfflineStore.stagingDir(this@SettingsActivity)

        // file names come from the page, keep them to plain names inside the staging folder
        private fun safe(name: String) = name.isNotEmpty() && !name.contains('/') && !name.startsWith(".")

        @JavascriptInterface
        fun progress(done: Int, total: Int, label: String) = runOnUiThread {
            if (!downloading) return@runOnUiThread
            bar.isIndeterminate = false
            bar.max = total
            bar.progress = done
            setStatus(label)
        }

        @JavascriptInterface
        fun begin(name: String): Boolean {
            if (!downloading || !safe(name)) return false
            synchronized(open) { open[name] = FileOutputStream(File(staging, name)) }
            return true
        }

        @JavascriptInterface
        fun chunk(name: String, b64: String): Boolean {
            val out = synchronized(open) { open[name] } ?: return false
            return runCatching { out.write(Base64.decode(b64, Base64.DEFAULT)) }.isSuccess
        }

        @JavascriptInterface
        fun end(name: String): Boolean {
            val out = synchronized(open) { open.remove(name) } ?: return false
            runCatching { out.close() }
            return File(staging, name).length() > 0
        }

        /** Videos on another host (a cdn) are fetched here, the page couldn't read them without cors. */
        @JavascriptInterface
        fun nativeDownload(url: String, name: String) {
            if (!downloading || !safe(name) || !url.startsWith("https://")) return report(name, false)
            thread {
                val ok = runCatching {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(this@SettingsActivity))
                    c.setRequestProperty("Referer", "https://smolish.com/")
                    CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
                    c.connectTimeout = 15_000
                    c.readTimeout = 30_000
                    if (c.responseCode !in 200..299) error("http ${c.responseCode}")
                    val file = File(staging, name)
                    c.inputStream.use { input -> FileOutputStream(file).use { input.copyTo(it) } }
                    file.length() > 0
                }.getOrDefault(false)
                report(name, ok)
            }
        }

        private fun report(name: String, ok: Boolean) = runOnUiThread {
            if (downloading) downloader.evaluateJavascript("window.__smolishNativeDone(${JSONObject.quote(name)}, $ok)", null)
        }

        @JavascriptInterface
        fun finish(manifest: String) = runOnUiThread {
            if (!downloading) return@runOnUiThread
            stop()
            OfflineStore.commitStaging(this@SettingsActivity, manifest)
            setStatus(getString(R.string.offline_done))
            showSaved()
        }

        @JavascriptInterface
        fun fail(message: String) = runOnUiThread {
            if (!downloading) return@runOnUiThread
            stop()
            OfflineStore.stagingDir(this@SettingsActivity).deleteRecursively()
            setStatus(getString(R.string.offline_failed, message))
        }
    }
}
