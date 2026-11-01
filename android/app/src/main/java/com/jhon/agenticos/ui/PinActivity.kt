package com.jhon.agenticos.ui

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.function.Consumer

/** Pinned shortcuts he accepted (PinActivity), as UI prefs "aos" key "pins": [{"pkg","id","label"}], oldest first.
 *  APPS reads it for search and removes entries from their menu. */
object Pins {
    const val KEY = "pins"

    /** json with this pin added at the end; the same pkg + id again replaces the old entry (its label may change). */
    fun added(json: String?, pkg: String, id: String, label: String): String {
        val old = try { JSONArray(json ?: "[]") } catch (_: Exception) { JSONArray() }
        val out = JSONArray()
        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            if (o.optString("pkg") == pkg && o.optString("id") == id) continue
            out.put(o)
        }
        out.put(JSONObject().put("pkg", pkg).put("id", id).put("label", label))
        return out.toString()
    }

    @Synchronized fun add(c: Context, pkg: String, id: String, label: String) {
        val p = c.getSharedPreferences("aos", Context.MODE_PRIVATE)
        p.edit().putString(KEY, added(p.getString(KEY, null), pkg, id, label)).apply()
    }
}

/** Another app's "Add to Home screen" (CONFIRM_PIN_SHORTCUT / CONFIRM_PIN_APPWIDGET, PLAN P1 10): a glass sheet rises
 *  over that app, "Add <label> to Agentic OS?". Yes accepts the request and saves it: a shortcut into "pins" (it shows
 *  up in search), a widget onto the widgets page. The sheet says it's done, then dissolves. */
class PinActivity : Activity() {
    private lateinit var look: Look
    private var still = false
    private var req: LauncherApps.PinItemRequest? = null
    private var pending = AppWidgetManager.INVALID_APPWIDGET_ID  // allocated, not accepted yet
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "pin-sheet") }
    private lateinit var root: FrameLayout
    private lateinit var scrim: View
    private lateinit var sheet: LinearLayout
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var buttons: LinearLayout
    private lateinit var presence: Spring
    private var blurOn = false
    private var blurPx = -1
    private var leaving = false
    private var answered = false
    private val blurChanged = Consumer<Boolean> { on -> blurOn = on; if (::presence.isInitialized) arrive(presence.value) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val r = try { getSystemService(LauncherApps::class.java)?.getPinItemRequest(intent) } catch (_: Exception) { null }
        val savedId = state?.getInt(PENDING, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // rotated mid-setup: the request is already accepted (no longer valid), but the saved id still waits for it
        if (r == null || (!r.isValid && savedId == AppWidgetManager.INVALID_APPWIDGET_ID)) { finish(); return }
        req = r
        look = Look(this)
        still = reducedMotion(this)
        pending = savedId

        window.setDecorFitsSystemWindows(false)
        window.setDimAmount(0f)  // our own scrim instead of the theme's grey dim
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.isNavigationBarContrastEnforced = false
        blurOn = windowManager.isCrossWindowBlurEnabled
        windowManager.addCrossWindowBlurEnabledListener(mainExecutor, blurChanged)  // battery saver can switch blur off
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { leave() }

        build()
        setContentView(root)
        presence = Spring(0f, 0.001f) { arrive(it) }.apply { onRest = { if (target == 0f) finishQuietly() } }
        arrive(0f)

        // label and icon are package-manager reads: off the main thread, then the sheet rises
        val dpi = resources.displayMetrics.densityDpi
        io.execute {
            val shortcut = r.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT
            var label: CharSequence? = null; var icon: Drawable? = null
            try {
                if (shortcut) {
                    val s = r.shortcutInfo
                    label = s?.shortLabel?.takeIf { it.isNotBlank() } ?: s?.longLabel
                    icon = s?.let { getSystemService(LauncherApps::class.java)?.getShortcutIconDrawable(it, dpi) }
                } else {
                    val info = r.getAppWidgetProviderInfo(this)
                    label = info?.loadLabel(packageManager)
                    icon = info?.loadIcon(this, dpi)
                }
            } catch (_: Exception) {}  // SecurityException if Home was switched away meanwhile: the words still work
            main.post { if (!isDestroyed) fill(shortcut, label?.toString()?.takeIf { it.isNotBlank() }, icon) }
        }
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt(PENDING, pending) }

    private fun build() {
        scrim = View(this).apply {
            setBackgroundColor(look.shade(70)); alpha = 0f
            isClickable = true; setOnClickListener { leave() }
            contentDescription = "Close"
        }
        // live regions: TalkBack reads the question, then "Added" or the failure, as the words change
        title = look.text("", 20f, look.ink, true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        body = look.text("", 15f, look.ink2).apply { setPadding(0, look.px(6), 0, 0); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        buttons = LinearLayout(this).apply { setPadding(0, look.px(18), 0, 0) }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(look.px(20), look.px(20), look.px(20), look.px(16))
            addView(title); addView(body); addView(buttons, LinearLayout.LayoutParams(-1, -2))
        }
        sheet = LinearLayout(this).apply {  // double bezel: the clear tray, the glass plate inside it
            orientation = LinearLayout.VERTICAL
            background = look.shell()
            val p = look.px(6); setPadding(p, p, p, p)
            addView(inner, LinearLayout.LayoutParams(-1, -2))
            elevation = look.px(24).toFloat()
            outlineSpotShadowColor = look.ink; outlineAmbientShadowColor = look.ink
            isClickable = true  // taps on the sheet don't reach the scrim
            alpha = 0f
        }
        sheet.tag = inner
        root = FrameLayout(this).apply {
            addView(scrim, FrameLayout.LayoutParams(-1, -1))
            addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            setOnApplyWindowInsetsListener { _, ins ->
                val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                (sheet.layoutParams as FrameLayout.LayoutParams).setMargins(i.left + look.px(12), 0, i.right + look.px(12), i.bottom + look.px(12))
                sheet.requestLayout()
                WindowInsets.CONSUMED
            }
        }
    }

    private fun fill(shortcut: Boolean, label: String?, icon: Drawable?) {
        val name = label ?: if (shortcut) "this shortcut" else "this widget"
        val inner = sheet.tag as LinearLayout
        // nearly solid when the system can't blur what's behind (battery saver), so the words stay readable
        inner.background = if (blurOn) look.glassCard(22) else look.round(look.withAlpha(look.ground, 245), 22, look.glassEdge)
        if (icon != null) inner.addView(ImageView(this).apply {
            setImageDrawable(icon); scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, 0, LinearLayout.LayoutParams(look.px(48), look.px(48)).apply { bottomMargin = look.px(14) })
        if (!answered) {  // after a rotation the bind answer can land before this: keep "Added" on screen
            title.text = "Add $name to Agentic OS?"
            body.text = if (shortcut) "You'll find it when you search for it." else "It goes at the end of your widgets page."
            buttons.removeAllViews()
            buttons.addView(look.button("Cancel", false) { leave() }, LinearLayout.LayoutParams(0, -2, 1f))
            buttons.addView(View(this), LinearLayout.LayoutParams(look.px(10), 1))
            buttons.addView(look.button("Add", true) { if (shortcut) acceptShortcut(name) else acceptWidget() }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        if (still) presence.snap(1f) else presence.to(1f, response = 0.5f, damping = 1f)
        if (pending != AppWidgetManager.INVALID_APPWIDGET_ID) buttons.visibility = View.INVISIBLE  // a bind screen is answering
    }

    /** Materialize from the bottom: up 24 dp, scale 0.92 to 1, alpha and an 8 dp blur clearing together; the scrim and
     *  the window blur behind follow the same spring. Leaving runs the same path back. */
    private fun arrive(p: Float) {
        val q = p.coerceIn(0f, 1f)
        scrim.alpha = q
        sheet.alpha = q
        if (!still) {
            val k = 0.92f + 0.08f * p
            sheet.scaleX = k; sheet.scaleY = k
            sheet.pivotX = sheet.width / 2f; sheet.pivotY = sheet.height.toFloat()
            sheet.translationY = (1 - p) * look.px(24)
            sheet.blur(look.px(8) * (1 - q))
        }
        val r = if (blurOn && q > 0.02f) (look.px(24) * (if (still) 1f else q)).toInt() else 0
        if (r != blurPx && (kotlin.math.abs(r - blurPx) >= 2 || r == 0)) {  // stepped: each change is a window update
            blurPx = r
            window.attributes = window.attributes.apply { blurBehindRadius = r }
        }
    }

    // ── Shortcut ──────────────────────────────────────────────────────────────────────────────────

    private fun acceptShortcut(name: String) {
        val r = req ?: return leave()
        val s = r.shortcutInfo
        val ok = try { r.isValid && r.accept() } catch (_: Exception) { false }  // IllegalStateException once it's stale
        if (!ok || s == null) return failed()
        Pins.add(this, s.`package`, s.id, name)
        done("Added", "Search for $name to open it.")
    }

    // ── Widget: bind (Android asks once per app), accept with the id, then its own setup screen ──

    private fun acceptWidget() {
        val r = req ?: return leave()
        val info: AppWidgetProviderInfo = try { r.getAppWidgetProviderInfo(this) } catch (_: Exception) { null } ?: return failed()
        if (pending != AppWidgetManager.INVALID_APPWIDGET_ID) return
        val host = AppWidgetHost(applicationContext, WIDGET_HOST_ID)
        pending = try { host.allocateAppWidgetId() } catch (_: Exception) { return failed() }
        val bound = try { AppWidgetManager.getInstance(this).bindAppWidgetIdIfAllowed(pending, info.profile, info.provider, null) } catch (_: Exception) { false }
        if (bound) return accepted()
        buttons.visibility = View.INVISIBLE
        try {
            startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pending)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile), BIND)
        } catch (_: Exception) { drop(); failed() }
    }

    override fun onActivityResult(code: Int, res: Int, data: Intent?) {
        super.onActivityResult(code, res, data)
        if (pending == AppWidgetManager.INVALID_APPWIDGET_ID) return
        when (code) {
            BIND -> if (res == RESULT_OK) accepted() else { drop(); leave() }  // he said no on Android's screen
            CONFIGURE -> if (res == RESULT_OK) saved() else { drop(); leave() }  // backed out of the widget's setup
        }
    }

    private fun accepted() {
        val r = req
        val id = pending
        val ok = try { r != null && r.isValid && r.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id) }) }
            catch (_: Exception) { false }
        if (!ok) { drop(); return failed() }
        // contact, photo or notes widgets show blank until set up: run their screen, save only when he finishes it
        val info = try { AppWidgetManager.getInstance(this).getAppWidgetInfo(id) } catch (_: Exception) { null }
        if (info != null && widgetNeedsSetup(info.configure != null, info.widgetFeatures)) {
            buttons.visibility = View.INVISIBLE  // the widget's own setup screen takes over from here
            try { AppWidgetHost(applicationContext, WIDGET_HOST_ID).startAppWidgetConfigureActivityForResult(this, id, 0, CONFIGURE, null) }
            catch (_: Exception) { drop(); failed() }
            return
        }
        saved()
    }

    private fun saved() {
        val id = pending
        pending = AppWidgetManager.INVALID_APPWIDGET_ID
        WidgetStore.add(this, id)
        done("Added", "It's at the end of your widgets page.")
    }

    /** Hand an unused id back so none leak. */
    private fun drop() {
        val id = pending
        pending = AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val app = applicationContext
        io.execute { try { AppWidgetHost(app, WIDGET_HOST_ID).deleteAppWidgetId(id) } catch (_: Exception) {} }
    }

    // ── Endings ───────────────────────────────────────────────────────────────────────────────────

    /** The sheet itself confirms (no toast), holds a beat, then dissolves. */
    private fun done(head: String, line: String) {
        root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        answered = true
        title.text = head; body.text = line
        buttons.visibility = View.GONE
        main.postDelayed({ leave() }, 1100)
    }

    private fun failed() {
        root.performHapticFeedback(HapticFeedbackConstants.REJECT)
        answered = true
        title.text = "Couldn't add it"
        body.text = "The app may have cancelled the request. Try again from the app."
        buttons.visibility = View.GONE
        main.postDelayed({ leave() }, 1600)
    }

    private fun leave() {
        if (leaving) return
        leaving = true
        main.removeCallbacksAndMessages(null)
        if (!::presence.isInitialized || still) return finishQuietly()
        presence.to(0f, response = 0.35f, damping = 1f)
    }

    @Suppress("DEPRECATION")  // overridePendingTransition: only the API 33 path
    private fun finishQuietly() {
        if (isFinishing) return
        finish()
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        else overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::presence.isInitialized) {
            presence.stop()
            windowManager.removeCrossWindowBlurEnabledListener(blurChanged)
        }
        // left half way (rotation keeps it: isChangingConfigurations): the allocated id goes back
        if (!isChangingConfigurations) drop()
        io.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val PENDING = "pending"
        const val BIND = 1
        const val CONFIGURE = 2
    }
}
