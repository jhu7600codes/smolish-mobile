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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.max
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
        private const val MAX_SPLASH_MS = 12_000L
        private const val LOADER_MIN_MS = 600L // the cube loader stays at least this long, no flicker

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
        const val ACTION_OFFLINE = "com.smolish.action.OFFLINE"
        private val AUTH_HOST_SUFFIXES = listOf(".auth0.com", ".clerk.accounts.dev", ".supabase.co", ".firebaseapp.com")
    }

    private lateinit var web: WebView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var errorView: View
    private lateinit var fullscreen: FrameLayout
    private lateinit var splash: BouncingSplashView
    private lateinit var freeze: ImageView
    private lateinit var cube: OfflineCubeView
    private lateinit var popupHost: FrameLayout
    private lateinit var statusBg: View
    private lateinit var navBg: View
    private var popup: WebView? = null

    private val bridgeJs by lazy { assets.open("bridge.js").bufferedReader().use { it.readText() } }

    @Volatile private var splashHidden = false
    private var pageReady = false
    // cube loader over a frozen screenshot, used for every page load after the first
    @Volatile private var loading = false
    private var loaderShownAt = 0L
    private var frozen: Bitmap? = null
    // seamless mode: the sound reload only freezes the last frame (no cube), so it looks like a lag
    private var seamlessLoad = false
    private var waitingForVideo = false
    private val seamlessTimeout = Runnable { finishHide() }
    private var hideTopbarNext = false
    @Volatile private var resumed = false
    private var translateState = ""
    private val translateJs by lazy { assets.open("translate.js").bufferedReader().use { it.readText() } }
    @Volatile private var soundRefreshQueued = false
    // opened from the "Offline mode" shortcut: stay offline even if the internet comes back
    private var offlineByChoice = false
    private var network: ConnectivityManager.NetworkCallback? = null
    private var loadFailed = false
    private var onChallenge = false // cloudflare "verify your browser" page is showing
    @Volatile private var pageAtTop = true
    // cached because the refresh layout asks on every touch move
    @Volatile private var onFeed = true
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
        // older androids read the splash from the current theme, so pick the holiday one first
        if (Build.VERSION.SDK_INT < 31) setTheme(Icons.splashTheme(Icons.wanted(this)))
        installSplashScreen() // system splash, dismissed right away; our overlay takes over
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        refresh = findViewById(R.id.refresh)
        errorView = findViewById(R.id.error)
        fullscreen = findViewById(R.id.fullscreen)
        splash = findViewById(R.id.splash)
        freeze = findViewById(R.id.freeze)
        cube = findViewById(R.id.cube)
        // the intro always plays to the end; "loading" only starts once the cube falls
        splash.onIntroDone = { maybeHideSplash() }
        splash.theme = Icons.wanted(this) // holiday hat / eyes match the launcher icon
        rememberSplashTheme()
        translateState = Translator.state(this)
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

        Notify.createChannel(this)
        val openUrl = intent?.getStringExtra(Notify.EXTRA_URL)
        if (intent?.action == ACTION_OFFLINE) {
            if (OfflineStore.isEnabled(this)) offlineByChoice = true else toast(R.string.offline_disabled)
        }
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(if (shouldBeOffline()) OfflineStore.URL else openUrl ?: HOME)
        }
        splash.postDelayed({ onPageReady() }, MAX_SPLASH_MS) // never get stuck on the splash
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
        // keep the renderer process at foreground priority so it doesn't get throttled.
        // (no offscreenPreRaster: on phones like the pixel 3a the extra gpu memory starved
        // video/audio decoding and made audio cut out or stutter after scrolling)
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
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
        refresh.setOnRefreshListener {
            // no spinner, the cube loader takes over (posted so the spinner is gone from the screenshot)
            refresh.isRefreshing = false
            refresh.post { reloadWithLoader() }
        }
    }

    private fun isOurHost(host: String?) = host != null && (host == HOST || host.endsWith(".$HOST"))

    private fun isFeed(url: String?): Boolean {
        val uri = url?.let(Uri::parse) ?: return true
        if (uri.host == OfflineStore.HOST) return true // the offline feed acts like the real one
        return isOurHost(uri.host) && (uri.path ?: "").trimEnd('/') in FEED_PATHS
    }

    private fun inOffline() = web.url?.let { Uri.parse(it).host } == OfflineStore.HOST

    /** Offline when asked to, or when there's no internet and a pack is saved. */
    private fun shouldBeOffline() = offlineByChoice || OfflineStore.isForced(this) ||
        (!OfflineStore.isOnline(this) && OfflineStore.canUse(this))

    private fun goOffline() = showLoader { hideError(); web.loadUrl(OfflineStore.URL) }
    private fun goOnline() = showLoader { hideError(); web.loadUrl(HOME) }

    // follow the connection: lose it -> offline feed (if there's a pack), get it back -> the site
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) runOnUiThread {
                    // the flashlight cube found wifi: show it off, then load the site again
                    if (errorView.isVisible && cube.mode == OfflineCubeView.Mode.SEARCH) cube.foundWifi { reloadWithLoader() }
                    if (inOffline() && !offlineByChoice && !OfflineStore.isForced(this@MainActivity)) goOnline()
                }
            }
            override fun onLost(n: Network) {
                // wait a moment, wifi <-> mobile handovers drop the network for a split second
                web.postDelayed({
                    if (!inOffline() && !OfflineStore.isOnline(this@MainActivity) && OfflineStore.canUse(this@MainActivity)) {
                        toast(R.string.went_offline)
                        goOffline()
                    }
                }, 3000)
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        network = cb
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // tapped a notification while the app was open in the background
        intent.getStringExtra(Notify.EXTRA_URL)?.let { url -> if (!inOffline()) showLoader { web.loadUrl(url) } }
        if (intent.action == ACTION_OFFLINE) {
            if (!OfflineStore.isEnabled(this)) return toast(R.string.offline_disabled)
            offlineByChoice = true
            if (!inOffline()) goOffline()
        }
    }

    private fun isAuthPage(uri: Uri): Boolean {
        val host = uri.host ?: return false
        val path = uri.path ?: ""
        return AUTH_PAGES.any { (h, prefix) -> host == h && path.startsWith(prefix) } ||
            AUTH_HOST_SUFFIXES.any { host.endsWith(it) }
    }

    private fun isHttp(uri: Uri) = uri.scheme == "http" || uri.scheme == "https"

    private fun injectBridge() {
        // the last unread count we saw, so a reload doesn't notify about the same things again
        val last = getSharedPreferences("app", 0).getInt("last_unread", -1)
        web.evaluateJavascript("window.__smolishLastUnread = $last;", null)
        web.evaluateJavascript(bridgeJs, null)
        // only smolish itself, not the login pages of google & co
        if (Translator.isOn(this) && isOurHost(web.url?.let { Uri.parse(it).host })) web.evaluateJavascript(translateJs, null)
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            if (request.url.host == OfflineStore.HOST) OfflineStore.serve(this@MainActivity, request) else null

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (!request.isForMainFrame) return false // iframes (captcha, embeds) load as usual
            if (isHttp(uri) && (isOurHost(uri.host) || uri.host == OfflineStore.HOST || isAuthPage(uri))) return false
            openExternal(uri)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            loadFailed = false
            onChallenge = false
            pageAtTop = true
            onFeed = isFeed(url)
            pageReady = false
            soundRefreshQueued = false
            setVideoPlaying(false) // the old page's videos are gone
            // every full page load (links, redirects, the site reloading itself) gets the cube too
            if (splashHidden) showLoader()
        }

        // cloudflare serves its challenge page with 403/503. leave that page completely alone,
        // our injected script would look like tampering and make the check fail
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && response.statusCode in setOf(403, 429, 503)) onChallenge = true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            refresh.isRefreshing = false
            if (!loadFailed) {
                hideError()
                if (inOffline()) setBarColor("#08090b") // the offline feed's nav color
                else if (!onChallenge) {
                    injectBridge()
                    if (hideTopbarNext) web.evaluateJavascript("window.__smolishHideTopbar && window.__smolishHideTopbar()", null)
                }
                hideTopbarNext = false
            }
            onPageReady()
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
            // the site can't be reached but there's a pack: show that instead of the error screen
            if (isOurHost(request.url.host) && OfflineStore.canUse(this@MainActivity)) {
                view.post { web.loadUrl(OfflineStore.URL) }
                return
            }
            loadFailed = true
            refresh.isRefreshing = false
            showError()
            onPageReady()
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

        /**
         * The webview's audio starts glitching after a few videos and a fresh page load fixes it,
         * so bridge.js asks for a reload when the 4th video starts. Returns whether we reload, so
         * the page only stops that video when we really do.
         */
        @JavascriptInterface
        fun refreshForSound(): Boolean {
            // no feed check on purpose: the site may give every video its own url
            if (loading || soundRefreshQueued || !splashHidden || !resumed || customView != null) return false
            soundRefreshQueued = true
            runOnUiThread {
                // the site doesn't show the Smols/Friends bar this deep into the feed, so keep it
                // hidden on the reloaded page too, or the jump would give the reload away
                hideTopbarNext = true
                reloadWithLoader(seamless = SettingsActivity.isSeamless(this@MainActivity))
            }
            return true
        }

        /** From translate.js: [json] is an array of english texts, answered via __smolishTrDone(id, [...]). */
        @JavascriptInterface
        fun translate(id: Int, json: String) {
            val texts = runCatching { org.json.JSONArray(json).let { a -> List(a.length()) { a.getString(it) } } }.getOrNull() ?: return
            Translator.translate(texts, Translator.lang(this@MainActivity)) { out ->
                val arr = out?.let { org.json.JSONArray(it).toString() } ?: "null"
                runOnUiThread { web.evaluateJavascript("window.__smolishTrDone && window.__smolishTrDone($id, $arr)", null) }
            }
        }

        @JavascriptInterface
        fun onAccount(handle: String) {
            getSharedPreferences("app", 0).edit().putString("account_handle", handle).apply()
        }

        @JavascriptInterface
        fun onUnread(count: Int) {
            getSharedPreferences("app", 0).edit().putInt("last_unread", count).apply()
        }

        /** The unread count went up: show it as a real notification if you're not in the app. */
        @JavascriptInterface
        fun onNotification(count: Int, who: String, what: String) {
            if (resumed) return // you're looking at the site, its own bell shows it
            Notify.show(this@MainActivity, count, who, what)
        }

        @JavascriptInterface
        fun onScrollTop(atTop: Boolean) {
            pageAtTop = atTop
        }

        @JavascriptInterface
        fun saveBase64(dataUrl: String, mime: String, name: String) = runOnUiThread { saveDataUrl(dataUrl, mime, name) }
    }

    /**
     * Android 13+ draws the system splash before our code runs, from a theme it remembers per app,
     * so tell it which holiday splash to use next time. (Android 12 can't, it keeps the classic one.)
     */
    private fun rememberSplashTheme() {
        if (Build.VERSION.SDK_INT >= 33) runCatching { splashScreen.setSplashScreenTheme(Icons.splashTheme(Icons.wanted(this))) }
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

    /** Site down but internet fine -> the cube naps. No internet at all -> it searches with a flashlight. */
    private fun showError() {
        val online = OfflineStore.isOnline(this)
        cube.setMode(if (online) OfflineCubeView.Mode.SLEEP else OfflineCubeView.Mode.SEARCH)
        findViewById<TextView>(R.id.error_title).setText(if (online) R.string.sleep_title else R.string.search_title)
        findViewById<TextView>(R.id.error_body).setText(if (online) R.string.sleep_body else R.string.search_body)
        errorView.isVisible = true
    }

    private fun hideError() {
        errorView.isVisible = false
        cube.setMode(OfflineCubeView.Mode.NONE)
    }

    // the sleeping cube wakes up and hops first, then we reload
    private fun retry() {
        if (cube.mode == OfflineCubeView.Mode.SLEEP) cube.wakeUp { reloadWithLoader() } else reloadWithLoader()
    }

    private fun reloadWithLoader(seamless: Boolean = false) {
        showLoader(seamless) {
            hideError()
            if (web.url == null) web.loadUrl(HOME) else web.reload()
        }
    }

    /**
     * Freezes the screen as it is right now (a real screenshot, so the black video frame and the
     * site's menus stay exactly where they were, no blur), bounces the cube on top of it, then
     * runs [then], e.g. the reload. Hidden again by [onPageReady].
     */
    private fun showLoader(seamless: Boolean = false, then: (() -> Unit)? = null) {
        if (!splashHidden || loading) {
            then?.invoke()
            return
        }
        loading = true
        val decor = window.decorView
        val shot = Bitmap.createBitmap(max(1, decor.width), max(1, decor.height), Bitmap.Config.ARGB_8888)
        // PixelCopy reads what's actually on screen, video surfaces included (drawing the
        // webview into a bitmap would miss those and the system bar strips)
        PixelCopy.request(window, shot, { result ->
            if (loading) {
                frozen?.recycle()
                frozen = if (result == PixelCopy.SUCCESS) shot else null
                freeze.setImageBitmap(frozen)
                freeze.animate().cancel()
                splash.animate().cancel()
                freeze.alpha = 1f
                splash.alpha = 1f
                // a seamless reload still waiting for its video is superseded by this one
                waitingForVideo = false
                freeze.removeCallbacks(seamlessTimeout)
                freeze.isVisible = true
                seamlessLoad = seamless
                if (!seamless) {
                    splash.background = null // see the frozen screen through it
                    splash.isVisible = true
                    splash.startLoader()
                }
                loaderShownAt = SystemClock.uptimeMillis()
            }
            if (frozen !== shot) shot.recycle()
            then?.invoke()
        }, Handler(Looper.getMainLooper()))
    }

    private fun hideLoader() {
        if (!loading) return
        loading = false
        if (seamlessLoad) {
            // keep the frozen frame until the new page's video really plays, then swap instantly,
            // like the video just hitched for a moment. never wait more than a few seconds.
            waitingForVideo = true
            freeze.postDelayed(seamlessTimeout, 5000)
            return
        }
        val wait = (LOADER_MIN_MS - (SystemClock.uptimeMillis() - loaderShownAt)).coerceAtLeast(0)
        splash.animate().alpha(0f).setStartDelay(wait).setDuration(250)
        freeze.animate().alpha(0f).setStartDelay(wait).setDuration(250).withEndAction {
            splash.stop()
            splash.isVisible = false
            freeze.isVisible = false
            freeze.setImageDrawable(null)
            frozen?.recycle()
            frozen = null
        }
    }

    private fun finishHide() {
        if (!waitingForVideo) return
        waitingForVideo = false
        seamlessLoad = false
        freeze.removeCallbacks(seamlessTimeout)
        freeze.isVisible = false
        freeze.setImageDrawable(null)
        frozen?.recycle()
        frozen = null
    }

    private fun onPageReady() {
        pageReady = true
        if (!splashHidden) maybeHideSplash() else hideLoader()
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

    /** The first splash goes away once the intro is done and the page has loaded, whichever is last. */
    private fun maybeHideSplash() {
        if (splashHidden || !pageReady || !splash.introFinished) return
        splashHidden = true
        splash.animate().alpha(0f).setStartDelay(150).setDuration(350).withEndAction {
            splash.stop()
            splash.isVisible = false
            // first launch: Smolish Setup (birthday + region for the seasonal icons)
            if (!Icons.setupDone(this)) startActivity(Intent(this, SetupActivity::class.java))
            else web.postDelayed({ maybeAskNotifications() }, 1500)
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
        if (playing && waitingForVideo) finishHide()
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
        // "always use offline mode" may have changed in settings
        if (!OfflineStore.isEnabled(this)) offlineByChoice = false // switched off in settings
        if (splashHidden) {
            val forced = OfflineStore.isForced(this)
            if (forced && !inOffline()) goOffline()
            else if (!forced && !offlineByChoice && inOffline() && OfflineStore.isOnline(this)) goOnline()
        }
        // translation turned on/off or another language picked in settings
        val tr = Translator.state(this)
        if (tr != translateState) {
            translateState = tr
            if (splashHidden && !inOffline()) reloadWithLoader(seamless = false)
        }
        resumed = true
        web.onResume()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        CookieManager.getInstance().flush() // keep the login if the app gets killed
        if (!isInPictureInPictureMode) web.onPause()
    }

    override fun onStart() {
        super.onStart()
        watchNetwork()
    }

    override fun onStop() {
        Icons.apply(this) // seasonal icon, swapped while you're not looking at the launcher
        rememberSplashTheme()
        network?.let { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) }
        network = null
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
