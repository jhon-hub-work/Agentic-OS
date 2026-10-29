package com.jhon.agenticos

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher

/** Agentic OS on the phone: the dashboard (served by the PC over Tailscale Serve) in a full-screen WebView. */
class MainActivity : Activity() {
    companion object {
        const val ORIGIN = "https://desktop-tge4frg.tailcb1411.ts.net:8810"
        const val MIC = 1
    }

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var offline: View
    private var failedUrl: String? = null
    private var waitingForMic: PermissionRequest? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        root = FrameLayout(this)
        web = WebView(this)
        offline = offlineScreen()
        root.addView(web)
        root.addView(offline)
        setContentView(root)

        // Edge-to-edge: the root (page colour) runs under the bars, the WebView sits between them and above the keyboard.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsets.CONSUMED
        }
        barColor(Color.parseColor("#E6EEF4"))

        CookieManager.getInstance().setAcceptCookie(true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false // Jharvis's voice plays without a tap
        }
        // The page reports its background (light/dark theme) so the bars match. Only sets a colour.
        web.addJavascriptInterface(object {
            @JavascriptInterface fun bar(css: String) = runOnUiThread { parseCss(css)?.let(::barColor) }
        }, "AOSApp")
        web.webViewClient = object : WebViewClient() {
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
                offline.visibility = if (failedUrl != null) View.VISIBLE else View.GONE
                if (failedUrl == null) view.evaluateJavascript(
                    "(()=>{const s=()=>AOSApp.bar(getComputedStyle(document.documentElement).backgroundColor);" +
                        "s();new MutationObserver(s).observe(document.documentElement,{attributes:true,attributeFilter:['class']})})()", null)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            // Microphone only, and only for the OS itself.
            override fun onPermissionRequest(req: PermissionRequest) {
                if (!sameOrigin(req.origin) || PermissionRequest.RESOURCE_AUDIO_CAPTURE !in req.resources) return req.deny()
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return grantMic(req)
                waitingForMic?.deny()
                waitingForMic = req
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC)
            }
        }

        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
        }
        web.loadUrl(startUrl(intent))
    }

    // Long-press shortcuts (res/xml/shortcuts.xml) land here when the app is already open.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { web.loadUrl(startUrl(intent)) }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        val req = waitingForMic ?: return
        waitingForMic = null
        if (code == MIC && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) grantMic(req) else req.deny()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush() // keep the pairing cookie if Android kills the app
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
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
        val pad = (24 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#E6EEF4"))
            visibility = View.GONE
            isClickable = true
            addView(TextView(context).apply {
                text = "Can't reach your PC — is it awake and Tailscale on?"
                textSize = 18f
                setTextColor(Color.parseColor("#0E1726"))
                gravity = Gravity.CENTER
            })
            addView(Button(context).apply {
                text = "Retry"
                setOnClickListener {
                    offline.visibility = View.GONE
                    web.loadUrl(failedUrl ?: "$ORIGIN/")
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad })
        }
    }
}
