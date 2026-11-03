package com.jhon.agenticos.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.InsetDrawable
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
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Pure type arithmetic for Typographic Matrix, tested on the JVM: which size and weight a rank gets, and how names
 *  flow into justified lines. */
internal object TypeMath {
    val SIZES = floatArrayOf(28f, 22f, 18f, 15f, 13f, 12f)   // sp, headline down to caption
    val WEIGHTS = intArrayOf(800, 700, 600, 500, 400, 300)
    private val ENDS = intArrayOf(3, 8, 16, 28, 44)          // the rank each step ends at: 3 headlines, 5 subheads...
    val LAST = SIZES.size - 1

    /** The step (0 biggest .. LAST smallest) for the app at rank (0 = most used) when `used` apps have ever been
     *  opened. Never-opened apps are all the smallest, so a phone with no history isn't headlined A to Z. */
    fun step(rank: Int, used: Int): Int {
        if (rank < 0 || rank >= used) return LAST
        var s = 0
        while (s < ENDS.size && rank >= ENDS[s]) s++
        return s
    }

    fun weight(step: Int) = WEIGHTS[step.coerceIn(0, LAST)].coerceIn(300, 800)

    /** Tracking by size: big type tighter, small a touch looser (as Look.text). */
    fun tracking(sp: Float) = when { sp >= 20f -> -0.02f; sp >= 17f -> -0.01f; sp <= 13f -> 0.01f; else -> 0f }

    /** Packs slots of width w (gap[i] at least after slot i) into lines no wider than maxW, filling line[] and x[].
     *  Full lines are justified: the spare width spreads between their slots, at most `loose` times their gaps so a
     *  short line never opens rivers. The last line stays left. A slot wider than maxW sits on its own line.
     *  Returns the number of lines. */
    fun flow(w: FloatArray, gap: FloatArray, maxW: Float, line: IntArray, x: FloatArray, loose: Float = 4f): Int {
        val n = w.size
        var i = 0; var ln = 0
        while (i < n) {
            var used = w[i]; var j = i + 1
            while (j < n && used + gap[j - 1] + w[j] <= maxW) { used += gap[j - 1] + w[j]; j++ }
            var spare = 0f
            if (j < n && j - i > 1) {
                var gaps = 0f
                for (k in i until j - 1) gaps += gap[k]
                spare = min((maxW - used) / (j - i - 1), gaps / (j - i - 1) * loose)
            }
            var at = 0f
            for (k in i until j) { line[k] = ln; x[k] = at; at += w[k] + gap[k] + spare }
            ln++; i = j
        }
        return ln
    }
}

/** Typographic Matrix: every app's name and nothing else, set like a page of type. The most used are the biggest and
 *  boldest, rare ones small and light, in his launcher font; names flow left to right in justified lines under a
 *  search field. Jharvis's orb stays in the middle (home draws it; this view says where): drag it onto a name and it
 *  frames that name in a pill, shrinks to a dot at the pill's end, and that app's shortcuts show as chips underneath.
 *  It stays where he leaves it until the drawer shuts. One view draws every name; all measuring happens when the list
 *  or the size changes, so onDraw only paints. */
class TypeView(c: Context, private val look: Look, private val onLaunch: (icon: View, key: String) -> Unit,
               private val onClose: () -> Unit, private val onSearch: () -> Unit) : FrameLayout(c) {

    val view: View get() = this
    /** Press and hold a name: its app menu (home supplies what Add to dock and a change mean). */
    var menuActions: AppMenu.Actions? = null
    /** A tap on the orb (no drag): home's own orb click (Jharvis). */
    var onOrbTap: (() -> Unit)? = null
    /** The orb's pose changed: home re-reads orbX, orbY and orbK. */
    var onOrbChanged: (() -> Unit)? = null
    /** Where home should draw its orb while this is open: offset from the screen's middle (px) and its size (1 = as is). */
    var orbX = 0f; private set
    var orbY = 0f; private set
    var orbK = 1f; private set

    /** 0 = shut, 1 = open. Home's spring drives it. */
    var progress = 0f
        set(v) { field = v; applyProgress() }

    private val still = reducedMotion(c)
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    // the list, rebuilt when it changes (arrays indexed like `apps`)
    private var apps: List<AppEntry> = emptyList()
    private var used = 0
    private var pending: Pair<List<AppEntry>, Int>? = null  // a newer ranking waits until shut, so nothing jumps
    private var builtVersion = -1
    private var loadGen = 0
    private var badges: Map<String, AppBadges.Badge> = emptyMap()
    private var n = 0
    private var steps = IntArray(0)
    private var labels = emptyArray<String>()
    private var desc = emptyArray<String>()
    private var paused = BooleanArray(0)
    // placed by geometry(), in content px (screen y = content y - scroll)
    private var drawW = FloatArray(0); private var tx = FloatArray(0); private var base = FloatArray(0)
    private var hitL = FloatArray(0); private var hitR = FloatArray(0); private var hitT = FloatArray(0); private var hitB = FloatArray(0)
    private var lineOf = IntArray(0)
    private var lineStart = IntArray(1)                 // first item of each line, plus n at the end
    private var lineTop = FloatArray(0); private var lineH = FloatArray(0)
    private var lines = 0
    private var contentH = 0f
    private var bars = Insets.NONE
    private var top = 0f                                // the search field's top: the top tenth stays clear

    private val paints = Array(TypeMath.SIZES.size) { s ->
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            // the ladder sits on his font's own body weight, so Medium sets every step heavier than System
            typeface = look.faceAt(TypeMath.weight(s) + look.font.body - 400); textSize = sp(TypeMath.SIZES[s]); letterSpacing = TypeMath.tracking(TypeMath.SIZES[s])
            color = look.ink; look.legible(this)
        }
    }
    // the halo (on the wallpaper only) fades with its name: a shadow keeps its own alpha, so it would outlast it
    private val haloC = IntArray(paints.size) { if (paints[it].shadowLayerRadius > 0f) paints[it].shadowLayerColor else 0 }
    private val haloR = FloatArray(paints.size) { paints[it].shadowLayerRadius }
    private val haloA = IntArray(paints.size) { Color.alpha(haloC[it]) }  // the alpha each shadow is set at now
    private val mids = FloatArray(paints.size) { -(paints[it].fontMetrics.ascent + paints[it].fontMetrics.descent) / 2 }  // baseline below the centre
    private val textH = FloatArray(paints.size) { paints[it].fontMetrics.descent - paints[it].fontMetrics.ascent }
    private val ascents = FloatArray(paints.size) { paints[it].fontMetrics.ascent }
    private val inkA = Color.alpha(look.ink)

    // the framed name: a light pill (navy on light themes, glass on dark) holding it in near-white, the orb a dot at its end
    private val hot = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val hotInk = if (look.night) Color.WHITE else look.onNavy
    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (look.night) look.glass else look.navy }
    private val pillEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = look.px(1).toFloat() * 1.5f
        color = if (look.night) Color.argb(150, 255, 255, 255) else look.navy
    }
    private val fillA = Color.alpha(pillFill.color); private val edgeA = Color.alpha(pillEdge.color)
    private val dotR = look.px(11).toFloat()
    private val pillNow = RectF(); private val pillFrom = RectF(); private val pillTo = RectF(); private val inner = RectF()

    // the orb: the lens he drags (pointer, screen px from the middle) and where it shows
    private var lensR = 1f
    private var ptrX = 0f; private var ptrY = 0f
    private var moved = false                           // he has dragged it since the drawer opened: only then does it frame
    private var framed = -1                             // the name under the lens
    private var lit = -1                                // the name the pill shows (stays while it fades out)
    private var litDx = 0f                              // the lit name slides in when its pill had to move off an edge
    private val frame = Spring(0f, 0.002f) { pose(); invalidate() }
    private val glide = Spring(1f, 0.002f) { pose(); invalidate() }  // the pill travelling from one name to the next
    private val press = Spring(1f, 0.0005f) { invalidate() }

    // scrolling: content px from the top, rubber-band past the bottom while dragged
    private var scroll = 0f
    private var raw = 0f
    private val scroller = OverScroller(c)

    /** Stands in for the canvas-drawn name wherever a real view is needed: the launch grows from it, the app menu
     *  lifts a copy of it. Never seen itself. */
    private val anchor = TextView(c).apply { alpha = 0f; includeFontPadding = false; maxLines = 1; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val searchPill = look.text("Search", 17f, look.ink3).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(look.px(20), 0, look.px(20), 0)
        background = look.glassCard(99)
        contentDescription = "Search apps"
        isClickable = true; look.press(this, 0.97f)
        setOnClickListener { onSearch() }
    }
    // up to three shortcuts and the unseen count, under the framed name
    private val chipRow = LinearLayout(c).apply { visibility = INVISIBLE }
    private val chips = Array(4) { k ->
        look.text("", 14f, look.ink, k < 3).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            maxWidth = look.px(160); minHeight = look.px(48)
            setPadding(look.px(14), 0, look.px(14), 0)
            background = InsetDrawable(look.wallGlass(99), 0, look.px(6), 0, look.px(6))  // drawn 36 dp, touched 48
            isClickable = true; look.press(this, 0.95f)
            setOnClickListener { chipTap(k, this) }
            visibility = GONE
        }
    }
    private var cuts: List<ShortcutInfo> = emptyList()
    private var cutsLocked = false
    private val chipsIn = Spring(1f, 0.002f) { placeChips() }
    private var chipsFor = -1
    private var chipGen = 0
    private val chipJob = Runnable { loadChips() }

    init {
        setWillNotDraw(false)
        clipChildren = false
        addView(anchor, LayoutParams(-2, -2))
        addView(searchPill, LayoutParams(-1, look.px(48), Gravity.TOP))
        for ((k, chip) in chips.withIndex()) chipRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { if (k > 0) leftMargin = look.px(6) })
        addView(chipRow, LayoutParams(-2, -2))
        visibility = INVISIBLE
        setOnApplyWindowInsetsListener { _, i -> useInsets(i); i }
    }

    // ── Contract: load, opening, reset, ownsTouch ───────────────────────────────────────────────────

    /** Re-rank apps and re-read hidden apps and badges on the loader thread. "Used" = opened at least once: those
     *  take the big steps in rank order, the rest stay small in the A to Z they came in. */
    fun load() {
        val app = context.applicationContext
        val g = ++loadGen
        Loader.run {
            val all = Apps.get(app)
            val ver = Apps.version
            val hidden = HiddenApps.all(app)
            val byKey = all.associateBy { it.key }
            val got = LaunchCounts.ranked(app, all.map { it.key }.filter { it !in hidden }).mapNotNull { byKey[it] }
            val p = app.getSharedPreferences("launches", Context.MODE_PRIVATE)
            val seen = LaunchCounts.parse(p.getString("h", null)).mapTo(HashSet()) { it.key }
            val u = got.count { it.key in seen || p.getFloat("s:${it.key}", 0f) > 0f }  // ranked puts these first
            val b = AppBadges.read()
            Loader.main.post {
                if (g != loadGen) return@post
                badges = b
                val cur = pending?.first ?: apps
                val same = ver == builtVersion && u == (pending?.second ?: used) && cur.size == got.size && cur.indices.all { cur[it].key == got[it].key }
                if (!same) {
                    builtVersion = ver
                    if (progress <= 0.001f || MenuChange.recent()) { apps = got; used = u; pending = null; build() } else pending = got to u
                } else showBadges()
            }
        }
    }

    /** Called once as an open starts: anything that arrived while shut is laid out first. */
    fun opening() { pending?.let { apps = it.first; used = it.second; pending = null; build() } }

    /** Back to the top, the orb back in the middle, nothing framed or pressed. */
    fun reset() {
        removeCallbacks(hold); removeCallbacks(chipJob)
        scroller.forceFinished(true)
        touching = false; dragging = false; mode = NONE
        moved = false; ptrX = 0f; ptrY = 0f
        framed = -1; lit = -1; litDx = 0f; chipsFor = -1; chipGen++
        frame.snap(0f); glide.snap(1f); press.snap(1f)
        hit = -1; pressed = -1
        setScroll(0f)
        showChips()
        pose()
    }

    /** True on the orb (a drag there moves it) and anywhere once the names have scrolled (a drag scrolls). At the
     *  top a pull down is home's: it closes; a push up still scrolls if home leaves it alone. */
    fun ownsTouch(x: Float, y: Float) = progress >= 0.95f && n > 0 && (onOrb(x, y) || scroll > 0.5f)

    // ── Building the page ───────────────────────────────────────────────────────────────────────────

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
        removeCallbacks(hold); removeCallbacks(chipJob)
        loadGen++; chipGen++  // answers still on their way are dropped
        frame.stop(); glide.stop(); press.stop(); chipsIn.stop()
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
        steps = IntArray(n) { TypeMath.step(it, used) }
        paused = BooleanArray(n) { list[it].suspended }
        desc = Array(n) { list[it].label }
        labels = Array(n) { list[it].label }
        hit = -1; pressed = -1; hover = -1; a11yFocus = -1
        framed = -1; lit = -1; chipsFor = -1; chipGen++; frame.snap(0f)
        showChips()
        showBadges()
        geometry()
        if (moved) reframe()  // the lens still rests on a name after an app was hidden or locked from its menu
    }

    /** Sets every name: sizes and widths per step, justified lines of at least 48 dp, the search field above them.
     *  Runs when the list, the insets or the size change; never per frame. */
    private fun geometry() {
        if (width == 0) return
        lensR = min(width.toFloat(), height * 0.42f) * OrbMath.LENS * 0.45f  // home's orb view is 42% of the height, compact 0.45
        top = max(height * 0.10f, bars.top + look.px(8).toFloat())
        if (moved) clampLens(ptrX, ptrY)  // turned with the drawer open (home handles rotation itself): keep it on screen
        val left = bars.left + look.px(20).toFloat(); val maxW = width - bars.right - look.px(20) - left
        val lp = searchPill.layoutParams as LayoutParams
        if (lp.leftMargin != left.toInt() || lp.rightMargin != bars.right + look.px(20) || lp.topMargin != top.toInt()) {
            lp.leftMargin = left.toInt(); lp.rightMargin = bars.right + look.px(20); lp.topMargin = top.toInt()
            post { searchPill.requestLayout() }  // not mid-layout
        }
        val min48 = look.px(48).toFloat()
        val slot = FloatArray(n); val gap = FloatArray(n)
        drawW = FloatArray(n); tx = FloatArray(n); base = FloatArray(n)
        hitL = FloatArray(n); hitR = FloatArray(n); hitT = FloatArray(n); hitB = FloatArray(n); lineOf = IntArray(n)
        for (i in 0 until n) {
            val p = paints[steps[i]]
            val full = apps[i].label
            labels[i] = if (p.measureText(full) > maxW) TextUtils.ellipsize(full, p, maxW, TextUtils.TruncateAt.END).toString() else full
            drawW[i] = p.measureText(labels[i])
            slot[i] = max(min48, drawW[i])  // a short name still gets a thumb-wide target
            gap[i] = max(look.px(10).toFloat(), p.textSize * 0.45f)
        }
        val x = FloatArray(n)
        lines = TypeMath.flow(slot, gap, maxW, lineOf, x)
        lineStart = IntArray(lines + 1).also { it[lines] = n }
        lineTop = FloatArray(lines); lineH = FloatArray(lines)
        for (i in n - 1 downTo 0) lineStart[lineOf[i]] = i
        var y = top + min48 + look.px(20)
        for (l in 0 until lines) {
            var big = TypeMath.LAST
            for (i in lineStart[l] until lineStart[l + 1]) big = min(big, steps[i])
            val h = max(min48, paints[big].textSize * 1.3f)
            lineTop[l] = y; lineH[l] = h
            val bl = y + h / 2 + mids[big]  // one baseline per line, centred on its biggest name
            for (i in lineStart[l] until lineStart[l + 1]) {
                base[i] = bl
                tx[i] = left + x[i] + (slot[i] - drawW[i]) / 2
                hitL[i] = left + x[i] - gap[i] / 2; hitR[i] = left + x[i] + slot[i] + gap[i] / 2
                hitT[i] = y; hitB[i] = y + h
            }
            // justified gaps are wider than gap[]: neighbours meet halfway, so no strip between them is dead
            for (i in lineStart[l] until lineStart[l + 1] - 1) {
                val m = (left + x[i] + slot[i] + left + x[i + 1]) / 2
                hitR[i] = m; hitL[i + 1] = m
            }
            y += h
        }
        contentH = y + bars.bottom + look.px(24)
        setScroll(scroll.coerceIn(0f, maxScroll()))
        if (framed >= 0) { val was = framed; framed = -1; reframe(); if (framed < 0) { framed = was; setFramed(-1) } }  // re-set: its pill moved
        pose()
        invalidate()
    }

    private fun maxScroll() = max(0f, contentH - height)

    private fun showBadges() {
        for (i in 0 until n) {
            val a = apps[i]
            val k = count(i)
            desc[i] = a.label + when { k == 1 -> ", 1 notification"; k > 1 -> ", $k notifications"; else -> "" } + if (a.suspended) ", paused" else ""
        }
        if (chipsFor >= 0) showChips()
    }

    private fun count(i: Int): Int { val a = apps[i]; return if (a.main) badges[a.pkg]?.count ?: 0 else 0 }  // badges.json counts the main profile

    // ── The orb: drag it, frame a name ──────────────────────────────────────────────────────────────

    private fun orbCx() = width / 2f + orbX
    private fun orbCy() = height / 2f + orbY

    /** On the orb as it shows now: its lens with a little reach, never less than a thumb. */
    private fun onOrb(x: Float, y: Float) = hypot(x - orbCx(), y - orbCy()) <= orbReach()
    private fun orbReach() = max(lensR * orbK * 1.15f, look.px(24).toFloat())  // names inside it dim too: a tap there is Jharvis's

    /** The lens moved (or the names did under it): frame the name under its centre. A little slack keeps a framed
     *  name while the lens wobbles on its edge, so it never flickers between neighbours. */
    private fun reframe() {
        if (!moved || n == 0) { setFramed(-1); return }
        val x = width / 2f + ptrX; val y = height / 2f + ptrY + scroll
        val f = framed
        if (f in 0 until n) {
            val s = look.px(8).toFloat()
            if (x >= hitL[f] - s && x <= hitR[f] + s && y >= hitT[f] - s && y <= hitB[f] + s) return
        }
        setFramed(nameAt(x, y))
    }

    private fun nameAt(x: Float, y: Float): Int {
        for (l in 0 until lines) {
            if (y < lineTop[l] || y >= lineTop[l] + lineH[l]) continue
            for (i in lineStart[l] until lineStart[l + 1]) if (x >= hitL[i] && x < hitR[i]) return i
            return -1
        }
        return -1
    }

    private fun setFramed(b: Int) {
        if (b == framed) return
        framed = b
        if (b < 0) { frame.go(0f); chipsFor = -1; chipGen++; removeCallbacks(chipJob); showChips(); return }
        val gliding = lit >= 0 && frame.value > 0.05f
        // the pill's speed now (px/s along its last path), so a sweep across names keeps moving instead of restarting
        val vx = glide.velocity * (pillTo.centerX() - pillFrom.centerX()); val vy = glide.velocity * (pillTo.centerY() - pillFrom.centerY())
        if (gliding) { pillAt(pillFrom) }
        litDx = pillFor(b, pillTo)
        if (!gliding) pillFrom.set(pillTo)
        val ddx = pillTo.centerX() - pillFrom.centerX(); val ddy = pillTo.centerY() - pillFrom.centerY(); val d2 = ddx * ddx + ddy * ddy
        val gv = if (d2 > 1f) max(0f, (vx * ddx + vy * ddy) / d2) else 0f  // that speed along the new path, as glide's 0..1
        lit = b
        val p = paints[steps[b]]
        hot.typeface = p.typeface; hot.textSize = p.textSize; hot.letterSpacing = p.letterSpacing
        if (gliding && !still) { glide.snap(0f); glide.to(1f, response = 0.3f, damping = 0.9f, velocity = gv) } else glide.snap(1f)
        frame.go(1f)
        if (mode == ORB && dragging) Haptics.crossing(this)  // his drag lands on it; names scrolling under a parked orb stay quiet
        chipsFor = -1; showChips()  // the last app's chips never show under this one
        removeCallbacks(chipJob); postDelayed(chipJob, CHIP_WAIT)  // a quick sweep across names reads nothing
    }

    private fun Spring.go(to: Float) { if (still) snap(to) else to(to, response = 0.32f, damping = 0.86f) }

    /** The pill around name i, in content px; returns how far the name moves so its pill stays on screen. */
    private fun pillFor(i: Int, out: RectF): Float {
        val s = steps[i]
        val cx = tx[i] + drawW[i] / 2; val cy = base[i] - mids[s]
        val tw = drawW[i] * GROW; val h = max(look.px(44).toFloat(), textH[s] * GROW + look.px(12))
        var l = cx - tw / 2 - look.px(16); var r = cx + tw / 2 + look.px(12) + dotR * 2 + look.px(8)
        val e = look.px(8).toFloat()
        val dx = when { r > width - e -> width - e - r; l < e -> e - l; else -> 0f }
        l += dx; r += dx
        out.set(l, cy - h / 2, r, cy + h / 2)
        return dx
    }

    private fun pillAt(out: RectF) {
        val g = glide.value
        out.set(pillFrom.left + (pillTo.left - pillFrom.left) * g, pillFrom.top + (pillTo.top - pillFrom.top) * g,
            pillFrom.right + (pillTo.right - pillFrom.right) * g, pillFrom.bottom + (pillTo.bottom - pillFrom.bottom) * g)
    }

    /** Where home's orb shows: on the lens he moved, or, framing, shrunk to a dot at the pill's right end. */
    private fun pose() {
        val f = if (lit >= 0) frame.value.coerceIn(0f, 1f) else 0f
        var x = ptrX; var y = ptrY; var k = 1f
        if (f > 0f) {
            pillAt(pillNow)
            val dx = pillNow.right - look.px(8) - dotR - width / 2f; val dy = pillNow.centerY() - scroll - height / 2f
            x += (dx - x) * f; y += (dy - y) * f
            k = 1f + (dotR / lensR - 1f) * f
        }
        if (abs(x - orbX) < 0.1f && abs(y - orbY) < 0.1f && abs(k - orbK) < 0.0005f) { placeChips(); return }
        orbX = x; orbY = y; orbK = k
        placeChips()
        onOrbChanged?.invoke()
    }

    /** The lens kept inside the screen, clear of the bars and the top tenth. */
    private fun clampLens(x: Float, y: Float) {
        val r = lensR
        ptrX = x.coerceIn(bars.left + r - width / 2f, max(bars.left + r - width / 2f, width / 2f - bars.right - r))
        ptrY = y.coerceIn(top + r - height / 2f, max(top + r - height / 2f, height / 2f - bars.bottom - r))
    }

    // ── Chips: shortcuts and the unseen count under the framed name ─────────────────────────────────

    private fun loadChips() {
        val i = framed
        if (i !in 0 until n) return
        val e = apps[i]; val g = ++chipGen
        val app = context.applicationContext
        Loader.run {
            val got = AppMenu.shortcuts(app.getSystemService(LauncherApps::class.java), e.pkg, e.user).take(3)
            val lock = LockedApps.isLocked(app, e.key)  // its menu shows no notifications then, so no count to tap into
            Loader.main.post { if (g == chipGen && framed == i) { cuts = got; cutsLocked = lock; chipsFor = i; showChips() } }
        }
    }

    private fun showChips() {
        val i = chipsFor
        if (i !in 0 until n) { chipRow.visibility = INVISIBLE; return }
        val was = chipRow.visibility
        for (k in 0 until 3) {
            val sc = cuts.getOrNull(k)
            chips[k].visibility = if (sc == null) GONE else VISIBLE
            if (sc != null) { val t = sc.shortLabel ?: sc.longLabel ?: ""; chips[k].text = t; chips[k].contentDescription = "${apps[i].label}, $t" }
        }
        val c = if (cutsLocked) 0 else count(i)
        chips[3].visibility = if (c > 0) VISIBLE else GONE
        chips[3].text = "${AppBadges.word(c)} new"
        chips[3].contentDescription = "${apps[i].label}, " + if (c == 1) "1 notification" else "$c notifications"
        chipRow.visibility = if (cuts.isNotEmpty() || c > 0) VISIBLE else INVISIBLE
        if (was != VISIBLE && chipRow.visibility == VISIBLE) { chipsIn.snap(0f); chipsIn.go(1f) }  // they fade in when they arrive
        chipRow.post { placeChips() }  // once it has its width
        placeChips()
    }

    /** Under the pill (above it near the bottom edge), kept on screen, fading with the frame. */
    private fun placeChips() {
        if (chipRow.visibility != VISIBLE) return
        pillAt(pillNow)
        val e = look.px(8).toFloat(); val gap = look.px(4)
        chipRow.translationX = pillNow.left.coerceIn(e, max(e, width - e - chipRow.width))
        var y = pillNow.bottom - scroll + gap
        if (y + chipRow.height > height - bars.bottom) y = pillNow.top - scroll - gap - chipRow.height
        chipRow.translationY = y
        chipRow.alpha = frame.value.coerceIn(0f, 1f) * entrance() * chipsIn.value.coerceIn(0f, 1f)
    }

    private fun chipTap(k: Int, from: View) {
        val i = chipsFor
        if (i !in 0 until n) return
        val e = apps[i]
        if (k == 3) { menuActions?.let { AppMenu.show(anchorAt(i), e.key, look, it) }; return }  // its menu lists the notifications
        val sc = cuts.getOrNull(k) ?: return
        val act = context as? Activity ?: return
        LockedApps.launch(act, e.key, e.label) {  // a locked app's shortcuts ask first too, as in its menu
            try { act.getSystemService(LauncherApps::class.java).startShortcut(e.pkg, sc.id, Apps.bounds(from), Apps.options(from), sc.userHandle) }
            catch (_: Exception) { Toast.makeText(act, "Couldn't open that shortcut", Toast.LENGTH_SHORT).show() }  // disabled since it was read
        }
    }

    // ── Frames ──────────────────────────────────────────────────────────────────────────────────────

    private fun stage(p: Float, from: Float, span: Float) = if (still) 1f else ((p - from) / span).coerceIn(0f, 1f)
    private fun entrance() = if (still) progress.coerceIn(0f, 1f) else stage(progress, 0.35f, 0.65f)

    private fun applyProgress() {
        val p = progress
        if (p <= 0.001f) pending?.let { apps = it.first; used = it.second; pending = null; build() }
        // the search field arrives once the names are mostly in, same path in and out
        val a = entrance()
        searchPill.alpha = a
        searchPill.translationY = -scroll + if (still) 0f else look.px(12) * (1 - a)
        val pv = if (a > 0.02f) VISIBLE else INVISIBLE
        if (searchPill.visibility != pv) searchPill.visibility = pv
        placeChips()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val p = progress
        if (n == 0 || p <= 0.001f) return
        val f = if (lit >= 0) frame.value.coerceIn(0f, 1f) else 0f
        val others = 1f - 0.65f * f              // the rest step back while one is framed
        val lensOn = framed < 0                  // names under the resting orb dim, as glass would hide them
        val ox = orbCx(); val oy = orbCy(); val or2 = orbReach()
        val h = height.toFloat()
        for (l in 0 until lines) {
            val t = lineTop[l] - scroll
            if (t > h) break
            if (t + lineH[l] < 0f) continue
            // opening: line after line rises in, the same path back out; "Remove animations": a plain fade
            val e = if (still) p.coerceIn(0f, 1f) else stage(p, 0.04f + 0.02f * min(l, 16), 0.5f)
            if (e <= 0.01f) continue
            val dy = if (still) 0f else (1 - e) * look.px(16)
            for (i in lineStart[l] until lineStart[l + 1]) {
                if (i == lit && f > 0.01f) continue
                val s = steps[i]
                val y = base[i] - scroll + dy
                var a = e * (if (paused[i]) 0.5f else 1f) * others
                if (lensOn && near(ox, oy, or2, tx[i], y + ascents[s], tx[i] + drawW[i], y + textH[s] + ascents[s])) a *= 0.3f
                val paint = paints[s]
                paint.alpha = (inkA * a).toInt()
                if (haloC[s] != 0) {
                    val ha = (Color.alpha(haloC[s]) * a).toInt()
                    if (ha != haloA[s]) { haloA[s] = ha; paint.setShadowLayer(haloR[s], 0f, 0f, look.withAlpha(haloC[s], ha)) }
                }
                if (i == pressed && press.value != 1f) {
                    canvas.save(); canvas.scale(press.value, press.value, tx[i] + drawW[i] / 2, y - mids[s])
                    canvas.drawText(labels[i], tx[i], y, paint)
                    canvas.restore()
                } else canvas.drawText(labels[i], tx[i], y, paint)
            }
        }
        if (f > 0.01f && lit in 0 until n) drawFrame(canvas, f)
    }

    /** Does the circle (cx, cy, r) touch the box? */
    private fun near(cx: Float, cy: Float, r: Float, l: Float, t: Float, rr: Float, b: Float): Boolean {
        val dx = cx - cx.coerceIn(l, rr); val dy = cy - cy.coerceIn(t, b)
        return dx * dx + dy * dy < r * r
    }

    /** The pill: its outline round the name and the orb's dot, the name on a filled capsule that stops short of the
     *  dot (home draws the orb under this view, so the dot's place stays clear). */
    private fun drawFrame(canvas: Canvas, f: Float) {
        val i = lit; val s = steps[i]
        pillAt(pillNow)
        pillNow.offset(0f, -scroll)
        val sc = if (still) 1f else 0.94f + 0.06f * f
        canvas.save()
        canvas.scale(sc, sc, pillNow.centerX(), pillNow.centerY())
        val r = pillNow.height() / 2
        inner.set(pillNow.left + look.px(3), pillNow.top + look.px(3), pillNow.right - look.px(6) - dotR * 2 - look.px(6), pillNow.bottom - look.px(3))
        pillFill.alpha = (fillA * f).toInt(); pillEdge.alpha = (edgeA * f).toInt()
        canvas.drawRoundRect(inner, inner.height() / 2, inner.height() / 2, pillFill)
        canvas.drawRoundRect(pillNow, r, r, pillEdge)
        canvas.restore()
        // the name grows into it and turns light, sliding in when its pill had to step off an edge; moving between
        // names it turns light only as the capsule arrives under it, never near-white on the bare ice
        val t = min(f, glide.value.coerceIn(0f, 1f))
        val g = 1f + (GROW - 1f) * t
        val x = tx[i] + litDx * t; val y = base[i] - scroll
        hot.color = mix(look.ink, hotInk, t)
        hot.alpha = (255 * t + inkA * (1 - 0.65f * f) * (1 - t)).toInt().coerceIn(0, 255)  // from where its neighbours stand
        canvas.save()
        canvas.scale(g, g, x + drawW[i] / 2, y - mids[s])
        canvas.drawText(labels[i], x, y, hot)
        canvas.restore()
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(sh: Int) = (((a shr sh) and 0xFF) + ((((b shr sh) and 0xFF) - ((a shr sh) and 0xFF)) * t)).toInt()
        return Color.argb(255, ch(16), ch(8), ch(0))
    }

    // ── Scrolling ───────────────────────────────────────────────────────────────────────────────────

    private fun setScroll(v: Float) {
        if (v == scroll) return
        scroll = v
        searchPill.translationY = -scroll + if (still) 0f else look.px(12) * (1 - entrance())
        if (moved) reframe()
        pose()
        invalidate()
    }

    /** Past either end while dragged: rubber-band, so the edge is felt, never a wall. */
    private fun banded(r: Float): Float {
        val m = maxScroll()
        return when { r < 0f -> -rubberband(-r, height.toFloat()); r > m -> m + rubberband(r - m, height.toFloat()); else -> r }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { setScroll(scroller.currY.toFloat()); postInvalidateOnAnimation() }
        else if (!touching) fast(false)
    }

    private var fastOn = false
    private fun fast(on: Boolean) { if (on != fastOn) { fastOn = on; fastFrames(on) } }

    private fun scrollBy(dy: Float) {
        scroller.forceFinished(true)
        val to = (scroll + dy).coerceIn(0f, maxScroll())
        if (still) setScroll(to) else { scroller.startScroll(0, scroll.toInt(), 0, (to - scroll).toInt(), 350); postInvalidateOnAnimation() }
    }

    // ── Touch: tap, hold, scroll, drag the orb ──────────────────────────────────────────────────────

    private var touching = false
    private var dragging = false
    private var held = false
    private var caught = false      // the touch stopped a running fling: it doesn't also launch
    private var mode = NONE
    private var hit = -1
    private var pressed = -1
    private var pid = -1
    private var downX = 0f; private var downY = 0f; private var lastY = 0f
    private var grabX = 0f; private var grabY = 0f  // finger to lens: the orb moves 1:1 from where he took it
    private var vt: VelocityTracker? = null

    private val hold = Runnable {
        val i = hit
        if (i !in 0 until n || dragging || progress < 0.9f) return@Runnable
        held = true
        unpress()
        menuActions?.let { AppMenu.show(anchorAt(i), apps[i].key, look, it) }
    }

    private fun itemAt(x: Float, y: Float) = nameAt(x, y + scroll)

    /** Finger to lens, not to the orb as drawn: framing, the dot sits past the name, and taking it from there would
     *  pull the lens off the name it holds. */
    private fun grab(x: Float, y: Float) { grabX = x - (width / 2f + ptrX); grabY = y - (height / 2f + ptrY) }

    /** On the framed name's pill: it spreads over its neighbours' edges, and a tap there is the name it shows. */
    private fun onPill(x: Float, y: Float): Boolean {
        if (framed < 0 || frame.value < 0.5f) return false
        pillAt(pillNow)
        return pillNow.contains(x, y + scroll)
    }

    // the orb can rest under the search field or a chip: on it, the touch is the orb's before they see it
    override fun onInterceptTouchEvent(e: MotionEvent) =
        e.actionMasked == MotionEvent.ACTION_DOWN && progress >= 0.9f && n > 0 && onOrb(e.x, e.y)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // a finger already down when it starts closing still gets its UP/CANCEL, so nothing stays "touching"
        if (progress < 0.9f && e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true; dragging = false; held = false; hit = -1
                downX = e.x; downY = e.y; lastY = e.y; pid = e.getPointerId(0)
                vt?.recycle(); vt = VelocityTracker.obtain().apply { addMovement(e) }
                caught = !scroller.isFinished
                scroller.forceFinished(true)
                fast(true)
                if (onOrb(e.x, e.y)) {
                    mode = ORB; tick()
                    grab(e.x, e.y)
                    parent?.requestDisallowInterceptTouchEvent(true)  // a drag here moves the orb, never closes
                    return true
                }
                mode = LIST
                if (scroll > 0.5f || caught) parent?.requestDisallowInterceptTouchEvent(true)  // every drag scrolls once off the top
                raw = scroll
                hit = when { caught -> -1; onPill(e.x, e.y) -> framed; else -> itemAt(e.x, e.y) }
                if (hit >= 0) {
                    tick(); pressed = hit
                    if (!still) press.to(0.94f, response = 0.16f, damping = 1f)
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
                    reframe(); pose()
                    return true
                }
                if (!dragging) {
                    val dx = x - downX; val dy = y - downY
                    if (abs(dy) <= slop && abs(dx) <= slop) return true
                    removeCallbacks(hold); unpress(); hit = -1
                    if (abs(dy) <= abs(dx) * 1.3f) return true  // sideways or diagonal (home's own test): not a scroll yet
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    lastY = y  // scrolls 1:1 from here, so the slop doesn't jump
                }
                raw -= y - lastY; lastY = y
                setScroll(banded(raw))
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == pid) {
                val k = if (e.actionIndex == 0) 1 else 0  // the other finger carries on from where things are
                pid = e.getPointerId(k)
                lastY = e.getY(k)
                if (mode == ORB) grab(e.getX(k), e.getY(k))
            }
            MotionEvent.ACTION_UP -> finish(e, true)
            MotionEvent.ACTION_CANCEL -> finish(e, false)
        }
        return true
    }

    private fun finish(e: MotionEvent, up: Boolean) {
        removeCallbacks(hold)
        touching = false
        val wasOrb = mode == ORB
        if (wasOrb) {
            if (up && !dragging && !caught && progress >= 0.9f) onOrbTap?.invoke()  // a tap on the orb is still Jharvis; one that stopped a fling isn't
        } else {
            val tap = up && progress >= 0.9f && !dragging && !held && !caught && hit >= 0
            unpress()
            if (tap) launch(hit)
            val m = maxScroll().toInt()
            if (dragging && up) {
                vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
                val v = vt?.getYVelocity(pid) ?: 0f
                if (scroll < 0f || scroll > m) scroller.springBack(0, scroll.toInt(), 0, 0, 0, m)
                else scroller.fling(0, scroll.toInt(), 0, (-v).toInt(), 0, 0, 0, m, 0, look.px(40))
                postInvalidateOnAnimation()
            } else if (scroll < 0f || scroll > m) { scroller.springBack(0, scroll.toInt(), 0, 0, 0, m); postInvalidateOnAnimation() }
        }
        vt?.recycle(); vt = null
        dragging = false; mode = NONE
        if (scroller.isFinished) fast(false)
    }

    private fun unpress() { if (still) press.snap(1f) else press.to(1f, response = 0.42f, damping = 0.82f) }

    /** The invisible stand-in, set in name i's face and moved onto it. */
    private fun anchorAt(i: Int): View {
        val s = steps[i]; val p = paints[s]
        anchor.typeface = p.typeface; anchor.setTextSize(TypedValue.COMPLEX_UNIT_PX, p.textSize); anchor.letterSpacing = p.letterSpacing
        anchor.setTextColor(look.ink); anchor.text = labels[i]
        val w = ceil(drawW[i]).toInt() + look.px(2); val h = ceil(textH[s]).toInt()
        anchor.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        anchor.layout(0, 0, w, h)
        anchor.translationX = tx[i]; anchor.translationY = base[i] - scroll + ascents[s]
        return anchor
    }

    /** Through the lock prompt when the app is locked; onLaunch runs only once it passes. */
    private fun launch(i: Int) {
        val key = apps[i].key
        val from = anchorAt(i)
        val act = context as? Activity
        if (act == null) onLaunch(from, key) else LockedApps.launch(act, key, apps[i].label) { onLaunch(from, key) }
    }

    // ── TalkBack: every name on screen is a virtual button; scroll forward or back pages ───────────────

    private var hover = -1
    private var a11yFocus = -1
    private val screenAt = IntArray(2)

    private fun placed() = min(n, hitB.size)  // names geometry() has set (none before the first layout)
    private fun onScreen(i: Int) = hitB[i] - scroll > 0f && hitT[i] - scroll < height

    private val a11y = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(id: Int): AccessibilityNodeInfo? {
            val host = this@TypeView
            if (id == NO_ID) {
                val info = AccessibilityNodeInfo(host)
                onInitializeAccessibilityNodeInfo(info)
                if (searchPill.visibility == VISIBLE) info.addChild(searchPill)  // a provider host lists its real children itself
                for (i in 0 until placed()) if (onScreen(i)) info.addChild(host, i)
                if (chipRow.visibility == VISIBLE) info.addChild(chipRow)
                if (maxScroll() > 0f) {
                    info.isScrollable = true
                    if (scroll < maxScroll()) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                    if (scroll > 0f) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                }
                return info
            }
            if (id !in 0 until placed()) return null
            return AccessibilityNodeInfo(host, id).apply {
                setParent(host)
                packageName = context.packageName; className = Button::class.java.name
                contentDescription = desc[id]
                isEnabled = true; isFocusable = true; isClickable = true; isLongClickable = true
                isVisibleToUser = onScreen(id)
                isAccessibilityFocused = a11yFocus == id
                getLocationOnScreen(screenAt)
                setBoundsInScreen(Rect((screenAt[0] + hitL[id]).toInt(), (screenAt[1] + hitT[id] - scroll).toInt(),
                    (screenAt[0] + hitR[id]).toInt(), (screenAt[1] + hitB[id] - scroll).toInt()))
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
                scrollBy(dir * height * 0.7f)
                postDelayed({ sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED) }, if (still) 0L else 380L)  // once it has moved
                return true
            }
            if (id !in 0 until placed()) return false
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
        val child = over(searchPill, e) || over(chipRow, e)  // the search field and the chips are real views: theirs
        val i = if (child || e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) -1 else itemAt(e.x, e.y)
        if (i != hover) {
            val was = hover; hover = i
            if (i >= 0) send(i, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            if (was >= 0) send(was, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
        }
        return i >= 0 || super.dispatchHoverEvent(e)
    }

    private fun over(v: View, e: MotionEvent) = v.visibility == VISIBLE &&
        e.x >= v.left + v.translationX && e.x < v.right + v.translationX && e.y >= v.top + v.translationY && e.y < v.bottom + v.translationY

    private fun send(i: Int, type: Int) {
        val ev = AccessibilityEvent(type).apply {
            setSource(this@TypeView, i)
            packageName = context.packageName; className = Button::class.java.name
            contentDescription = desc[i]
        }
        parent?.requestSendAccessibilityEvent(this, ev)
    }

    private companion object {
        const val NONE = 0; const val LIST = 1; const val ORB = 2
        const val GROW = 1.12f          // the framed name, a touch bigger
        const val CHIP_WAIT = 140L      // ms the lens rests on a name before its shortcuts are read
    }
}
