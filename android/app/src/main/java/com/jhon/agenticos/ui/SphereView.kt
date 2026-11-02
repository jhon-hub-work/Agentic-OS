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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure globe arithmetic, kept apart so it can be tested on the JVM. Screen axes: x right, y down, z toward him.
 *  Turns are unit quaternions [w, x, y, z] (no gimbal lock), applied to points through a 3x3 matrix once a frame. */
internal object SphereMath {
    const val D = 3f            // camera distance in globe radii: back icons draw at half the front's size
    const val FRONT = 0f        // the front half takes a tap or hold: what draws near full strength can be tapped (review 2 Nov)
    const val REST = 20f        // px/s: a fling slower than this has stopped
    const val IDLE = 0.12f      // rad/s, the idle spin: a turn in about 52 s
    private val K = sqrt(D * D - 1)

    /** n even points on the unit sphere, packed [x0, y0, z0, ...]: OrbMath's golden spiral with its pole turned to
     *  face him, so the first apps of a ranked list start in front. A lone app sits dead centre. */
    fun points(n: Int): FloatArray {
        val out = FloatArray(n.coerceAtLeast(0) * 3)
        if (n == 1) { out[2] = 1f; return out }
        val s = FloatArray(out.size)
        OrbMath.sphere(n, s)
        for (i in 0 until n) { out[i * 3] = s[i * 3]; out[i * 3 + 1] = s[i * 3 + 2]; out[i * 3 + 2] = s[i * 3 + 1] }
        return out
    }

    /** A turn of `angle` radians about (ax, ay, az), any length; a zero axis is no turn. */
    fun axisAngle(ax: Float, ay: Float, az: Float, angle: Float, out: FloatArray) {
        val l = sqrt(ax * ax + ay * ay + az * az)
        if (l < 1e-9f || angle == 0f) { out[0] = 1f; out[1] = 0f; out[2] = 0f; out[3] = 0f; return }
        val s = sin(angle / 2) / l
        out[0] = cos(angle / 2); out[1] = ax * s; out[2] = ay * s; out[3] = az * s
    }

    /** out = a·b: b's turn, then a's. out may be a or b. Renormalised, so thousands of small turns never drift. */
    fun mul(a: FloatArray, b: FloatArray, out: FloatArray) {
        val w = a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3]
        val x = a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2]
        val y = a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1]
        val z = a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0]
        val l = sqrt(w * w + x * x + y * y + z * z)
        out[0] = w / l; out[1] = x / l; out[2] = y / l; out[3] = z / l
    }

    /** The turn as a row-major 3x3 matrix, so a frame turns every point with nine multiplies. */
    fun matrix(q: FloatArray, m: FloatArray) {
        val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
        m[0] = 1 - 2 * (y * y + z * z); m[1] = 2 * (x * y - w * z); m[2] = 2 * (x * z + w * y)
        m[3] = 2 * (x * y + w * z); m[4] = 1 - 2 * (x * x + z * z); m[5] = 2 * (y * z - w * x)
        m[6] = 2 * (x * z - w * y); m[7] = 2 * (y * z + w * x); m[8] = 1 - 2 * (x * x + y * y)
    }

    fun apply(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
        out[0] = m[0] * x + m[1] * y + m[2] * z
        out[1] = m[3] * x + m[4] * y + m[5] * z
        out[2] = m[6] * x + m[7] * y + m[8] * z
    }

    /** The turn for a finger moving (dx, dy) px over a globe of radius r px: about the screen axis square to the move,
     *  by as much as keeps the icon under the finger at the front (trackball). */
    fun drag(dx: Float, dy: Float, r: Float, out: FloatArray) = axisAngle(-dy, dx, 0f, hypot(dx, dy) / (r * spread(1f)), out)

    /** Perspective: a point at depth z sits spread(z) globe radii out per unit of x or y. Scaled so the silhouette's
     *  widest point is exactly one radius. */
    fun spread(z: Float) = K / (D - z)

    /** Icon size at depth z: 1 in front, 0.5 at the back. */
    fun scale(z: Float) = (D - 1) / (D - z)

    /** Back icons show through the ball, faint; past the middle they're fully there. */
    fun alpha(z: Float) = 0.2f + 0.8f * OrbMath.smooth((z + 0.9f) / 1.15f)

    fun front(z: Float) = z > FRONT

    /** The front icon nearest (x, y) within its radius rs[i] plus pad; rs < 0 = not shown. -1 if none. */
    fun nearest(x: Float, y: Float, xs: FloatArray, ys: FloatArray, rs: FloatArray, zs: FloatArray, n: Int, pad: Float): Int {
        var best = -1; var bestD = Float.MAX_VALUE
        for (i in 0 until n) {
            if (rs[i] < 0f || !front(zs[i])) continue
            val d = hypot(x - xs[i], y - ys[i])
            if (d <= rs[i] + pad && d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** A fling's speed after dt s of friction: Honeycomb's scroll feel (0.998 per ms), so it lands where project() says. */
    fun decay(v: Float, dt: Float, rate: Float = 0.998f) = v * rate.pow(dt * 1000f)

    /** The idle spin's speed (rad/s), eased in by ramp 0..1; none under "Remove animations". */
    fun idle(still: Boolean, ramp: Float) = if (still) 0f else IDLE * OrbMath.smooth(ramp)
}

/** Every app on the surface of one glass globe, Jharvis's orb glowing inside it (home draws the orb at the screen's
 *  middle; the globe is centred on it). Most used start facing him. Drag spins it like a trackball, 1:1 under the
 *  finger; a flick carries on and slows. Left alone it turns slowly on a tilted axis, and stops the instant he touches.
 *  Back icons show through, smaller and faint, drawn back to front; only front ones take a tap or a hold. One view
 *  draws every icon from the Icons cache, so a hundred moving icons stay at 120 Hz.
 *  progress (home's spring) grows the globe out of the orb with a little spin, and folds it back the same way. */
class SphereView(c: Context, private val look: Look, private val onLaunch: (icon: View, key: String) -> Unit,
                 private val onClose: () -> Unit, private val onSearch: () -> Unit) : FrameLayout(c) {

    val view: View get() = this
    /** Press and hold an icon: its app menu (home supplies what Add to dock and a change mean). */
    var menuActions: AppMenu.Actions? = null
    /** A tap inside the globe that missed every icon: home says whether it was on its orb (and opens Jharvis if so). */
    var onOrb: ((x: Float, y: Float) -> Boolean)? = null

    /** 0 = folded into the orb, 1 = open. Home's spring drives it. */
    var progress = 0f
        set(v) { field = v; applyProgress() }

    private val still = reducedMotion(c)
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private val iconMax = look.px(54)
    private var iconPx = iconMax.toFloat()     // shrinks so a long app list still has room between icons
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    // the globe, rebuilt when the app list changes (arrays indexed like `apps`)
    private var apps: List<AppEntry> = emptyList()
    private var pending: List<AppEntry>? = null       // a newer list that waits until the globe is shut, so nothing jumps
    private var builtVersion = -1
    private var loadGen = 0
    private var badges: Map<String, AppBadges.Badge> = emptyMap()
    private var n = 0
    private var base = FloatArray(0)                  // unit points, x y z each
    private var bmps = arrayOfNulls<Bitmap>(0)
    private var labels = emptyArray<String>(); private var labelW = FloatArray(0)
    private var badgeText = arrayOfNulls<String>(0); private var badgeW = FloatArray(0)
    private var desc = emptyArray<String>()
    private var paused = BooleanArray(0)
    // where each icon was drawn last frame (touch and TalkBack hit-test these); drawR < 0 = not shown
    private var drawX = FloatArray(0); private var drawY = FloatArray(0); private var drawR = FloatArray(0); private var drawZ = FloatArray(0)
    private var order = IntArray(0)                   // draw order, back to front; kept between frames (nearly sorted)

    private var cx = 0f; private var cy = 0f; private var radius = 1f
    private var orbR = 0f                             // home's orb lens at the centre: back icons behind it dim
    private var bars = Insets.NONE

    // the turn: q is where the globe rests; the rest are scratch, so a frame allocates nothing
    private val q = floatArrayOf(1f, 0f, 0f, 0f)
    private val dq = FloatArray(4); private val spin = FloatArray(4); private val qd = FloatArray(4)
    private val m = FloatArray(9); private val v3 = FloatArray(3)
    private var vx = 0f; private var vy = 0f          // fling, px/s on screen
    private var ramp = 0f                             // the idle spin eased in, 0..1
    private var waiting = false                       // let go: the idle spin waits IDLE_WAIT before it resumes
    private var exploring = false                     // TalkBack: targets mustn't drift away under his finger

    private val press = Spring(1f, 0.0005f) { invalidate() }  // squish of the pressed icon
    private val pill = Spring(0f, 0.002f) { invalidate() }    // the name pill, 0..1

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.withAlpha(look.ground, 240) }  // ground, not white: white is faint on dark themes
    private val pillEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = look.px(1).toFloat(); color = look.glassEdge }
    private val nameText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = look.ink; typeface = look.sans; textSize = sp(13f); textAlign = Paint.Align.CENTER }
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.withAlpha(look.ground, 245) }
    private val badgeEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = look.px(1).toFloat(); color = look.accent }
    private val badgeInk = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = look.ink; typeface = look.medium; textAlign = Paint.Align.CENTER }
    private val badgeSp = sp(11f)
    private val edgeA = Color.alpha(look.glassEdge)
    private val glassA = Color.alpha(look.glass)
    private val dst = RectF()

    /** Stands in for the canvas-drawn icon wherever a real view is needed: the launch grows from it, the app menu lifts
     *  a copy of it. Never seen itself. */
    private val anchor = ImageView(c).apply { alpha = 0f; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val searchPill = look.text("Search", 15f, look.ink2).apply {
        gravity = Gravity.CENTER
        setPadding(look.px(26), 0, look.px(26), 0)
        background = look.glassCard(99)
        contentDescription = "Search apps"
        isClickable = true; look.press(this, 0.94f)
        setOnClickListener { onSearch() }
        visibility = INVISIBLE
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        addView(anchor, LayoutParams(iconMax, iconMax))
        addView(searchPill, LayoutParams(-2, look.px(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        visibility = INVISIBLE
        setOnApplyWindowInsetsListener { _, i -> useInsets(i); i }
    }

    // ── Contract: load, opening, reset, ownsTouch ───────────────────────────────────────────────────

    /** Re-rank apps and re-read hidden apps and badges on the loader thread. The front's icons are drawn before the
     *  list comes back; the rest fill in as they're drawn. */
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

    /** Called once as an open starts: anything that arrived while it was shut is laid out, and the idle spin eases in. */
    fun opening() {
        pending?.let { apps = it; pending = null; build() }
        pill.snap(0f)
        ramp = 0f; waiting = false
        exploring = context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    }

    /** Globe back to rest (most used in front), nothing pressed or spinning. */
    fun reset() {
        removeCallbacks(hold); removeCallbacks(resume)
        sleep()
        q[0] = 1f; q[1] = 0f; q[2] = 0f; q[3] = 0f
        vx = 0f; vy = 0f; ramp = 0f; waiting = false; touching = false; dragging = false
        press.snap(1f); pill.snap(0f)
        hit = -1; named = -1; pressed = -1
    }

    /** True inside the globe once it's mostly open: a drag there spins it, so home must leave it alone. Outside the
     *  ball (the top band, the sides, round the search pill) a pull down is home's: it closes. */
    fun ownsTouch(x: Float, y: Float) = progress >= 0.5f && n > 0 && inGlobe(x, y)

    private fun inGlobe(x: Float, y: Float) = hypot(x - cx, y - cy) <= radius + iconPx / 2f

    // ── Building the globe ──────────────────────────────────────────────────────────────────────────

    private val appsChanged: () -> Unit = { load() }

    /** :core wrote new counts: read them off the main thread, then update the pills. */
    private val badgeRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Loader.run { val b = AppBadges.read(); Loader.main.post { badges = b; showBadges() } }
        }
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
        removeCallbacks(hold); removeCallbacks(resume)
        loadGen++  // answers still on their way are dropped
        sleep(); press.stop(); pill.stop()
        super.onDetachedFromWindow()
    }

    private fun useInsets(i: WindowInsets) {
        val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        if (b == bars) return
        bars = b
        (searchPill.layoutParams as LayoutParams).bottomMargin = b.bottom + look.px(16)
        requestLayout()
        geometry()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = geometry()

    /** The globe is centred where home parks the orb (the screen's middle), as big as fits below the top tenth, inside
     *  the bars and above the search pill. */
    private fun geometry() {
        if (width == 0) return
        cx = width / 2f; cy = height / 2f
        val top = max(height * 0.10f, bars.top + look.px(8).toFloat())
        val bottom = height - bars.bottom - look.px(16 + 48 + 12).toFloat()
        val room = minOf(cx - bars.left, width - bars.right - cx, cy - top, bottom - cy) - look.px(8)
        radius = max(look.px(60).toFloat(), room - iconMax / 2f)
        orbR = min(width.toFloat(), height * 0.42f) * OrbMath.LENS * 0.45f  // Home's orb view is 42% of the height, compact 0.45
        fit()
        invalidate()
    }

    /** Icon size: full where there's room, smaller when the even spacing in front would make them touch. */
    private fun fit() {
        val gap = if (n > 1) radius * SphereMath.spread(1f) * sqrt(4f * PI.toFloat() / n) else Float.MAX_VALUE
        iconPx = min(iconMax.toFloat(), max(look.px(24).toFloat(), gap * 0.8f))
    }

    private fun build() {
        val list = apps
        n = list.size
        base = SphereMath.points(n)
        bmps = Array(n) { Icons.cached(list[it].key) }
        val maxLabel = look.px(200).toFloat()
        labels = Array(n) { TextUtils.ellipsize(list[it].label, nameText, maxLabel, TextUtils.TruncateAt.END).toString() }
        labelW = FloatArray(n) { nameText.measureText(labels[it]) }
        paused = BooleanArray(n) { list[it].suspended }
        badgeText = arrayOfNulls(n); badgeW = FloatArray(n); desc = Array(n) { list[it].label }
        drawX = FloatArray(n); drawY = FloatArray(n); drawR = FloatArray(n) { -1f }; drawZ = FloatArray(n)
        order = IntArray(n) { n - 1 - it }  // the last (least used) start at the back
        hit = -1; named = -1; pressed = -1; hover = -1; a11yFocus = -1
        showBadges()
        fit()
        q[0] = 1f; q[1] = 0f; q[2] = 0f; q[3] = 0f
    }

    private fun fillIcons() { for (i in 0 until n) if (bmps[i] == null) bmps[i] = Icons.cached(apps[i].key) }

    private fun showBadges() {
        badgeInk.textSize = badgeSp
        for (i in 0 until n) {
            val a = apps[i]
            val k = if (a.main) badges[a.pkg]?.count ?: 0 else 0  // badges.json counts the main profile
            badgeText[i] = if (k > 0) AppBadges.word(k) else null
            badgeW[i] = badgeText[i]?.let { badgeInk.measureText(it) } ?: 0f
            desc[i] = a.label + when { k == 1 -> ", 1 notification"; k > 1 -> ", $k notifications"; else -> "" } + if (a.suspended) ", paused" else ""
        }
        invalidate()
    }

    // ── Motion: fling and idle spin on one frame loop ───────────────────────────────────────────────

    private var running = false
    private var last = 0L
    private val frame = Choreographer.FrameCallback { step(it) }
    // the globe never turns under an open long-press menu: it waits until the menu is gone
    private val resume: Runnable = Runnable { if (AppMenu.isOpen) postDelayed(resume, IDLE_WAIT) else { waiting = false; wake() } }

    private fun idleOn() = !still && !exploring && !waiting && !touching && !AppMenu.isOpen

    private fun wake() {
        if (running || !isAttachedToWindow) return
        running = true; last = 0L
        Choreographer.getInstance().postFrameCallback(frame)
        fastFrames(true)
    }

    private fun sleep() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        fastFrames(false)
    }

    private fun step(now: Long) {
        if (!running) return
        val dt = if (last == 0L) 1f / 120 else ((now - last) / 1e9f).coerceIn(0f, 1f / 30)
        last = now
        if (touching || progress <= 0.001f || n == 0) { sleep(); return }
        val s = hypot(vx, vy)
        if (s > SphereMath.REST) {  // a flick carrying on, slowing
            turn(vx * dt, vy * dt)
            val k = SphereMath.decay(s, dt) / s; vx *= k; vy *= k
            if (hypot(vx, vy) <= SphereMath.REST) { vx = 0f; vy = 0f; settle() }
        } else if (idleOn()) {
            ramp = min(1f, ramp + dt / RAMP_S)
            SphereMath.axisAngle(AXIS_X, AXIS_Y, 0f, SphereMath.idle(still, ramp) * dt, dq)
            SphereMath.mul(dq, q, q)
        } else { sleep(); return }
        invalidate()
        if (running) Choreographer.getInstance().postFrameCallback(frame)
    }

    /** Let go and come to rest: the idle spin eases back in a few seconds later. */
    private fun settle() {
        sleep()
        ramp = 0f; waiting = true
        removeCallbacks(resume)
        if (!still) postDelayed(resume, IDLE_WAIT)
    }

    private fun turn(dx: Float, dy: Float) { SphereMath.drag(dx, dy, radius, dq); SphereMath.mul(dq, q, q) }

    // ── Frames ──────────────────────────────────────────────────────────────────────────────────────

    private fun applyProgress() {
        val p = progress
        if (p <= 0.001f) { pending?.let { apps = it; pending = null; build() }; sleep() } else wake()
        if (!still) blur(look.px(8) * (1 - p).coerceIn(0f, 1f))  // materialize: blur, scale and alpha together
        // the search pill arrives once the globe is mostly out, same path in and out
        val a = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
        searchPill.alpha = a
        val s = if (still) 1f else 0.92f + 0.08f * a; searchPill.scaleX = s; searchPill.scaleY = s
        searchPill.translationY = if (still) 0f else look.px(12) * (1 - a)
        val pv = if (a > 0.02f) VISIBLE else INVISIBLE
        if (searchPill.visibility != pv) searchPill.visibility = pv
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val p = progress
        if (n == 0 || p <= 0.001f) return
        // opening: grows out of the orb and turns in the last bit of a spin; "Remove animations": a plain fade
        val grow = if (still) 1f else 0.4f + 0.6f * p
        val fade = if (still) min(1f, p) else min(1f, p * 1.6f)
        if (still) SphereMath.matrix(q, m)
        else { SphereMath.axisAngle(AXIS_X, AXIS_Y, 0f, (p - 1f) * SPIN_IN, spin); SphereMath.mul(spin, q, qd); SphereMath.matrix(qd, m) }
        val r = radius * grow; val half = iconPx / 2f * grow
        for (i in 0 until n) {
            SphereMath.apply(m, base[i * 3], base[i * 3 + 1], base[i * 3 + 2], v3)
            val z = v3[2]; val k = SphereMath.spread(z) * r
            drawX[i] = cx + v3[0] * k; drawY[i] = cy + v3[1] * k; drawZ[i] = z
        }
        // back to front: insertion sort on last frame's order, which a frame's turn barely changes
        for (a in 1 until n) {
            val i = order[a]; val z = drawZ[i]; var b = a - 1
            while (b >= 0 && drawZ[order[b]] > z) { order[b + 1] = order[b]; b-- }
            order[b + 1] = i
        }
        for (o in 0 until n) {
            val i = order[o]
            val x = drawX[i]; val y = drawY[i]; val z = drawZ[i]
            var s = SphereMath.scale(z)
            if (i == pressed) s *= press.value
            var a = SphereMath.alpha(z) * fade
            if (z < 0f && hypot(x - cx, y - cy) < orbR) a *= 0.3f  // behind the orb: it hides them, as glass would
            if (paused[i]) a *= 0.5f  // paused (Digital Wellbeing or work apps off)
            if (a < 0.01f) { drawR[i] = -1f; continue }
            val rr = half * s
            drawR[i] = rr
            dst.set(x - rr, y - rr, x + rr, y + rr)
            val bm = bmps[i]
            if (bm != null) { iconPaint.alpha = (255 * a).toInt(); canvas.drawBitmap(bm, null, dst, iconPaint) }
            else { discPaint.color = look.withAlpha(look.glass, (glassA * a).toInt()); canvas.drawCircle(x, y, rr * 0.9f, discPaint) }  // icon still being drawn
            val bt = badgeText[i]
            val bf = OrbMath.smooth(z / 0.2f)  // badges fade in with the icon coming round, never pop
            if (bt != null && bf > 0.01f && s > 0.55f) {
                val bh = look.px(18) * s
                val bw = max(bh, badgeW[i] * s + look.px(10) * s)
                val bx = x + rr * 0.72f; val by = y - rr * 0.72f
                dst.set(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2)
                badgeFill.alpha = (245 * a * bf).toInt(); badgeEdge.alpha = (255 * a * bf).toInt(); badgeInk.alpha = (255 * a * bf).toInt()
                badgeInk.textSize = badgeSp * s
                canvas.drawRoundRect(dst, bh / 2, bh / 2, badgeFill)
                canvas.drawRoundRect(dst, bh / 2, bh / 2, badgeEdge)
                canvas.drawText(bt, bx, by + badgeInk.textSize * 0.36f, badgeInk)
            }
        }
        drawName(canvas)
    }

    /** The name of the icon under the finger, in a small pill just under it (above it near the bottom edge). */
    private fun drawName(canvas: Canvas) {
        val j = named; val pa = pill.value
        if (j !in 0 until n || pa < 0.01f || drawR[j] < 0f) return
        val h = look.px(28).toFloat(); val w = labelW[j] + look.px(24)
        val gap = look.px(6)
        var top = drawY[j] + drawR[j] + gap
        if (top + h > height - bars.bottom) top = drawY[j] - drawR[j] - gap - h
        val left = (drawX[j] - w / 2).coerceIn(look.px(8).toFloat(), max(look.px(8).toFloat(), width - look.px(8) - w))
        dst.set(left, top, left + w, top + h)
        val s = if (still) 1f else 0.92f + 0.08f * pa
        canvas.save()
        canvas.scale(s, s, drawX[j], top)
        pillFill.alpha = (240 * pa).toInt(); pillEdge.alpha = (edgeA * pa).toInt(); nameText.alpha = (255 * pa).toInt()
        canvas.drawRoundRect(dst, h / 2, h / 2, pillFill)
        canvas.drawRoundRect(dst, h / 2, h / 2, pillEdge)
        canvas.drawText(labels[j], left + w / 2, top + h / 2 + nameText.textSize * 0.36f, nameText)
        canvas.restore()
    }

    // ── Touch: spin, tap, hold ──────────────────────────────────────────────────────────────────────

    private var touching = false
    private var dragging = false
    private var yielded = false     // the drag went to home (it began outside the globe): ignore the rest
    private var yieldDown = false   // this touch started outside the globe, where home may take a pull down
    private var held = false
    private var caught = false      // the touch stopped a running flick: it doesn't also launch
    private var hit = -1            // icon under the finger at touch-down
    private var named = -1          // icon whose name the pill shows
    private var pressed = -1
    private var pid = -1
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var vt: VelocityTracker? = null

    private val hold = Runnable {
        val i = hit
        if (i !in 0 until n || dragging || progress < 0.9f) return@Runnable
        held = true
        unpress(); pill.to(0f, response = 0.3f)
        menuActions?.let { AppMenu.show(anchorAt(i), apps[i].key, look, it) }
    }

    // on the orb only an icon's drawn face counts, so a tap on the glowing middle is Jharvis, not a nearby app (review 2 Nov)
    private fun hitAt(x: Float, y: Float) = SphereMath.nearest(x, y, drawX, drawY, drawR, drawZ, n,
        if (hypot(x - cx, y - cy) < orbR) 0f else look.px(6).toFloat())

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // a finger already down when the globe starts closing still gets its UP/CANCEL, so nothing stays "touching"
        if (progress < 0.9f && e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true; dragging = false; yielded = false; held = false
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; pid = e.getPointerId(0)
                vt?.recycle(); vt = VelocityTracker.obtain().apply { addMovement(e) }
                caught = hypot(vx, vy) > look.px(120)
                vx = 0f; vy = 0f; ramp = 0f  // grabbed: it stops where it is, idle spin too
                removeCallbacks(resume); sleep()
                yieldDown = !ownsTouch(e.x, e.y)
                if (!yieldDown) parent?.requestDisallowInterceptTouchEvent(true)
                hit = if (caught) -1 else hitAt(e.x, e.y)
                named = hit
                if (hit >= 0) {
                    tick(); pressed = hit
                    if (!still) press.to(0.9f, response = 0.16f, damping = 1f)
                    pill.to(1f, response = 0.3f)
                    postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                }
                fastFrames(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (yielded || held) return !yielded
                val i = e.findPointerIndex(pid).takeIf { it >= 0 } ?: return true
                val x = e.getX(i); val y = e.getY(i)
                if (!dragging) {
                    if (hypot(x - downX, y - downY) <= slop) return true
                    if (yieldDown) { yielded = true; release(); return false }
                    dragging = true
                    removeCallbacks(hold); unpress()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    lastX = x; lastY = y  // spins 1:1 from here, so the slop doesn't jump
                }
                turn(x - lastX, y - lastY); lastX = x; lastY = y
                invalidate()
                val now = hitAt(x, y)  // the name follows whichever icon is under the finger now
                if (now != named) { named = now; pill.to(if (now >= 0) 1f else 0f, response = 0.3f) }
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == pid) {
                val k = if (e.actionIndex == 0) 1 else 0  // the other finger carries on from where the globe is
                pid = e.getPointerId(k)
                lastX = e.getX(k); lastY = e.getY(k)
            }
            MotionEvent.ACTION_UP -> finish(e, true)
            MotionEvent.ACTION_CANCEL -> finish(e, false)
        }
        return true
    }

    private fun finish(e: MotionEvent, up: Boolean) {
        removeCallbacks(hold)
        touching = false
        if (dragging && up && !still) {  // the flick's speed carries on; "Remove animations" stops where he let go
            vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
            vx = vt?.getXVelocity(pid) ?: 0f; vy = vt?.getYVelocity(pid) ?: 0f
        }
        val tap = up && progress >= 0.9f && !dragging && !yielded && !held && !caught
        release()
        // a tap off the globe closes, like the orbit's core; inside, a miss may be on the orb (Jharvis), else nothing
        if (tap) { if (hit >= 0) launch(hit) else if (!inGlobe(downX, downY)) onClose() else onOrb?.invoke(downX, downY) }
        vt?.recycle(); vt = null
        dragging = false
        if (hypot(vx, vy) > SphereMath.REST) wake() else { vx = 0f; vy = 0f; settle() }
    }

    private fun release() {
        unpress()
        pill.to(0f, response = 0.3f)
    }

    private fun unpress() { if (still) press.snap(1f) else press.to(1f, response = 0.42f, damping = 0.82f) }

    /** The invisible stand-in, moved onto icon i at its drawn size. */
    private fun anchorAt(i: Int): View {
        anchor.setImageBitmap(bmps[i])
        anchor.translationX = drawX[i] - iconMax / 2f; anchor.translationY = drawY[i] - iconMax / 2f
        val s = if (drawR[i] > 0f) drawR[i] / (iconMax / 2f) else 1f
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

    // ── TalkBack: every front icon is a virtual button; scroll forward or back turns the globe ──────────

    private var hover = -1
    private var a11yFocus = -1
    private val screenAt = IntArray(2)

    private fun reachable(i: Int) = drawR[i] > 0f && SphereMath.front(drawZ[i])

    private val a11y = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(id: Int): AccessibilityNodeInfo? {
            val host = this@SphereView
            if (id == NO_ID) {
                val info = AccessibilityNodeInfo(host)
                onInitializeAccessibilityNodeInfo(info)
                for (i in 0 until n) if (reachable(i)) info.addChild(host, i)
                if (searchPill.visibility == VISIBLE) info.addChild(searchPill)  // a provider host lists its real children itself
                if (n > 0) {  // the back half comes round with a scroll (TalkBack swallows drags)
                    info.isScrollable = true
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP)  // a second axis reaches the poles too
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)
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
                val r = drawR[id].coerceAtLeast(0f)
                setBoundsInScreen(Rect((screenAt[0] + drawX[id] - r).toInt(), (screenAt[1] + drawY[id] - r).toInt(),
                    (screenAt[0] + drawX[id] + r).toInt(), (screenAt[1] + drawY[id] + r).toInt()))
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
                addAction(if (a11yFocus == id) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                    else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
            }
        }

        override fun performAction(id: Int, action: Int, args: Bundle?): Boolean {
            if (id == NO_ID) {
                val up = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                val down = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                val dir = when (action) { AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, down -> 1f; AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, up -> -1f; else -> 0f }
                if (dir == 0f || n == 0) return performAccessibilityAction(action, args)
                // forward/back spin round the upright axis, up/down tip it over the screen's x axis: together every app comes round
                if (action == up || action == down) SphereMath.axisAngle(1f, 0f, 0f, dir * A11Y_TURN, dq)
                else SphereMath.axisAngle(AXIS_X, AXIS_Y, 0f, dir * A11Y_TURN, dq)
                SphereMath.mul(dq, q, q)
                invalidate()
                post { sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED) }  // after the frame that moved them
                return true
            }
            if (id !in 0 until n) return false
            // a moving globe can carry a node round the back after a service read it: only front apps open (review 2 Nov)
            if ((action == AccessibilityNodeInfo.ACTION_CLICK || action == AccessibilityNodeInfo.ACTION_LONG_CLICK) && !reachable(id)) return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> { launch(id); true }
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> { menuActions?.let { AppMenu.show(anchorAt(id), apps[id].key, look, it) }; true }
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    if (a11yFocus != id) { a11yFocus = id; send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) }; true
                }
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (a11yFocus == id) { a11yFocus = -1; send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED) }; true
                }
                else -> false
            }
        }
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = a11y

    override fun dispatchHoverEvent(e: MotionEvent): Boolean {
        if (context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled != true) return super.dispatchHoverEvent(e)
        val i = if (e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) -1 else hitAt(e.x, e.y)
        if (i != hover) {
            val was = hover; hover = i
            if (i >= 0) send(i, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            if (was >= 0) send(was, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
        }
        return i >= 0 || super.dispatchHoverEvent(e)
    }

    private fun send(i: Int, type: Int) {
        val ev = AccessibilityEvent(type).apply {
            setSource(this@SphereView, i)
            packageName = context.packageName; className = Button::class.java.name
            contentDescription = desc[i]
        }
        parent?.requestSendAccessibilityEvent(this, ev)
    }

    private companion object {
        const val FIRST = 61            // icons drawn before the list comes back: about the front half of a full phone
        const val AXIS_X = 0.3f; const val AXIS_Y = 1f  // the idle spin's axis: upright, leaning a little like a globe's
        const val SPIN_IN = 0.9f        // rad of turn the globe opens through
        const val RAMP_S = 1.2f         // the idle spin eases in over this long
        const val IDLE_WAIT = 3000L     // ms after he lets go (and a flick has settled) before it spins on its own
        const val A11Y_TURN = 1.0f      // rad per TalkBack scroll: about a sixth of the way round
    }
}
