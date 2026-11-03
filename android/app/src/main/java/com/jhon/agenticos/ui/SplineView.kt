package com.jhon.agenticos.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.OverScroller
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Pure thread arithmetic for Fluid Spline, tested on the JVM: the spring chain, the curve through it sampled by arc
 *  length, and where each ranked app sits along it. Points are packed x and y arrays; index 0 is the orb. */
internal object SplineMath {
    const val PER = 24              // samples per span: 11 spans = 265 points, smooth at any phone size
    /** The thread at rest, as fractions of the box it winds through (x across, y down); point 0 is the orb, which
     *  the view puts at the screen's middle. It leaves the orb to the left, swings under it, climbs the right side,
     *  crosses back above it and ends along the top: one winding line over the whole screen that never crosses itself. */
    val REST = floatArrayOf(0.5f, 0.5f, 0.26f, 0.54f, 0.10f, 0.70f, 0.30f, 0.86f, 0.62f, 0.84f, 0.88f, 0.68f,
        0.88f, 0.44f, 0.66f, 0.30f, 0.36f, 0.30f, 0.12f, 0.20f, 0.32f, 0.04f, 0.80f, 0.03f)
    val N = REST.size / 2
    const val EPS = 0.3f            // px: a point this close to its target, this slow, has landed
    const val VEPS = 4f             // px/s

    fun size(n: Int) = if (n < 2) n else (n - 1) * PER + 1

    /** How much of the orb's pull point i keeps: all of it at the head, none at the tail, which stays put. */
    fun weight(i: Int, n: Int): Float { if (n < 2) return 1f; val t = 1f - i.toFloat() / (n - 1); return (t * t).coerceIn(0f, 1f) }

    /** Where point i wants to be: its rest place plus its leader's displacement, scaled down by the weights. Followed
     *  down the chain, a parked orb bends the line by weight(i) of its offset and no more. */
    private fun tx(i: Int, n: Int, p: FloatArray, r: FloatArray) = r[i] + weight(i, n) / weight(i - 1, n) * (p[i - 1] - r[i - 1])

    /** One frame of the chain (follow-the-leader): p[0] is pinned where the caller put the orb; every other point is
     *  a spring toward tx() of the point ahead of it as that point is now, so a pull runs down the line a beat late,
     *  like silk. damping 1 = no overshoot anywhere (springs in series that each don't overshoot); < 1 bounces a little.
     *  Small fixed steps, as Spring, so it stays stable on a slow frame. Returns true while anything still moves. */
    fun chain(px: FloatArray, py: FloatArray, vx: FloatArray, vy: FloatArray, rx: FloatArray, ry: FloatArray, n: Int,
              response: Float, damping: Float, dt: Float): Boolean {
        val w = 2 * PI.toFloat() / response; val k = w * w; val c = 4 * PI.toFloat() * damping / response
        var left = dt
        while (left > 0f) {
            val h = min(left, 1f / 480)
            for (i in 1 until n) {
                vx[i] += (-k * (px[i] - tx(i, n, px, rx)) - c * vx[i]) * h; px[i] += vx[i] * h
                vy[i] += (-k * (py[i] - tx(i, n, py, ry)) - c * vy[i]) * h; py[i] += vy[i] * h
            }
            left -= h
        }
        for (i in 1 until n) {
            if (abs(px[i] - tx(i, n, px, rx)) > EPS || abs(py[i] - tx(i, n, py, ry)) > EPS || abs(vx[i]) > VEPS || abs(vy[i]) > VEPS) return true
        }
        settle(px, py, vx, vy, rx, ry, n)
        return false
    }

    /** Every point straight onto its target, at rest: a new size, "Remove animations", or the last sub-pixel. */
    fun settle(px: FloatArray, py: FloatArray, vx: FloatArray, vy: FloatArray, rx: FloatArray, ry: FloatArray, n: Int) {
        for (i in 1 until n) { px[i] = tx(i, n, px, rx); py[i] = tx(i, n, py, ry); vx[i] = 0f; vy[i] = 0f }
    }

    /** The Catmull-Rom curve through the n points (it passes through every one), sampled PER times a span into xs/ys,
     *  with the arc length up to each sample in len. The ends are mirrored, so the line leaves the orb straight.
     *  Returns the sample count, size(n). */
    fun sample(px: FloatArray, py: FloatArray, n: Int, xs: FloatArray, ys: FloatArray, len: FloatArray): Int {
        if (n < 2) { if (n == 1) { xs[0] = px[0]; ys[0] = py[0]; len[0] = 0f }; return n }
        var m = 0
        for (s in 0 until n - 1) {
            val ax = if (s == 0) 2 * px[0] - px[1] else px[s - 1]; val ay = if (s == 0) 2 * py[0] - py[1] else py[s - 1]
            val dx = if (s + 2 < n) px[s + 2] else 2 * px[n - 1] - px[n - 2]; val dy = if (s + 2 < n) py[s + 2] else 2 * py[n - 1] - py[n - 2]
            for (q in (if (s == 0) 0 else 1)..PER) {
                val t = q.toFloat() / PER
                val x = cr(ax, px[s], px[s + 1], dx, t); val y = cr(ay, py[s], py[s + 1], dy, t)
                len[m] = if (m == 0) 0f else len[m - 1] + hypot(x - xs[m - 1], y - ys[m - 1])
                xs[m] = x; ys[m] = y; m++
            }
        }
        return m
    }

    private fun cr(a: Float, b: Float, c: Float, d: Float, t: Float) =
        0.5f * (2 * b + (c - a) * t + (2 * a - 5 * b + 4 * c - d) * t * t + (3 * b - a - 3 * c + d) * t * t * t)

    /** The point and unit tangent at arc length s (clamped to the ends) into out = [x, y, tx, ty]. */
    fun at(s: Float, xs: FloatArray, ys: FloatArray, len: FloatArray, m: Int, out: FloatArray) {
        if (m < 2) { out[0] = if (m == 1) xs[0] else 0f; out[1] = if (m == 1) ys[0] else 0f; out[2] = 1f; out[3] = 0f; return }
        val d = s.coerceIn(0f, len[m - 1])
        var lo = 0; var hi = m - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (len[mid] <= d) lo = mid else hi = mid }
        val seg = len[hi] - len[lo]; val f = if (seg > 1e-6f) (d - len[lo]) / seg else 0f
        val dx = xs[hi] - xs[lo]; val dy = ys[hi] - ys[lo]
        out[0] = xs[lo] + dx * f; out[1] = ys[lo] + dy * f
        val l = hypot(dx, dy)
        if (l > 1e-6f) { out[2] = dx / l; out[3] = dy / l } else { out[2] = 1f; out[3] = 0f }
    }

    /** Arc length of the sample nearest (x, y): where a finger took hold of the thread. */
    fun nearest(x: Float, y: Float, xs: FloatArray, ys: FloatArray, len: FloatArray, m: Int): Float {
        var best = 0; var bestD = Float.MAX_VALUE
        for (k in 0 until m) { val dx = xs[k] - x; val dy = ys[k] - y; val d = dx * dx + dy * dy; if (d < bestD) { bestD = d; best = k } }
        return if (m > 0) len[best] else 0f
    }

    /** Rank to place: the most used app (rank 0) sits `lead` along from the orb, each next one a pitch further. */
    fun beadAt(rank: Int, lead: Float, pitch: Float, scroll: Float) = lead + rank * pitch - scroll

    /** The ranks whose beads fall between arc lengths from and to (first > last = none). */
    fun first(from: Float, lead: Float, pitch: Float, scroll: Float) = ceil((from + scroll - lead) / pitch).toInt().coerceAtLeast(0)
    fun last(to: Float, lead: Float, pitch: Float, scroll: Float, n: Int) = min(n - 1, floor((to + scroll - lead) / pitch).toInt())

    /** Scrolled all the way: the last app's bead half a pitch short of the thread's end. */
    fun maxScroll(n: Int, lead: Float, pitch: Float, length: Float) = max(0f, lead + (n - 1) * pitch - (length - pitch / 2))

    /** A bead's strength by where it is: it melts into the orb a pitch before the head, and off the thread's tail. */
    fun fade(s: Float, lead: Float, pitch: Float, length: Float) = OrbMath.smooth((s - lead + pitch) / pitch) * OrbMath.smooth((length - s) / (pitch / 2))
}

/** Fluid Spline: no grid. Every app is a glass bead on one luminous thread that winds over the frosted screen, and
 *  Jharvis's orb is the thread's head (home draws the orb, under this view; this view says where and keeps a hole for it). The most used sit nearest the orb.
 *  Drag the orb and the whole line bends after it like silk, a spring chain a beat behind; let go and it settles into
 *  its rest shape round wherever he left the orb, which stays there until the drawer shuts. A drag anywhere else slides
 *  the beads along the thread, 1:1 under the finger, with momentum and a rubber band at both ends. One view draws it
 *  all; the frame loop runs only while the chain moves, so a still thread costs nothing. */
class SplineView(c: Context, private val look: Look, private val onLaunch: (icon: View, key: String) -> Unit,
                 private val onClose: () -> Unit, private val onSearch: () -> Unit) : FrameLayout(c) {

    val view: View get() = this
    /** Press and hold a bead: its app menu (home supplies what Add to dock and a change mean). */
    var menuActions: AppMenu.Actions? = null
    /** A tap on the orb (no drag): home's own orb click (Jharvis). */
    var onOrbTap: (() -> Unit)? = null
    /** The orb moved: home re-reads orbX and orbY. */
    var onOrbChanged: (() -> Unit)? = null
    /** Where home should draw its orb while this is open: offset from the screen's middle, px. */
    var orbX = 0f; private set
    var orbY = 0f; private set

    /** 0 = shut, 1 = open. Home's spring drives it. */
    var progress = 0f
        set(v) { field = v; applyProgress() }

    private val still = reducedMotion(c)
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private val beadR = look.px(24).toFloat()           // a 48 dp bead, which is the touch target too
    private val iconR = look.px(18).toFloat()           // the icon inside it, 36 dp
    private val pitch = look.px(62).toFloat()           // bead to bead along the thread
    private val iconMax = look.px(48)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    // the list, rebuilt when it changes (arrays indexed like `apps`)
    private var apps: List<AppEntry> = emptyList()
    private var pending: List<AppEntry>? = null         // a newer ranking waits until shut, so nothing jumps
    private var builtVersion = -1
    private var loadGen = 0
    private var badges: Map<String, AppBadges.Badge> = emptyMap()
    private var n = 0
    private var bmps = arrayOfNulls<Bitmap>(0)
    private var labels = emptyArray<String>(); private var labelW = FloatArray(0)
    private var desc = emptyArray<String>()
    private var paused = BooleanArray(0)
    private var badged = BooleanArray(0)
    // beads placed this frame (ranks first..last), screen px, and how much of each shows
    private var bx = FloatArray(0); private var by = FloatArray(0); private var ba = FloatArray(0)
    private var first = 0; private var last = -1

    // the thread: the chain's points (0 = the orb), their rest places, the curve sampled through them
    private val cn = SplineMath.N
    private val px = FloatArray(cn); private val py = FloatArray(cn); private val vx = FloatArray(cn); private val vy = FloatArray(cn)
    private val rx = FloatArray(cn); private val ry = FloatArray(cn)
    private val xs = FloatArray(SplineMath.size(cn)); private val ys = FloatArray(xs.size); private val len = FloatArray(xs.size)
    private var m = 0
    private var length = 0f
    private val thread = Path()
    private var threadDirty = true                     // the curve moved: the drawn thread is rebuilt, a plain slide reuses it
    private var threadRev = -1f
    private val pt = FloatArray(4)
    private var damp = 1f                               // 1, or a little bounce for the settle after a flick
    private var lensR = 1f
    private var lead = 0f
    private var bars = Insets.NONE
    private var top = 0f                                // the search field's top: the top tenth stays clear

    // the orb: where he left it, screen px from the middle
    private var ptrX = 0f; private var ptrY = 0f
    private var moved = false

    // scrolling: arc px the beads have slid toward the orb, rubber-band past either end while dragged
    private var scroll = 0f
    private var raw = 0f
    private val scroller = OverScroller(c)

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {  // the light round the thread: wide and faint, no blur filter
        style = Paint.Style.STROKE; strokeWidth = look.px(10).toFloat(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = look.withAlpha(look.glacier, 46)
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = look.px(2).toFloat(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = look.withAlpha(look.glacier, 235)
    }
    private val glowA = Color.alpha(glow.color); private val coreA = Color.alpha(core.color)
    private val beadFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.glass }
    private val beadEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = look.px(1).toFloat(); color = look.glassEdge }
    private val fillA = Color.alpha(look.glass); private val edgeA = Color.alpha(look.glassEdge)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.accent }
    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.withAlpha(look.ground, 240) }  // ground, not white: white is faint on dark themes
    private val pillEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = look.px(1).toFloat(); color = look.glassEdge }
    private val nameText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = look.ink; typeface = look.sans; textSize = sp(13f); textAlign = Paint.Align.CENTER }
    private val dst = RectF()
    private val lensHole = Path()                       // this view draws over home's orb: nothing is painted on its lens

    private val press = Spring(1f, 0.0005f) { invalidate() }
    private val nameIn = Spring(1f, 0.002f) { postInvalidateOnAnimation() }  // may start inside a draw (computeScroll)
    private var named = -1                              // the bead whose name shows

    /** Stands in for the canvas-drawn bead wherever a real view is needed: the launch grows from it, the app menu
     *  lifts a copy of it. Never seen itself. */
    private val anchor = ImageView(c).apply { alpha = 0f; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val searchPill = look.text("Search", 17f, look.ink3).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(look.px(20), 0, look.px(20), 0)
        background = look.glassCard(99)
        contentDescription = "Search apps"
        isClickable = true; look.press(this, 0.97f)
        setOnClickListener { onSearch() }
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        addView(anchor, LayoutParams(iconMax, iconMax))
        addView(searchPill, LayoutParams(-1, look.px(48), Gravity.TOP))
        visibility = INVISIBLE
        setOnApplyWindowInsetsListener { _, i -> useInsets(i); i }
    }

    // ── Contract: load, opening, reset, ownsTouch ───────────────────────────────────────────────────

    /** Re-rank apps and re-read hidden apps and badges on the loader thread. The first beads' icons are drawn before
     *  the list comes back; the rest fill in as they're drawn. */
    fun load() {
        val app = context.applicationContext
        val g = ++loadGen
        Loader.run {
            Icons.sync(app)
            val all = Apps.get(app)
            val ver = Apps.version
            val hidden = HiddenApps.all(app)
            val byKey = all.associateBy { it.key }
            val got = LaunchCounts.ranked(app, all.map { it.key }.filter { it !in hidden }).mapNotNull { byKey[it] }
            for (e in got.take(FIRST)) Icons.load(app, e)
            val b = AppBadges.read()
            Loader.main.post {
                if (g != loadGen) return@post
                badges = b
                val cur = pending ?: apps
                val same = ver == builtVersion && cur.size == got.size && cur.indices.all { cur[it].key == got[it].key }
                if (!same) {
                    builtVersion = ver
                    if (progress <= 0.001f || MenuChange.recent()) { apps = got; pending = null; build() } else pending = got
                } else showBadges()
            }
            for (e in got.drop(FIRST)) Loader.run { Icons.load(app, e) }  // one job each, so a menu or lookup can cut in
            Loader.run { Loader.main.post { if (g == loadGen) { fillIcons(); invalidate() } } }  // queued after them
        }
    }

    /** Called once as an open starts: anything that arrived while shut is laid out first. */
    fun opening() { pending?.let { apps = it; pending = null; build() } }

    /** Back to the start, the orb back in the middle, the line at rest, nothing pressed. */
    fun reset() {
        removeCallbacks(hold)
        scroller.forceFinished(true)
        sleep()
        touching = false; dragging = false; mode = NONE
        moved = false; ptrX = 0f; ptrY = 0f; damp = 1f
        px[0] = rx[0]; py[0] = ry[0]
        SplineMath.settle(px, py, vx, vy, rx, ry, cn)
        resample()
        hit = -1; pressed = -1; named = -1
        press.snap(1f); nameIn.snap(1f)
        scroll = 0f; raw = 0f
        place(); pose(); invalidate()
        frames()
    }

    /** True on the orb (a drag there moves it), on a bead (the thread runs every way, so a pull down there may be a
     *  slide) and anywhere once the beads have slid. At the start a pull down on empty frost is home's: it closes. */
    fun ownsTouch(x: Float, y: Float) = progress >= 0.95f && n > 0 && (onOrb(x, y) || scroll > 0.5f || beadAt(x, y) >= 0)

    // ── Building ────────────────────────────────────────────────────────────────────────────────────

    private val appsChanged: () -> Unit = { load() }

    private val badgeRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { Loader.run { val b = AppBadges.read(); Loader.main.post { badges = b; showBadges() } } }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rootWindowInsets?.let { useInsets(it) }
        Apps.listen(context, appsChanged)
        try { context.registerReceiver(badgeRx, IntentFilter(AppBadges.ACTION), Context.RECEIVER_NOT_EXPORTED) } catch (_: Exception) {}
    }

    override fun onDetachedFromWindow() {
        Apps.unlisten(appsChanged)
        try { context.unregisterReceiver(badgeRx) } catch (_: Exception) {}
        removeCallbacks(hold)
        loadGen++  // answers still on their way are dropped
        sleep(); press.stop(); nameIn.stop()
        super.onDetachedFromWindow()
    }

    private fun useInsets(i: WindowInsets) {
        val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        if (b == bars) return
        bars = b
        geometry()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = geometry()

    private fun build() {
        val list = apps
        n = list.size
        bmps = Array(n) { Icons.cached(list[it].key) }
        val maxLabel = look.px(200).toFloat()
        labels = Array(n) { TextUtils.ellipsize(list[it].label, nameText, maxLabel, TextUtils.TruncateAt.END).toString() }
        labelW = FloatArray(n) { nameText.measureText(labels[it]) }
        paused = BooleanArray(n) { list[it].suspended }
        badged = BooleanArray(n); desc = Array(n) { list[it].label }
        bx = FloatArray(n); by = FloatArray(n); ba = FloatArray(n)
        hit = -1; pressed = -1; hover = -1; a11yFocus = -1; named = -1
        showBadges()
        setScroll(scroll.coerceIn(0f, maxScroll()))
        place(); invalidate()
    }

    private fun fillIcons() { for (i in 0 until n) if (bmps[i] == null) bmps[i] = Icons.cached(apps[i].key) }

    private fun showBadges() {
        for (i in 0 until n) {
            val a = apps[i]
            val k = if (a.main) badges[a.pkg]?.count ?: 0 else 0  // badges.json counts the main profile
            badged[i] = k > 0
            desc[i] = a.label + when { k == 1 -> ", 1 notification"; k > 1 -> ", $k notifications"; else -> "" } + if (a.suspended) ", paused" else ""
        }
        invalidate()
    }

    /** The rest shape fitted to the screen: below the search field, inside the bars, the head at the middle where home
     *  parks the orb. Runs when the size or insets change; the line lands there at once. */
    private fun geometry() {
        if (width == 0) return
        lensR = min(width.toFloat(), height * 0.42f) * OrbMath.LENS * 0.45f  // home's orb view is 42% of the height, compact 0.45
        lead = lensR + beadR + look.px(10)
        top = max(height * 0.10f, bars.top + look.px(8).toFloat())
        val lp = searchPill.layoutParams as LayoutParams
        val left = bars.left + look.px(20)
        if (lp.leftMargin != left || lp.rightMargin != bars.right + look.px(20) || lp.topMargin != top.toInt()) {
            lp.leftMargin = left; lp.rightMargin = bars.right + look.px(20); lp.topMargin = top.toInt()
            post { searchPill.requestLayout() }  // not mid-layout
        }
        val l = bars.left + look.px(16) + beadR; val r = width - bars.right - look.px(16) - beadR
        val t = top + look.px(48 + 16) + beadR; val b = max(t + 1f, height - bars.bottom - look.px(16) - beadR)
        for (i in 0 until cn) { rx[i] = l + (r - l) * SplineMath.REST[i * 2]; ry[i] = t + (b - t) * SplineMath.REST[i * 2 + 1] }
        rx[0] = width / 2f; ry[0] = height / 2f
        if (moved) clampLens(ptrX, ptrY)  // turned with the drawer open: keep it on screen
        px[0] = rx[0] + ptrX; py[0] = ry[0] + ptrY
        SplineMath.settle(px, py, vx, vy, rx, ry, cn)
        resample()
        setScroll(scroll.coerceIn(0f, maxScroll()))
        place(); pose(); invalidate()
    }

    private fun resample() { m = SplineMath.sample(px, py, cn, xs, ys, len); length = if (m > 0) len[m - 1] else 0f; threadDirty = true }

    private fun maxScroll() = SplineMath.maxScroll(n, lead, pitch, length)

    // ── Placing: beads along the thread, the thread itself, whose name shows ────────────────────────

    private fun stage(p: Float, from: Float, span: Float) = if (still) 1f else ((p - from) / span).coerceIn(0f, 1f)
    private fun start() = lensR * 0.9f  // the thread starts just inside the orb's rim; onDraw's hole for the lens hides that tip

    /** How far along the thread has drawn itself: opening, it runs out from the orb as the orb lands; "Remove animations": all at once. */
    private fun reveal() = start() + (length - start()) * OrbMath.smooth(stage(progress, 0.3f, 0.65f))

    /** Sets every shown bead's place and strength and rebuilds the drawn thread. Runs when the chain, the scroll or
     *  the opening moves; onDraw only paints. */
    private fun place() {
        if (n == 0 || m < 2) { first = 0; last = -1; thread.rewind(); threadDirty = true; return }
        val rev = reveal()
        first = SplineMath.first(lead - pitch, lead, pitch, scroll); last = SplineMath.last(min(length, rev), lead, pitch, scroll, n)
        for (j in first..last) {
            val s = SplineMath.beadAt(j, lead, pitch, scroll)
            SplineMath.at(s, xs, ys, len, m, pt)
            bx[j] = pt[0]; by[j] = pt[1]
            ba[j] = SplineMath.fade(s, lead, pitch, length) * (if (still) 1f else OrbMath.smooth((rev - s) / pitch))  // each grows in as the thread passes it
        }
        if (threadDirty || rev != threadRev) buildThread(rev)
        name()
    }

    /** The drawn thread from the orb's rim to how far it has revealed, through the samples. */
    private fun buildThread(rev: Float) {
        threadDirty = false; threadRev = rev
        thread.rewind()
        val s0 = start()
        if (rev > s0) {
            SplineMath.at(s0, xs, ys, len, m, pt); thread.moveTo(pt[0], pt[1])
            for (k in 0 until m) if (len[k] > s0 && len[k] < rev) thread.lineTo(xs[k], ys[k])
            SplineMath.at(rev, xs, ys, len, m, pt); thread.lineTo(pt[0], pt[1])
        }
    }

    /** The name shows for the focused bead, else the one nearest his finger while he slides, else the one nearest the
     *  orb. A new one fades in, with a light tick if his finger brought it. */
    private fun name() {
        val f = a11yFocus
        val want = if (f in first..last && ba[f] >= 0.5f) f else if (touching && mode == LIST && (dragging || hit >= 0)) nearest(fingerX, fingerY, Float.MAX_VALUE)
            else nearest(px[0], py[0], Float.MAX_VALUE)
        if (want == named) return
        named = want
        if (want >= 0 && dragging) Haptics.crossing(this)  // touch-down has its own tick
        if (want >= 0 && !still) { nameIn.snap(0f); nameIn.to(1f, response = 0.25f) }
    }

    /** The shown bead nearest (x, y) within reach of its centre, or -1. */
    private fun nearest(x: Float, y: Float, reach: Float): Int {
        var best = -1; var bestD = reach * reach
        for (j in first..last) {
            if (ba[j] < 0.5f) continue
            val dx = bx[j] - x; val dy = by[j] - y; val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = j }
        }
        return best
    }

    // ── The orb ─────────────────────────────────────────────────────────────────────────────────────

    private fun orbCx() = width / 2f + orbX
    private fun orbCy() = height / 2f + orbY
    private fun onOrb(x: Float, y: Float) = hypot(x - orbCx(), y - orbCy()) <= max(lensR * 1.15f, look.px(24).toFloat())

    private fun pose() {
        if (abs(ptrX - orbX) < 0.1f && abs(ptrY - orbY) < 0.1f) return
        orbX = ptrX; orbY = ptrY
        onOrbChanged?.invoke()
    }

    /** The orb kept inside the screen, clear of the bars and the top tenth. */
    private fun clampLens(x: Float, y: Float) {
        val r = lensR
        ptrX = x.coerceIn(bars.left + r - width / 2f, max(bars.left + r - width / 2f, width / 2f - bars.right - r))
        ptrY = y.coerceIn(top + r - height / 2f, max(top + r - height / 2f, height / 2f - bars.bottom - r))
    }

    /** The head follows the orb this frame; the rest of the chain catches up on the frame loop. */
    private fun moveHead() {
        px[0] = rx[0] + ptrX; py[0] = ry[0] + ptrY
        if (!still) { pose(); wake(); return }  // this frame's step resamples and places, once
        SplineMath.settle(px, py, vx, vy, rx, ry, cn)  // "Remove animations": the line just follows, no wobble
        resample(); place(); pose(); invalidate()
    }

    // ── The frame loop: only while the chain moves ──────────────────────────────────────────────────

    private var running = false
    private var lastFrame = 0L
    private val frameCb = Choreographer.FrameCallback { step(it) }
    private var fastOn = false

    private fun frames() { val on = running || touching || !scroller.isFinished; if (on != fastOn) { fastOn = on; fastFrames(on) } }

    private fun wake() {
        if (running || !isAttachedToWindow) return
        running = true; lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(frameCb)
        frames()
    }

    private fun sleep() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCb)
        frames()
    }

    private fun step(now: Long) {
        if (!running) return
        val dt = if (lastFrame == 0L) 1f / 120 else ((now - lastFrame) / 1e9f).coerceIn(0f, 1f / 30)
        lastFrame = now
        val moving = SplineMath.chain(px, py, vx, vy, rx, ry, cn, RESPONSE, damp, dt)
        resample(); place(); invalidate()
        if (moving) { Choreographer.getInstance().postFrameCallback(frameCb); return }
        running = false; damp = 1f
        settleScroll()  // the line's length changed under the scroll: back inside its ends
        frames()
    }

    // ── Frames ──────────────────────────────────────────────────────────────────────────────────────

    private fun applyProgress() {
        val p = progress
        if (p <= 0.001f) pending?.let { apps = it; pending = null; build() }
        // the search field arrives once the thread is mostly out, same path in and out
        val a = if (still) p.coerceIn(0f, 1f) else stage(p, 0.35f, 0.65f)
        searchPill.alpha = a
        searchPill.translationY = if (still) 0f else look.px(12) * (1 - a)
        val pv = if (a > 0.02f) VISIBLE else INVISIBLE
        if (searchPill.visibility != pv) searchPill.visibility = pv
        place()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val p = progress
        if (n == 0 || p <= 0.001f || m < 2) return
        val a = if (still) p.coerceIn(0f, 1f) else min(1f, p * 1.6f)
        glow.alpha = (glowA * a).toInt(); core.alpha = (coreA * a).toInt()
        // home draws the orb at progress x where he left it, so as it shuts or search opens the whole thread rides with it
        val t = min(p, 1f) - 1f
        canvas.save()
        canvas.translate(t * orbX, t * orbY)
        lensHole.rewind(); lensHole.addCircle(orbCx(), orbCy(), lensR, Path.Direction.CW)
        canvas.clipOutPath(lensHole)
        canvas.drawPath(thread, glow)
        canvas.drawPath(thread, core)
        for (j in last downTo first) {  // the most used drawn last, on top where beads meet round a bend
            val f = ba[j] * a * (if (paused[j]) 0.5f else 1f)
            if (f < 0.01f) continue
            val k = (if (still) 1f else 0.6f + 0.4f * ba[j]) * (if (j == pressed) press.value else 1f)
            val x = bx[j]; val y = by[j]; val r = beadR * k
            beadFill.alpha = (fillA * f).toInt(); beadEdge.alpha = (edgeA * f).toInt()
            canvas.drawCircle(x, y, r, beadFill)
            canvas.drawCircle(x, y, r, beadEdge)
            val bm = bmps[j]
            if (bm != null) { val ir = iconR * k; dst.set(x - ir, y - ir, x + ir, y + ir); iconPaint.alpha = (255 * f).toInt(); canvas.drawBitmap(bm, null, dst, iconPaint) }
            if (badged[j]) { dotPaint.alpha = (255 * f).toInt(); canvas.drawCircle(x + r * 0.72f, y - r * 0.72f, look.px(4) * k, dotPaint) }
        }
        drawName(canvas, a)
        canvas.restore()
    }

    /** The named bead's name in a small pill just under it (above it near the bottom edge). */
    private fun drawName(canvas: Canvas, a: Float) {
        val j = named; val pa = nameIn.value.coerceIn(0f, 1f) * a * (if (j in first..last) ba[j] else 0f)
        if (j !in first..last || pa < 0.01f) return
        val h = look.px(28).toFloat(); val w = labelW[j] + look.px(24); val gap = look.px(6)
        var t = by[j] + beadR + gap
        if (t + h > height - bars.bottom) t = by[j] - beadR - gap - h
        val l = (bx[j] - w / 2).coerceIn(look.px(8).toFloat(), max(look.px(8).toFloat(), width - look.px(8) - w))
        dst.set(l, t, l + w, t + h)
        pillFill.alpha = (240 * pa).toInt(); pillEdge.alpha = (edgeA * pa).toInt(); nameText.alpha = (255 * pa).toInt()
        canvas.drawRoundRect(dst, h / 2, h / 2, pillFill)
        canvas.drawRoundRect(dst, h / 2, h / 2, pillEdge)
        canvas.drawText(labels[j], l + w / 2, t + h / 2 + nameText.textSize * 0.36f, nameText)
    }

    // ── Scrolling: along the thread ─────────────────────────────────────────────────────────────────

    private fun setScroll(v: Float) {
        if (v == scroll) return
        scroll = v
        place()
        invalidate()
    }

    /** Past either end while dragged: rubber-band, so the edge is felt, never a wall. */
    private fun banded(r: Float): Float {
        val mx = maxScroll()
        return when { r < 0f -> -rubberband(-r, height.toFloat()); r > mx -> mx + rubberband(r - mx, height.toFloat()); else -> r }
    }

    /** banded's inverse: the finger travel that shows the scroll now, so catching it past an end doesn't jump. */
    private fun unbanded() = HexMath.unband(scroll, 0f, maxScroll(), height.toFloat())

    private fun settleScroll() {
        if (touching || !scroller.isFinished) return
        val mx = maxScroll()
        if (scroll >= 0f && scroll <= mx) return
        if (still || !scroller.springBack(0, scroll.toInt(), 0, 0, 0, mx.toInt())) setScroll(scroll.coerceIn(0f, mx)) else postInvalidateOnAnimation()  // under a pixel out: no spring, just in
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { setScroll(scroller.currY.toFloat()); postInvalidateOnAnimation() } else frames()
    }

    private fun scrollBy(d: Float) {
        scroller.forceFinished(true)
        val to = (scroll + d).coerceIn(0f, maxScroll())
        if (still) setScroll(to) else { scroller.startScroll(0, scroll.toInt(), 0, (to - scroll).toInt(), 350); postInvalidateOnAnimation() }
    }

    // ── Touch: tap, hold, slide, drag the orb ───────────────────────────────────────────────────────

    private var touching = false
    private var dragging = false
    private var held = false
    private var caught = false      // the touch stopped a running fling: it doesn't also launch
    private var mode = NONE
    private var hit = -1
    private var pressed = -1
    private var pid = -1
    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f
    private var fingerX = 0f; private var fingerY = 0f
    private var grabX = 0f; private var grabY = 0f  // finger to orb: it moves 1:1 from where he took it
    private var grabS = 0f                          // the thread's point he took hold of, in arc px with the scroll added
    private var vt: VelocityTracker? = null

    private val hold = Runnable {
        val i = hit
        if (i !in 0 until n || dragging || progress < 0.9f) return@Runnable
        held = true
        unpress()
        menuActions?.let { AppMenu.show(anchorAt(i), apps[i].key, look, it) }
    }

    private fun beadAt(x: Float, y: Float) = nearest(x, y, max(beadR, look.px(24).toFloat()) + look.px(4))

    private fun grab(x: Float, y: Float) { grabX = x - orbCx(); grabY = y - orbCy() }

    // the orb can rest under the search field: on it, the touch is the orb's before the field sees it
    override fun onInterceptTouchEvent(e: MotionEvent) =
        e.actionMasked == MotionEvent.ACTION_DOWN && progress >= 0.9f && n > 0 && onOrb(e.x, e.y)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // a finger already down when it starts closing still gets its UP/CANCEL, so nothing stays "touching"
        if (progress < 0.9f && e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true; dragging = false; held = false; hit = -1
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; fingerX = e.x; fingerY = e.y; pid = e.getPointerId(0)
                vt?.recycle(); vt = VelocityTracker.obtain().apply { addMovement(e) }
                caught = !scroller.isFinished
                scroller.forceFinished(true)
                frames()
                if (onOrb(e.x, e.y)) {
                    mode = ORB; tick(); damp = 1f
                    grab(e.x, e.y)
                    parent?.requestDisallowInterceptTouchEvent(true)  // a drag here moves the orb, never closes
                    name()
                    return true
                }
                mode = LIST
                if (scroll > 0.5f || caught) parent?.requestDisallowInterceptTouchEvent(true)  // every drag slides once off the start
                raw = unbanded()
                grabS = SplineMath.nearest(e.x, e.y, xs, ys, len, m) + scroll
                hit = if (caught) -1 else beadAt(e.x, e.y)
                name(); invalidate()
                if (hit >= 0) {
                    if (hit != pressed) press.snap(1f)  // the last bead's spring back doesn't hand its squish to this one
                    tick(); pressed = hit
                    if (!still) press.to(0.9f, response = 0.16f, damping = 1f)
                    postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (held) return true
                val k = e.findPointerIndex(pid).takeIf { it >= 0 } ?: return true
                val x = e.getX(k); val y = e.getY(k)
                if (mode == ORB) {
                    if (!dragging) {
                        if (hypot(x - downX, y - downY) <= slop) return true
                        dragging = true; moved = true
                        grab(x, y)  // moves 1:1 from here, so the slop doesn't jump it
                    }
                    clampLens(x - grabX - width / 2f, y - grabY - height / 2f)
                    moveHead()
                    return true
                }
                if (!dragging) {
                    val dx = x - downX; val dy = y - downY
                    if (abs(dy) <= slop && abs(dx) <= slop) return true
                    removeCallbacks(hold); unpress(); hit = -1
                    // home's own test: wait for a clear direction, and an unowned pull down is home's (it closes)
                    val vertical = abs(dy) > abs(dx) * 1.3f; val horizontal = abs(dx) > abs(dy) * 1.3f
                    if (!vertical && !horizontal) return true
                    if (vertical && dy > 0 && !ownsTouch(downX, downY)) return true
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    lastX = x; lastY = y  // slides 1:1 from here, so the slop doesn't jump
                }
                // the finger's move along the thread where he took hold of it: that point stays under his finger
                SplineMath.at(grabS - scroll, xs, ys, len, m, pt)
                raw -= (x - lastX) * pt[2] + (y - lastY) * pt[3]
                lastX = x; lastY = y; fingerX = x; fingerY = y
                setScroll(banded(raw))
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == pid) {
                val k = if (e.actionIndex == 0) 1 else 0  // the other finger carries on from where things are
                pid = e.getPointerId(k)
                lastX = e.getX(k); lastY = e.getY(k)
                if (mode == ORB) grab(e.getX(k), e.getY(k))
                else { grabS = SplineMath.nearest(lastX, lastY, xs, ys, len, m) + scroll; raw = unbanded() }
            }
            MotionEvent.ACTION_UP -> finish(e, true)
            MotionEvent.ACTION_CANCEL -> finish(e, false)
        }
        return true
    }

    private fun finish(e: MotionEvent, up: Boolean) {
        removeCallbacks(hold)
        touching = false
        vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
        val fx = vt?.getXVelocity(pid) ?: 0f; val fy = vt?.getYVelocity(pid) ?: 0f
        if (mode == ORB) {
            if (up && !dragging && !caught && progress >= 0.9f) onOrbTap?.invoke()  // a tap on the orb is still Jharvis; one that stopped a fling isn't
            // the orb stays where he let go; a flick leaves the line swinging a little as it settles
            if (dragging && up && !still && hypot(fx, fy) > look.px(FLICK_DP)) { damp = FLICK_DAMP; wake() }
        } else {
            val tap = up && progress >= 0.9f && !dragging && !held && !caught && hit >= 0
            unpress()
            if (tap) launch(hit)
            val mx = maxScroll()
            if (dragging && up && !still && scroll in 0f..mx) {
                SplineMath.at(grabS - scroll, xs, ys, len, m, pt)
                val v = fx * pt[2] + fy * pt[3]  // the flick along the thread
                scroller.fling(0, scroll.toInt(), 0, (-v).toInt(), 0, 0, 0, mx.toInt(), 0, look.px(40))
                postInvalidateOnAnimation()
            }
        }
        vt?.recycle(); vt = null
        dragging = false; mode = NONE
        settleScroll()
        place(); invalidate()  // the name goes back to the bead nearest the orb
        frames()
    }

    private fun unpress() { if (still) press.snap(1f) else press.to(1f, response = 0.42f, damping = 0.82f) }

    /** The invisible stand-in, moved onto bead i at its icon's size. */
    private fun anchorAt(i: Int): View {
        anchor.setImageBitmap(bmps[i])
        anchor.translationX = bx[i] - iconMax / 2f; anchor.translationY = by[i] - iconMax / 2f
        val s = iconR * (if (still) 1f else 0.6f + 0.4f * ba[i]) / (iconMax / 2f)  // as onDraw sizes it, so the menu's lift starts there
        anchor.scaleX = s; anchor.scaleY = s
        return anchor
    }

    /** Through the lock prompt when the app is locked; onLaunch runs only once it passes. */
    private fun launch(i: Int) {
        val key = apps[i].key
        val from = anchorAt(i)
        val act = context as? Activity
        if (act == null) onLaunch(from, key) else LockedApps.launch(act, key, apps[i].label) { onLaunch(from, key) }
    }

    // ── TalkBack: every shown bead is a virtual button; scroll forward or back slides the thread ─────────

    private var hover = -1
    private var a11yFocus = -1
    private val screenAt = IntArray(2)

    private fun reachable(i: Int) = i in first..last && ba[i] >= 0.5f

    private val a11y = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(id: Int): AccessibilityNodeInfo? {
            val host = this@SplineView
            if (id == NO_ID) {
                val info = AccessibilityNodeInfo(host)
                onInitializeAccessibilityNodeInfo(info)
                if (searchPill.visibility == VISIBLE) info.addChild(searchPill)  // a provider host lists its real children itself
                for (i in first..last) if (reachable(i)) info.addChild(host, i)
                if (maxScroll() > 0f) {
                    info.isScrollable = true
                    if (scroll < maxScroll()) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                    if (scroll > 0f) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                }
                return info
            }
            if (id !in 0 until n) return null
            return AccessibilityNodeInfo(host, id).apply {
                setParent(host)
                packageName = context.packageName; className = Button::class.java.name
                contentDescription = desc[id]
                isEnabled = true; isFocusable = true; isClickable = true; isLongClickable = true
                isVisibleToUser = reachable(id)
                isAccessibilityFocused = a11yFocus == id
                getLocationOnScreen(screenAt)
                val r = beadR
                setBoundsInScreen(Rect((screenAt[0] + bx[id] - r).toInt(), (screenAt[1] + by[id] - r).toInt(),
                    (screenAt[0] + bx[id] + r).toInt(), (screenAt[1] + by[id] + r).toInt()))
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
                addAction(if (a11yFocus == id) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                    else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
            }
        }

        override fun performAction(id: Int, action: Int, args: Bundle?): Boolean {
            if (id == NO_ID) {
                val dir = when (action) { AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 1f; AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -1f; else -> 0f }
                if (dir == 0f || maxScroll() <= 0f) return performAccessibilityAction(action, args)
                scrollBy(dir * (length - lead) * 0.7f)
                postDelayed({ sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED) }, if (still) 0L else 380L)  // once it has moved
                return true
            }
            if (id !in 0 until n) return false
            // a node read before a slide may have melted into the orb since: only shown beads open
            if ((action == AccessibilityNodeInfo.ACTION_CLICK || action == AccessibilityNodeInfo.ACTION_LONG_CLICK) && !reachable(id)) return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> { launch(id); true }
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> { menuActions?.let { AppMenu.show(anchorAt(id), apps[id].key, look, it) }; true }
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    if (a11yFocus != id) { a11yFocus = id; name(); invalidate(); send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) }; true
                }
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (a11yFocus == id) { a11yFocus = -1; name(); invalidate(); send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED) }; true
                }
                else -> false
            }
        }
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = a11y

    override fun dispatchHoverEvent(e: MotionEvent): Boolean {
        if (context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled != true) return super.dispatchHoverEvent(e)
        val onPill = searchPill.visibility == VISIBLE && e.x >= searchPill.left && e.x < searchPill.right &&
            e.y >= searchPill.top + searchPill.translationY && e.y < searchPill.bottom + searchPill.translationY  // a real view: its own
        val i = if (onPill || e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) -1 else beadAt(e.x, e.y)
        if (i != hover) {
            val was = hover; hover = i
            if (i >= 0) send(i, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            if (was >= 0) send(was, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
        }
        return i >= 0 || super.dispatchHoverEvent(e)
    }

    private fun send(i: Int, type: Int) {
        val ev = AccessibilityEvent(type).apply {
            setSource(this@SplineView, i)
            packageName = context.packageName; className = Button::class.java.name
            contentDescription = desc[i]
        }
        parent?.requestSendAccessibilityEvent(this, ev)
    }

    private companion object {
        const val NONE = 0; const val LIST = 1; const val ORB = 2
        const val FIRST = 30            // icons drawn before the list comes back: about the beads on a full thread
        const val RESPONSE = 0.15f      // s, each link of the chain: eleven in a row give the tail its silk lag
        const val FLICK_DP = 900        // dp/s: let go of the orb faster than this and the line swings as it lands
        const val FLICK_DAMP = 0.72f    // that swing: a little overshoot, gone in a beat
    }
}
