package com.jhon.agenticos.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Insets
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.jhon.agenticos.core.Mode
import com.jhon.agenticos.core.Modes
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

/** Pure parts of Jharvis picks, tested on the JVM: A to Z sections, the scrubber's letter under a finger, the reason line. */
internal object PicksMath {
    const val OTHER = '#'

    /** The section an app goes under: its first letter without accents ("Écran" -> E); digits, symbols, emoji and other
     *  scripts go under #. */
    fun letter(label: String): Char {
        val c = SmartSearch.normalize(label).firstOrNull { !it.isWhitespace() }?.uppercaseChar() ?: return OTHER
        return if (c in 'A'..'Z') c else OTHER
    }

    class Section<T>(val letter: Char, val items: List<T>)

    /** A to Z, then # last as on the iPhone; inside a section by name, accents folded. */
    fun <T> sections(items: List<T>, label: (T) -> String): List<Section<T>> {
        val by = items.groupBy { letter(label(it)) }
        return by.keys.sortedBy { if (it == OTHER) '~' else it }
            .map { k -> Section(k, by.getValue(k).sortedBy { SmartSearch.normalize(label(it)) }) }
    }

    /** Where each section's letter row sits in the flat list (letter row, then its apps). */
    fun starts(sizes: List<Int>): IntArray {
        var at = 0
        return IntArray(sizes.size) { i -> at.also { at += 1 + sizes[i] } }
    }

    /** Which of n letters spread evenly over a band (top, height) is under y; past either end, the first or the last. */
    fun at(y: Float, top: Float, height: Float, n: Int): Int =
        if (n <= 0 || height <= 0f) -1 else ((y - top) / height * n).toInt().coerceIn(0, n - 1)

    private val parts = listOf("late nights", "early mornings", "mornings", "afternoons", "evenings", "nights")

    /** The line under "For now": what the picks follow (LaunchCounts weights the mode, then the part of the day). */
    fun reason(mode: Mode, hour: Int, history: Boolean) = when {
        !history -> "Apps you open most will show up here"
        mode != Mode.OFF -> "Picked for ${mode.label.lowercase()}"
        else -> "Picked for ${parts[LaunchCounts.bucket(hour)]}"
    }
}

/** Jharvis picks: "For now" with the eight apps he most likely wants at this hour and in this mode (LaunchCounts), a
 *  search field that hands off to the orbit's search, then every app A to Z with section letters and a letter scrubber
 *  on the right edge. Same app keys, icons, badges, hidden and locked apps as OrbitView; locked apps have passed their
 *  prompt when onLaunch runs. progress (home's spring) pops the picks in one after another, then the list rises in. */
class PicksView(c: Context, private val look: Look, private val onLaunch: (icon: View, key: String) -> Unit,
                private val onClose: () -> Unit, private val onSearch: () -> Unit) : FrameLayout(c) {

    private class Data(val picks: List<AppEntry>, val rows: List<Any>, val starts: IntArray, val letters: CharArray, val reason: String)
    private class Cell(val root: View, val img: ImageView, val label: TextView, val pill: TextView) { var app: AppEntry? = null }

    val view: View get() = this
    /** Press and hold an app: its menu (home supplies what Add to dock and a change mean). */
    var menuActions: AppMenu.Actions? = null

    /** 0 = shut, 1 = open. Home's spring drives it. */
    var progress = 0f
        set(v) { field = v; applyProgress() }

    private val still = reducedMotion(c)
    private val list = ListView(c)
    private val header = LinearLayout(c)
    private val heading = look.text("For now", 28f, look.ink, true)
    private val reasonLine = look.text("", 13f, look.ink2)
    private val picks = ArrayList<Cell>(PICKS)
    private val pickRows = listOf(LinearLayout(c), LinearLayout(c))
    private val searchPill = LinearLayout(c)
    private val scrubber = PicksScrubber(c, look, { i -> jump(i) }, { hideBubble() })
    private val bubble = look.text("", 30f, look.ink, true)
    private val titles = arrayOf<View>(heading, reasonLine)

    private var rows: List<Any> = emptyList()  // Char = a section letter, AppEntry = an app
    private var starts = IntArray(0)
    private var pending: Data? = null           // a newer ranking waits until shut, so nothing jumps under his finger
    private var badges: Map<String, AppBadges.Badge> = emptyMap()
    private var loadGen = 0
    private var bars = Insets.NONE; private var edge = 0
    private var listA = 1f; private var listDy = 0f  // the rows' entrance, also applied to rows bound mid-entrance

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (rows[p] is Char) 0 else 1
        override fun areAllItemsEnabled() = false
        override fun isEnabled(p: Int) = false  // rows take their own taps (press, menu); no list selection
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val r = rows[p]
            val v = if (r is Char) ((cv as? TextView) ?: letterRow()).apply { text = r.toString() }
                else (cv ?: appRow()).also { bind(it.tag as Cell, r as AppEntry) }
            v.setPadding(bars.left + look.px(20), v.paddingTop, rowRight(), v.paddingBottom)  // no-op unless the insets changed
            v.alpha = listA; v.translationY = listDy
            return v
        }
    }

    init {
        header.orientation = LinearLayout.VERTICAL
        heading.apply { isAccessibilityHeading = true; look.legible(this) }
        reasonLine.apply { setPadding(0, look.px(2), 0, 0); look.legible(this) }
        header.addView(heading, LinearLayout.LayoutParams(-2, -2))
        header.addView(reasonLine, LinearLayout.LayoutParams(-2, -2))
        for ((n, row) in pickRows.withIndex()) {
            for (i in 0 until PICKS / 2) { val cell = pickCell(); picks += cell; row.addView(cell.root, LinearLayout.LayoutParams(0, -2, 1f)) }
            header.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.px(if (n == 0) 18 else 12) })
        }
        searchPill.apply {
            gravity = Gravity.CENTER_VERTICAL
            background = look.glassCard(99)
            setPadding(look.px(18), 0, look.px(18), 0)
            addView(PicksLens(c, look), LinearLayout.LayoutParams(look.px(18), look.px(18)))
            addView(look.text("Search", 17f, look.ink3), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = look.px(10) })
            contentDescription = "Search apps"
            isClickable = true; look.press(this, 0.97f)
            setOnClickListener { onSearch() }
        }
        header.addView(searchPill, LinearLayout.LayoutParams(-1, look.px(50)).apply { topMargin = look.px(22); bottomMargin = look.px(14) })

        list.apply {
            divider = null; selector = ColorDrawable(0); cacheColorHint = 0
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(look.px(24))  // rows dissolve into the clear top and bottom
            addHeaderView(header, null, false)
            adapter = this@PicksView.adapter
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView, s: Int) {}
                override fun onScroll(v: AbsListView, first: Int, count: Int, total: Int) { if (scrubber.active < 0) scrubber.follow(sectionAt(first)) }
            })
        }
        addView(list, LayoutParams(-1, -1))
        addView(scrubber, LayoutParams(look.px(28), -1, Gravity.END))
        bubble.apply {
            gravity = Gravity.CENTER
            background = look.glassCard(32)
            visibility = INVISIBLE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(bubble, LayoutParams(look.px(64), look.px(64), Gravity.END or Gravity.TOP))
        setOnApplyWindowInsetsListener { _, i -> useInsets(i); i }
    }

    // ── Building blocks ─────────────────────────────────────────────────────────────────────────────

    private fun pickCell(): Cell {
        val size = look.px(56)
        val img = ImageView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        val pill = AppBadges.pill(look)
        val frame = FrameLayout(context).apply {
            clipChildren = false
            addView(img, LayoutParams(size, size))
            addView(pill, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = -look.px(5); rightMargin = -look.px(7) })
        }
        val label = look.text("", 12f, look.ink).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(look.px(2), look.px(6), look.px(2), look.px(2))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            look.legible(this)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            addView(frame, LinearLayout.LayoutParams(size, size))
            addView(label, LinearLayout.LayoutParams(-1, -2))
        }
        return Cell(root, img, label, pill).also { cell -> tapAndHold(root, cell, 0.88f) }
    }

    private fun appRow(): View {
        val img = ImageView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        val label = look.text("", 16f, look.ink).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            look.legible(this)
        }
        val pill = AppBadges.pill(look)
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = look.px(56)
            setPadding(bars.left + look.px(20), look.px(4), rowRight(), look.px(4))
            addView(img, LinearLayout.LayoutParams(look.px(40), look.px(40)).apply { rightMargin = look.px(14) })
            addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            addView(pill, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = look.px(8) })
            layoutParams = AbsListView.LayoutParams(-1, -2)
        }
        return row.also { val cell = Cell(row, img, label, pill); row.tag = cell; tapAndHold(row, cell, 0.97f) }
    }

    private fun letterRow() = look.text("", 13f, look.ink2, true).apply {
        setPadding(bars.left + look.px(20), look.px(14), rowRight(), look.px(4))
        isAccessibilityHeading = true
        layoutParams = AbsListView.LayoutParams(-1, -2)
        look.legible(this)
    }

    private fun rowRight() = edge + look.px(28 + 8)  // clear of the scrubber

    private fun tapAndHold(v: View, cell: Cell, depth: Float) {
        v.isClickable = true; look.press(v, depth)
        v.setOnClickListener { cell.app?.let { launch(cell.img, it) } }
        v.setOnLongClickListener { val a = cell.app; val m = menuActions; if (a != null && m != null) AppMenu.show(cell.img, a.key, look, m); a != null && m != null }
    }

    private fun bind(cell: Cell, a: AppEntry) {
        cell.app = a
        Icons.into(cell.img, a.key)  // cached now, or filled in from the loader thread
        cell.img.alpha = if (a.suspended) 0.5f else 1f
        cell.label.text = a.label
        showBadge(cell, a)
    }

    private fun showBadge(cell: Cell, a: AppEntry) {
        val n = if (a.main) badges[a.pkg]?.count ?: 0 else 0  // badges.json counts the main profile
        cell.pill.text = AppBadges.word(n)
        cell.pill.visibility = if (n > 0) VISIBLE else GONE
        cell.root.contentDescription = a.label + when { n == 1 -> ", 1 notification"; n > 1 -> ", $n notifications"; else -> "" } +
            if (a.suspended) ", paused" else ""
    }

    /** Through the lock prompt when the app is locked, then home opens it (and counts the launch). */
    private fun launch(from: View, e: AppEntry) {
        val go = { onLaunch(from, e.key) }
        val act = context as? Activity
        if (act == null) go() else LockedApps.launch(act, e.key, e.label, go)
    }

    // ── Insets and the scrubber ─────────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); post { pad() } }  // not mid-layout

    private fun useInsets(i: WindowInsets) {
        val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        // the right edge is the system Back swipe: the scrubber sits just inside it so both keep working
        val e = max(b.right, i.getInsets(WindowInsets.Type.systemGestures()).right) + look.px(2)
        if (b == bars && e == edge) return
        bars = b; edge = e
        pad()
    }

    /** Keeps the top tenth (the orb's corner, the status bar) and the gesture area clear. */
    private fun pad() {
        val top = max(bars.top + look.px(8), (height * 0.10f).toInt())
        val bottom = bars.bottom + look.px(16)
        list.setPadding(0, top, 0, bottom)
        header.setPadding(bars.left + look.px(20), look.px(4), bars.right + look.px(20), 0)
        (scrubber.layoutParams as LayoutParams).apply { topMargin = top; bottomMargin = bottom; rightMargin = edge }
        (bubble.layoutParams as LayoutParams).rightMargin = edge + look.px(28 + 16)
        adapter.notifyDataSetChanged()  // rows on screen take the new side padding
        requestLayout()
    }

    /** The section of the list's first visible row, for the scrubber's highlight while he scrolls. */
    private fun sectionAt(first: Int): Int {
        val p = first - list.headerViewsCount
        if (p < 0 || starts.isEmpty()) return -1
        var s = 0
        while (s + 1 < starts.size && starts[s + 1] <= p) s++
        return s
    }

    private fun jump(i: Int) {
        if (i !in starts.indices) return
        list.smoothScrollBy(0, 0)  // stops a fling
        list.setSelectionFromTop(list.headerViewsCount + starts[i], 0)
        bubble.text = scrubber.letters[i].toString()
        bubble.translationY = scrubber.top + scrubber.centreOf(i) - look.px(32)
        if (bubble.visibility != VISIBLE) {
            bubble.visibility = VISIBLE
            bubble.animate().cancel()
            if (still) { bubble.alpha = 1f; return }
            bubble.alpha = 0f; bubble.scaleX = 0.85f; bubble.scaleY = 0.85f
            bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(SpringCurve(1f)).start()
        }
    }

    private fun hideBubble() {
        if (bubble.visibility != VISIBLE) return
        bubble.animate().cancel()
        if (still) { bubble.visibility = INVISIBLE; return }
        bubble.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(220).setInterpolator(SpringCurve(1f))
            .withEndAction { bubble.visibility = INVISIBLE }.start()
    }

    // ── Frames: the entrance ────────────────────────────────────────────────────────────────────────

    private fun stage(p: Float, from: Float, span: Float) = if (still) 1f else ((p - from) / span).coerceIn(0f, 1f)

    private fun applyProgress() {
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0.001f) pending?.let { show(it); pending = null }
        val a = if (still) p else 1f
        val h = stage(p, 0.05f, 0.5f)
        for (v in titles) {
            v.alpha = h * a; v.translationY = (1 - h) * look.px(14)
            val s = 0.92f + 0.08f * h; v.scaleX = s; v.scaleY = s
        }
        // the picks pop in one after another, the same path back out
        for (i in picks.indices) {
            val t = stage(p, 0.12f + 0.045f * i, 0.5f)
            val v = picks[i].root
            v.alpha = t * a; v.translationY = (1 - t) * look.px(18)
            val s = 0.82f + 0.18f * t; v.scaleX = s; v.scaleY = s
        }
        val sp = stage(p, 0.35f, 0.5f)
        searchPill.alpha = sp * a; searchPill.translationY = (1 - sp) * look.px(14)
        val l = stage(p, 0.4f, 0.6f)
        listA = l * a; listDy = (1 - l) * look.px(28)
        for (i in 0 until list.childCount) {
            val ch = list.getChildAt(i)
            if (ch !== header) { ch.alpha = listA; ch.translationY = listDy }
        }
        scrubber.alpha = listA
    }

    // ── Data ────────────────────────────────────────────────────────────────────────────────────────

    private fun show(d: Data) {
        for ((i, cell) in picks.withIndex()) {
            val a = d.picks.getOrNull(i)
            cell.root.visibility = if (a == null) INVISIBLE else VISIBLE
            if (a != null) bind(cell, a) else cell.app = null
        }
        pickRows[1].visibility = if (d.picks.size > PICKS / 2) VISIBLE else GONE
        reasonLine.text = d.reason
        rows = d.rows; starts = d.starts
        adapter.notifyDataSetChanged()
        scrubber.letters = d.letters
        scrubber.visibility = if (d.letters.size >= 2) VISIBLE else GONE
    }

    private fun showBadges() {
        for (cell in picks) cell.app?.let { showBadge(cell, it) }
        adapter.notifyDataSetChanged()  // rebinds only the rows on screen
    }

    /** An app was installed, removed, updated or paused. */
    private val appsChanged: () -> Unit = { load() }

    /** :core wrote new counts: read them off the main thread, then update the pills in place. */
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
        loadGen++  // a ranking still on its way is dropped
        super.onDetachedFromWindow()
    }

    // ── The interface home uses (AppLayout) ─────────────────────────────────────────────────────────

    /** Re-rank and re-read hidden apps, badges and the mode on the loader thread. A changed list waits until shut. */
    fun load() {
        val app = context.applicationContext
        val g = ++loadGen
        Loader.run {
            Icons.sync(app)
            val hidden = HiddenApps.all(app)
            val shown = Apps.get(app).filter { it.key !in hidden }
            val byKey = shown.associateBy { it.key }
            // the dock seeds the picks before there is any launch history, as it seeds the orbit's inner ring
            val prefs = app.getSharedPreferences("aos", Context.MODE_PRIVATE)
            val mode = try { Modes.current() } catch (_: Exception) { Mode.OFF }
            val dock = (0 until 4).mapNotNull { prefs.getString("dock$it", null) }  // one dock for every mode, as fillDock
                .map { if (it in byKey) it else AppKey.forPackage(app, it) }.filter { it in byKey }.distinct()
            val top = LaunchCounts.ranked(app, dock + shown.map { it.key }.filter { it !in dock }).take(PICKS).mapNotNull { byKey[it] }
            for (e in top) Icons.load(app, e)  // the picks' icons are ready before they pop in
            val secs = PicksMath.sections(shown) { it.label }
            val flat = ArrayList<Any>(shown.size + secs.size)
            for (s in secs) { flat += s.letter; flat.addAll(s.items) }
            // LaunchCounts keeps its history in "launches"/"h": empty = nothing learned yet, so no "Picked for" claim
            val history = !app.getSharedPreferences("launches", Context.MODE_PRIVATE).getString("h", null).isNullOrBlank()
            val d = Data(top, flat, PicksMath.starts(secs.map { it.items.size }), CharArray(secs.size) { secs[it].letter },
                PicksMath.reason(mode, Calendar.getInstance().get(Calendar.HOUR_OF_DAY), history))
            val b = AppBadges.read()
            Loader.main.post {
                if (g != loadGen) return@post
                badges = b
                if (progress <= 0.001f || rows.isEmpty() || MenuChange.recent()) { show(d); pending = null } else pending = d
                showBadges()
            }
            for (e in shown) if (e !in top) Loader.run { Icons.load(app, e) }  // the rest, one job each, so a menu can cut in
        }
    }

    /** Called once as an open starts: anything that arrived while shut is laid out before the picks pop in. */
    fun opening() { pending?.let { show(it); pending = null } }

    /** Back to the top, scrubber let go. */
    fun reset() {
        list.setSelection(0)
        scrubber.release()
        bubble.animate().cancel(); bubble.visibility = INVISIBLE
    }

    /** true when a drag starting here belongs to the picks: on the scrubber, or anywhere once the list has scrolled
     *  off its top (every vertical drag scrolls then). At the top it's false, so home may take a drag down to close;
     *  a drag up there still scrolls the list if home leaves it alone. */
    fun ownsTouch(x: Float, y: Float): Boolean {
        if (progress < 0.95f) return false
        if (scrubber.visibility == VISIBLE && x >= scrubber.left - look.px(8) && scrubber.hits(y - scrubber.top)) return true
        return list.canScrollVertically(-1)
    }

    companion object { const val PICKS = 8 }
}

/** The letter scrubber: the sections' letters on a glass pill; touch or drag to jump, a light tick per letter. All
 *  measuring happens when the letters or the size change; onDraw allocates nothing. */
private class PicksScrubber(c: Context, private val look: Look, private val pick: (Int) -> Unit, private val done: () -> Unit) : View(c) {
    var letters = CharArray(0)
        set(v) { field = v; strs = Array(v.size) { v[it].toString() }; active = -1; shownAt = -1; measureBand(); invalidate() }
    var active = -1; private set   // the letter under his finger, -1 when not touched
    private var shownAt = -1       // the section at the top of the list while he scrolls
    private var strs = emptyArray<String>()
    private var pitch = 0f; private var bandTop = 0f
    private val band = RectF()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = look.medium
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, c.resources.displayMetrics)
    }
    private val baseline = -(text.fontMetrics.ascent + text.fontMetrics.descent) / 2
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.glass }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = look.px(1).toFloat(); color = look.glassEdge }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }  // TalkBack scrolls the list; the letters are headings there

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = measureBand()

    private fun measureBand() {
        val n = letters.size
        if (n == 0 || height == 0) { band.setEmpty(); return }
        val pad = look.px(6).toFloat()
        pitch = min(look.px(18).toFloat(), (height - 2 * pad) / n)
        bandTop = (height - pitch * n) / 2
        band.set(look.px(2).toFloat(), bandTop - pad, width - look.px(2).toFloat(), bandTop + pitch * n + pad)
    }

    fun centreOf(i: Int) = bandTop + pitch * (i + 0.5f)
    /** y (in this view) is on the letters, with a little room. */
    fun hits(y: Float) = !band.isEmpty && y >= band.top - look.px(8) && y <= band.bottom + look.px(8)

    fun follow(i: Int) { if (i != shownAt) { shownAt = i; invalidate() } }
    fun release() { if (active >= 0) { active = -1; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        if (band.isEmpty) return
        val r = band.width() / 2
        canvas.drawRoundRect(band, r, r, fill); canvas.drawRoundRect(band, r, r, rim)
        val cx = width / 2f
        val on = if (active >= 0) active else shownAt
        for (i in strs.indices) {
            text.color = if (i == on) look.accentInk else look.ink2
            canvas.drawText(strs[i], cx, centreOf(i) + baseline, text)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hits(e.y)) return false  // empty edge above or below the letters stays home's
                parent?.requestDisallowInterceptTouchEvent(true)  // a drag here jumps letters, never closes or scrolls
                tick(); active = -1; move(e.y, false)
            }
            MotionEvent.ACTION_MOVE -> move(e.y, true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (active >= 0) shownAt = active; release(); done() }
        }
        return true
    }

    private fun move(y: Float, haptic: Boolean) {
        val i = PicksMath.at(y, bandTop, pitch * letters.size, letters.size)
        if (i < 0 || i == active) return
        active = i; invalidate()
        if (haptic) Haptics.crossing(this)  // the lightest tick, rate-limited so a fast drag doesn't buzz
        pick(i)
    }
}

/** The search field's magnifier, a thin line glyph. Path built once per size. */
private class PicksLens(c: Context, look: Look) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.75f * c.resources.displayMetrics.density; color = look.ink3
    }
    private val path = Path()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        path.reset()
        val mx = w / 2f; val my = h / 2f; val s = min(w, h).toFloat()
        path.addCircle(mx - s * 0.08f, my - s * 0.08f, s * 0.32f, Path.Direction.CW)
        path.moveTo(mx + s * 0.16f, my + s * 0.16f); path.lineTo(mx + s * 0.44f, my + s * 0.44f)
    }

    override fun onDraw(canvas: Canvas) { canvas.drawPath(path, paint) }
}
