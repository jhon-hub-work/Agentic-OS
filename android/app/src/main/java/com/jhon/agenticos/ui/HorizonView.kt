package com.jhon.agenticos.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Insets
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
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
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Pure tunnel arithmetic for Horizon Core, tested on the JVM. A floor and a ceiling are seen through a camera whose
 *  vanishing point is the orb: a point u px across a plane's near line and d rows past it draws at scale
 *  s = near / (near + d), pulled from its place on that near line (ax + u, ay) toward the vanishing point (vx, vy) by
 *  1 - s. The floor's near line is at the bottom, the ceiling's at the top, the same formula for both. The near lines
 *  stay put wherever the orb goes, so moving the orb shears the whole tunnel: the warp. */
internal object HorizonMath {
    const val NEAR = 3f             // rows from the camera to the near line: row 1 at 3/4 size, row 2 at 3/5
    const val S_GRID = 0.06f        // grid lines run on to here, a hair from the horizon
    const val S_FAR = 0.14f         // a tile smaller than this is not drawn
    const val S_CLEAR = 0.34f       // ... and fully there from this size on
    const val S_OFF = 1.45f         // a tile sliding off the bottom has faded out by this size
    const val LEAN = 0.35f          // a tile leans this share of its lane's slant toward the orb
    const val TILE = 0.62f          // tile size, of its lane at the near line
    const val TAP_DP = 40f          // drawn this big or more, a tile takes taps (on a 48 dp target)
    const val TAP_ALPHA = 0.6f      // ... and only once it is mostly there

    // Cyberpunk 2077's neon. Each app keeps one (neon(key)), so its colour stays with it as the ranking shifts
    const val NEON_YELLOW = 0xFFFCEE0A.toInt()
    const val NEON_CYAN = 0xFF00F0FF.toInt()
    const val NEON_PINK = 0xFFFF003C.toInt()
    val NEON = intArrayOf(NEON_YELLOW, NEON_CYAN, NEON_PINK)

    /** An app's colour, an index into NEON: the same every run. */
    fun neon(key: String) = Math.floorMod(key.hashCode(), NEON.size)

    /** Rows alternate planes, most used first: row 0 on the floor, row 1 on the ceiling over it, row 2 on the floor
     *  one depth back, and so on. A row's depth is how many rows stand before it on its own plane. */
    fun ceiling(row: Int) = row % 2 == 1
    fun depthOf(row: Int) = row / 2
    fun depths(rows: Int) = (rows + 1) / 2

    /** 3 lanes on a phone, 4 on a wide screen. */
    fun cols(widthDp: Float) = if (widthDp < 520f) 3 else 4

    /** A row's scale d rows past the near line: 1 on it, toward 0 at the horizon, over 1 once passed; 0 = behind the camera. */
    fun scale(d: Float, near: Float = NEAR) = if (d <= -near * 0.999f) 0f else near / (near + d)

    /** scale's inverse: how many rows past the near line a size sits. */
    fun depth(s: Float, near: Float = NEAR) = near / s - near

    /** A plane point's screen x and y at scale s, ay that plane's near line. */
    fun x(u: Float, s: Float, ax: Float, vx: Float) = vx + (ax + u - vx) * s
    fun y(s: Float, ay: Float, vy: Float) = vy + (ay - vy) * s

    /** How much of a tile shows at size s: none far off, rising in, then fading as it slides off the edge. */
    fun alpha(s: Float): Float {
        if (s <= S_FAR || s >= S_OFF) return 0f
        val far = OrbMath.smooth((s - S_FAR) / (S_CLEAR - S_FAR))
        return if (s > 1f) far * OrbMath.smooth((S_OFF - s) / (S_OFF - 1f)) else far
    }

    /** The nearest and farthest depths (of depths) with anything to draw, scrolled sr depths forward. last < first = none. */
    fun firstRow(sr: Float) = max(0, ceil(sr + depth(S_OFF)).toInt())
    fun lastRow(sr: Float, rows: Int) = min(rows - 1, floor(sr + depth(S_FAR)).toInt())

    /** A tile standing at (bx, by) leans a share of its lane's slant toward the vanishing point: a skewX for Matrix.
     *  On the ceiling (by over vy) the sign flips by itself, so a hanging tile leans toward the orb too. */
    fun lean(bx: Float, by: Float, vx: Float, vy: Float) = if (abs(by - vy) < 1f) 0f else -LEAN * (vx - bx) / (by - vy)

    /** How far a tile h px tall, base at y, reaches past the row in front on its own plane: `front` is that row's far
     *  edge (toward the orb: its top on the floor, its bottom hanging from the ceiling). */
    fun shows(front: Float, y: Float, h: Float, down: Boolean) = if (down) y + h - front else front - (y - h)

    /** Big enough, there enough, and showing enough over the row in front (`shows` px past its far edge) to take a tap.
     *  With the orb near a plane its rows crowd together, and a row behind may show only a sliver. */
    fun tappable(s: Float, a: Float, tile: Float, cut: Float, shows: Float) = s * tile >= cut && shows >= cut && a >= TAP_ALPHA

    /** The tile under (x, y), the nearest first (order runs front to back): each a tile px tall at its scale, base
     *  at (bx, by), standing up from it (or hanging down, where `down`), leaning by k, its target grown to at least
     *  `target` round its middle. -1 = none. */
    fun hit(x: Float, y: Float, order: IntArray, count: Int, bx: FloatArray, by: FloatArray, s: FloatArray, k: FloatArray,
            ok: BooleanArray, tile: Float, target: Float, down: BooleanArray? = null): Int {
        for (j in 0 until count) {
            val i = order[j]
            if (!ok[i]) continue
            val h = tile * s[i]; val t = max(h, target)
            val cy = if (down?.get(i) == true) by[i] + h / 2 else by[i] - h / 2
            if (abs(y - cy) > t / 2) continue
            val cx = bx[i] + k[i] * (y - by[i])  // the lean carries the middle across, at the height tapped
            if (abs(x - cx) <= t / 2) return i
        }
        return -1
    }
}

/** Horizon Core: a neon tunnel of apps running away to Jharvis. The orb (home draws it, under this view; this view
 *  says where and keeps a hole for it) is the vanishing point on the horizon; a floor below it and a ceiling above
 *  it run out to it, their neon lines fading, all on a dark wash so the neon reads on any theme. Apps are dark glass
 *  tiles with neon edges, three or four abreast, rows alternating floor and ceiling, the most used at the front;
 *  ceiling tiles hang down with their icons upright. Swipe up to move forward: tiles rise small and faint out of the
 *  distance and slide off the edges. Drag the orb and the vanishing point follows a beat behind on a spring, the whole
 *  tunnel shearing after it; it stays where he leaves it until the drawer shuts. Nothing runs while it is still. */
class HorizonView(c: Context, private val look: Look, private val onLaunch: (icon: View, key: String) -> Unit,
                  private val onClose: () -> Unit, private val onSearch: () -> Unit) : FrameLayout(c) {

    val view: View get() = this
    /** Press and hold a tile: its app menu (home supplies what Add to dock and a change mean). */
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
    private val target = look.px(48).toFloat()          // every tappable tile's touch target, at least
    private val tapCut = look.px(HorizonMath.TAP_DP.toInt()).toFloat()
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
    private var bmps = arrayOfNulls<Bitmap>(0)
    private var labels = emptyArray<String>(); private var labelW = FloatArray(0)
    private var desc = emptyArray<String>()
    private var paused = BooleanArray(0)
    private var badged = BooleanArray(0)
    private var tint = IntArray(0)                      // each app's neon, an index into HorizonMath.NEON

    // drawn this frame, by rank: base point, scale (0 = not drawn), alpha, lean, takes taps, hangs from the ceiling;
    // order = front to back
    private var bx = FloatArray(0); private var by = FloatArray(0); private var ts = FloatArray(0)
    private var ta = FloatArray(0); private var tk = FloatArray(0); private var tap = BooleanArray(0)
    private var hangs = BooleanArray(0)
    private var order = IntArray(0); private var drawn = 0
    private var dirty = true                            // something the projection reads has moved since it last ran

    // the tunnel: lanes, the floor's near line (ax, ay) where row 0 stands, the ceiling's (ayC) row 1 hangs from, a
    // tile's size there, finger px per depth
    private var cols = 3
    private var laneW = 1f
    private var tile = 1f
    private var ax = 0f; private var ay = 0f; private var ayC = 0f
    private var rowPx = 1f
    private var lensR = 1f
    private var bars = Insets.NONE
    private var top = 0f                                // the search field's top: the top tenth stays clear

    // the grid: one line list per depth band, lanes then cross lines (both planes), refilled only when the camera or
    // the scroll moves
    private val bandLines = Array(2 * BANDS) { FloatArray(2 * (LANES_MAX + CROSS_MAX) * 4) }
    private val bandCount = IntArray(2 * BANDS)

    // the orb: where he left it, screen px from the middle; the camera (the drawn vanishing point) follows it
    private var ptrX = 0f; private var ptrY = 0f
    private var moved = false
    private val camX = Spring(0f, 0.3f) { moveCamera() }
    private val camY = Spring(0f, 0.3f) { moveCamera() }

    // scrolling: finger px the road has moved forward, rubber-band past either end while dragged
    private var scroll = 0f
    private var raw = 0f
    private val scroller = OverScroller(c)

    // neon lines: cyan lanes, pink cross lines, each a thin bright line over a wide faint one (a glow, no blur)
    private fun neonLine(c: Int, dp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp * resources.displayMetrics.density; color = c }
    private val gridPaint = arrayOf(neonLine(HorizonMath.NEON_CYAN, 1f), neonLine(HorizonMath.NEON_PINK, 1f))
    private val gridGlow = arrayOf(neonLine(HorizonMath.NEON_CYAN, 4f), neonLine(HorizonMath.NEON_PINK, 4f))
    private val horizonPaint = neonLine(HorizonMath.NEON_YELLOW, 1.5f)
    private val glowPaint = Paint()
    private var glowH = 0f
    private val washPaint = Paint().apply { color = WASH }  // the dark the tunnel runs through: neon reads on light themes too
    private val tileFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TILE_GLASS }
    private val tileTint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tileEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edgeA = android.graphics.Color.alpha(look.glassEdge)
    private val tileBmps = arrayOfNulls<Bitmap>(HorizonMath.NEON.size)  // the neon tile at its near-line size, one per colour
    private var tileFor = -1f
    private val tilePad = look.px(4).toFloat()          // clear round it, so the glow isn't cut at the bitmap's border
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HorizonMath.NEON_YELLOW }  // not the accent: Graphite's is near black on the dark glass
    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.withAlpha(look.ground, 240) }  // ground, not white: white is faint on dark themes
    private val pillEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = look.px(1).toFloat(); color = look.glassEdge }
    private val nameText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = look.ink; typeface = look.sans; textSize = sp(13f); textAlign = Paint.Align.CENTER }
    private val dst = RectF()
    private val m = Matrix()                            // one tile's lean, size and place, reused for every tile
    private val lensHole = Path()                       // this view draws over home's orb: nothing is painted on its lens

    private val press = Spring(1f, 0.0005f) { invalidate() }
    private val nameIn = Spring(1f, 0.002f) { postInvalidateOnAnimation() }  // may start inside a draw
    private var named = -1                              // the tile whose name shows

    /** Stands in for the canvas-drawn tile wherever a real view is needed: the launch grows from it, the app menu
     *  lifts a copy of it. Never seen itself. */
    private val anchor = ImageView(c).apply { alpha = 0f; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val searchPill = look.text("Search", 17f, look.ink3).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(look.px(20), 0, look.px(20), 0)
        background = look.round(look.withAlpha(look.ground, 240), 99, look.glassEdge)  // ground, not glass: light glass goes grey on the dark wash
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

    /** Re-rank apps and re-read hidden apps and badges on the loader thread. The front rows' icons are drawn
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

    /** Back to the start: the orb and the camera back in the middle, the road at its start, nothing pressed. */
    fun reset() {
        removeCallbacks(hold)
        scroller.forceFinished(true)
        touching = false; dragging = false; mode = NONE
        moved = false; ptrX = 0f; ptrY = 0f
        pose()
        camX.snap(0f); camY.snap(0f)
        hit = -1; pressed = -1; named = -1
        press.snap(1f); nameIn.snap(1f)
        scroll = 0f; raw = 0f
        changed()
        frames()
    }

    /** True on the orb (a drag there moves it), on a floor tile, and anywhere once the road has moved forward. At the
     *  start a pull down on empty frost or on the ceiling (just under the search field, where it is most natural) is
     *  home's: it closes. A tap on a ceiling tile still lands: home only decides past the slop. */
    fun ownsTouch(x: Float, y: Float) = progress >= 0.95f && n > 0 && (onOrb(x, y) || scroll > 0.5f || tileAt(x, y).let { it >= 0 && !hangs[it] })

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
        removeCallbacks(a11yRefresh); a11yQueued = false
        loadGen++  // answers still on their way are dropped
        camX.stop(); camY.stop(); press.stop(); nameIn.stop()
        fastOn = false; fastFrames(false)
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
        tint = IntArray(n) { HorizonMath.neon(list[it].key) }
        bx = FloatArray(n); by = FloatArray(n); ts = FloatArray(n); ta = FloatArray(n); tk = FloatArray(n); tap = BooleanArray(n)
        hangs = BooleanArray(n)
        order = IntArray(n); drawn = 0
        hit = -1; pressed = -1; hover = -1; a11yFocus = -1; named = -1
        showBadges()
        setScroll(scroll.coerceIn(0f, maxScroll()))
        changed()
        treeChanged()
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

    /** Fits the road to the screen: the search field, the orb's lens, the lanes and the near line, the glow.
     *  Runs when the size or insets change. */
    private fun geometry() {
        if (width == 0) return
        scroller.forceFinished(true)  // a running fling counts in the old row size
        val rows = scroll / rowPx  // the same row stays in front through a turn
        lensR = min(width.toFloat(), height * 0.42f) * OrbMath.LENS * 0.45f  // home's orb view is 42% of the height, compact 0.45
        top = max(height * 0.10f, bars.top + look.px(8).toFloat())
        val lp = searchPill.layoutParams as LayoutParams
        val left = bars.left + look.px(20)
        if (lp.leftMargin != left || lp.rightMargin != bars.right + look.px(20) || lp.topMargin != top.toInt()) {
            lp.leftMargin = left; lp.rightMargin = bars.right + look.px(20); lp.topMargin = top.toInt()
            post { searchPill.requestLayout() }  // not mid-layout
        }
        val road = width - bars.left - bars.right - look.px(32).toFloat()
        cols = HorizonMath.cols(road / resources.displayMetrics.density)
        laneW = road / cols
        tile = min(laneW * HorizonMath.TILE, look.px(72).toFloat())
        if (tileFor != tile) {
            // the neon tiles drawn once, one per colour: a stroked round rect under a skew is a slow path draw, a bitmap
            // isn't; the glow is wide faint strokes under a thin bright one, baked in, never a blur
            tileFor = tile
            val sz = ceil(tile + 2 * tilePad).toInt()
            val r = tile * 0.26f
            dst.set(tilePad, tilePad, tilePad + tile, tilePad + tile)
            for (c in HorizonMath.NEON.indices) tileBmps[c] = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888).apply {
                setHasMipMap(true)  // far tiles are drawn small
                val neon = HorizonMath.NEON[c]
                tileTint.color = look.withAlpha(neon, 30)
                Canvas(this).apply {
                    drawRoundRect(dst, r, r, tileFill); drawRoundRect(dst, r, r, tileTint)  // dark glass with a breath of its colour, so icons read
                    for (k in EDGE_W.indices) {
                        tileEdge.strokeWidth = EDGE_W[k] * resources.displayMetrics.density; tileEdge.color = look.withAlpha(neon, EDGE_A[k])
                        drawRoundRect(dst, r, r, tileEdge)
                    }
                }
            }
        }
        ax =bars.left + look.px(16) + road / 2
        ay = height - bars.bottom - look.px(56).toFloat()  // a name pill fits under the front row
        ayC = top + look.px(48 + 12)                       // the ceiling's front row hangs just under the search field
        // at rest the front row keeps up with the finger
        rowPx = max(look.px(72).toFloat(), (ay - height / 2f) * (1f - HorizonMath.scale(1f)))
        scroll = rows * rowPx
        glowH = look.px(48).toFloat()
        val pink = HorizonMath.NEON_PINK
        glowPaint.shader = LinearGradient(0f, -glowH, 0f, glowH,
            intArrayOf(look.withAlpha(pink, 0), look.withAlpha(pink, 70), look.withAlpha(HorizonMath.NEON_YELLOW, 120), look.withAlpha(pink, 70), look.withAlpha(pink, 0)),
            null, Shader.TileMode.CLAMP)  // once per size: the frame only moves it
        if (moved) clampLens(ptrX, ptrY)  // turned with the drawer open: keep it on screen and between the planes
        pose()
        camX.snap(orbX); camY.snap(orbY)  // straight there: springing from the old screen could put it under the near line
        setScroll(scroll.coerceIn(0f, maxScroll()))
        changed()
    }

    private fun rows() = (n + cols - 1) / cols
    private fun maxScroll() = max(0f, (HorizonMath.depths(rows()) - 1) * rowPx)

    // ── Projection: where every tile and line falls this frame ──────────────────────────────────────

    private fun stage(p: Float, from: Float, span: Float) = if (still) 1f else ((p - from) / span).coerceIn(0f, 1f)

    /** Something the projection reads moved: it runs again before anything reads it (draw, touch, TalkBack). */
    private fun changed() { dirty = true; invalidate() }

    private fun ensure() { if (dirty) project() }

    /** The camera spring moved the vanishing point. */
    private fun moveCamera() { changed(); frames() }

    /** Every tile's base, size, alpha, lean and plane, front to back (each depth's floor row, then its ceiling row),
     *  and the grid's lines by band. Nothing allocated. */
    private fun project() {
        dirty = false
        for (j in 0 until drawn) { val i = order[j]; ts[i] = 0f; tap[i] = false }
        drawn = 0
        bandCount.fill(0)
        if (width == 0) return
        val vx = width / 2f + camX.value; val vy = height / 2f + camY.value
        // opening, the tunnel runs in from the distance; shutting, it runs back out the same way
        val sr = scroll / rowPx - (1f - OrbMath.smooth(stage(progress, 0.15f, 0.6f))) * OPEN_ROWS
        val mid = (cols - 1) / 2f
        val last = HorizonMath.lastRow(sr, HorizonMath.depths(rows()))
        // the far edge of the row drawn in front on each plane (rows run front to back): a floor top, a ceiling bottom
        var floorFront = Float.MAX_VALUE; var ceilFront = -Float.MAX_VALUE
        var sig = 0
        var floorRows = 0; var ceilRows = 0
        val under = top + look.px(48)                   // the search field's bottom: a ceiling tile behind it takes no taps
        for (d in HorizonMath.firstRow(sr)..last) {
            val s = HorizonMath.scale(d - sr)
            val a = HorizonMath.alpha(s)
            if (a <= 0.003f) continue
            val h = tile * s
            for (row in d * 2..d * 2 + 1) {
                val down = HorizonMath.ceiling(row)
                val y = HorizonMath.y(s, if (down) ayC else ay, vy)
                val off = if (down) y + h < 0f || y > height else y - h > height || y < 0f
                if (off) continue
                val shows = HorizonMath.shows(if (down) ceilFront else floorFront, y, h, down)
                if (down) ceilFront = y + h else floorFront = y - h
                val my = if (down) y + h / 2 else y - h / 2
                var any = false
                for (col in 0 until cols) {
                    val i = row * cols + col
                    if (i >= n) break
                    val x = HorizonMath.x((col - mid) * laneW, s, ax, vx)
                    if (x + h < 0f || x - h > width) continue
                    bx[i] = x; by[i] = y; ts[i] = s; ta[i] = a; tk[i] = HorizonMath.lean(x, y, vx, vy); hangs[i] = down
                    tap[i] = HorizonMath.tappable(s, a, tile, tapCut, shows) && hypot(x - orbCx(), my - orbCy()) > lensR + h / 2 && (!down || my > under)
                    if (tap[i]) { any = true; sig = sig * 31 + i + 1 }
                    order[drawn++] = i
                }
                if (any) { if (down) ceilRows++ else floorRows++ }
            }
        }
        // a TalkBack page: the depths both planes can tap, so a scroll skips none on either
        tapRows = if (floorRows > 0 && ceilRows > 0) min(floorRows, ceilRows) else max(floorRows, ceilRows)
        lines(vx, vy, sr)
        name()
        // the tiles TalkBack can reach changed (a scroll, the orb, the camera): it re-reads them, at most every 100 ms
        if (sig != tapSig) { tapSig = sig; if (!a11yQueued) { a11yQueued = true; postDelayed(a11yRefresh, 100) } }
    }

    private var tapRows = 0                             // depths with a tappable tile: TalkBack's page
    private var tapSig = 0
    private var a11yQueued = false
    private val a11yRefresh = Runnable { a11yQueued = false; treeChanged() }

    /** The grid as neon hairlines on the floor and on the ceiling. */
    private fun lines(vx: Float, vy: Float, sr: Float) {
        plane(ay, max(1f, (height - vy) / max(1f, ay - vy)), vx, vy, sr)  // the floor, meeting the bottom edge
        plane(ayC, max(1f, vy / max(1f, vy - ayC)), vx, vy, sr)          // the ceiling, meeting the top edge
    }

    /** One plane's lanes (the road and as much again either side) out to the vanishing point, and a cross line between
     *  each two depths, each piece in the band of its depth so it fades toward the horizon. near is the plane's near
     *  line, sEdge the scale where it leaves the screen. */
    private fun plane(near: Float, sEdge: Float, vx: Float, vy: Float, sr: Float) {
        val half = cols / 2f
        for (b in -cols..2 * cols) {
            val u = (b - half) * laneW
            var hi = sEdge
            for (band in 0 until BANDS) {
                val lo = EDGES[band]
                if (hi > lo) addLine(band, HorizonMath.x(u, hi, ax, vx), HorizonMath.y(hi, near, vy), HorizonMath.x(u, lo, ax, vx), HorizonMath.y(lo, near, vy))
                hi = lo
            }
        }
        val uL = (-cols - half) * laneW; val uR = (2 * cols - half) * laneW
        var k = ceil(sr - 0.5f + HorizonMath.depth(sEdge)).toInt()
        repeat(CROSS_MAX) {
            val s = HorizonMath.scale(k + 0.5f - sr); k++
            if (s < HorizonMath.S_GRID) return
            if (s > sEdge) return@repeat
            var band = 0; while (band < BANDS - 1 && s < EDGES[band]) band++
            val y = HorizonMath.y(s, near, vy)
            addLine(BANDS + band, HorizonMath.x(uL, s, ax, vx), y, HorizonMath.x(uR, s, ax, vx), y)
        }
    }

    private fun addLine(band: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        val a = bandLines[band]; val o = bandCount[band]
        if (o + 4 > a.size) return
        a[o] = x0; a[o + 1] = y0; a[o + 2] = x1; a[o + 3] = y1
        bandCount[band] = o + 4
    }

    /** The name shows for the focused tile, else the one nearest his finger while he touches, else the front-centre
     *  one. A new one fades in, with a light tick if his finger brought it. */
    private fun name() {
        ensure()
        val f = a11yFocus
        val want = if (f >= 0 && f < n && tap[f]) f else if (touching && mode == ROAD && (dragging || hit >= 0)) nearest(fingerX, fingerY, false)
            else nearest(ax, ay, true)
        if (want == named) return
        val was = named
        named = want
        if (want >= 0 && dragging) Haptics.crossing(this)  // touch-down has its own tick
        // only a pill that wasn't showing fades in; one already up moves to the new tile, so a fling doesn't blink it
        if (want >= 0 && was < 0 && !still) { nameIn.snap(0f); nameIn.to(1f, response = 0.25f) }
    }

    /** The tappable tile nearest (x, y): by its base on the near line, else by its middle. */
    private fun nearest(x: Float, y: Float, base: Boolean): Int {
        var best = -1; var bestD = Float.MAX_VALUE
        for (j in 0 until drawn) {
            val i = order[j]
            if (!tap[i]) continue
            val dx = bx[i] - x; val dy = (if (base) by[i] else midY(i)) - y; val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** Tile i's middle as drawn: above its base on the floor, below it hanging from the ceiling, carried by its lean. */
    private fun midY(i: Int) = by[i] + (if (hangs[i]) 0.5f else -0.5f) * tile * ts[i]
    private fun midX(i: Int) = bx[i] + tk[i] * (midY(i) - by[i])

    private fun tileAt(x: Float, y: Float): Int { ensure(); return HorizonMath.hit(x, y, order, drawn, bx, by, ts, tk, tap, tile, target, hangs) }

    // ── The orb: the vanishing point ────────────────────────────────────────────────────────────────

    private fun orbCx() = width / 2f + orbX
    private fun orbCy() = height / 2f + orbY
    private fun onOrb(x: Float, y: Float) = hypot(x - orbCx(), y - orbCy()) <= max(lensR * 1.15f, touchR)

    /** Home's orb goes where the pointer is now; the camera follows on its spring, or there at once when still. */
    private fun pose() {
        if (abs(ptrX - orbX) < 0.1f && abs(ptrY - orbY) < 0.1f) return
        orbX = ptrX; orbY = ptrY
        onOrbChanged?.invoke()
        if (still) { camX.snap(orbX); camY.snap(orbY) }
        else { camX.to(orbX, response = CAMERA, damping = 1f); camY.to(orbY, response = CAMERA, damping = 1f) }  // critically damped: physical, never a wobble
        changed()
    }

    /** The orb kept inside the screen, clear of the bars and the top tenth, high enough over the floor's near line
     *  that its front row stays under the horizon and low enough under the ceiling's that its front row stays over
     *  it (past either, that plane would turn inside out). */
    private fun clampLens(x: Float, y: Float) {
        val r = lensR
        ptrX = x.coerceIn(bars.left + r - width / 2f, max(bars.left + r - width / 2f, width / 2f - bars.right - r))
        val lo = max(top + r, ayC + tile + look.px(64)) - height / 2f
        val hi = min(height / 2f - bars.bottom - r, ay - tile - look.px(64) - height / 2f)
        ptrY = if (lo <= hi) y.coerceIn(lo, hi) else (lo + hi) / 2  // too short for both (landscape): halfway, fair to each plane
    }

    // ── The frame loop: only while something moves ─────────────────────────────────────────────────

    private var fastOn = false

    private fun camMoving() = camX.value != camX.target || camX.velocity != 0f || camY.value != camY.target || camY.velocity != 0f

    private fun frames() { val on = touching || !scroller.isFinished || camMoving(); if (on != fastOn) { fastOn = on; fastFrames(on) } }

    // ── Frames ──────────────────────────────────────────────────────────────────────────────────────

    private fun applyProgress() {
        val p = progress
        if (p <= 0.001f) pending?.let { apps = it; pending = null; build() }
        // the search field arrives once the road is mostly in, same path in and out
        val a = if (still) p.coerceIn(0f, 1f) else stage(p, 0.35f, 0.65f)
        searchPill.alpha = a
        searchPill.translationY = if (still) 0f else look.px(12) * (1 - a)
        val pv = if (a > 0.02f) VISIBLE else INVISIBLE
        if (searchPill.visibility != pv) searchPill.visibility = pv
        changed()
    }

    override fun onDraw(canvas: Canvas) {
        val p = progress
        if (n == 0 || p <= 0.001f) return
        ensure()
        val a = if (still) p.coerceIn(0f, 1f) else min(1f, p * 1.6f)
        // home draws the orb at progress x where he left it, so as it shuts or search opens the whole road rides with it
        val t = min(p, 1f) - 1f
        canvas.save()
        // the wash and its clip in screen space, so they stay put while the road rides with the orb. Not under the bars:
        // a light theme keeps dark bar icons, which would vanish on the dark, and no neon spills onto them either
        canvas.clipRect(bars.left.toFloat(), bars.top.toFloat(), (width - bars.right).toFloat(), (height - bars.bottom).toFloat())
        lensHole.rewind(); lensHole.addCircle(orbCx() + t * orbX, orbCy() + t * orbY, lensR, Path.Direction.CW)
        canvas.clipOutPath(lensHole)
        washPaint.alpha = (android.graphics.Color.alpha(WASH) * a).toInt()
        canvas.drawPaint(washPaint)
        canvas.translate(t * orbX, t * orbY)
        val vy = height / 2f + camY.value
        // the horizon: a neon band and one bright line, where floor and ceiling meet
        glowPaint.alpha = (255 * a).toInt()
        canvas.save(); canvas.translate(0f, vy); canvas.drawRect(0f, -glowH, width.toFloat(), glowH, glowPaint); canvas.restore()
        horizonPaint.alpha = (200 * a).toInt()
        canvas.drawLine(0f, vy, width.toFloat(), vy, horizonPaint)
        for (b in 0 until 2 * BANDS) {
            val c = bandCount[b]
            if (c == 0) continue
            val f = BAND_A[b % BANDS] * a
            gridGlow[b / BANDS].alpha = (GLOW_A * f).toInt(); canvas.drawLines(bandLines[b], 0, c, gridGlow[b / BANDS])
            gridPaint[b / BANDS].alpha = (GRID_A * f).toInt(); canvas.drawLines(bandLines[b], 0, c, gridPaint[b / BANDS])
        }
        // tiles back to front, so the nearer stand in front
        val ic = tile * 0.6f
        for (j in drawn - 1 downTo 0) {
            val i = order[j]; val s = ts[i]
            val f = ta[i] * a * (if (paused[i]) 0.5f else 1f)
            val t0 = if (hangs[i]) 0f else -tile  // the tile's top: standing up from its base, or hanging down from it, icon upright
            m.setSkew(tk[i], 0f); m.postScale(s, s); m.postTranslate(bx[i], by[i])
            if (i == pressed) m.preScale(press.value, press.value, 0f, t0 + tile / 2)
            canvas.save(); canvas.concat(m)
            iconPaint.alpha = (255 * f).toInt()
            val glass = tileBmps[tint[i]]
            if (glass != null) {
                val l = -tile / 2 - tilePad; val t = t0 - tilePad
                dst.set(l, t, l + glass.width, t + glass.height)  // 1:1 with the bitmap, the tile itself on (-tile/2, t0, tile/2, t0 + tile)
                canvas.drawBitmap(glass, null, dst, iconPaint)
            }
            val bm = bmps[i]
            if (bm != null) { dst.set(-ic / 2, t0 + tile / 2 - ic / 2, ic / 2, t0 + tile / 2 + ic / 2); canvas.drawBitmap(bm, null, dst, iconPaint) }
            if (badged[i]) { dotPaint.alpha = (255 * f).toInt(); canvas.drawCircle(tile * 0.34f, t0 + tile * 0.16f, look.px(4) / max(s, 0.5f), dotPaint) }
            canvas.restore()
        }
        drawName(canvas, a)
        canvas.restore()
    }

    /** The named tile's name in a small pill just under it: under a floor tile's base (above it near the bottom
     *  edge), under a hanging ceiling tile. */
    private fun drawName(canvas: Canvas, a: Float) {
        val j = named
        if (j !in 0 until n || !tap[j]) return
        val pa = nameIn.value.coerceIn(0f, 1f) * a * ta[j]
        if (pa < 0.01f) return
        val h = look.px(28).toFloat(); val w = labelW[j] + look.px(24); val gapY = look.px(6)
        var t = by[j] + gapY + if (hangs[j]) tile * ts[j] else 0f
        if (!hangs[j] && t + h > height - bars.bottom) t = by[j] - tile * ts[j] - gapY - h
        val cx = midX(j)
        val l = (cx - w / 2).coerceIn(look.px(8).toFloat(), max(look.px(8).toFloat(), width - look.px(8) - w))
        dst.set(l, t, l + w, t + h)
        pillFill.alpha = (240 * pa).toInt(); pillEdge.alpha = (edgeA * pa).toInt(); nameText.alpha = (255 * pa).toInt()
        canvas.drawRoundRect(dst, h / 2, h / 2, pillFill)
        canvas.drawRoundRect(dst, h / 2, h / 2, pillEdge)
        canvas.drawText(labels[j], l + w / 2, t + h / 2 + nameText.textSize * 0.36f, nameText)
    }

    // ── Scrolling: along the road ───────────────────────────────────────────────────────────────────

    private fun setScroll(v: Float) {
        if (v == scroll) return
        scroll = v
        changed()
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

    // ── Touch: tap, hold, move along the road, drag the orb ─────────────────────────────────────────

    private var touching = false
    private var dragging = false
    private var held = false
    private var caught = false      // the touch stopped a running fling: it doesn't also launch
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
                downX = e.x; downY = e.y; lastY = e.y; fingerX = e.x; fingerY = e.y; pid = e.getPointerId(0)
                vt?.recycle(); vt = VelocityTracker.obtain().apply { addMovement(e) }
                caught = !scroller.isFinished
                scroller.forceFinished(true)
                frames()
                if (onOrb(e.x, e.y)) {
                    mode = ORB; tick()
                    grab(e.x, e.y)
                    parent?.requestDisallowInterceptTouchEvent(true)  // a drag here moves the orb, never closes
                    return true
                }
                mode = ROAD
                if (scroll > 0.5f || caught) parent?.requestDisallowInterceptTouchEvent(true)  // every drag moves the road once off the start
                raw = unbanded()
                val i = if (caught) -1 else tileAt(e.x, e.y)
                if (i >= 0) pressTile(i) else { name(); invalidate() }
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
                    lastY = y  // moves 1:1 from here, so the slop doesn't jump
                }
                raw -= y - lastY  // up is forward
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

    private fun pressTile(i: Int) {
        hit = i
        if (hit != pressed) press.snap(1f)  // the last tile's spring back doesn't hand its squish to this one
        tick(); pressed = hit
        if (!still) press.to(0.92f, response = 0.16f, damping = 1f)
        postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
        name(); invalidate()
    }

    private fun finish(e: MotionEvent, up: Boolean) {
        removeCallbacks(hold)
        touching = false
        vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
        val fy = vt?.getYVelocity(pid) ?: 0f
        if (mode == ORB) {
            // the orb stays where he let go; a tap is Jharvis
            if (up && !dragging && !caught && progress >= 0.9f) onOrbTap?.invoke()
        } else if (mode == ROAD) {
            val go = up && progress >= 0.9f && !dragging && !held && !caught && hit >= 0
            unpress()
            if (go) launch(hit)
            val mx = maxScroll()
            if (dragging && up && !still && progress >= 0.9f && scroll in 0f..mx) {  // not while it shuts
                scroller.fling(0, scroll.toInt(), 0, (-fy).toInt(), 0, 0, 0, mx.toInt(), 0, look.px(40))
                postInvalidateOnAnimation()
            }
        }
        vt?.recycle(); vt = null
        dragging = false; mode = NONE
        settleScroll()
        name(); invalidate()  // the name goes back to the front-centre tile
        frames()
    }

    private fun unpress() { if (still) press.snap(1f) else press.to(1f, response = 0.42f, damping = 0.82f) }

    /** The invisible stand-in, moved onto tile i's icon at its drawn size. */
    private fun anchorAt(i: Int): View {
        ensure()
        val s = ts[i]
        anchor.setImageBitmap(bmps[i])
        anchor.translationX = midX(i) - iconMax / 2f; anchor.translationY = midY(i) - iconMax / 2f
        val k = tile * 0.6f * s / iconMax  // as onDraw sizes it, so the menu's lift starts there
        anchor.scaleX = k; anchor.scaleY = k
        return anchor
    }

    /** Through the lock prompt when the app is locked; onLaunch runs only once it passes. */
    private fun launch(i: Int) {
        val key = apps[i].key
        val from = anchorAt(i)
        val act = context as? Activity
        if (act == null) onLaunch(from, key) else LockedApps.launch(act, key, apps[i].label) { onLaunch(from, key) }
    }

    // ── TalkBack: each tappable tile is a virtual button, front to back; scroll moves along the road ────

    private var hover = -1
    private var a11yFocus = -1
    private val screenAt = IntArray(2)

    private fun reachable(i: Int): Boolean { ensure(); return i in 0 until n && tap[i] }

    private val a11y = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(id: Int): AccessibilityNodeInfo? {
            val host = this@HorizonView
            ensure()
            if (id == NO_ID) {
                val info = AccessibilityNodeInfo(host)
                onInitializeAccessibilityNodeInfo(info)
                if (searchPill.visibility == VISIBLE) info.addChild(searchPill)  // a provider host lists its real children itself
                for (j in 0 until drawn) if (tap[order[j]]) info.addChild(host, order[j])  // far tiles are too small to tap: left out
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
                isVisibleToUser = tap[id]
                isAccessibilityFocused = a11yFocus == id
                getLocationOnScreen(screenAt)
                val h = tile * ts[id]; val t = max(h, target)
                val cx = screenAt[0] + midX(id); val cy = screenAt[1] + midY(id)
                setBoundsInScreen(Rect((cx - t / 2).toInt(), (cy - t / 2).toInt(), (cx + t / 2).toInt(), (cy + t / 2).toInt()))
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
                ensure(); scrollBy(dir * rowPx * max(1, tapRows))  // about the rows that take taps, so none is skipped
                postDelayed({ sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED) }, if (still) 0L else 380L)  // once it has moved
                return true
            }
            if (id !in 0 until n) return false
            // a node read before a scroll may have gone too far to tap since: only tappable tiles open
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
        val i = if (onPill || e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) -1 else tileAt(e.x, e.y)
        if (i != hover) {
            val was = hover; hover = i
            if (i >= 0) send(i, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            if (was >= 0) send(was, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
        }
        return i >= 0 || super.dispatchHoverEvent(e)
    }

    /** The list changed: TalkBack re-reads the tree. */
    private fun treeChanged() {
        if (context.getSystemService(AccessibilityManager::class.java)?.isEnabled != true) return
        val ev = AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            setSource(this@HorizonView); contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
        }
        parent?.requestSendAccessibilityEvent(this, ev)
    }

    private fun send(i: Int, type: Int) {
        val ev = AccessibilityEvent(type).apply {
            setSource(this@HorizonView, i)
            packageName = context.packageName; className = Button::class.java.name
            contentDescription = desc[i]
        }
        parent?.requestSendAccessibilityEvent(this, ev)
    }

    private companion object {
        const val NONE = 0; const val ROAD = 1; const val ORB = 2
        const val FIRST = 72            // icons drawn before the list comes back: the rows a screen shows (12 depths x floor and ceiling x 3)
        const val CAMERA = 0.3f         // s, the vanishing point's spring behind the orb: felt, not laggy
        const val OPEN_ROWS = 2.5f      // rows the road runs in from as the drawer opens
        const val BANDS = 6
        const val LANES_MAX = 13        // 3 x 4 lanes + 1: the road and as much again either side
        const val CROSS_MAX = 64
        const val GRID_A = 170          // the nearest lines' alpha: neon hairlines on the wash
        const val GLOW_A = 50           // ... and their wide glow's
        const val WASH = 0xD6060810.toInt()        // the tunnel's dark: most of the way to black, a hint of blue
        const val TILE_GLASS = 0xE00B0E18.toInt()  // a tile's dark glass
        val EDGE_W = floatArrayOf(7f, 3.5f, 1.5f)  // dp: a tile's neon edge, wide and faint to thin and bright
        val EDGE_A = intArrayOf(40, 90, 255)
        // band edges by scale, near to far (band 0 starts at the bottom edge), and each band's share of GRID_A
        val EDGES = floatArrayOf(1f, 0.62f, 0.4f, 0.25f, 0.15f, HorizonMath.S_GRID)
        val BAND_A = floatArrayOf(1f, 0.8f, 0.55f, 0.34f, 0.18f, 0.07f)
    }
}
