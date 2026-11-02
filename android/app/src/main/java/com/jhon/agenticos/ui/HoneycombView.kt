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
import kotlin.math.abs
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/** Pure honeycomb arithmetic, kept apart so it can be tested on the JVM. */
internal object HexMath {
    const val ROW = 0.8660254f  // a row's height in pitches (sqrt 3 / 2)
    private val DIRS = intArrayOf(1, 0, 1, -1, 0, -1, -1, 0, -1, 1, 0, 1)  // axial steps round a ring

    /** Spiral slot -> axial hex, packed [q0, r0, q1, r1, ...]: slot 0 is the middle, then ring 1 (6 cells), ring 2 (12),
     *  so the first apps in a ranked list sit in the centre. */
    fun spiral(count: Int): IntArray {
        val out = IntArray(count.coerceAtLeast(0) * 2)
        var n = 1; var k = 1
        while (n < count) {
            var q = -k; var r = k
            for (side in 0 until 6) for (j in 0 until k) {
                if (n == count) return out
                out[n * 2] = q; out[n * 2 + 1] = r; n++
                q += DIRS[side * 2]; r += DIRS[side * 2 + 1]
            }
            k++
        }
        return out
    }

    /** Rings from the middle to axial (q, r). */
    fun dist(q: Int, r: Int) = (abs(q) + abs(r) + abs(q + r)) / 2

    /** Centre of axial (q, r) in px from the middle: rows a pitch apart sideways, every row shifted half a pitch. */
    fun x(q: Int, r: Int, pitch: Float) = pitch * (q + r / 2f)
    fun y(r: Int, pitch: Float) = pitch * ROW * r

    /** Fisheye: a point u lens-radii from the centre is drawn at tanh(u)/u of that distance, so the middle stays true
     *  and the edges gather in (it never passes one lens radius). */
    fun pull(u: Float) = if (u < 1e-3f) 1f else tanh(u) / u

    /** Icon size at u: the lens squeezes spacing by sech² u, so the icon shrinks with it, times the room a pitch leaves
     *  round an icon, capped at full size. */
    fun scale(u: Float, room: Float): Float { val c = cosh(u); return min(1f, room / (c * c)) }

    /** Icons too small to read fade out instead of piling up at the rim. */
    fun fade(s: Float) = ((s - 0.22f) / 0.14f).coerceIn(0f, 1f)

    /** The finger's magnifier: an icon d px from the finger grows by up to `amount`, gone about `reach` px out. */
    fun magnify(d: Float, reach: Float, amount: Float): Float { val t = d / reach; return 1f + amount * exp(-t * t) }

    /** The point nearest (x, y) that is within its radius rs[i] plus pad; rs < 0 = not shown. -1 if none. */
    fun nearest(x: Float, y: Float, xs: FloatArray, ys: FloatArray, rs: FloatArray, n: Int, pad: Float): Int {
        var best = -1; var bestD = Float.MAX_VALUE
        for (i in 0 until n) {
            if (rs[i] < 0f) continue
            val d = hypot(x - xs[i], y - ys[i])
            if (d <= rs[i] + pad && d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** Pan range on one axis for cells spanning lo..hi: every cell can be brought within `reach` of the middle. Always
     *  holds 0 (the field at rest). */
    fun limits(lo: Float, hi: Float, reach: Float) = floatArrayOf(min(0f, reach - hi), max(0f, -reach - lo))

    /** A pan past [lo, hi] follows less and less (rubber band over `size`). */
    fun band(v: Float, lo: Float, hi: Float, size: Float) = when {
        v > hi -> hi + rubberband(v - hi, size)
        v < lo -> lo - rubberband(lo - v, size)
        else -> v
    }

    /** band's inverse: the finger travel that shows a pan of v, so catching a bounce mid-way doesn't jump. */
    fun unband(v: Float, lo: Float, hi: Float, size: Float) = when {
        v > hi -> hi + over(v - hi, size)
        v < lo -> lo - over(lo - v, size)
        else -> v
    }

    private fun over(f: Float, s: Float, k: Float = 0.55f): Float { val g = min(f, s * 0.99f); return g * s / (k * (s - g)) }
}

/** Every app in one hex-packed field, Apple Watch style: most used in the middle, spiralling out. A fisheye on the
 *  field's centre shrinks icons toward the edges; under the finger they grow a little, and the icon under it shows its
 *  name in a small pill. Drag pans 1:1 from where it was grabbed; a flick carries on and the edges rubber-band. One view
 *  draws every icon from the Icons cache (no view per app), so a hundred moving icons stay at 120 Hz.
 *  progress (home's spring) springs the field out of its centre, inner rings first, and folds it back the same way.
 *  onLaunch gets an app key after LockedApps has passed, as OrbitView does. The orb is home's, not drawn here. */
class HoneycombView(c: Context, private val look: Look, private val onLaunch: (icon: View, key: String) -> Unit,
                    private val onClose: () -> Unit, private val onSearch: () -> Unit) : FrameLayout(c) {

    val view: View get() = this
    /** Press and hold an icon: its app menu (home supplies what Add to dock and a change mean). */
    var menuActions: AppMenu.Actions? = null

    /** 0 = folded into the centre, 1 = open. Home's spring drives it. */
    var progress = 0f
        set(v) { field = v; applyProgress() }

    private val still = reducedMotion(c)
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private val iconPx = look.px(54)
    private val pitch = look.px(70).toFloat()
    private val room = pitch / iconPx * 0.98f   // how big an icon may get before it touches a squeezed neighbour
    private val edgePx = look.px(56)            // the field's top strip where a pull down can still close it
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    // the field, rebuilt when the app list changes (arrays indexed like `apps`)
    private var apps: List<AppEntry> = emptyList()
    private var pending: List<AppEntry>? = null       // a newer list that waits until the field is shut, so nothing jumps
    private var builtVersion = -1
    private var loadGen = 0
    private var badges: Map<String, AppBadges.Badge> = emptyMap()
    private var n = 0
    private var baseX = FloatArray(0); private var baseY = FloatArray(0)
    private var ringOf = IntArray(0); private var maxRing = 1
    private var bmps = arrayOfNulls<Bitmap>(0)
    private var labels = emptyArray<String>(); private var labelW = FloatArray(0)
    private var badgeText = arrayOfNulls<String>(0); private var badgeW = FloatArray(0)
    private var desc = emptyArray<String>()
    private var paused = BooleanArray(0)
    // where each icon was drawn last frame (touch and TalkBack hit-test these); drawR < 0 = not shown
    private var drawX = FloatArray(0); private var drawY = FloatArray(0); private var drawR = FloatArray(0)

    private val field = RectF()
    private var cx = 0f; private var cy = 0f
    private var lensX = 1f; private var lensY = 1f    // the fisheye's radius each way
    private var loX = 0f; private var hiX = 0f; private var loY = 0f; private var hiY = 0f
    private var bars = Insets.NONE

    private val fast: () -> Unit = { if (!touching && panX.velocity == 0f && panY.velocity == 0f) fastFrames(false) }
    private val panX = Spring(0f, 0.5f) { invalidate() }.apply { onRest = fast }
    private val panY = Spring(0f, 0.5f) { invalidate() }.apply { onRest = fast }
    private val lens = Spring(0f, 0.002f) { invalidate() }    // the finger's magnifier, 0..1
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
        addView(anchor, LayoutParams(iconPx, iconPx))
        addView(searchPill, LayoutParams(-2, look.px(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        visibility = INVISIBLE
        setOnApplyWindowInsetsListener { _, i -> useInsets(i); i }
    }

    // ── Contract: load, opening, reset, ownsTouch ───────────────────────────────────────────────────

    /** Re-rank apps and re-read hidden apps and badges on the loader thread. The middle's icons are drawn before the
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

    /** Called once as an open starts: anything that arrived while it was shut is laid out before the field springs out. */
    fun opening() {
        pending?.let { apps = it; pending = null; build() }
        pill.snap(0f)
    }

    /** Field back to rest, nothing pressed. */
    fun reset() {
        removeCallbacks(hold)
        panX.snap(0f); panY.snap(0f); lens.snap(0f); press.snap(1f); pill.snap(0f)
        hit = -1; named = -1; pressed = -1
    }

    /** True when a drag starting here pans the field, so home must leave it alone. False outside the field, and in its
     *  top strip while the field already shows its top row: a pull down there is home's (it closes). */
    fun ownsTouch(x: Float, y: Float): Boolean {
        if (progress < 0.5f || n == 0 || !field.contains(x, y)) return false
        return !(y < field.top + edgePx && atTop())
    }

    private fun atTop() = panY.value >= hiY - 1f

    // ── Building the field ──────────────────────────────────────────────────────────────────────────

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
        removeCallbacks(hold)
        loadGen++  // answers still on their way are dropped
        panX.stop(); panY.stop(); lens.stop(); press.stop(); pill.stop()
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

    /** The field sits below the top tenth (the orb's corner) and above the search pill and the gesture bar. */
    private fun geometry() {
        if (width == 0) return
        val top = max(height * 0.10f, bars.top + look.px(8).toFloat())
        val bottom = height - bars.bottom - look.px(16 + 48 + 12).toFloat()
        field.set(bars.left.toFloat(), top, (width - bars.right).toFloat(), max(bottom, top + 1f))
        cx = field.centerX(); cy = field.centerY()
        lensX = field.width() / 2f * 0.95f; lensY = field.height() / 2f * 0.95f
        limits()
        invalidate()
    }

    private fun limits() {
        if (n == 0) { loX = 0f; hiX = 0f; loY = 0f; hiY = 0f; return }
        val x = HexMath.limits(baseX.min(), baseX.max(), lensX * 0.5f)
        val y = HexMath.limits(baseY.min(), baseY.max(), lensY * 0.5f)
        loX = x[0]; hiX = x[1]; loY = y[0]; hiY = y[1]
    }

    private fun build() {
        val list = apps
        n = list.size
        val hex = HexMath.spiral(n)
        baseX = FloatArray(n) { HexMath.x(hex[it * 2], hex[it * 2 + 1], pitch) }
        baseY = FloatArray(n) { HexMath.y(hex[it * 2 + 1], pitch) }
        ringOf = IntArray(n) { HexMath.dist(hex[it * 2], hex[it * 2 + 1]) }
        maxRing = (ringOf.maxOrNull() ?: 1).coerceAtLeast(1)
        bmps = Array(n) { Icons.cached(list[it].key) }
        val maxLabel = look.px(200).toFloat()
        labels = Array(n) { TextUtils.ellipsize(list[it].label, nameText, maxLabel, TextUtils.TruncateAt.END).toString() }
        labelW = FloatArray(n) { nameText.measureText(labels[it]) }
        paused = BooleanArray(n) { list[it].suspended }
        badgeText = arrayOfNulls(n); badgeW = FloatArray(n); desc = Array(n) { list[it].label }
        drawX = FloatArray(n); drawY = FloatArray(n); drawR = FloatArray(n) { -1f }
        hit = -1; named = -1; pressed = -1; hover = -1; a11yFocus = -1
        showBadges()
        limits()
        panX.snap(0f); panY.snap(0f)
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

    // ── Frames ──────────────────────────────────────────────────────────────────────────────────────

    private fun applyProgress() {
        val p = progress
        if (p <= 0.001f) pending?.let { apps = it; pending = null; build() }
        if (!still) blur(look.px(8) * (1 - p).coerceIn(0f, 1f))  // materialize: blur, scale and alpha together
        // the search pill arrives once the field is mostly out, same path in and out
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
        val px = panX.value; val py = panY.value
        val half = iconPx / 2f
        val mag = lens.value * 0.16f
        for (i in 0 until n) {
            val rx = baseX[i] + px; val ry = baseY[i] + py
            val u = sqrt((rx / lensX) * (rx / lensX) + (ry / lensY) * (ry / lensY))
            val k = HexMath.pull(u)
            var x = cx + rx * k; var y = cy + ry * k
            var s = HexMath.scale(u, room)
            var a = HexMath.fade(s)
            if (a <= 0f) { drawR[i] = -1f; continue }
            if (mag > 0f) s *= HexMath.magnify(hypot(x - fingerX, y - fingerY), pitch, mag)
            if (i == pressed) s *= press.value
            if (still) a *= min(1f, p)
            else {  // each ring springs out of the centre a beat after the one inside it
                val t = ((p - 0.24f * ringOf[i] / maxRing) / 0.76f).coerceIn(0f, 1f)
                x = cx + (x - cx) * t; y = cy + (y - cy) * t
                s *= 0.5f + 0.5f * t; a *= min(1f, t * 1.6f)
            }
            if (paused[i]) a *= 0.5f  // paused (Digital Wellbeing or work apps off)
            if (a < 0.01f) { drawR[i] = -1f; continue }
            val r = half * s
            drawX[i] = x; drawY[i] = y; drawR[i] = r
            dst.set(x - r, y - r, x + r, y + r)
            val b = bmps[i]
            if (b != null) { iconPaint.alpha = (255 * a).toInt(); canvas.drawBitmap(b, null, dst, iconPaint) }
            else { discPaint.color = look.withAlpha(look.glass, (glassA * a).toInt()); canvas.drawCircle(x, y, r * 0.9f, discPaint) }  // icon still being drawn
            val bt = badgeText[i]
            if (bt != null && s > 0.55f) {
                val bh = look.px(18) * s
                val bw = max(bh, badgeW[i] * s + look.px(10) * s)
                val bx = x + r * 0.72f; val by = y - r * 0.72f
                dst.set(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2)
                badgeFill.alpha = (245 * a).toInt(); badgeEdge.alpha = (255 * a).toInt(); badgeInk.alpha = (255 * a).toInt()
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

    // ── Touch: pan, tap, hold ───────────────────────────────────────────────────────────────────────

    private var touching = false
    private var dragging = false
    private var yielded = false     // the drag went to home (a pull down from the top strip): ignore the rest
    private var yieldDown = false   // this touch started where home may take a pull down
    private var held = false
    private var caught = false      // the touch stopped a running flick: it doesn't also launch
    private var hit = -1            // icon under the finger at touch-down
    private var named = -1          // icon whose name the pill shows
    private var pressed = -1
    private var pid = -1
    private var downX = 0f; private var downY = 0f
    private var startX = 0f; private var startY = 0f
    private var fingerX = 0f; private var fingerY = 0f
    private var vt: VelocityTracker? = null

    private val hold = Runnable {
        val i = hit
        if (i !in 0 until n || dragging) return@Runnable
        held = true
        unpress(); pill.to(0f, response = 0.3f); lens.to(0f, response = 0.35f)
        menuActions?.let { AppMenu.show(anchorAt(i), apps[i].key, look, it) }
    }

    private fun hitAt(x: Float, y: Float) = HexMath.nearest(x, y, drawX, drawY, drawR, n, look.px(6).toFloat())

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (progress < 0.9f) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true; dragging = false; yielded = false; held = false
                downX = e.x; downY = e.y; fingerX = e.x; fingerY = e.y; pid = e.getPointerId(0)
                vt?.recycle(); vt = VelocityTracker.obtain().apply { addMovement(e) }
                caught = abs(panX.velocity) + abs(panY.velocity) > look.px(120)
                panX.stop(); panY.stop()  // grabbed mid-flight: it stays where it is
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
                if (!still) lens.to(1f, response = 0.3f)
                fastFrames(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (yielded || held) return !yielded
                val i = e.findPointerIndex(pid).takeIf { it >= 0 } ?: return true
                val x = e.getX(i); val y = e.getY(i)
                if (!dragging) {
                    val dx = x - downX; val dy = y - downY
                    if (hypot(dx, dy) <= slop) return true
                    if (yieldDown && (!field.contains(downX, downY) || dy > abs(dx))) { yielded = true; release(); return false }
                    dragging = true
                    removeCallbacks(hold); unpress()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    grabAt(x, y)
                }
                pan(x, y)
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == pid) {
                val k = if (e.actionIndex == 0) 1 else 0  // the other finger carries on from where the field is
                pid = e.getPointerId(k)
                if (dragging) grabAt(e.getX(k), e.getY(k))
            }
            MotionEvent.ACTION_UP -> finish(e, true)
            MotionEvent.ACTION_CANCEL -> finish(e, false)
        }
        return true
    }

    /** Pan from here on, 1:1 from where the field is now (through the rubber band, so a caught bounce doesn't jump). */
    private fun grabAt(x: Float, y: Float) {
        downX = x; downY = y
        startX = HexMath.unband(panX.value, loX, hiX, field.width())
        startY = HexMath.unband(panY.value, loY, hiY, field.height())
    }

    private fun pan(x: Float, y: Float) {
        panX.snap(HexMath.band(startX + x - downX, loX, hiX, field.width()))
        panY.snap(HexMath.band(startY + y - downY, loY, hiY, field.height()))
        fingerX = x; fingerY = y
        val now = hitAt(x, y)  // the name follows whichever icon is under the finger now
        if (now != named) { named = now; pill.to(if (now >= 0) 1f else 0f, response = 0.3f) }
    }

    private fun finish(e: MotionEvent, up: Boolean) {
        removeCallbacks(hold)
        touching = false
        var vx = 0f; var vy = 0f
        if (dragging && up) {
            vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
            vx = vt?.getXVelocity(pid) ?: 0f; vy = vt?.getYVelocity(pid) ?: 0f
        }
        val tap = up && !dragging && !yielded && !held && !caught
        if (!yielded) { glide(panX, vx, loX, hiX); glide(panY, vy, loY, hiY) }
        release()
        // a tap off the field closes, like the orbit's core; a near miss between icons does nothing
        if (tap) { if (hit >= 0) launch(hit) else if (!field.contains(downX, downY)) onClose() }
        vt?.recycle(); vt = null
        dragging = false
    }

    private fun release() {
        unpress()
        lens.to(0f, response = 0.4f)
        pill.to(0f, response = 0.3f)
    }

    private fun unpress() { if (still) press.snap(1f) else press.to(1f, response = 0.42f, damping = 0.82f) }

    /** Let go: a flick carries on to where it was heading. A free glide uses response 1.57 s, where a critically damped
     *  spring starts at the finger's speed and only slows (it lands on project()'s point with no push). A flick into an
     *  edge stops there with a little bounce; a release past an edge springs back. */
    private fun glide(s: Spring, v: Float, lo: Float, hi: Float) {
        if (still) { s.snap(s.value.coerceIn(lo, hi)); return }
        val free = s.value + project(v)
        val to = free.coerceIn(lo, hi)
        when {
            s.value !in lo..hi -> s.to(to, response = 0.45f, damping = 1f, velocity = v)
            to != free && abs(v) > look.px(300) -> s.to(to, response = 0.6f, damping = 0.82f, velocity = v)
            else -> s.to(to, response = 1.57f, damping = 1f, velocity = v)
        }
    }

    /** The invisible stand-in, moved onto icon i at its drawn size. */
    private fun anchorAt(i: Int): View {
        anchor.setImageBitmap(bmps[i])
        anchor.translationX = drawX[i] - iconPx / 2f; anchor.translationY = drawY[i] - iconPx / 2f
        val s = if (drawR[i] > 0f) drawR[i] / (iconPx / 2f) else 1f
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

    // ── TalkBack: every shown icon is a virtual button (explore by touch, double tap opens, hold for the menu) ──

    private var hover = -1
    private var a11yFocus = -1
    private val screenAt = IntArray(2)

    private val a11y = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(id: Int): AccessibilityNodeInfo? {
            val host = this@HoneycombView
            if (id == NO_ID) {
                val info = AccessibilityNodeInfo(host)
                onInitializeAccessibilityNodeInfo(info)
                for (i in 0 until n) if (drawR[i] > 0f) info.addChild(host, i)
                if (searchPill.visibility == VISIBLE) info.addChild(searchPill)  // a provider host lists its real children itself
                return info
            }
            if (id !in 0 until n) return null
            return AccessibilityNodeInfo(host, id).apply {
                setParent(host)
                packageName = context.packageName; className = Button::class.java.name
                contentDescription = desc[id]
                isEnabled = true; isFocusable = true; isClickable = true; isLongClickable = true
                isVisibleToUser = drawR[id] > 0f
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
            if (id == NO_ID) return performAccessibilityAction(action, args)
            if (id !in 0 until n) return false
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
            setSource(this@HoneycombView, i)
            packageName = context.packageName; className = Button::class.java.name
            contentDescription = desc[i]
        }
        parent?.requestSendAccessibilityEvent(this, ev)
    }

    private companion object {
        const val FIRST = 61  // rings 0 to 4: what the open field shows first
    }
}
