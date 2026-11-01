package com.jhon.agenticos

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import com.jhon.agenticos.core.Link

/** Agentic OS on the phone: the dashboard (served by the PC over Tailscale Serve) in a full-screen WebView. */
class MainActivity : Activity() {
    companion object {
        const val ORIGIN = Link.ORIGIN
        const val MIC = 1
        const val STARTUP = 2
    }

    private lateinit var root: FrameLayout
    private var web: WebView? = null  // null while WebView can't start, or between a dead renderer and its rebuild
    private lateinit var offline: View
    private lateinit var offlineTitle: TextView
    private var lastGone = 0L         // when the renderer last died (elapsed ms)
    private var reloadUrl: String? = null  // renderer died while he was away: load again on return
    private val loader = HandlerThread("web-apps")
    private lateinit var bg: Handler
    private var failedUrl: String? = null
    private var waitingForMic: PermissionRequest? = null
    @Volatile private var pageIsOS = false  // the bridge only obeys the OS's own page
    @Volatile private var appList: List<Pair<String, String>> = emptyList()  // (label, package), refreshed on resume
    @Volatile private var resumed = false
    @Volatile private var pending: String? = null  // an open that came while he was away

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        loader.start(); bg = Handler(loader.looper)
        root = FrameLayout(this)
        offline = offlineScreen()
        root.addView(offline)
        setContentView(root)

        // Edge-to-edge: the root (page colour) runs under the bars, the WebView sits between them and above the keyboard.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsets.CONSUMED
        }
        barColor(Color.parseColor("#E6EEF4"))

        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            val w = web
            if (w != null && w.canGoBack()) w.goBack() else moveTaskToBack(true)
        }
        newWeb()?.loadUrl(startUrl(intent))
        // asked once: the mic for "Jharvis", texts, calls and contact names for "text Mom"
        val need = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), STARTUP)
    }

    /** Builds the page's WebView: at start, and again after its renderer dies. This is the only process that loads
     *  WebView, so it keeps the default data folder (pairing cookie, page storage). Null when WebView can't start
     *  (missing or updating, or another process holds that folder): the offline screen says so instead of crashing. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun newWeb(): WebView? {
        val w = try {
            CookieManager.getInstance().setAcceptCookie(true)
            WebView(this)
        } catch (_: Throwable) {
            showOffline("Dashboard couldn't start")
            return null
        }
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false // Jharvis's voice plays without a tap
        }
        // The page reports its background (light/dark theme) so the bars match. Only sets a colour.
        w.addJavascriptInterface(object {
            @JavascriptInterface fun bar(css: String) = runOnUiThread { parseCss(css)?.let(::barColor) }
            // the names of the apps on this phone, so Jharvis knows what he can open (labels only)
            // read from a list built off the JS thread in onResume, so the page never waits on ~200 labels
            @JavascriptInterface fun apps(): String =
                if (!pageIsOS) "[]" else org.json.JSONArray(appList.map { it.first }.distinct().sorted()).toString()
            // Jharvis opens what Jhon asked for on the phone: an https link (YouTube app takes its own) or "app:<name>"
            @JavascriptInterface fun open(target: String): String {
                if (!pageIsOS) return "refused"
                // Jhon left the app while Jharvis thought: Android silently drops the launch, so it waits for his return
                if (!resumed && !Settings.canDrawOverlays(this@MainActivity)) { pending = target; return "later" }
                return openTarget(target)
            }
            // the page's mic button: the always-open mic lives in ListenService, so it hears him with Waze on screen
            // read while the page is still loading (before pageIsOS is set), so not gated: it only reads a switch
            @JavascriptInterface fun listening(): Boolean = ListenService.wanted(this@MainActivity)
            @JavascriptInterface fun listen(on: Boolean) {
                if (!pageIsOS) return
                ListenService.want(this@MainActivity, on)
                runOnUiThread { if (on) startListening() else try { ListenService.stop(this@MainActivity) } catch (_: Exception) {} }
            }
        }, "AOSApp")
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                if (sameOrigin(req.url)) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, req.url)) } catch (_: ActivityNotFoundException) {}
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { failedUrl = null }
            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) failedUrl = req.url.toString()
            }
            override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, res: WebResourceResponse) {
                if (req.isForMainFrame && res.statusCode >= 500) failedUrl = req.url.toString() // Serve says 502 when the OS is down
            }
            override fun onPageFinished(view: WebView, url: String?) {
                pageIsOS = url != null && sameOrigin(Uri.parse(url))
                if (pageIsOS) Link.saveCookie(CookieManager.getInstance().getCookie(ORIGIN))  // :core sends with it
                if (appList.isNotEmpty()) appsChanged()  // the list may have been ready before the page
                if (failedUrl != null) showOffline("PC offline") else offline.visibility = View.GONE
                if (failedUrl == null) view.evaluateJavascript(
                    "(()=>{const s=()=>AOSApp.bar(getComputedStyle(document.documentElement).backgroundColor);" +
                        "s();new MutationObserver(s).observe(document.documentElement,{attributes:true,attributeFilter:['class']})})()", null)
            }
            // The renderer crashed or Android killed it for memory. Returning true keeps this process (the bridge, the
            // mic switch) alive; the dead WebView can't be reused, so a fresh one takes its place.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val url = (try { view.url } catch (_: Exception) { null })?.takeIf { sameOrigin(Uri.parse(it)) } ?: "$ORIGIN/"
                root.removeView(view)
                try { view.destroy() } catch (_: Exception) {}
                if (web === view) web = null
                pageIsOS = false
                val now = SystemClock.elapsedRealtime()
                val again = now - lastGone < 30_000
                lastGone = now
                val w = newWeb() ?: return true
                when {
                    again -> { failedUrl = url; showOffline("The page stopped") }  // twice in 30 s: wait for his tap, no loop
                    resumed -> w.loadUrl(url)
                    else -> reloadUrl = url  // killed while he was away: load when he comes back
                }
                return true
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            // Microphone only, and only for the OS itself.
            override fun onPermissionRequest(req: PermissionRequest) {
                if (!sameOrigin(req.origin) || PermissionRequest.RESOURCE_AUDIO_CAPTURE !in req.resources) return req.deny()
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return grantMic(req)
                waitingForMic?.deny()
                waitingForMic = req
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC)
            }
        }

        root.addView(w, 0)  // under the offline screen
        web = w
        return w
    }

    // Long-press shortcuts (res/xml/shortcuts.xml) land here when the app is already open.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { web?.loadUrl(startUrl(intent)) }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code == STARTUP) return startListening()
        val req = waitingForMic ?: return
        waitingForMic = null
        if (code == MIC && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) grantMic(req) else req.deny()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // off the main thread: an "app:" open reads ~200 labels (the bridge and ListenService open from threads too)
        pending?.let { pending = null; bg.post { try { openTarget(it) } catch (_: Exception) {} } }
        reloadUrl?.let { reloadUrl = null; web?.loadUrl(it) }
        startListening()
        askChatAccessOnce()
        // back from the Play Store: re-read the apps, then tell the page so Jharvis knows a new one at once
        bg.removeCallbacks(refreshApps)  // only a stale refresh: a held-back open above must still run
        bg.post(refreshApps)
    }

    private val refreshApps = Runnable {
        try { appList = launcherApps() } catch (_: Exception) { return@Runnable }
        runOnUiThread { if (!isDestroyed) appsChanged() }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        if (web != null) try {
            CookieManager.getInstance().flush() // keep the pairing cookie if Android kills the app
            Link.saveCookie(CookieManager.getInstance().getCookie(ORIGIN))
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        loader.quitSafely()
        web?.let { root.removeView(it); it.destroy() }
        web = null
        super.onDestroy()
    }

    private fun appsChanged() { if (pageIsOS) web?.evaluateJavascript("dispatchEvent(new Event('aos-apps'))", null) }

    /** This screen runs in :web, while Home and Setup change the shared "aos" prefs from the UI process, and each
     *  process caches the file. Asking with MODE_MULTI_PROCESS makes this process re-read it if it changed on disk. */
    @Suppress("DEPRECATION")
    private fun freshPrefs() = getSharedPreferences("aos", MODE_MULTI_PROCESS)

    // the always-open mic (ListenService) can only be started while this app is on screen (Android 14+)
    private fun startListening() {
        if (ListenService.wanted(this) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            try { ListenService.start(this) } catch (_: Exception) {}  // FGS start refused (no longer in front): Home retries
    }

    /** Chat alerts need notification access, which only Settings can grant: take him there once. The "asked" mark
     *  lives in this process's own prefs file, so writing it never rewrites the shared "aos" file. */
    private fun askChatAccessOnce() {
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        val me = android.content.ComponentName(this, ChatListener::class.java)
        if (nm.isNotificationListenerAccessGranted(me)) return
        val mine = getSharedPreferences("web", MODE_PRIVATE)
        if (mine.getBoolean("chat_access_asked", false) || freshPrefs().getBoolean("chat_access_asked", false)) return
        mine.edit().putBoolean("chat_access_asked", true).apply()
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, me.flattenToString()))
        } catch (_: Exception) {}
    }

    private fun showOffline(title: String) {
        offlineTitle.text = title
        offline.visibility = View.VISIBLE
    }

    private fun grantMic(req: PermissionRequest) = req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))

    private fun sameOrigin(u: Uri) = "${u.scheme}://${u.authority}" == ORIGIN

    private fun startUrl(i: Intent?) = i?.data?.takeIf(::sameOrigin)?.toString() ?: "$ORIGIN/"

    private fun barColor(c: Int) {
        root.setBackgroundColor(c)
        val light = Color.luminance(c) > 0.5f
        val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (light) flags else 0, flags)
    }

    /** "rgb(230, 238, 244)" -> colour; anything else (transparent, odd formats) -> null. */
    private fun parseCss(css: String): Int? {
        val n = Regex("""\d+(\.\d+)?""").findAll(css).map { it.value.toFloat() }.toList()
        if (n.size < 3 || (n.size >= 4 && n[3] == 0f)) return null
        return Color.rgb(n[0].toInt(), n[1].toInt(), n[2].toInt())
    }

    private fun offlineScreen(): View {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        fun wrap(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(px(32), px(32), px(32), px(32))
            setBackgroundColor(Color.parseColor("#E6EEF4"))
            visibility = View.GONE
            isClickable = true
            // the app logo (ic_fg has adaptive-icon padding, so the mark shows at about half this size)
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_fg) }, LinearLayout.LayoutParams(px(112), px(112)))
            offlineTitle = TextView(context).apply {
                text = "PC offline"
                textSize = 22f
                letterSpacing = -0.01f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.parseColor("#0E1726"))
                gravity = Gravity.CENTER
            }
            addView(offlineTitle, wrap(4))
            addView(TextView(context).apply {
                text = "Reconnect"
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(px(28), px(12), px(28), px(12))
                minHeight = px(48)
                background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
                    GradientDrawable().apply { cornerRadius = px(999).toFloat(); setColor(Color.parseColor("#0E1726")) }, null)
                isClickable = true
                // press feedback on touch-down, not on release
                setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                    }
                    false
                }
                setOnClickListener {
                    offline.visibility = View.GONE
                    (web ?: newWeb())?.loadUrl(failedUrl ?: "$ORIGIN/")
                }
            }, wrap(28))
        }
    }
}
