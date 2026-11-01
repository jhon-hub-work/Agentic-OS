package com.jhon.agenticos.ui

import android.animation.LayoutTransition
import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.SizeF
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.ScrollView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Same number in the picker, the pin sheet and the page: Android ties widget ids to this app plus this host id. */
const val WIDGET_HOST_ID = 1024

/** "widgets" in the app's prefs: the ids in page order, "," between slots and "+" inside a stack, so "12+15,9" is a
 *  stack of 12 and 15, then 9 on its own. The old "12,15,9" reads as three singles. Heights he picked live under
 *  "widget_rows_<id>" (cells; missing = the widget's own size). */
object WidgetStore {
    private const val KEY = "widgets"
    private const val ROWS = "widget_rows_"

    fun groupsOf(s: String?): List<List<Int>> {
        val seen = HashSet<Int>()
        return s.orEmpty().split(',')
            .map { g -> g.split('+').mapNotNull { it.trim().toIntOrNull()?.takeIf(seen::add) } }
            .filter { it.isNotEmpty() }
    }
    fun parse(s: String?): List<Int> = groupsOf(s).flatten()
    fun join(ids: List<Int>) = ids.joinToString(",")
    fun joinGroups(g: List<List<Int>>) = g.filter { it.isNotEmpty() }.joinToString(",") { it.joinToString("+") }

    fun without(g: List<List<Int>>, id: Int) = g.map { it - id }.filter { it.isNotEmpty() }
    fun added(g: List<List<Int>>, id: Int) = without(g, id) + listOf(listOf(id))
    /** The slot holding id and the one after it become one stack. Nothing after it: unchanged. */
    fun stacked(g: List<List<Int>>, id: Int): List<List<Int>> {
        val i = g.indexOfFirst { id in it }
        if (i < 0 || i + 1 >= g.size) return g
        return g.take(i) + listOf(g[i] + g[i + 1]) + g.drop(i + 2)
    }
    /** The stack holding id splits back into singles, in place. */
    fun unstacked(g: List<List<Int>>, id: Int) = g.flatMap { if (id in it) it.map { x -> listOf(x) } else listOf(it) }

    private fun prefs(c: Context) = c.getSharedPreferences("aos", Context.MODE_PRIVATE)
    fun groups(c: Context) = groupsOf(prefs(c).getString(KEY, null))
    fun ids(c: Context) = groups(c).flatten()
    @Synchronized private fun edit(c: Context, f: (List<List<Int>>) -> List<List<Int>>) =
        prefs(c).edit().putString(KEY, joinGroups(f(groups(c)))).apply()
    fun add(c: Context, id: Int) = edit(c) { added(it, id) }
    fun remove(c: Context, id: Int) { edit(c) { without(it, id) }; prefs(c).edit().remove(ROWS + id).apply() }
    fun stack(c: Context, id: Int) = edit(c) { stacked(it, id) }
    fun unstack(c: Context, id: Int) = edit(c) { unstacked(it, id) }
    fun rows(c: Context, id: Int) = prefs(c).getInt(ROWS + id, 0)
    fun setRows(c: Context, id: Int, rows: Int) = prefs(c).edit().putInt(ROWS + id, rows).apply()
}

/** Home-screen cells a widget asks for, by Android's own rule: n cells = 70n - 30 dp. */
fun widgetCells(minDp: Int) = maxOf(1, (minDp + 30 + 69) / 70)

/** A widget's own setup screen runs after binding when it has one, unless the provider marks setup optional. */
fun widgetNeedsSetup(hasConfigure: Boolean, features: Int) =
    hasConfigure && features and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL == 0

/** Widget heights in cells. The page is one column, so a widget always spans its width; the height snaps to whole
 *  cells inside what the provider allows (its target, minimum and max resize sizes). All sizes in dp. */
object WidgetSizes {
    const val MAX_ROWS = 4
    fun heightDp(rows: Int) = 90 * rows - 16
    /** The fewest cells whose height holds dp. */
    fun rowsFor(dp: Int) = maxOf(1, (dp + 16 + 89) / 90)
    /** Where it starts: the provider's target height, else the fewest cells that hold its minimum. */
    fun default(minH: Int, targetRows: Int) = if (targetRows > 0) targetRows else rowsFor(minH)
    /** What the menu offers. Not resizable up and down: just its own height. maxResizeH 0 = no limit. */
    fun choices(minH: Int, minResizeH: Int, maxResizeH: Int, targetRows: Int, resizable: Boolean): List<Int> {
        val d = default(minH, targetRows)
        if (!resizable) return listOf(d)
        val lo = rowsFor(if (minResizeH in 1..minH) minResizeH else minH)  // a min resize above the min means none
        val hi = if (maxResizeH > 0) (maxResizeH + 16) / 90 else MAX_ROWS
        return ((lo..maxOf(lo, minOf(hi, MAX_ROWS))) + d).distinct().sorted()
    }
    /** His saved height when the provider still allows it, else its default. */
    fun pick(saved: Int, choices: List<Int>, default: Int) = if (saved in choices) saved else default
}

/** Paging a stack: where a released drag lands (the page a flick was heading for, at most one page from where the
 *  drag started, inside the stack) and how the position reads. Positions in pages, velocity in pages per second. */
object StackMath {
    fun settle(pos: Float, velocity: Float, from: Int, count: Int): Int =
        (pos + project(velocity)).roundToInt().coerceIn(from - 1, from + 1).coerceIn(0, maxOf(0, count - 1))
    fun label(page: Int, count: Int) = "${page + 1} of $count"
}

/** A widget that knows when it's held: on a long press the widget's own buttons get a cancel and the page shows its
 *  menu. Every touch also reports down/up so the glass plate can press in on the touch-down frame. Updates that
 *  arrive while the page is swiping wait (holding), and the last one lands on settle (flush). */
class HoldHostView(c: Context) : AppWidgetHostView(c) {
    var onHold: (() -> Unit)? = null
    var onPress: ((Boolean) -> Unit)? = null
    var holding: (() -> Boolean)? = null
    var onHeld: (() -> Unit)? = null
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var pressed = false; private var held = false; private var stolen = false
    private var parked: RemoteViews? = null; private var hasParked = false
    private var sentW = 0; private var sentH = 0
    private val fire = Runnable {
        release(); held = true
        parent?.requestDisallowInterceptTouchEvent(true)  // the page mustn't scroll under the menu
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onHold?.invoke()
    }
    // the size goes to the widget once the layout has settled, not on every frame of a resize
    private val sendSize = Runnable {
        val d = resources.displayMetrics.density
        val w = (width / d).toInt(); val h = (height / d).toInt()
        if (w <= 0 || h <= 0 || (w == sentW && h == sentH)) return@Runnable
        sentW = w; sentH = h
        val o = Bundle().apply {  // Lawnchair's way: min/max next to the exact size, for widgets that read only those
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
        }
        try { updateAppWidgetSize(o, listOf(SizeF(w.toFloat(), h.toFloat()))) } catch (_: Exception) {}
    }

    // dispatch, not intercept: a scrolling widget can turn interception off, and the timer must still be cancelled
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                held = false; stolen = false; downX = e.x; downY = e.y
                pressed = true; onPress?.invoke(true)
                postDelayed(fire, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (!held && hypot(e.x - downX, e.y - downY) > slop) release()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!held) release()
        }
        if (held) {
            if (!stolen) {
                stolen = true
                val c = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(c); c.recycle()
            }
            return true
        }
        return super.dispatchTouchEvent(e) || e.actionMasked == MotionEvent.ACTION_DOWN  // see the whole gesture
    }

    private fun release() {
        removeCallbacks(fire)
        if (pressed) { pressed = false; onPress?.invoke(false) }
    }

    // views live on main: an update arriving on any other thread hops over
    override fun updateAppWidget(rv: RemoteViews?) {
        if (Looper.myLooper() !== Looper.getMainLooper()) { MAIN.post { updateAppWidget(rv) }; return }
        if (holding?.invoke() == true) { parked = rv; hasParked = true; onHeld?.invoke(); return }
        hasParked = false; parked = null
        super.updateAppWidget(rv)
    }

    /** The page settled: the last update that waited lands now. */
    fun flush() {
        if (!hasParked) return
        val rv = parked; hasParked = false; parked = null
        super.updateAppWidget(rv)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        removeCallbacks(sendSize); postDelayed(sendSize, 150)
    }

    override fun onDetachedFromWindow() { removeCallbacks(fire); removeCallbacks(sendSize); super.onDetachedFromWindow() }

    private companion object { val MAIN = Handler(Looper.getMainLooper()) }
}

private class Host(c: Context, private val changed: () -> Unit) : AppWidgetHost(c, WIDGET_HOST_ID) {
    override fun onCreateView(c: Context, id: Int, info: AppWidgetProviderInfo?): AppWidgetHostView = HoldHostView(c)
    override fun onProvidersChanged() = changed()
}

/** The widgets page, one swipe right of Home (the iPhone Today view): the widgets he added, each in a double-bezel
 *  glass tray, stacks paged up and down, then Add widget. Transparent on purpose: it sits on Home's frost. Home calls
 *  start() in onStart and stop() in onStop; the host listens only while Home is resumed. Works with the PC off. */
class WidgetsPage(c: Context, private val look: Look) : FrameLayout(c) {
    /** What reload hands to the main thread: one widget, with the heights it allows and the one it shows. */
    private class W(val id: Int, val info: AppWidgetProviderInfo, val label: CharSequence, val choices: List<Int>, val rows: Int)

    /** One place on the page: a single widget, or a stack of them sharing one height. */
    private inner class Slot(val key: String, val ids: List<Int>, val labels: List<CharSequence>, val choices: List<Int>, var rows: Int) {
        val shell = FrameLayout(context)
        lateinit var box: View               // the view whose height is the widget's: the plate, or the stack
        var stack: StackView? = null
        var grow: Spring? = null
        fun page() = stack?.page ?: 0
        fun current() = ids[page().coerceIn(0, ids.size - 1)]
        fun describe() {
            shell.contentDescription = if (ids.size == 1) labels[0]
                else "Stack of ${ids.size} widgets, showing ${labels[page()]}, ${StackMath.label(page(), ids.size)}"
        }
    }

    private val awm = AppWidgetManager.getInstance(c)
    private val host = Host(c.applicationContext) { if (listening) reload() }
    private val main = Handler(Looper.getMainLooper())
    private val still = reducedMotion(c)
    // widgets draw in the theme's own day or night look so they sit right on its glass (look.glass), whatever the
    // phone's dark mode says
    private val widgetCtx = ContextThemeWrapper(c,
        if (look.night) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar).apply {
        applyOverrideConfiguration(Configuration(c.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (look.night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        })
    }
    private val slots = LinkedHashMap<String, Slot>()
    private var order: List<String> = emptyList()
    private val views = HashMap<Int, HoldHostView>()   // one host view per id, kept across stack and unstack
    private val column = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val empty = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(look.px(24), look.px(64), look.px(24), look.px(16))
        addView(look.text("No widgets yet", 20f, look.ink, true).apply { gravity = Gravity.CENTER })
        addView(look.text("Add the ones you check most, like your calendar or notes.", 15f, look.ink2).apply {
            gravity = Gravity.CENTER; setPadding(0, look.px(6), 0, 0)
        })
    }
    private val foot = FrameLayout(c)
    private var footFilled: Boolean? = null
    private val scroll = ScrollView(c)
    private val overlay = FrameLayout(c)
    private val menu = LinearLayout(c)
    private var held: Slot? = null      // whose menu is open
    private var lift: View? = null      // the tray that rises while its menu is open
    private val dimmed = ArrayList<View>()
    private val menuSpring: Spring
    private val blurIn = look.px(8).toFloat()
    private val blurBack = look.px(18).toFloat()
    private var started = false; private var resumed = false; private var listening = false
    private var loaded = false; private var visibleNow = false; private var cascadeNext = true
    private var gen = 0
    private var insetTop = 0; private var insetBottom = 0
    private val back = OnBackInvokedCallback { closeMenu() }
    private var backOn = false

    // updates wait while Home slides this page (P1 9); the last one lands once it stops, or after HOLD_MAX_MS anyway
    private var swiping = false
    private var timeoutArmed = false
    private val settle = Runnable { swiping = false; flushAll() }
    private val timeout = Runnable { flushAll() }

    private val activity = c as? Activity
    private val life = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: Activity) { resumed = true; sync() }
        override fun onActivityPaused(a: Activity) { resumed = false; sync() }
        override fun onActivityDestroyed(a: Activity) = a.unregisterActivityLifecycleCallbacks(this)
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    }

    init {
        column.addView(look.text("Widgets", 34f, look.ink, true).apply {
            letterSpacing = -0.025f; setPadding(look.px(4), 0, look.px(4), look.px(14))
        })
        column.addView(empty, LinearLayout.LayoutParams(-1, -2))
        column.addView(foot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.px(4) })
        // trays below a removed one glide up into its place; arrivals animate themselves (materialize)
        if (!still) column.layoutTransition = LayoutTransition().apply {
            disableTransitionType(LayoutTransition.APPEARING)
            disableTransitionType(LayoutTransition.DISAPPEARING)
            disableTransitionType(LayoutTransition.CHANGE_APPEARING)
            setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, SpringCurve(1f))
            setDuration(LayoutTransition.CHANGE_DISAPPEARING, 480)
        }
        scroll.apply {
            addView(column)
            isVerticalScrollBarEnabled = false; clipToPadding = false
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(look.px(28))
        }
        addView(scroll, LayoutParams(-1, -1))

        menu.apply {
            orientation = LinearLayout.VERTICAL
            background = look.round(look.withAlpha(look.ground, 232), 18, look.glassEdge)
            clipToOutline = true; isClickable = true
            elevation = look.px(18).toFloat()
            outlineSpotShadowColor = look.ink; outlineAmbientShadowColor = look.ink
        }
        overlay.apply {
            visibility = GONE
            setOnClickListener { closeMenu() }  // tap anywhere else to close
            addView(menu, LayoutParams(look.px(272), -2, Gravity.TOP or Gravity.START))
        }
        addView(overlay, LayoutParams(-1, -1))

        // one spring for the whole menu moment: the menu materializes, the held tray rises, everything else blurs back
        menuSpring = Spring(0f, 0.001f) { p ->
            val q = p.coerceIn(0f, 1f)
            menu.alpha = q
            val k = 0.92f + 0.08f * p; menu.scaleX = k; menu.scaleY = k
            lift?.let { val s = 1f + 0.03f * p; it.scaleX = s; it.scaleY = s }
            for (v in dimmed) { v.alpha = 1f - 0.3f * q; if (!still) v.blur(blurBack * q) }
            if (!still) menu.blur(blurIn * (1 - q))
        }.apply { onRest = { if (target == 0f) menuDone() } }

        setOnApplyWindowInsetsListener { _, ins -> pad(ins); ins }
        refreshEmpty(false)
        if (activity != null) activity.registerActivityLifecycleCallbacks(life) else resumed = true
    }

    fun start() {
        started = true
        cascadeNext = true
        sync()  // reloads once Home is also resumed
    }

    fun stop() {
        started = false
        sync()
        closeMenu(instant = true)
    }

    /** Listen while Home is both started and resumed (P1 9), on the main thread: startListening applies the updates
     *  that piled up meanwhile (views, provider changes, list data) on the calling thread. A TransactionTooLarge from
     *  that pile is ignored (the next update brings each widget back). Every resume reloads, so a widget pinned from
     *  another app's sheet shows up when Home comes back. */
    private fun sync() {
        val want = started && resumed
        if (want == listening) return
        listening = want
        try { if (want) host.startListening() else host.stopListening() } catch (_: Throwable) {}
        if (want) reload()
    }

    private fun work(job: () -> Unit) { bg.post { try { job() } catch (_: Throwable) {} } }

    // Home slides this page with translationX: any change means it's moving, a 120 ms pause means it settled
    override fun setTranslationX(tx: Float) {
        if (tx != translationX) { swiping = true; removeCallbacks(settle); postDelayed(settle, SETTLE_MS) }
        super.setTranslationX(tx)
    }

    private fun armTimeout() { if (!timeoutArmed) { timeoutArmed = true; postDelayed(timeout, HOLD_MAX_MS) } }

    private fun flushAll() {
        timeoutArmed = false; removeCallbacks(timeout)
        for (v in views.values) try { v.flush() } catch (_: Exception) {}
    }

    // Home may consume insets before they reach us, so the window's own insets are read too
    override fun onAttachedToWindow() { super.onAttachedToWindow(); rootWindowInsets?.let(::pad) }

    override fun onDetachedFromWindow() {
        removeCallbacks(settle); removeCallbacks(timeout); timeoutArmed = false; swiping = false
        super.onDetachedFromWindow()
    }

    private fun pad(ins: WindowInsets) {
        val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        insetTop = i.top; insetBottom = i.bottom
        scroll.setPadding(i.left + look.px(16), i.top + look.px(16), i.right + look.px(16), i.bottom + look.px(32))
    }

    /** The page opening (Home shows it): the trays cascade in, once per start(). */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visibleNow = isVisible
        if (isVisible && loaded && cascadeNext) cascade()
        if (!isVisible) closeMenu(instant = true)
    }

    // ── Loading: ids, provider info and saved sizes off the main thread, views on it ─────────────

    private fun reload() {
        val g = ++gen
        val app = context.applicationContext
        val d = resources.displayMetrics.density
        work {
            val bound = try { host.appWidgetIds.toSet() } catch (_: Exception) { null }
            val saved = WidgetStore.groups(app)
            val live = ArrayList<List<W>>()
            for (group in saved) {
                val ws = ArrayList<W>()
                for (id in group) {
                    // gone from Android's list: its app was uninstalled and Android already dropped it
                    if (bound != null && id !in bound) { WidgetStore.remove(app, id); continue }
                    // no info while bound: the app is mid-update; keep the id and show it next time
                    val info = try { awm.getAppWidgetInfo(id) } catch (_: Exception) { null } ?: continue
                    val label = try { info.loadLabel(app.packageManager) } catch (_: Exception) { null } ?: "Widget"
                    val target = info.targetCellHeight
                    val choices = WidgetSizes.choices((info.minHeight / d).toInt(), (info.minResizeHeight / d).toInt(),
                        (info.maxResizeHeight / d).toInt(), target, info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0)
                    val rows = WidgetSizes.pick(WidgetStore.rows(app, id), choices, WidgetSizes.default((info.minHeight / d).toInt(), target))
                    ws += W(id, info, label, choices, rows)
                }
                if (ws.isNotEmpty()) live += ws
            }
            // bound but never saved (the picker or pin sheet closed half way): give them back
            val all = saved.flatten().toSet()
            bound?.filter { it !in all }?.forEach { try { host.deleteAppWidgetId(it) } catch (_: Exception) {} }
            main.post { if (g == gen) show(live) }
        }
    }

    private fun show(live: List<List<W>>) {
        val keys = live.map { ws -> ws.joinToString("+") { it.id.toString() } }
        for (k in slots.keys.filter { it !in keys }) column.removeView(slots.remove(k)!!.shell)
        val keepIds = live.flatten().mapTo(HashSet()) { it.id }
        views.keys.retainAll(keepIds)
        val fresh = ArrayList<View>()
        var prev: View? = null  // kept slots never change order; a new one goes right after the slot before it
        live.forEachIndexed { i, ws ->
            var s = slots[keys[i]]
            if (s == null) {
                s = makeSlot(keys[i], ws) ?: return@forEachIndexed
                slots[keys[i]] = s
                fresh += s.shell
                val at = prev?.let { column.indexOfChild(it) + 1 } ?: 1  // 0 is the title
                column.addView(s.shell, at, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = look.px(14) })
            }
            prev = s.shell
        }
        order = keys.filter { it in slots }
        refreshEmpty(false)
        if (loaded && !cascadeNext) fresh.forEachIndexed { k, v -> materialize(v, 60L * k) }
        loaded = true
        if (cascadeNext && visibleNow) cascade()
    }

    private fun hostView(w: W): HoldHostView? {
        views[w.id]?.let { (it.parent as? ViewGroup)?.removeView(it); return it }
        val hv = try { host.createView(widgetCtx, w.id, w.info) as? HoldHostView } catch (_: Exception) { null } ?: return null
        views[w.id] = hv
        return hv
    }

    /** Double bezel: a clear outer tray (white 28%, bright hairline) holding the glass plate the widget lives on; a
     *  stack holds several plates in one rounded window and pages them up and down. */
    private fun makeSlot(key: String, ws: List<W>): Slot? {
        val members = ws.mapNotNull { w -> hostView(w)?.let { w to it } }
        if (members.isEmpty()) return null
        val choices = members.map { it.first.choices }.reduce { a, b -> a.intersect(b.toSet()).toList() }
            .ifEmpty { listOf(members[0].first.rows) }
        val slot = Slot(key, members.map { it.first.id }, members.map { it.first.label }, choices, members[0].first.rows)
        val plates = members.map { (_, hv) ->
            val plate = FrameLayout(context).apply {
                background = look.glassCard(22); clipToOutline = true
                addView(hv, LayoutParams(-1, -1))
            }
            // the plate presses into its tray on the touch-down frame; no tick here, or every scroll would buzz
            val press = Spring(1f, 0.0005f) { plate.scaleX = it; plate.scaleY = it }
            hv.onPress = { down ->
                if (!still) if (down) press.to(0.97f, response = 0.16f, damping = 1f) else press.to(1f, response = 0.42f, damping = 0.82f)
            }
            hv.onHold = { openMenu(slot) }
            hv.holding = { swiping }
            hv.onHeld = { armTimeout() }
            plate
        }
        slot.box = if (plates.size == 1) plates[0] else StackView(plates) { slot.describe() }.also { slot.stack = it }
        slot.shell.apply {
            background = look.shell()
            val p = look.px(6); setPadding(p, p, p, p)
            addView(slot.box, LayoutParams(-1, look.px(WidgetSizes.heightDp(slot.rows))))
        }
        slot.describe()
        slot.shell.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(v: View, n: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(v, n)
                n.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, "Widget options"))
                val st = slot.stack ?: return
                if (st.page < slot.ids.size - 1) n.addAction(AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "Next widget in stack"))
                if (st.page > 0) n.addAction(AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, "Previous widget in stack"))
            }
            override fun performAccessibilityAction(v: View, action: Int, args: Bundle?): Boolean {
                when (action) {
                    AccessibilityNodeInfo.ACTION_LONG_CLICK -> openMenu(slot)
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> slot.stack?.go(slot.page() + 1) ?: return false
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> slot.stack?.go(slot.page() - 1) ?: return false
                    else -> return super.performAccessibilityAction(v, action, args)
                }
                return true
            }
        }
        return slot
    }

    /** A stack: plates one above the other in a rounded window, dragged up and down 1:1, released onto the page a
     *  flick was heading for (Spring + project), a light tick when it lands, and "2 of 3" while it moves. A drag that
     *  would leave the stack past its ends, or goes sideways, stays with the page (scroll) and Home (swipe). */
    private inner class StackView(private val plates: List<View>, private val changed: () -> Unit) : FrameLayout(context) {
        var page = 0; private set
        private val gap = look.px(12)
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val count = look.text("", 12f, look.ink2).apply {
            background = look.round(look.glass, 10, look.glassEdge); setPadding(look.px(8), look.px(3), look.px(8), look.px(3))
            alpha = 0f; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val pos = Spring(0f, 0.001f) { place(it) }.apply { onRest = { landed() } }
        private var downX = 0f; private var downY = 0f; private var startPos = 0f
        private var decided = false; private var dragging = false
        private var vt: VelocityTracker? = null
        private val hide = Runnable { fadeCount(false) }

        init {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, look.px(22).toFloat())
            }
            clipToOutline = true
            for (p in plates) addView(p, LayoutParams(-1, -1))
            addView(count, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = look.px(8); rightMargin = look.px(8) })
            place(0f)
        }

        private fun span() = (height + gap).coerceAtLeast(1).toFloat()

        private fun place(p: Float) {
            plates.forEachIndexed { i, v ->
                v.translationY = (i - p) * span()
                v.visibility = if (abs(i - p) < 1.02f) VISIBLE else INVISIBLE
            }
            val l = StackMath.label(p.roundToInt().coerceIn(0, plates.size - 1), plates.size)
            if (l != count.text.toString()) count.text = l  // not every frame: setText relayouts
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); place(pos.value) }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; decided = false; dragging = false
                    parent?.requestDisallowInterceptTouchEvent(true)  // decide before the scroll view takes it
                    if (abs(pos.value - pos.target) > 0.01f) { pos.stop(); begin(e); return true }  // caught mid-flight
                }
                MotionEvent.ACTION_MOVE -> if (!decided) {
                    val dx = e.x - downX; val dy = e.y - downY
                    if (hypot(dx, dy) > slop) {
                        decided = true
                        val inside = if (dy > 0) pos.value > 0.01f else pos.value < plates.size - 1.01f
                        if (abs(dy) > abs(dx) && inside) { begin(e); return true }
                        parent?.requestDisallowInterceptTouchEvent(false)  // sideways or past an end: not ours
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
            }
            return false
        }

        private fun begin(e: MotionEvent) {
            decided = true; dragging = true; startPos = pos.value; downY = e.y
            vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) }
            removeCallbacks(hide); fadeCount(true)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!dragging) return false
            vt?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val raw = startPos - (e.y - downY) / span()
                    val last = (plates.size - 1).toFloat()
                    pos.snap(when { raw < 0f -> -rubberband(-raw, 1f); raw > last -> last + rubberband(raw - last, 1f); else -> raw })
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    val v = vt?.let { it.computeCurrentVelocity(1000); -it.yVelocity / span() } ?: 0f
                    vt?.recycle(); vt = null
                    val to = StackMath.settle(pos.value, if (e.actionMasked == MotionEvent.ACTION_UP) v else 0f, page, plates.size)
                    if (still) { pos.snap(to.toFloat()); landed() }
                    else pos.to(to.toFloat(), response = 0.46f, damping = if (abs(v) > 1f) 0.84f else 1f, velocity = v)
                }
            }
            return true
        }

        /** TalkBack's next and previous. */
        fun go(to: Int) {
            val t = to.coerceIn(0, plates.size - 1)
            fadeCount(true)
            if (still) { pos.snap(t.toFloat()); landed() } else pos.to(t.toFloat(), response = 0.46f, damping = 1f)
        }

        private fun landed() {
            val p = pos.value.roundToInt().coerceIn(0, plates.size - 1)
            if (p != page) { page = p; Haptics.snap(this); changed() }
            removeCallbacks(hide); postDelayed(hide, 1400)
        }

        private fun fadeCount(on: Boolean) {
            if (still) { count.alpha = if (on) 1f else 0f; return }
            count.animate().alpha(if (on) 1f else 0f).setDuration(if (on) 180 else 420).setInterpolator(SpringCurve(1f)).start()
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(hide); pos.stop(); vt?.recycle(); vt = null
            super.onDetachedFromWindow()
        }
    }

    private fun refreshEmpty(animate: Boolean) {
        val none = slots.isEmpty()
        val was = empty.visibility == VISIBLE
        empty.visibility = if (none) VISIBLE else GONE
        if (footFilled != none) {  // the one main action while the page is empty, a quiet glass pill after
            footFilled = none
            foot.removeAllViews()
            foot.addView(look.button("Add widget", none) {
                context.startActivity(Intent(context, WidgetPickerActivity::class.java))
            }.apply { minWidth = look.px(168) }, LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
        }
        if (animate && none && !was) { materialize(empty); materialize(foot, 60) }
    }

    // ── Motion ────────────────────────────────────────────────────────────────────────────────────

    private fun cascade() {
        cascadeNext = false
        if (still) return
        var k = 0
        for (i in 0 until column.childCount) {
            val v = column.getChildAt(i)
            if (v.visibility == VISIBLE) materialize(v, 40L + 60L * minOf(k++, 6))
        }
    }

    /** Materialize, don't fade: scale 0.92 to 1, alpha and an 8 dp blur together; out goes back the same way. */
    private fun materialize(v: View, delay: Long = 0, out: Boolean = false, done: (() -> Unit)? = null) {
        if (still) { v.alpha = if (out) 0f else 1f; done?.invoke(); return }
        val s = Spring(if (out) 1f else 0f, 0.001f) { p ->
            v.alpha = p.coerceIn(0f, 1f); val k = 0.92f + 0.08f * p; v.scaleX = k; v.scaleY = k
            v.blur(blurIn * (1 - p).coerceIn(0f, 1f))
        }
        s.onRest = done
        if (!out) { v.alpha = 0f; v.scaleX = 0.92f; v.scaleY = 0.92f; v.blur(blurIn) }
        v.postDelayed({ s.to(if (out) 0f else 1f, response = if (out) 0.32f else 0.55f, damping = 1f) }, delay)
    }

    // ── Long-press menu ───────────────────────────────────────────────────────────────────────────

    private fun openMenu(slot: Slot) {
        if (held != null) return
        held = slot; lift = slot.shell
        dimmed.clear()
        for (i in 0 until column.childCount) column.getChildAt(i).let { if (it !== slot.shell) dimmed += it }
        menuStage(slot, confirm = false)
        overlay.visibility = VISIBLE
        placeMenu()
        if (!backOn) { findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, back); backOn = true }
        if (still) menuSpring.snap(1f) else menuSpring.to(1f, response = 0.4f, damping = 1f)
    }

    private fun closeMenu(instant: Boolean = false) {
        if (held == null) return
        if (backOn) { findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(back); backOn = false }
        if (instant || still) { menuSpring.snap(0f); menuDone() } else menuSpring.to(0f, response = 0.35f, damping = 1f)
    }

    private fun menuDone() {
        overlay.visibility = GONE
        for (v in dimmed) { v.alpha = 1f; v.blur(0f) }
        lift?.let { it.scaleX = 1f; it.scaleY = 1f }
        dimmed.clear(); held = null; lift = null
    }

    private fun next(slot: Slot) = order.indexOf(slot.key).let { i -> if (i >= 0) order.getOrNull(i + 1)?.let(slots::get) else null }

    private fun row(label: String, color: Int, onTap: () -> Unit) = look.text(label, 17f, color).apply {
        gravity = Gravity.CENTER_VERTICAL; minHeight = look.px(52); setPadding(look.px(18), 0, look.px(18), 0)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(look.pressed))
            setExitFadeDuration(250)
        }
        isClickable = true; look.press(this, 0.985f)
        setOnClickListener { onTap() }
    }

    private fun hair() = View(context).apply { setBackgroundColor(look.hairline) }

    /** Size, stack and remove; tapping Remove turns the same glass into the confirm, so nothing jumps elsewhere. */
    private fun menuStage(slot: Slot, confirm: Boolean) {
        menu.removeAllViews()
        if (!confirm) {
            val items = ArrayList<View>()
            if (slot.choices.size > 1) items += LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(look.px(14), look.px(12), look.px(14), look.px(12))
                addView(look.text("Size", 13f, look.ink3).apply { setPadding(look.px(4), 0, 0, look.px(8)) })
                addView(LinearLayout(context).apply {
                    slot.choices.take(5).forEachIndexed { i, r ->
                        if (i > 0) addView(View(context), LinearLayout.LayoutParams(look.px(8), 1))
                        val on = r == slot.rows
                        addView(look.button("4 × $r", on) { resize(slot, r) }.apply {
                            setPadding(0, 0, 0, 0); minWidth = look.px(48)
                            contentDescription = "4 by $r cells" + if (on) ", current size" else ""
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                })
            }
            val nx = next(slot)
            if (nx != null && slot.rows in nx.choices) items += row("Make stack with next", look.ink) { stackWithNext(slot, nx) }
            if (slot.ids.size > 1) items += row("Unstack", look.ink) { unstack(slot) }
            items += row("Remove widget", look.bad) { menuStage(slot, confirm = true); placeMenu() }
            items.forEachIndexed { i, v ->
                if (i > 0) menu.addView(hair(), LinearLayout.LayoutParams(-1, 1))
                menu.addView(v, LinearLayout.LayoutParams(-1, -2))
            }
            return
        }
        val id = slot.current()
        val title = if (slot.ids.size > 1) "Remove ${slot.labels[slot.page()]} from the stack?" else "Remove this widget?"
        menu.addView(look.text(title, 17f, look.ink, true).apply { setPadding(look.px(18), look.px(16), look.px(18), 0) })
        menu.addView(look.text("The app stays on your phone.", 13f, look.ink2).apply { setPadding(look.px(18), look.px(4), look.px(18), 0) })
        menu.addView(LinearLayout(context).apply {
            setPadding(look.px(10), look.px(12), look.px(10), look.px(10))
            addView(look.button("Cancel", false) { closeMenu() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(View(context), LinearLayout.LayoutParams(look.px(8), 1))
            addView(look.button("Remove", false) { remove(slot, id) }.apply { setTextColor(look.bad) }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        if (!still) for (i in 0 until menu.childCount) menu.getChildAt(i).apply {
            alpha = 0f; animate().alpha(1f).setDuration(220).setInterpolator(SpringCurve(1f)).start()
        }
    }

    /** Under the held tray, or above it when there's no room below; inside the safe area either way. */
    private fun placeMenu() {
        val slot = held ?: return
        if (width == 0) return
        val w = minOf(look.px(272), width - look.px(32))
        (menu.layoutParams as LayoutParams).width = w
        menu.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val h = menu.measuredHeight
        val at = IntArray(2); slot.shell.getLocationInWindow(at)
        val me = IntArray(2); getLocationInWindow(me)
        val top = at[1] - me[1]; val bottom = top + slot.shell.height
        val gap = look.px(12)
        val below = bottom + gap + h <= height - insetBottom - look.px(16)
        val y = if (below) bottom + gap else maxOf(insetTop + look.px(8), top - gap - h)
        menu.translationX = (at[0] - me[0] + look.px(6)).coerceAtMost(width - w - look.px(16)).toFloat()
        menu.translationY = y.toFloat()
        menu.pivotX = 0f; menu.pivotY = if (below) 0f else h.toFloat()
        menu.requestLayout()
    }

    /** The tray grows or shrinks to whole cells on a spring; the widget hears its new size once it settles. */
    private fun resize(slot: Slot, rows: Int) {
        closeMenu()
        if (rows == slot.rows) return
        for (id in slot.ids) WidgetStore.setRows(context, id, rows)
        slot.rows = rows
        Haptics.snap(slot.shell)
        val box = slot.box
        val to = look.px(WidgetSizes.heightDp(rows)).toFloat()
        val g = slot.grow ?: Spring(box.height.toFloat().takeIf { it > 0f } ?: to, 0.5f) { h ->
            box.layoutParams = box.layoutParams.apply { height = h.roundToInt() }
        }.also { slot.grow = it }
        if (still) g.snap(to) else g.to(to, response = 0.45f, damping = 1f)
    }

    private fun stackWithNext(slot: Slot, nx: Slot) {
        closeMenu(instant = true)
        for (id in nx.ids) WidgetStore.setRows(context, id, slot.rows)
        WidgetStore.stack(context, slot.ids[0])
        Haptics.confirm(slot.shell)
        reload()
    }

    private fun unstack(slot: Slot) {
        closeMenu(instant = true)
        WidgetStore.unstack(context, slot.ids[0])
        Haptics.snap(slot.shell)
        reload()
    }

    private fun remove(slot: Slot, id: Int) {
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        WidgetStore.remove(context, id)
        if (slot.ids.size > 1) {  // one leaves a stack: the rest comes back as a fresh slot in the same place
            closeMenu(instant = true)
            views.remove(id)
            work { host.deleteAppWidgetId(id) }
            reload()
            return
        }
        slots.remove(slot.key)
        order = order - slot.key
        lift = null  // the closing menu lets go of this tray; it dissolves on its own path
        closeMenu()
        materialize(slot.shell, out = true) {
            column.removeView(slot.shell)
            views.remove(id)
            work { host.deleteAppWidgetId(id) }  // after: no error flash
            refreshEmpty(animate = true)
        }
    }

    private companion object {
        const val SETTLE_MS = 120L
        const val HOLD_MAX_MS = 1000L
        // one worker for the widget-service calls that touch no views (load, delete), shared by every page this
        // process makes; listening stays on main, so an old page's stop still lands before a new page's start
        val bg: Handler by lazy { Handler(HandlerThread("widgets").apply { start() }.looper) }
    }
}
