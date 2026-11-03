package com.jhon.agenticos.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Insets
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.os.Build
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Pure drop arithmetic for Mercury Flow, tested on the JVM: the liquid's field (the shader's twin), where the drops
 *  sit, the orb's pull, the swallow test, the pop's velocity, the quick tray's rings and the springs that move it all. */
internal object MercuryMath {
    const val REACH = 1.6f          // a drop's field reaches 1.6 of its radius: far enough to bridge, short enough to skip
    /** The field's value at a lone drop's rim, so the drawn edge sits exactly at its radius. */
    val LEVEL = (1f - 1f / (REACH * REACH)).let { it * it * it }
    const val EPS = 0.3f            // px: a drop this close to its target, this slow, has landed
    const val VEPS = 4f             // px/s
    const val KEPS = 0.002f         // size, as a share of the drop
    const val SPARE = 4             // floats per drop in the shader's array: x, y, 1/reach², top edge (y - reach)

    /** One drop's share of the field at squared distance d2: (1 - d²/R²)³ inside R = REACH r, nothing beyond, so a
     *  pixel far from a drop costs one compare. The shader uses the same kernel. */
    fun kernel(d2: Float, r: Float): Float {
        if (r <= 0f) return 0f
        val q = d2 / (REACH * REACH * r * r)
        if (q >= 1f) return 0f
        val u = 1f - q
        return u * u * u
    }

    /** The liquid's field at (x, y) over n drops; liquid where it reaches LEVEL. */
    fun field(x: Float, y: Float, xs: FloatArray, ys: FloatArray, rs: FloatArray, n: Int): Float {
        var f = 0f
        for (i in 0 until n) { val dx = x - xs[i]; val dy = y - ys[i]; f += kernel(dx * dx + dy * dy, rs[i]) }
        return f
    }

    /** The orb's pull on a drop, (dx, dy) from the drop's home to the orb: a lean toward it that grows as the orb
     *  comes near, peaks at a third of reach (most px) and fades again at its heart, so a drop passing right under
     *  the orb never flips sides. Written into out as an offset. */
    fun pull(dx: Float, dy: Float, reach: Float, most: Float, out: FloatArray) {
        val d = hypot(dx, dy)
        if (d < 1e-3f || d >= reach || reach <= 0f) { out[0] = 0f; out[1] = 0f; return }
        val x = d / reach
        val s = most * 6.75f * x * (1 - x) * (1 - x)  // 27/4 x(1-x)²: 1 at x = 1/3
        out[0] = dx / d * s; out[1] = dy / d * s
    }

    /** A drop the dragged orb has run over: its centre is inside the orb's rim, plus a little. */
    fun swallows(dx: Float, dy: Float, orbR: Float, dropR: Float): Boolean { val m = orbR + dropR * 0.3f; return dx * dx + dy * dy < m * m }

    /** A drop popped out by a flick: the flick's velocity, a share of it, carries straight into the drop, plus a
     *  burst out toward its home (dx, dy from the orb). px/s into out. */
    fun pop(fvx: Float, fvy: Float, dx: Float, dy: Float, handoff: Float, burst: Float, out: FloatArray) {
        val d = hypot(dx, dy)
        val ux = if (d > 1e-3f) dx / d else 0f; val uy = if (d > 1e-3f) dy / d else -1f
        out[0] = fvx * handoff + ux * burst; out[1] = fvy * handoff + uy * burst
    }

    /** One frame for every drop: its offset (ox, oy) and size (ks) each a spring toward the targets, with its own
     *  damping (1 = no overshoot; a popped drop splashes). A drop that lands is put exactly on its targets with its
     *  damping back to 1. Small fixed steps, as Spring. Returns true while any drop still moves. */
    fun step(ox: FloatArray, oy: FloatArray, vx: FloatArray, vy: FloatArray, tx: FloatArray, ty: FloatArray,
             ks: FloatArray, kv: FloatArray, kt: FloatArray, damp: FloatArray, n: Int, response: Float, dt: Float): Boolean {
        val w = 2 * PI.toFloat() / response; val k = w * w
        var any = false
        for (i in 0 until n) {
            if (ox[i] == tx[i] && oy[i] == ty[i] && ks[i] == kt[i] && vx[i] == 0f && vy[i] == 0f && kv[i] == 0f) continue  // resting: no work
            val c = 4 * PI.toFloat() * damp[i] / response
            var left = dt
            while (left > 0f) {
                val h = min(left, 1f / 480)
                vx[i] += (-k * (ox[i] - tx[i]) - c * vx[i]) * h; ox[i] += vx[i] * h
                vy[i] += (-k * (oy[i] - ty[i]) - c * vy[i]) * h; oy[i] += vy[i] * h
                kv[i] += (-k * (ks[i] - kt[i]) - c * kv[i]) * h; ks[i] += kv[i] * h
                left -= h
            }
            if (abs(ox[i] - tx[i]) <= EPS && abs(oy[i] - ty[i]) <= EPS && abs(vx[i]) <= VEPS && abs(vy[i]) <= VEPS &&
                abs(ks[i] - kt[i]) <= KEPS && abs(kv[i]) <= KEPS * 10) {
                ox[i] = tx[i]; oy[i] = ty[i]; ks[i] = kt[i]; vx[i] = 0f; vy[i] = 0f; kv[i] = 0f; damp[i] = 1f
            } else any = true
        }
        return any
    }

    /** n drops scattered like a Poisson disc (Bridson): no two centres closer than gap, none within clear of the
     *  orb's rest place (cx, cy), all inside l..r across and from t down. The box starts at b and grows downward
     *  until n fit, so a long list scrolls. Sorted nearest the orb first: rank 0, the most used, gets the nearest
     *  place. The same seed and box give the same points, and the box only grows in whole steps, so one app more or
     *  less keeps every other place. Returns the lowest centre used (t when n is 0). */
    fun place(n: Int, l: Float, t: Float, r: Float, b: Float, gap: Float, cx: Float, cy: Float, clear: Float, seed: Int,
              outX: FloatArray, outY: FloatArray): Float {
        if (n <= 0) return t
        val right = max(l, r); val grow = max(gap * 2, b - t) * 0.5f
        // a Bridson scatter fills about 0.75 / gap² of its box: start tall enough that one round usually fits them all
        var bottom = max(max(t, b), t + n * gap * gap * 1.4f / max(gap, right - l))
        bottom = t + ceil((bottom - t) / grow - 1e-4f) * grow  // whole steps: hiding one app doesn't change the box
        repeat(64) {
            val rnd = Random(seed)
            val cell = gap / sqrt(2f)
            val cols = max(1, ceil((right - l) / cell).toInt() + 1); val rows = max(1, ceil((bottom - t) / cell).toInt() + 1)
            val grid = IntArray(cols * rows) { -1 }
            val px = FloatArray(cols * rows); val py = FloatArray(cols * rows); var m = 0
            val active = IntArray(cols * rows); var na = 0
            fun ok(x: Float, y: Float): Boolean {
                if (x < l || x > right || y < t || y > bottom || hypot(x - cx, y - cy) < clear) return false
                val gc = ((x - l) / cell).toInt(); val gr = ((y - t) / cell).toInt()
                for (rr in max(0, gr - 2)..min(rows - 1, gr + 2)) for (cc in max(0, gc - 2)..min(cols - 1, gc + 2)) {
                    val o = grid[rr * cols + cc]
                    if (o >= 0 && hypot(px[o] - x, py[o] - y) < gap) return false
                }
                return true
            }
            fun add(x: Float, y: Float) {
                px[m] = x; py[m] = y; grid[((y - t) / cell).toInt() * cols + ((x - l) / cell).toInt()] = m; active[na++] = m; m++
            }
            // the first drop just above the orb's clear ring (or under it, or the corner): always the same start
            when {
                ok(cx, cy - clear) -> add(cx, cy - clear)
                ok(cx, cy + clear) -> add(cx, cy + clear)
                ok(l, t) -> add(l, t)
            }
            while (na > 0 && m < px.size) {
                val ai = rnd.nextInt(na); val p = active[ai]
                var found = false
                for (k in 0 until 30) {
                    val a = rnd.nextFloat() * 2 * PI.toFloat(); val d = gap * (1f + rnd.nextFloat())
                    val x = px[p] + cos(a) * d; val y = py[p] + sin(a) * d
                    if (ok(x, y)) { add(x, y); found = true; break }
                }
                if (!found) active[ai] = active[--na]
            }
            if (m >= n) {
                val order = (0 until m).sortedBy { hypot(px[it] - cx, py[it] - cy) }
                var low = t
                for (i in 0 until n) { outX[i] = px[order[i]]; outY[i] = py[order[i]]; low = max(low, outY[i]) }
                return low
            }
            bottom += grow
        }
        // shortcut: a box too narrow for one drop never fills; the rest stand in a column, never seen on a phone
        for (i in 0 until n) { outX[i] = l; outY[i] = t + i * gap }
        return t + (n - 1) * gap
    }

    /** The shader's count drop records (SPARE floats each) sorted by top edge, in place, nothing allocated: a pixel
     *  stops at the first drop that starts below it. Insertion sort: at most 49, mostly in order already. */
    fun sortByTop(a: FloatArray, count: Int) {
        for (i in 1 until count) {
            val o = i * SPARE; val x = a[o]; val y = a[o + 1]; val z = a[o + 2]; val w = a[o + 3]
            var j = i - 1
            while (j >= 0 && a[j * SPARE + 3] > w) { System.arraycopy(a, j * SPARE, a, (j + 1) * SPARE, SPARE); j-- }
            val p = (j + 1) * SPARE
            a[p] = x; a[p + 1] = y; a[p + 2] = z; a[p + 3] = w
        }
    }

    /** The quick tray: count small drops in rings round the orb (cx, cy), the first ring r0 out and each next one
     *  step further, neighbours on a ring at least step apart (a chord, not an arc), starting at the top and skipping
     *  any slot outside l..r, t..b. Centres into outX/outY; returns how many found a place. */
    fun fan(count: Int, cx: Float, cy: Float, r0: Float, step: Float, l: Float, t: Float, r: Float, b: Float,
            outX: FloatArray, outY: FloatArray): Int {
        var placed = 0
        for (ring in 0 until 8) {
            if (placed >= count) break
            val rr = r0 + ring * step
            val slots = if (step >= 2 * rr) 1 else floor(PI / asin(step / (2 * rr))).toInt().coerceAtLeast(1)
            val turn = if (ring % 2 == 1) PI / slots else 0.0  // every other ring half a slot round: the rings interleave
            for (s in 0 until slots) {
                if (placed >= count) break
                val a = -PI / 2 + 2 * PI * s / slots + turn
                val x = cx + (rr * cos(a)).toFloat(); val y = cy + (rr * sin(a)).toFloat()
                if (x < l || x > r || y < t || y > b) continue
                outX[placed] = x; outY[placed] = y; placed++
            }
        }
        return placed
    }
}

/** The liquid, compiled per view (it carries this view's uniforms). null = this device can't run it: plain glass. */
private object MercuryShader {
    const val CAP = 48              // drops the shader takes at once, besides the orb; any more are drawn as plain glass

    fun make(look: Look): RuntimeShader? = try {
        RuntimeShader(SRC).apply {
            setFloatUniform("drops", FloatArray((CAP + 1) * MercuryMath.SPARE))
            setFloatUniform("count", 0f)
            setFloatUniform("level", MercuryMath.LEVEL)
            setFloatUniform("fade", 0f)
            setColorUniform("body", look.glass)
            setColorUniform("tint", look.glacier)
            setColorUniform("deep", look.shade(60))   // the lower rim: ink-tinted on light themes, deeper black on dark ones
            setColorUniform("shine", look.white(235))
        }
    } catch (t: Throwable) { Log.w("MercuryView", "liquid shader off, plain glass", t); null }

    // Metaballs: the field summed over the drops (and its gradient, in the same loop), liquid where it reaches level.
    // The edge is antialiased by the field's slope; the surface normal comes from the same gradient: a chrome rim of
    // glacier above and deep below, the frost's light from the top left as one highlight. Premultiplied out.
    // The drops come sorted by top edge (d.w), so a pixel's loop ends at the first drop that starts below it.
    private const val SRC = """
uniform float4 drops[49];
uniform float count;
uniform float level;
uniform float fade;
layout(color) uniform float4 body;
layout(color) uniform float4 tint;
layout(color) uniform float4 deep;
layout(color) uniform float4 shine;

half4 main(float2 p) {
    float f = 0.0;
    float2 g = float2(0.0);
    for (int i = 0; i < 49; i++) {
        if (float(i) >= count) { break; }
        float4 d = drops[i];
        if (p.y < d.w) { break; }
        float2 v = p - d.xy;
        float q = dot(v, v) * d.z;
        if (q < 1.0) {
            float u = 1.0 - q;
            f += u * u * u;
            g += (-6.0 * u * u * d.z) * v;
        }
    }
    if (f < level * 0.5) { return half4(0.0); }
    float gl = max(length(g), 0.0001);
    float a = clamp((f - level) / gl + 0.5, 0.0, 1.0);
    if (a <= 0.0) { return half4(0.0); }
    float h = clamp((f - level) / (1.0 - level), 0.0, 1.0);
    float3 n = float3((-g / gl) * sqrt(1.0 - h), sqrt(h));
    float rim = (1.0 - n.z) * (1.0 - n.z);
    float3 env = mix(deep.rgb, tint.rgb, smoothstep(-0.4, 0.4, -n.y));
    float3 rgb = mix(body.rgb, env, rim);
    float al = mix(body.a, 1.0, rim * 0.85);
    float spec = pow(max(dot(n, float3(-0.247, -0.330, 0.911)), 0.0), 60.0) * shine.a;
    rgb = mix(rgb, shine.rgb, spec);
    al = max(al, spec) * a * fade;
    return half4(rgb * al, al);
}
"""
}

/** Mercury Flow: no grid. Every app floats in a liquid drop scattered over the frost, the most used nearest Jharvis's
 *  orb (home draws the orb, under this view; this view says where and keeps a hole for it). The orb is a dense
 *  magnetic drop: drops near it lean toward it; drag it over them and they melt in, a deck up to DECK_MAX; with a deck,
 *  a tap on the orb fans them out round it as small drops (the quick tray), a flick pops them back to their places
 *  with a splash. A drag anywhere else scrolls the field. The liquid is one AGSL metaball pass; the frame loop runs
 *  only while something moves, so a still field costs nothing. */
class MercuryView(c: Context, private val look: Look, private val onLaunch: (icon: View, key: String) -> Unit,
                  private val onClose: () -> Unit, private val onSearch: () -> Unit) : FrameLayout(c) {

    val view: View get() = this
    /** Press and hold a drop: its app menu (home supplies what Add to dock and a change mean). */
    var menuActions: AppMenu.Actions? = null
    /** A tap on the orb with an empty deck: home's own orb click (Jharvis). */
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
    private val dropR = look.px(26).toFloat()           // a 52 dp drop; its touch target is the same
    private val iconR = look.px(18).toFloat()           // the icon inside it, 36 dp
    private val gap = dropR * 3                          // centre to centre at rest: the liquid of two drops never touches
    private val fanR = look.px(22).toFloat()            // a quick-tray drop, 44 dp drawn, 48 dp to touch
    private val fanStep = look.px(56).toFloat()         // 48 dp targets with 8 dp between
    private val touchR = look.px(24).toFloat()
    private val iconMax = look.px(48)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    // the list, rebuilt when it changes (arrays indexed like `apps`: index = rank, 0 the most used)
    private var apps: List<AppEntry> = emptyList()
    private var pending: List<AppEntry>? = null         // a newer ranking waits until shut, so nothing jumps
    private var builtVersion = -1
    private var loadGen = 0
    private var badges: Map<String, AppBadges.Badge> = emptyMap()
    private var n = 0
    private var keys = emptyArray<String>()
    private var bmps = arrayOfNulls<Bitmap>(0)
    private var labels = emptyArray<String>(); private var labelW = FloatArray(0)
    private var desc = emptyArray<String>()
    private var paused = BooleanArray(0)
    private var badged = BooleanArray(0)

    // each drop: its home in the field (y before the scroll), its spring offset and size, what they aim at
    private var hx = FloatArray(0); private var hy = FloatArray(0); private var near = FloatArray(0)
    private var ox = FloatArray(0); private var oy = FloatArray(0); private var vx = FloatArray(0); private var vy = FloatArray(0)
    private var tx = FloatArray(0); private var ty = FloatArray(0)
    private var ks = FloatArray(0); private var kv = FloatArray(0); private var kt = FloatArray(0); private var damp = FloatArray(0)
    // drawn this frame: screen centre and radius (0 = not drawn)
    private var qx = FloatArray(0); private var qy = FloatArray(0); private var qr = FloatArray(0)
    private var fieldLow = 0f
    private val pt = FloatArray(2)

    // the deck: keys in the order he swallowed them (keys, so a hidden app drops out on a rebuild); deckAt[i] = its place
    private val deck = ArrayList<String>()
    private var deckAt = IntArray(0)
    private var fanOpen = false
    private val fanX = FloatArray(DECK_MAX); private val fanY = FloatArray(DECK_MAX)
    private var fanN = 0

    private var lensR = 1f
    private var bars = Insets.NONE
    private var top = 0f                                // the search field's top: the top tenth stays clear

    // the orb: where he left it, screen px from the middle
    private var ptrX = 0f; private var ptrY = 0f
    private var moved = false

    // scrolling: px the field has moved up, rubber-band past either end while dragged
    private var scroll = 0f
    private var raw = 0f
    private val scroller = OverScroller(c)

    // the liquid: one shader over the whole view, its uniforms rewritten in place each frame
    private val liquid = MercuryShader.make(look)
    private val liquidPaint = Paint().apply { shader = liquid }
    private val uni = FloatArray((MercuryShader.CAP + 1) * MercuryMath.SPARE)
    private var glassFallback = false                   // the shader threw once: plain glass from then on

    private val dropFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.glass }
    private val dropEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = look.px(1).toFloat(); color = look.glassEdge }
    private val fillA = android.graphics.Color.alpha(look.glass); private val edgeA = android.graphics.Color.alpha(look.glassEdge)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.accent }
    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.withAlpha(look.ground, 240) }  // ground, not white: white is faint on dark themes
    private val pillEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = look.px(1).toFloat(); color = look.glassEdge }
    private val nameText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = look.ink; typeface = look.sans; textSize = sp(13f); textAlign = Paint.Align.CENTER }
    private val dst = RectF()
    private val lensHole = Path()                       // this view draws over home's orb: nothing is painted on its lens

    private val press = Spring(1f, 0.0005f) { invalidate() }
    private val nameIn = Spring(1f, 0.002f) { postInvalidateOnAnimation() }  // may start inside a draw (computeScroll)
    private var named = -1                              // the drop whose name shows

    /** Stands in for the canvas-drawn drop wherever a real view is needed: the launch grows from it, the app menu
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

    /** Back with the quick tray out folds it first (over home's own Back, which would close the drawer). */
    private val foldBack = OnBackInvokedCallback { fold() }
    private var backOn = false

    init {
        setWillNotDraw(false)
        clipChildren = false
        addView(anchor, LayoutParams(iconMax, iconMax))
        addView(searchPill, LayoutParams(-1, look.px(48), Gravity.TOP))
        visibility = INVISIBLE
        setOnApplyWindowInsetsListener { _, i -> useInsets(i); i }
    }

    // ── Contract: load, opening, reset, ownsTouch ───────────────────────────────────────────────────

    /** Re-rank apps and re-read hidden apps and badges on the loader thread. The nearest drops' icons are drawn
     *  before the list comes back; the rest fill in as they're drawn. */
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

    /** Back to the start: the deck empty, the tray folded, the orb back in the middle, every drop home, nothing pressed. */
    fun reset() {
        removeCallbacks(hold)
        scroller.forceFinished(true)
        sleep()
        touching = false; dragging = false; mode = NONE
        deck.clear(); deckAt.fill(-1); fanOpen = false; fanN = 0; backOff()
        moved = false; ptrX = 0f; ptrY = 0f
        pose()  // first: the drops aim at where the orb is now
        hit = -1; pressed = -1; named = -1
        press.snap(1f); nameIn.snap(1f)
        scroll = 0f; raw = 0f
        aim(); settle()
        place(); invalidate()
        frames()
    }

    /** True on the orb (a drag there moves it), on a drop, with the tray out, and anywhere once the field has
     *  scrolled. At the top a pull down on empty frost is home's: it closes. */
    fun ownsTouch(x: Float, y: Float) = progress >= 0.95f && n > 0 && (fanOpen || onOrb(x, y) || scroll > 0.5f || dropAt(x, y) >= 0)

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
        backOff()
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
        val oldKeys = keys; val oldX = qx; val oldY = qy  // open (a hide from the menu): the drops that stay glide from here
        n = list.size
        keys = Array(n) { list[it].key }
        bmps = Array(n) { Icons.cached(list[it].key) }
        val maxLabel = look.px(200).toFloat()
        labels = Array(n) { TextUtils.ellipsize(list[it].label, nameText, maxLabel, TextUtils.TruncateAt.END).toString() }
        labelW = FloatArray(n) { nameText.measureText(labels[it]) }
        paused = BooleanArray(n) { list[it].suspended }
        badged = BooleanArray(n); desc = Array(n) { list[it].label }
        hx = FloatArray(n); hy = FloatArray(n); near = FloatArray(n)
        ox = FloatArray(n); oy = FloatArray(n); vx = FloatArray(n); vy = FloatArray(n); tx = FloatArray(n); ty = FloatArray(n)
        ks = FloatArray(n) { 1f }; kv = FloatArray(n); kt = FloatArray(n) { 1f }; damp = FloatArray(n) { 1f }
        qx = FloatArray(n); qy = FloatArray(n); qr = FloatArray(n)
        // the deck keeps what is still listed (a hidden app is gone from it), in his order
        val at = HashMap<String, Int>(n * 2); for (i in 0 until n) at[list[i].key] = i
        deck.retainAll { it in at }
        deckAt = IntArray(n) { -1 }; for ((d, k) in deck.withIndex()) at[k]?.let { deckAt[it] = d; ks[it] = 0f; kt[it] = 0f }
        if (deck.isEmpty() && fanOpen) { fanOpen = false; backOff() }
        if (fanOpen) layFan()
        hit = -1; pressed = -1; hover = -1; a11yFocus = -1; named = -1
        showBadges()
        layField()
        if (progress > 0.001f && !still && oldKeys.isNotEmpty()) {
            val was = HashMap<String, Int>(oldKeys.size * 2); for (j in oldKeys.indices) was[oldKeys[j]] = j
            for (i in 0 until n) { val j = was[keys[i]] ?: continue; ox[i] = oldX[j] - hx[i]; oy[i] = oldY[j] - (hy[i] - scroll) }
            place(); wake()
        }
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

    /** Fits everything to the screen: the search field, the orb's lens, the field below it. Runs when the size or
     *  insets change; the drops land there at once. */
    private fun geometry() {
        if (width == 0) return
        lensR = min(width.toFloat(), height * 0.42f) * OrbMath.LENS * 0.45f  // home's orb view is 42% of the height, compact 0.45
        top = max(height * 0.10f, bars.top + look.px(8).toFloat())
        val lp = searchPill.layoutParams as LayoutParams
        val left = bars.left + look.px(20)
        if (lp.leftMargin != left || lp.rightMargin != bars.right + look.px(20) || lp.topMargin != top.toInt()) {
            lp.leftMargin = left; lp.rightMargin = bars.right + look.px(20); lp.topMargin = top.toInt()
            post { searchPill.requestLayout() }  // not mid-layout
        }
        if (moved) clampLens(ptrX, ptrY)  // turned with the drawer open: keep it on screen
        pose()
        if (fanOpen) layFan()
        layField()
    }

    /** Every drop's home: one fixed Poisson scatter, ranked nearest the orb's rest place. */
    private fun layField() {
        if (width == 0 || n == 0) { fieldLow = 0f; place(); invalidate(); return }
        val l = bars.left + look.px(16) + dropR; val r = width - bars.right - look.px(16) - dropR
        val t = top + look.px(48 + 16) + dropR; val b = max(t + 1f, height - bars.bottom - look.px(16) - dropR)
        val cx = width / 2f; val cy = height / 2f
        // a fixed seed: a hidden app or a new rank keeps the scatter, only the apps whose rank changed swap places
        fieldLow = MercuryMath.place(n, l, t, r, b, gap, cx, cy, lensR + dropR + look.px(20), SEED, hx, hy)
        val far = hypot(width.toFloat(), height.toFloat()) * 0.6f
        for (i in 0 until n) near[i] = (hypot(hx[i] - cx, hy[i] - cy) / far).coerceIn(0f, 1f)
        setScroll(scroll.coerceIn(0f, maxScroll()))
        aim(); settle()
        place(); invalidate()
    }

    private fun maxScroll() = max(0f, fieldLow + dropR + look.px(16) + bars.bottom - height)

    // ── Aiming and placing: where each drop wants to be, where it is drawn ──────────────────────────

    private fun stage(p: Float, from: Float, span: Float) = if (still) 1f else ((p - from) / span).coerceIn(0f, 1f)

    /** Each drop's targets: free ones home plus the orb's pull (none under "Remove animations"), swallowed ones into
     *  the orb at no size, fanned ones on their tray slot at the tray's size. */
    private fun aim() {
        val cx = orbCx(); val cy = orbCy(); val reach = lensR + dropR * 4; val most = look.px(14).toFloat()
        for (i in 0 until n) {
            val sx = hx[i]; val sy = hy[i] - scroll
            val d = deckAt[i]
            when {
                d < 0 -> {
                    if (still) { tx[i] = 0f; ty[i] = 0f } else { MercuryMath.pull(cx - sx, cy - sy, reach, most, pt); tx[i] = pt[0]; ty[i] = pt[1] }
                    kt[i] = 1f
                }
                fanOpen && d < fanN -> { tx[i] = fanX[d] - sx; ty[i] = fanY[d] - sy; kt[i] = fanR / dropR }
                else -> { tx[i] = cx - sx; ty[i] = cy - sy; kt[i] = 0f }
            }
        }
    }

    /** Every drop straight onto its targets, at rest: a new size, "Remove animations", a reset. */
    private fun settle() {
        for (i in 0 until n) { ox[i] = tx[i]; oy[i] = ty[i]; ks[i] = kt[i]; vx[i] = 0f; vy[i] = 0f; kv[i] = 0f; damp[i] = 1f }
    }

    /** Where every drop is drawn this frame and how big: its size, times how far the opening has grown it in (a
     *  ripple out from the orb). Runs when anything moves; onDraw only paints. */
    private fun place() {
        val p = progress
        for (i in 0 until n) {
            qx[i] = hx[i] + ox[i]; qy[i] = hy[i] - scroll + oy[i]
            val grow = if (still) 1f else OrbMath.smooth(stage(p, 0.3f + 0.3f * near[i], 0.4f))
            qr[i] = dropR * max(0f, ks[i]) * grow
        }
        name()
    }

    /** A drop he can see and touch: out on the tray, or free, grown in, and neither behind the tray nor under the lens. */
    private fun reachable(i: Int) = i in 0 until n && qy[i] > -qr[i] && qy[i] < height + qr[i] &&
        if (deckAt[i] >= 0) fanOpen && deckAt[i] < fanN && qr[i] >= fanR * 0.5f
        else !fanOpen && qr[i] >= dropR * 0.5f && hypot(qx[i] - orbCx(), qy[i] - orbCy()) > lensR

    /** The name shows for the focused drop, else the one nearest his finger while he touches, else the one nearest
     *  the orb. A new one fades in, with a light tick if his finger brought it. */
    private fun name() {
        val f = a11yFocus
        val want = if (f >= 0 && reachable(f)) f else if (touching && mode == FIELD && (dragging || hit >= 0)) nearest(fingerX, fingerY, Float.MAX_VALUE)
            else nearest(orbCx(), orbCy(), Float.MAX_VALUE)
        if (want == named) return
        named = want
        if (want >= 0 && dragging) Haptics.crossing(this)  // touch-down has its own tick
        if (want >= 0 && !still) { nameIn.snap(0f); nameIn.to(1f, response = 0.25f) }
    }

    /** The reachable drop nearest (x, y) within reach of its centre, or -1 (with the tray out, only its drops reach). */
    private fun nearest(x: Float, y: Float, reach: Float): Int {
        var best = -1; var bestD = reach * reach
        for (i in 0 until n) {
            if (!reachable(i)) continue
            val dx = qx[i] - x; val dy = qy[i] - y; val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    // ── The orb, the deck, the tray ─────────────────────────────────────────────────────────────────

    private fun orbCx() = width / 2f + orbX
    private fun orbCy() = height / 2f + orbY
    private fun onOrb(x: Float, y: Float) = hypot(x - orbCx(), y - orbCy()) <= max(lensR * 1.15f, touchR)

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

    /** The dragged orb runs over free drops: each it covers melts in, up to DECK_MAX. */
    private fun swallow() {
        if (deck.size >= DECK_MAX) return
        val cx = orbCx(); val cy = orbCy(); val had = deck.size
        for (i in 0 until n) {
            if (deckAt[i] >= 0 || qr[i] < dropR * 0.5f || !MercuryMath.swallows(qx[i] - cx, qy[i] - cy, lensR, dropR)) continue
            deckAt[i] = deck.size; deck += apps[i].key
            Haptics.snap(this)
            if (deck.size >= DECK_MAX) break
        }
        if (deck.size != had) treeChanged()
    }

    /** A flick of the orb: the deck pops back out, each drop leaving with the flick's speed and splashing home. */
    private fun popAll(fvx: Float, fvy: Float) {
        val cx = orbCx(); val cy = orbCy()
        for (i in 0 until n) {
            if (deckAt[i] < 0) continue
            deckAt[i] = -1
            val sx = hx[i]; val sy = hy[i] - scroll
            if (ks[i] < 0.05f) { ox[i] = cx - sx; oy[i] = cy - sy }  // gone: out of the orb's heart; still melting: from where it is
            if (!still) {
                MercuryMath.pop(fvx, fvy, sx - cx, sy - cy, HANDOFF, look.px(BURST_DP).toFloat(), pt)
                vx[i] = pt[0]; vy[i] = pt[1]; damp[i] = SPLASH
            }
        }
        deck.clear(); treeChanged()
        performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CONFIRM)
    }

    /** The tray's slots round the orb where it is now, inside the bars and below the search field. */
    private fun layFan() {
        val r = fanStep / 2
        fanN = MercuryMath.fan(deck.size, orbCx(), orbCy(), lensR + fanR + look.px(12), fanStep,
            bars.left + r, top + look.px(48 + 8) + r, width - bars.right - r, height - bars.bottom - r, fanX, fanY)
    }

    private fun openFan() {
        fanOpen = true; layFan(); backOn()
        moveDrops()  // touch-down on the orb already ticked
        treeChanged()
    }

    private fun fold() {
        if (!fanOpen) return
        fanOpen = false; backOff()
        if (pressed >= 0) unpress()
        moveDrops()
        treeChanged()
    }

    /** TalkBack's double-tap on home's orb: with a deck, the tray (open, or folded if out), as a finger's tap. */
    fun tapOrb(): Boolean {
        if (progress < 0.9f || n == 0 || deck.isEmpty()) return false
        if (fanOpen) fold() else openFan()
        return true
    }

    private fun backOn() {
        if (backOn) return
        val act = context as? Activity ?: return
        try { act.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, foldBack); backOn = true } catch (_: Exception) {}
    }

    private fun backOff() {
        if (!backOn) return
        backOn = false
        try { (context as? Activity)?.onBackInvokedDispatcher?.unregisterOnBackInvokedCallback(foldBack) } catch (_: Exception) {}
    }

    /** Something changed where drops aim: they glide there, or under "Remove animations" are there at once. */
    private fun moveDrops() {
        aim()
        if (still) { settle(); place(); invalidate(); return }
        wake()
    }

    // ── The frame loop: only while a drop moves ─────────────────────────────────────────────────────

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
        aim()  // the orb or the scroll may have moved since the last frame
        val moving = MercuryMath.step(ox, oy, vx, vy, tx, ty, ks, kv, kt, damp, n, RESPONSE, dt)
        place(); invalidate()
        if (moving) { Choreographer.getInstance().postFrameCallback(frameCb); return }
        running = false
        frames()
    }

    // ── Frames ──────────────────────────────────────────────────────────────────────────────────────

    private fun applyProgress() {
        val p = progress
        if (p <= 0.001f) pending?.let { apps = it; pending = null; build() }
        if (p < 0.9f) fold()  // shutting, or search over it: Back is theirs again, not a hidden tray's
        // the search field arrives once the drops are mostly in, same path in and out
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
        if (n == 0 || p <= 0.001f) return
        val a = if (still) p.coerceIn(0f, 1f) else min(1f, p * 1.6f)
        // home draws the orb at progress x where he left it, so as it shuts or search opens the whole field rides with it
        val t = min(p, 1f) - 1f
        canvas.save()
        canvas.translate(t * orbX, t * orbY)
        lensHole.rewind(); lensHole.addCircle(orbCx(), orbCy(), lensR, Path.Direction.CW)
        canvas.clipOutPath(lensHole)
        val sh = liquid
        val wet = sh != null && !glassFallback && !still && !Blur.saver(context)  // battery saver, "Remove animations": plain glass
        var count = 0
        if (wet) {
            // the orb first: a dense drop the size of its lens, so a neck forms where a drop comes near
            count = pack(0, orbCx(), orbCy(), lensR)
            for (i in 0 until n) {
                if (qr[i] < 0.5f || !onScreen(i, qr[i] * MercuryMath.REACH)) continue
                if (count > MercuryShader.CAP) { drawGlass(canvas, i, a); continue }  // past the cap: plain glass
                count = pack(count, qx[i], qy[i], qr[i] * (if (i == pressed) press.value else 1f))
            }
            MercuryMath.sortByTop(uni, count)  // the orb too: pinned first, its early top edge would end every pixel below it
            try {
                sh!!.setFloatUniform("drops", uni)
                sh.setFloatUniform("count", count.toFloat())
                sh.setFloatUniform("fade", a)
                canvas.drawPaint(liquidPaint)
            } catch (t: Throwable) { Log.w("MercuryView", "liquid shader failed, plain glass", t); glassFallback = true; postInvalidateOnAnimation() }  // never again: next frame is glass
        } else for (i in 0 until n) if (qr[i] >= 0.5f && onScreen(i, qr[i])) drawGlass(canvas, i, a)
        for (i in 0 until n) {
            val r = qr[i]
            if (r < 0.5f || !onScreen(i, r)) continue
            val k = r / dropR * (if (i == pressed) press.value else 1f)
            val f = a * (if (paused[i]) 0.5f else 1f) * min(1f, r / (dropR * 0.4f))  // a melting drop's icon goes with it
            val bm = bmps[i]
            if (bm != null) { val ir = iconR * k; dst.set(qx[i] - ir, qy[i] - ir, qx[i] + ir, qy[i] + ir); iconPaint.alpha = (255 * f).toInt(); canvas.drawBitmap(bm, null, dst, iconPaint) }
            if (badged[i]) { dotPaint.alpha = (255 * f).toInt(); canvas.drawCircle(qx[i] + r * 0.72f, qy[i] - r * 0.72f, look.px(4) * k, dotPaint) }
        }
        drawName(canvas, a)
        canvas.restore()
    }

    private fun onScreen(i: Int, r: Float) = qy[i] + r > 0f && qy[i] - r < height && qx[i] + r > 0f && qx[i] - r < width

    /** Drop slot k of the shader's array: centre and 1/reach², reach = REACH radius. Returns the next slot. */
    private fun pack(k: Int, x: Float, y: Float, r: Float): Int {
        val o = k * MercuryMath.SPARE; val reach = r * MercuryMath.REACH
        uni[o] = x; uni[o + 1] = y; uni[o + 2] = 1f / (reach * reach); uni[o + 3] = y - reach
        return k + 1
    }

    /** One drop as plain frosted glass: no shader, battery saver, "Remove animations", or past the shader's cap. */
    private fun drawGlass(canvas: Canvas, i: Int, a: Float) {
        val r = qr[i] * (if (i == pressed) press.value else 1f)
        dropFill.alpha = (fillA * a).toInt(); dropEdge.alpha = (edgeA * a).toInt()
        canvas.drawCircle(qx[i], qy[i], r, dropFill)
        canvas.drawCircle(qx[i], qy[i], r, dropEdge)
    }

    /** The named drop's name in a small pill just under it (above it near the bottom edge). */
    private fun drawName(canvas: Canvas, a: Float) {
        val j = named
        if (j !in 0 until n || !reachable(j)) return
        val pa = nameIn.value.coerceIn(0f, 1f) * a
        if (pa < 0.01f) return
        val h = look.px(28).toFloat(); val w = labelW[j] + look.px(24); val gapY = look.px(6); val r = qr[j]
        var t = qy[j] + r + gapY
        if (t + h > height - bars.bottom) t = qy[j] - r - gapY - h
        val l = (qx[j] - w / 2).coerceIn(look.px(8).toFloat(), max(look.px(8).toFloat(), width - look.px(8) - w))
        dst.set(l, t, l + w, t + h)
        pillFill.alpha = (240 * pa).toInt(); pillEdge.alpha = (edgeA * pa).toInt(); nameText.alpha = (255 * pa).toInt()
        canvas.drawRoundRect(dst, h / 2, h / 2, pillFill)
        canvas.drawRoundRect(dst, h / 2, h / 2, pillEdge)
        canvas.drawText(labels[j], l + w / 2, t + h / 2 + nameText.textSize * 0.36f, nameText)
    }

    // ── Scrolling: the field moves up and down ──────────────────────────────────────────────────────

    private fun setScroll(v: Float) {
        if (v == scroll) return
        scroll = v
        if (still) { place(); invalidate() } else { place(); invalidate(); wake() }  // the orb's pull follows the drops past it
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

    // ── Touch: tap, hold, scroll, drag the orb, the tray ────────────────────────────────────────────

    private var touching = false
    private var dragging = false
    private var held = false
    private var caught = false      // the touch stopped a running fling: it doesn't also launch
    private var folding = false     // the touch folded the tray: its tap opens nothing
    private var deckDown = 0        // the deck as the touch began: only a deck he already had pops (a sweep that builds one ends fast too)
    private var mode = NONE
    private var hit = -1
    private var pressed = -1
    private var pid = -1
    private var downX = 0f; private var downY = 0f; private var lastY = 0f
    private var fingerX = 0f; private var fingerY = 0f
    private var grabX = 0f; private var grabY = 0f  // finger to orb: it moves 1:1 from where he took it
    private var vt: VelocityTracker? = null

    private val hold = Runnable {
        val i = hit
        if (i !in 0 until n || dragging || progress < 0.9f) return@Runnable
        held = true
        unpress()
        menuActions?.let { AppMenu.show(anchorAt(i), apps[i].key, look, it) }
    }

    private fun dropAt(x: Float, y: Float) = nearest(x, y, touchR + look.px(4))

    private fun grab(x: Float, y: Float) { grabX = x - orbCx(); grabY = y - orbCy() }

    // the orb can rest under the search field: on it, the touch is the orb's before the field sees it
    override fun onInterceptTouchEvent(e: MotionEvent) =
        e.actionMasked == MotionEvent.ACTION_DOWN && progress >= 0.9f && n > 0 && (fanOpen || onOrb(e.x, e.y))

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // a finger already down when it starts closing still gets its UP/CANCEL, so nothing stays "touching"
        if (progress < 0.9f && e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true; dragging = false; held = false; folding = false; hit = -1; deckDown = deck.size
                downX = e.x; downY = e.y; lastY = e.y; fingerX = e.x; fingerY = e.y; pid = e.getPointerId(0)
                vt?.recycle(); vt = VelocityTracker.obtain().apply { addMovement(e) }
                caught = !scroller.isFinished
                scroller.forceFinished(true)
                frames()
                if (fanOpen) {
                    val i = dropAt(e.x, e.y)
                    parent?.requestDisallowInterceptTouchEvent(true)  // the tray is out: this touch is the tray's
                    if (i >= 0) { mode = FIELD; pressDrop(i); return true }
                    folding = true; fold()  // a touch off the tray folds it; on the orb it may still drag
                    if (!onOrb(e.x, e.y)) { mode = NONE; return true }
                }
                if (onOrb(e.x, e.y)) {
                    mode = ORB; if (!folding) tick()
                    grab(e.x, e.y)
                    parent?.requestDisallowInterceptTouchEvent(true)  // a drag here moves the orb, never closes
                    return true
                }
                mode = FIELD
                if (scroll > 0.5f || caught) parent?.requestDisallowInterceptTouchEvent(true)  // every drag scrolls once off the top
                raw = unbanded()
                val i = if (caught) -1 else dropAt(e.x, e.y)
                if (i >= 0) pressDrop(i) else { name(); invalidate() }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (held || mode == NONE) return true
                val k = e.findPointerIndex(pid).takeIf { it >= 0 } ?: return true
                val x = e.getX(k); val y = e.getY(k)
                if (mode == ORB) {
                    if (!dragging) {
                        if (hypot(x - downX, y - downY) <= slop) return true
                        dragging = true; moved = true
                        grab(x, y)  // moves 1:1 from here, so the slop doesn't jump it
                    }
                    clampLens(x - grabX - width / 2f, y - grabY - height / 2f)
                    pose()
                    swallow()
                    moveDrops()
                    return true
                }
                if (!dragging) {
                    val dx = x - downX; val dy = y - downY
                    if (abs(dy) <= slop && abs(dx) <= slop) return true
                    removeCallbacks(hold); unpress(); hit = -1
                    if (fanOpen) { dragging = true; return true }  // off a tray drop: no launch, nothing scrolls under the tray
                    // home's own test: wait for a clear direction, and an unowned pull down is home's (it closes)
                    val vertical = abs(dy) > abs(dx) * 1.3f; val horizontal = abs(dx) > abs(dy) * 1.3f
                    if (!vertical && !horizontal) return true
                    if (vertical && dy > 0 && !ownsTouch(downX, downY)) return true
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    lastY = y  // scrolls 1:1 from here, so the slop doesn't jump
                }
                if (fanOpen) return true
                raw -= y - lastY
                lastY = y; fingerX = x; fingerY = y
                setScroll(banded(raw))
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == pid) {
                val k = if (e.actionIndex == 0) 1 else 0  // the other finger carries on from where things are
                pid = e.getPointerId(k)
                lastY = e.getY(k)
                if (mode == ORB) grab(e.getX(k), e.getY(k)) else raw = unbanded()
            }
            MotionEvent.ACTION_UP -> finish(e, true)
            MotionEvent.ACTION_CANCEL -> finish(e, false)
        }
        return true
    }

    private fun pressDrop(i: Int) {
        hit = i
        if (hit != pressed) press.snap(1f)  // the last drop's spring back doesn't hand its squish to this one
        tick(); pressed = hit
        if (!still) press.to(0.9f, response = 0.16f, damping = 1f)
        postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
        name(); invalidate()
    }

    private fun finish(e: MotionEvent, up: Boolean) {
        removeCallbacks(hold)
        touching = false
        vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
        val fx = vt?.getXVelocity(pid) ?: 0f; val fy = vt?.getYVelocity(pid) ?: 0f
        if (mode == ORB) {
            val tap = up && !dragging && !caught && !folding && progress >= 0.9f
            // with a deck the orb is the quick tray; empty, it is still Jharvis
            if (tap) { if (deck.isNotEmpty()) openFan() else onOrbTap?.invoke() }
            // the orb stays where he let go; a flick pops the deck back out
            if (dragging && up && deckDown > 0 && deck.isNotEmpty() && hypot(fx, fy) > look.px(FLICK_DP)) { popAll(fx, fy); moveDrops() }
        } else if (mode == FIELD) {
            val tap = up && progress >= 0.9f && !dragging && !held && !caught && hit >= 0
            unpress()
            if (tap) launch(hit)
            val mx = maxScroll()
            if (!fanOpen && dragging && up && !still && scroll in 0f..mx) {
                scroller.fling(0, scroll.toInt(), 0, (-fy).toInt(), 0, 0, 0, mx.toInt(), 0, look.px(40))
                postInvalidateOnAnimation()
            }
        }
        vt?.recycle(); vt = null
        dragging = false; mode = NONE
        settleScroll()
        place(); invalidate()  // the name goes back to the drop nearest the orb
        frames()
    }

    private fun unpress() { if (still) press.snap(1f) else press.to(1f, response = 0.42f, damping = 0.82f) }

    /** The invisible stand-in, moved onto drop i at its icon's size. */
    private fun anchorAt(i: Int): View {
        anchor.setImageBitmap(bmps[i])
        anchor.translationX = qx[i] - iconMax / 2f; anchor.translationY = qy[i] - iconMax / 2f
        val s = iconR * (qr[i] / dropR) / (iconMax / 2f)  // as onDraw sizes it, so the menu's lift starts there
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

    // ── TalkBack: every reachable drop (field or tray) is a virtual button; scroll moves the field ──────

    private var hover = -1
    private var a11yFocus = -1
    private val screenAt = IntArray(2)

    private val a11y = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(id: Int): AccessibilityNodeInfo? {
            val host = this@MercuryView
            if (id == NO_ID) {
                val info = AccessibilityNodeInfo(host)
                onInitializeAccessibilityNodeInfo(info)
                if (searchPill.visibility == VISIBLE) info.addChild(searchPill)  // a provider host lists its real children itself
                for (i in 0 until n) if (reachable(i)) info.addChild(host, i)
                if (maxScroll() > 0f && !fanOpen) {
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
                contentDescription = if (deckAt[id] >= 0) "${desc[id]}, in the quick tray" else desc[id]
                isEnabled = true; isFocusable = true; isClickable = true; isLongClickable = true
                isVisibleToUser = reachable(id)
                isAccessibilityFocused = a11yFocus == id
                getLocationOnScreen(screenAt)
                val r = max(qr[id], touchR)
                setBoundsInScreen(Rect((screenAt[0] + qx[id] - r).toInt(), (screenAt[1] + qy[id] - r).toInt(),
                    (screenAt[0] + qx[id] + r).toInt(), (screenAt[1] + qy[id] + r).toInt()))
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
                addAction(if (a11yFocus == id) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                    else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
            }
        }

        override fun performAction(id: Int, action: Int, args: Bundle?): Boolean {
            if (id == NO_ID) {
                val dir = when (action) { AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 1f; AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -1f; else -> 0f }
                if (dir == 0f || maxScroll() <= 0f || fanOpen) return performAccessibilityAction(action, args)
                scrollBy(dir * height * 0.6f)
                postDelayed({ sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED) }, if (still) 0L else 380L)  // once it has moved
                return true
            }
            if (id !in 0 until n) return false
            // a node read before a scroll or a swallow may be gone since: only reachable drops open
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
        val i = if (onPill || e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) -1 else dropAt(e.x, e.y)
        if (i != hover) {
            val was = hover; hover = i
            if (i >= 0) send(i, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            if (was >= 0) send(was, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
        }
        return i >= 0 || super.dispatchHoverEvent(e)
    }

    /** The tray or the deck changed which drops are reachable: TalkBack re-reads the tree. */
    private fun treeChanged() {
        if (context.getSystemService(AccessibilityManager::class.java)?.isEnabled != true) return
        val ev = AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            setSource(this@MercuryView); contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
        }
        parent?.requestSendAccessibilityEvent(this, ev)
    }

    private fun send(i: Int, type: Int) {
        val ev = AccessibilityEvent(type).apply {
            setSource(this@MercuryView, i)
            packageName = context.packageName; className = Button::class.java.name
            contentDescription = desc[i]
        }
        parent?.requestSendAccessibilityEvent(this, ev)
    }

    private companion object {
        const val NONE = 0; const val FIELD = 1; const val ORB = 2
        const val FIRST = 48            // icons drawn before the list comes back: about a screenful of drops
        const val DECK_MAX = 12         // the orb holds this many: two rings of tray round it on a phone
        const val RESPONSE = 0.4f       // s, each drop's spring: a slow drift as it settles, never an idle wobble
        const val FLICK_DP = 900        // dp/s: let go of the orb faster than this and the deck pops out
        const val HANDOFF = 0.35f       // share of the flick each popped drop carries
        const val BURST_DP = 700        // dp/s out of the orb toward its home
        const val SPLASH = 0.62f        // a popped drop's damping: one overshoot as it lands, the splash
        const val SEED = 1234
    }
}
