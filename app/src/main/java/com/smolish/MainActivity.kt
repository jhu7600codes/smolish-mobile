package com.smolish

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    companion object {
        private const val HOME = "https://smolish.com/"
        private const val HOST = "smolish.com"
        private const val MIN_SPLASH_MS = 800L
        private const val MAX_SPLASH_MS = 12_000L

        // paths that count as "the video feed" (no pull to refresh, no long press, back exits).
        // adjust if the site moves its feed somewhere else.
        private val FEED_PATHS = setOf("", "/feed", "/foryou", "/for-you", "/following")

        // login providers that stay inside the app (host to path prefix). everything else
        // outside smolish.com still opens in the browser.
        private val AUTH_PAGES = listOf(
            "accounts.google.com" to "", "accounts.youtube.com" to "",
            "appleid.apple.com" to "",
            "discord.com" to "/oauth2", "discord.com" to "/login",
            "github.com" to "/login", "github.com" to "/sessions",
            "x.com" to "/i/oauth2", "twitter.com" to "/i/oauth2", "api.twitter.com" to "/oauth",
            "www.facebook.com" to "/dialog/oauth", "m.facebook.com" to "",
            "challenges.cloudflare.com" to "",
        )
        private val AUTH_HOST_SUFFIXES = listOf(".auth0.com", ".clerk.accounts.dev", ".supabase.co", ".firebaseapp.com")
    }

    private lateinit var web: WebView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var errorView: View
    private lateinit var fullscreen: FrameLayout
    private lateinit var splash: BouncingSplashView
    private lateinit var popupHost: FrameLayout
    private lateinit var statusBg: View
    private lateinit var navBg: View
    private var popup: WebView? = null

    private val startedAt = SystemClock.uptimeMillis()
    private val bridgeJs by lazy { assets.open("bridge.js").bufferedReader().use { it.readText() } }

    private var splashHidden = false
    private var loadFailed = false
    private var onChallenge = false // cloudflare "verify your browser" page is showing
    @Volatile private var pageAtTop = true
    // cached because the refresh layout asks on every touch move
    private var onFeed = true
    private var videoPlaying = false

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingStorageAction: (() -> Unit)? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        val uris = when {
            res.resultCode != RESULT_OK || data == null -> null
            data.clipData != null -> Array(data.clipData!!.itemCount) { data.clipData!!.getItemAt(it).uri }
            data.data != null -> arrayOf(data.data!!)
            else -> null
        }
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pendingStorageAction?.invoke() else toast(R.string.download_failed)
        pendingStorageAction = null
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen() // system splash, dismissed right away; our overlay takes over
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        refresh = findViewById(R.id.refresh)
        errorView = findViewById(R.id.error)
        fullscreen = findViewById(R.id.fullscreen)
        splash = findViewById(R.id.splash)
        popupHost = findViewById(R.id.popup_host)
        statusBg = findViewById(R.id.status_bg)
        navBg = findViewById(R.id.nav_bg)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false // no grey scrim over our nav color

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.content)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            // the colored strips behind the status bar and the gesture/nav bar
            setHeight(statusBg, bars.top)
            setHeight(navBg, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            WindowInsetsCompat.CONSUMED
        }

        setupWebView()
        setupRefresh()
        findViewById<View>(R.id.retry).setOnClickListener { retry() }
        onBackPressedDispatcher.addCallback(this, backHandler)

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(HOME)
        }
        splash.postDelayed({ hideSplash() }, MAX_SPLASH_MS) // never get stuck on the splash
    }

    /** Shared by the main webview and login popups. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun applySettings(w: WebView) {
        w.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            // login buttons often use window.open popups, handled in onCreateWindow
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            // user agent stays exactly the stock webview one: cloudflare compares it with what the
            // browser really is (client hints, js features), and any extra token fails the check
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, true)
        }
    }

    private fun setupWebView() {
        applySettings(web)
        web.overScrollMode = View.OVER_SCROLL_NEVER
        // keep the renderer process at foreground priority and pre-render tiles just outside the
        // screen, so swiping to the next video doesn't show half drawn content
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        web.settings.offscreenPreRaster = true
        web.isHapticFeedbackEnabled = false
        // consuming the long press on the feed stops the text selection / copy menu
        web.setOnLongClickListener { onFeed }

        web.addJavascriptInterface(Bridge(), "SmolishBridge")
        web.webViewClient = Client()
        web.webChromeClient = Chrome()

        web.setDownloadListener { url, userAgent, disposition, mime, _ ->
            when {
                // blob: urls only live inside the page, so the page has to read them for us (see bridge.js)
                url.startsWith("blob:") && !onChallenge -> web.evaluateJavascript(
                    bridgeJs + "\nwindow.__smolishBlob(${JSONObject.quote(url)}, ${JSONObject.quote(mime.orEmpty())});", null
                )
                url.startsWith("data:") -> saveDataUrl(url, mime.orEmpty(), "")
                else -> withStoragePermission {
                    runCatching { Downloads.enqueue(this, url, userAgent, disposition, mime, web.url) }
                        .onSuccess { toast(R.string.download_started) }
                        .onFailure { toast(R.string.download_failed) }
                }
            }
        }
    }

    private fun setupRefresh() {
        refresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.brand))
        refresh.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, R.color.bg))
        // returning true means "the child can still scroll up", which blocks the refresh gesture
        refresh.setOnChildScrollUpCallback { _, _ -> onFeed || web.scrollY > 0 || !pageAtTop }
        refresh.setOnRefreshListener { web.reload() }
    }

    private fun isOurHost(host: String?) = host != null && (host == HOST || host.endsWith(".$HOST"))

    private fun isFeed(url: String?): Boolean {
        val uri = url?.let(Uri::parse) ?: return true
        return isOurHost(uri.host) && (uri.path ?: "").trimEnd('/') in FEED_PATHS
    }

    private fun isAuthPage(uri: Uri): Boolean {
        val host = uri.host ?: return false
        val path = uri.path ?: ""
        return AUTH_PAGES.any { (h, prefix) -> host == h && path.startsWith(prefix) } ||
            AUTH_HOST_SUFFIXES.any { host.endsWith(it) }
    }

    private fun isHttp(uri: Uri) = uri.scheme == "http" || uri.scheme == "https"

    private fun injectBridge() = web.evaluateJavascript(bridgeJs, null)

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (!request.isForMainFrame) return false // iframes (captcha, embeds) load as usual
            if (isHttp(uri) && (isOurHost(uri.host) || isAuthPage(uri))) return false
            openExternal(uri)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            loadFailed = false
            onChallenge = false
            pageAtTop = true
            onFeed = isFeed(url)
        }

        // cloudflare serves its challenge page with 403/503. leave that page completely alone,
        // our injected script would look like tampering and make the check fail
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && response.statusCode in setOf(403, 429, 503)) onChallenge = true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            refresh.isRefreshing = false
            if (!loadFailed) {
                errorView.isVisible = false
                if (!onChallenge) injectBridge()
            }
            hideSplash()
        }

        // client side navigation in next.js only changes history, not the page
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (onChallenge) return
            pageAtTop = true
            onFeed = isFeed(url)
            web.evaluateJavascript("window.__smolishOnNav && window.__smolishOnNav()", null)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            loadFailed = true
            refresh.isRefreshing = false
            errorView.isVisible = true
            hideSplash()
        }
    }

    private inner class Chrome : WebChromeClient() {
        // fullscreen video: the webview hands us a separate view that renders the video.
        // we put it in the black container above everything, hide the system bars, and on exit
        // remove it and MUST call onCustomViewHidden() or the page stays stuck in fullscreen state.
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            fullscreen.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            fullscreen.isVisible = true
            WindowCompat.getInsetsController(window, fullscreen).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        override fun onHideCustomView() = exitFullscreen()

        // window.open (e.g. "sign in with ..." popups): give the page a real second webview on top,
        // so the popup keeps window.opener and can post the login result back and close itself
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
            closePopup()
            val child = WebView(this@MainActivity)
            applySettings(child)
            child.webViewClient = PopupClient()
            child.webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView) = closePopup()
            }
            popupHost.addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            popupHost.isVisible = true
            popup = child
            (resultMsg.obj as WebView.WebViewTransport).webView = child
            resultMsg.sendToTarget()
            return true
        }

        // without this the webview draws a grey play icon placeholder before videos start
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
            }
            return try {
                pickFiles.launch(Intent.createChooser(intent, null))
                true
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }
    }

    /** Popups only show login pages and smolish itself; other links (target=_blank etc) go to the browser. */
    private inner class PopupClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (!request.isForMainFrame || (isHttp(uri) && (isOurHost(uri.host) || isAuthPage(uri)))) return false
            openExternal(uri)
            return true
        }

        // the very first load of a popup doesn't go through shouldOverrideUrlLoading
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            val uri = Uri.parse(url ?: return)
            if (url == "about:blank" || (isHttp(uri) && (isOurHost(uri.host) || isAuthPage(uri)))) return
            view.stopLoading()
            openExternal(uri)
            closePopup()
        }
    }

    private fun closePopup() {
        val p = popup ?: return
        popup = null
        popupHost.removeView(p)
        popupHost.isVisible = false
        p.destroy()
    }

    /** Called from bridge.js. These run on a background "JavaBridge" thread, not the UI thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun onPlayingChanged(playing: Boolean) = runOnUiThread { setVideoPlaying(playing) }

        @JavascriptInterface
        fun onBarColor(color: String) = runOnUiThread { setBarColor(color) }

        @JavascriptInterface
        fun onScrollTop(atTop: Boolean) {
            pageAtTop = atTop
        }

        @JavascriptInterface
        fun saveBase64(dataUrl: String, mime: String, name: String) = runOnUiThread { saveDataUrl(dataUrl, mime, name) }
    }

    /** Paints the status bar and gesture/nav bar strips with the site's bottom nav color. */
    private fun setBarColor(css: String) {
        val color = runCatching { Color.parseColor(css) }.getOrDefault(ContextCompat.getColor(this, R.color.bg))
        statusBg.setBackgroundColor(color)
        navBg.setBackgroundColor(color)
        // dark icons on light colors and the other way around
        val light = Color.luminance(color) > 0.5f
        WindowCompat.getInsetsController(window, statusBg).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun saveDataUrl(dataUrl: String, mime: String, name: String) {
        val comma = dataUrl.indexOf(',')
        if (!dataUrl.startsWith("data:") || comma < 0) {
            toast(R.string.download_failed)
            return
        }
        val type = mime.ifBlank { dataUrl.substring(5, comma).substringBefore(';').ifBlank { "application/octet-stream" } }
        withStoragePermission {
            thread {
                val ok = runCatching {
                    val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
                    Downloads.saveBytes(this, bytes, name, type)
                }.isSuccess
                runOnUiThread { toast(if (ok) R.string.download_saved else R.string.download_failed) }
            }
        }
    }

    /** Writing to the public Downloads folder needs a permission only on android 8-9. */
    private fun withStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingStorageAction = action
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun openExternal(uri: Uri) {
        val intent = if (uri.scheme == "intent") {
            runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()?.apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }
        } else {
            Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        }
        try {
            startActivity(intent ?: return)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.no_app)
        }
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        fullscreen.removeView(view)
        fullscreen.isVisible = false
        WindowCompat.getInsetsController(window, fullscreen).show(WindowInsetsCompat.Type.systemBars())
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
    }

    private fun retry() {
        errorView.isVisible = false
        if (web.url == null) web.loadUrl(HOME) else web.reload()
    }

    private val backHandler = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                customView != null -> exitFullscreen()
                popup != null -> if (popup!!.canGoBack()) popup!!.goBack() else closePopup()
                onFeed && !errorView.isVisible -> finish()
                web.canGoBack() -> web.goBack()
                else -> web.loadUrl(HOME)
            }
        }
    }

    private fun hideSplash() {
        if (splashHidden) return
        splashHidden = true
        val wait = (MIN_SPLASH_MS - (SystemClock.uptimeMillis() - startedAt)).coerceAtLeast(0)
        splash.animate().alpha(0f).setStartDelay(wait).setDuration(350).withEndAction {
            splash.stop()
            splash.isVisible = false
            web.postDelayed({ maybeAskNotifications() }, 1500)
        }
    }

    /** Asks once, with a short explanation first, instead of throwing the system dialog at startup. */
    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT < 33 || isFinishing) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getPreferences(MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit { putBoolean("asked_notifications", true) }
        AlertDialog.Builder(this)
            .setTitle(R.string.notif_title)
            .setMessage(R.string.notif_body)
            .setPositiveButton(R.string.notif_yes) { _, _ -> notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            .setNegativeButton(R.string.notif_no, null)
            .show()
    }

    private fun setVideoPlaying(playing: Boolean) {
        videoPlaying = playing
        if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setPictureInPictureParams(pipParams())
    }

    private fun pipParams(): PictureInPictureParams = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(9, 16))
        .apply { if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(videoPlaying) }
        .build()

    // android 12+ enters pip by itself (setAutoEnterEnabled), older versions need this
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && videoPlaying && !isInPictureInPictureMode) {
            enterPictureInPictureMode(pipParams())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        refresh.isEnabled = !isInPictureInPictureMode
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush() // keep the login if the app gets killed
        if (!isInPictureInPictureMode) web.onPause()
    }

    override fun onStop() {
        super.onStop()
        // also covers closing the pip window
        web.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause()})", null)
        web.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        closePopup()
        web.destroy()
        super.onDestroy()
    }

    private fun setHeight(v: View, h: Int) {
        if (v.layoutParams.height == h) return
        v.layoutParams.height = h
        v.requestLayout()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
}
