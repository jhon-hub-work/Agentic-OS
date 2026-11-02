package com.jhon.agenticos.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Insets
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Smart groups, like the iPhone's App Library: a glass card per group (Work, Chats, Money, Create, Play, Tools, Other)
 *  in two columns. Each card shows its three most used apps big and a cluster of small icons; a big icon opens its
 *  app, the cluster opens the whole group as a sheet that grows out of it (Back or Done closes it). progress (home's
 *  spring) cascades the cards up row by row and folds them back the same way. Which group an app is in: AppGroups.
 *  onLaunch gets an app key; locked apps have already passed their lock prompt. */
class GroupsView(c: Context, private val look: Look, private val onLaunch: (icon: View, key: String) -> Unit,
                 @Suppress("unused") private val onClose: () -> Unit, private val onSearch: () -> Unit) : FrameLayout(c) {

    private class Group(val name: String, val apps: List<AppEntry>)

    val view: View get() = this
    /** Press and hold an icon: its app menu (home supplies what Add to dock and a change mean). */
    var menuActions: AppMenu.Actions? = null

    /** 0 = shut, 1 = open. Home's spring drives it. */
    var progress = 0f
        set(v) { field = v; applyProgress() }

    private val still = reducedMotion(c)
    private val scroll = ScrollView(c)
    private val column = LinearLayout(c)                 // the search pill, then rows of two cards
    private val searchPill = look.text("Search", 16f, look.ink3)
    private val cards = ArrayList<View>()               // grid order, for the cascade
    private val pills = ArrayList<Pair<TextView, AppEntry>>()       // card icons whose pill follows badges.json
    private val sheetPills = ArrayList<Pair<TextView, AppEntry>>()  // the same for the open group
    private var groups: List<Group> = emptyList()
    private var pending: List<Group>? = null            // a newer sort that waits until the layout is shut, so nothing jumps
    private var badges: Map<String, AppBadges.Badge> = emptyMap()
    private var loadGen = 0
    private var bars = Insets.NONE

    // the open group: a dim to tap away, and the sheet
    private val dim = View(c)
    private val sheet = FrameLayout(c)
    private val sheetTitle = look.text("", 22f, look.ink, true)
    private val sheetScroll = ScrollView(c)
    private val sheetGrid = LinearLayout(c)
    private var depthBlur = 0f; private var sheetBlur = 0f
    private val detail: Spring = Spring(0f, 0.001f) { applyDetail() }.apply { onRest = { sheet.fastFrames(false) } }

    init {
        column.orientation = LinearLayout.VERTICAL
        column.clipChildren = false
        scroll.apply {
            isVerticalScrollBarEnabled = false; clipToPadding = true; clipChildren = false  // cards stop at the top band, clear of the corner orb
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(look.px(24))
            addView(column, LayoutParams(-1, -2))
            visibility = INVISIBLE
        }
        addView(scroll, LayoutParams(-1, -1))

        searchPill.apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(look.px(20), 0, look.px(20), 0)
            background = glass(99)
            isClickable = true; look.press(this, 0.96f)
            setOnClickListener { onSearch() }
        }

        dim.apply {
            setBackgroundColor(look.shade(30))
            visibility = INVISIBLE
            contentDescription = "Close group"
            setOnClickListener { closeGroup() }
        }
        addView(dim, LayoutParams(-1, -1))

        sheetGrid.orientation = LinearLayout.VERTICAL
        sheetScroll.apply {
            isVerticalScrollBarEnabled = false; clipToPadding = false
            setPadding(look.px(8), 0, look.px(8), look.px(12))
            addView(sheetGrid, LayoutParams(-1, -2))
        }
        val done = look.text("Done", 15f, look.accentInk, true).apply {
            gravity = Gravity.CENTER; minWidth = look.px(48); minHeight = look.px(48)
            setPadding(look.px(12), 0, look.px(12), 0)
            isClickable = true; look.press(this, 0.9f)
            setOnClickListener { closeGroup() }
        }
        val header = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(look.px(18), look.px(8), look.px(6), look.px(4))
            addView(sheetTitle, LinearLayout.LayoutParams(0, -2, 1f))
            addView(done, LinearLayout.LayoutParams(-2, -2))
        }
        val core = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(22)
            isClickable = true  // taps inside the sheet never reach the dim behind it
            addView(header, LinearLayout.LayoutParams(-1, -2))
            addView(sheetScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        sheet.apply {
            background = look.shell(28)
            setPadding(look.px(6), look.px(6), look.px(6), look.px(6))
            addView(core, LayoutParams(-1, -1))
            visibility = INVISIBLE
        }
        addView(sheet, LayoutParams(-1, -1))

        setOnApplyWindowInsetsListener { _, i -> useInsets(i); i }
    }

    /** Glass that holds its ink over any wallpaper (the theme's card tint there), plain glass on the frost. */
    private fun glass(r: Int) = if (look.cardTint != 0) look.round(look.cardTint, r, look.glassEdge) else look.glassCard(r)

    private val appsChanged: () -> Unit = { load() }

    /** :core wrote new counts: read them off the main thread, then update the pills in place. */
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
        backWanted(false)
        Apps.unlisten(appsChanged)
        try { context.unregisterReceiver(badgeRx) } catch (_: Exception) {}
        loadGen++  // a sort still on its way is dropped
        super.onDetachedFromWindow()
    }

    // ── Size, insets, building the cards ────────────────────────────────────────────────────────────

    private fun useInsets(i: WindowInsets) {
        val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        if (b == bars) return
        bars = b
        fit(); build()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { fit(); build() }

    /** The top tenth stays clear for the orb, the bottom for the gesture bar. */
    private fun topClear() = max(bars.top + look.px(8), (height * 0.1f).toInt())

    private fun fit() {
        scroll.setPadding(bars.left + look.px(16), topClear(), bars.right + look.px(16), bars.bottom + look.px(40))
        (sheet.layoutParams as LayoutParams).apply {
            topMargin = topClear(); bottomMargin = bars.bottom + look.px(24)
            leftMargin = bars.left + look.px(12); rightMargin = bars.right + look.px(12)
        }
        sheet.requestLayout()
    }

    private fun build() {
        if (width == 0) return
        column.removeAllViews(); cards.clear(); pills.clear()
        column.addView(searchPill, LinearLayout.LayoutParams(-1, look.px(48)).apply { bottomMargin = look.px(20) })
        val gap = look.px(16)
        val cw = (width - bars.left - bars.right - look.px(32) - gap) / 2
        var row: LinearLayout? = null
        groups.forEachIndexed { i, g ->
            if (i % 2 == 0) row = LinearLayout(context).apply { clipChildren = false }
                .also { column.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = look.px(18) }) }
            val card = card(g, cw)
            row?.addView(card, LinearLayout.LayoutParams(cw, -2).apply { if (i % 2 == 1) leftMargin = gap })
            cards += card
        }
        showBadges()
        applyProgress()
    }

    /** A square of glass: three big icons and the cluster (or four big icons when that's the whole group), the group's
     *  name under it on the wallpaper. */
    private fun card(g: Group, cw: Int): View {
        val pad = look.px(12); val gap = look.px(10)
        val cell = (cw - 2 * pad - gap) / 2
        val box = FrameLayout(context).apply {
            background = glass(24); clipChildren = false
            isClickable = true; look.press(this, 0.97f)
            contentDescription = "Open ${g.name}"
            setOnClickListener { openGroup(g, this) }  // the gaps between icons open the group too
        }
        val big = if (g.apps.size <= 4) g.apps.size else 3
        for (j in 0 until big) {
            val icon = bigIcon(g.apps[j], cell, pills)
            box.addView(icon, LayoutParams(cell, cell).apply { leftMargin = pad + (j % 2) * (cell + gap); topMargin = pad + (j / 2) * (cell + gap) })
        }
        if (g.apps.size > 4) box.addView(cluster(g, cell), LayoutParams(cell, cell).apply { leftMargin = pad + cell + gap; topMargin = pad + cell + gap })
        val name = look.text(g.name, 13f, look.ink, true).apply {  // theme ink: it sits on the focus veil, as Picks and Orbit labels
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(look.px(4), look.px(6), look.px(4), look.px(2))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO  // the card says it
            look.legible(this)  // on the wallpaper, not glass
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false
            addView(box, LinearLayout.LayoutParams(cw, cw))
            addView(name, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** An app icon with its count pill at the top right corner; tap opens it, press and hold shows its menu. */
    private fun bigIcon(e: AppEntry, size: Int, into: ArrayList<Pair<TextView, AppEntry>>): FrameLayout {
        val img = ImageView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        Icons.cached(e.key)?.let { img.setImageBitmap(it) } ?: Icons.into(img, e.key)
        if (e.suspended) img.alpha = 0.5f
        val pill = AppBadges.pill(look)
        into += pill to e
        return FrameLayout(context).apply {
            clipChildren = false
            addView(img, LayoutParams(size, size))
            addView(pill, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = -look.px(5); rightMargin = -look.px(7) })
            isClickable = true; look.press(this, 0.88f)
            setOnClickListener { launch(img, e) }
            setOnLongClickListener { menuActions?.let { a -> AppMenu.show(img, e.key, look, a); true } ?: false }
        }
    }

    /** The next four apps small, in the fourth square: tap opens the whole group. */
    private fun cluster(g: Group, cell: Int) = FrameLayout(context).apply {
        val gap = look.px(6)
        val small = (cell - gap) / 2
        for (j in 0 until min(4, g.apps.size - 3)) {
            val img = ImageView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
            val key = g.apps[3 + j].key
            Icons.cached(key)?.let { img.setImageBitmap(it) } ?: Icons.into(img, key)
            addView(img, LayoutParams(small, small).apply { leftMargin = (j % 2) * (small + gap); topMargin = (j / 2) * (small + gap) })
        }
        contentDescription = "Open ${g.name}, ${g.apps.size} apps"
        isClickable = true; look.press(this, 0.9f)
        setOnClickListener { openGroup(g, this) }
    }

    private fun count(a: AppEntry) = if (a.main) badges[a.pkg]?.count ?: 0 else 0  // badges.json counts the main profile

    private fun showBadges() {
        for ((pill, a) in pills) showBadge(pill, a)
        for ((pill, a) in sheetPills) showBadge(pill, a)
    }

    private fun showBadge(pill: TextView, a: AppEntry) {
        val n = count(a)
        pill.text = AppBadges.word(n)
        pill.visibility = if (n > 0) VISIBLE else GONE
        (pill.parent as? View)?.contentDescription = a.label + when { n == 1 -> ", 1 notification"; n > 1 -> ", $n notifications"; else -> "" } +
            if (a.suspended) ", paused" else ""
    }

    private fun launch(from: View, e: AppEntry) {
        val go = { onLaunch(from, e.key) }
        val act = context as? Activity
        if (act == null) go() else LockedApps.launch(act, e.key, e.label, go)
    }

    // ── Frames: the cascade, the open group ─────────────────────────────────────────────────────────

    private fun applyProgress() {
        val p = progress
        if (p <= 0.001f) pending?.let { groups = it; pending = null; build(); return }
        val vis = if (p > 0.001f) VISIBLE else INVISIBLE  // see-through cards must not take taps
        if (scroll.visibility != vis) scroll.visibility = vis
        arrive(searchPill, p, 0f)
        for (i in cards.indices) arrive(cards[i], p, 0.06f * min(i / 2 + 1, 5) + 0.03f * (i % 2))
    }

    /** Row by row from below: rise, scale 0.92 to 1 and fade in together; Remove animations leaves the fade only. */
    private fun arrive(v: View, p: Float, delay: Float) {
        val t = if (still) p else ((p - delay) / 0.62f).coerceIn(0f, 1f)
        v.alpha = t
        if (still) return
        v.translationY = (1 - t) * look.px(56)
        val s = 0.92f + 0.08f * t; v.scaleX = s; v.scaleY = s
    }

    private fun fillSheet(g: Group) {
        sheetTitle.text = g.name
        sheetGrid.removeAllViews(); sheetPills.clear()
        val cols = 4
        g.apps.chunked(cols).forEach { line ->
            val row = LinearLayout(context)
            for (e in line) row.addView(sheetCell(e), LinearLayout.LayoutParams(0, -2, 1f))
            repeat(cols - line.size) { row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            sheetGrid.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        showBadges()
    }

    private fun sheetCell(e: AppEntry): View {
        val icon = bigIcon(e, look.px(56), sheetPills)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false
            setPadding(0, look.px(10), 0, look.px(6))
            addView(icon, LinearLayout.LayoutParams(look.px(56), look.px(56)))
            addView(look.text(e.label, 12f, look.ink).apply {
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(look.px(2), look.px(4), look.px(2), 0)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO  // the icon says its name
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** The group grows out of the card that was tapped; the cards behind recede and blur. */
    private fun openGroup(g: Group, from: View) {
        fillSheet(g)
        val a = IntArray(2); val me = IntArray(2)
        from.getLocationOnScreen(a); getLocationOnScreen(me)
        sheet.pivotX = a[0] + from.width / 2f - me[0] - sheet.left
        sheet.pivotY = a[1] + from.height / 2f - me[1] - sheet.top
        sheetScroll.scrollTo(0, 0)
        backWanted(true)
        if (still) detail.snap(1f) else { sheet.fastFrames(true); detail.to(1f, response = 0.5f, damping = 1f) }
    }

    private fun closeGroup() {
        backWanted(false)
        if (still) detail.snap(0f) else { sheet.fastFrames(true); detail.to(0f, response = 0.42f, damping = 1f) }
    }

    private fun applyDetail() {
        val d = detail.value.coerceIn(0f, 1f)
        val vis = if (d > 0.005f) VISIBLE else INVISIBLE
        if (sheet.visibility != vis) { sheet.visibility = vis; dim.visibility = vis }
        dim.alpha = d
        sheet.alpha = d
        if (still) return  // Remove animations: a cross-fade only
        val s = 0.6f + 0.4f * d; sheet.scaleX = s; sheet.scaleY = s
        sheetBlur = setBlur(sheet, look.px(8) * (1 - d), sheetBlur)
        val k = 1 - 0.08f * d; scroll.scaleX = k; scroll.scaleY = k
        depthBlur = setBlur(scroll, look.px(28) * d, depthBlur)
    }

    /** Blur only when the radius really changed (each change builds a RenderEffect). */
    private fun setBlur(v: View, r: Float, last: Float): Float {
        val q = (r * 2).roundToInt() / 2f
        if (q != last) v.blur(q)
        return q
    }

    // Back while a group is open closes it, following the gesture; home's own Back closes the layout
    private var backOn = false
    private val back: OnBackInvokedCallback = if (Build.VERSION.SDK_INT >= 34) object : OnBackAnimationCallback {
        override fun onBackProgressed(e: BackEvent) { if (!still) detail.snap(1f - 0.3f * e.progress) }
        override fun onBackCancelled() { if (!still) detail.to(1f, response = 0.35f, damping = 1f) }
        override fun onBackInvoked() = closeGroup()
    } else OnBackInvokedCallback { closeGroup() }

    private fun backWanted(on: Boolean) {
        if (on == backOn) return
        val d = findOnBackInvokedDispatcher() ?: return
        if (on) d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT + 1, back) else d.unregisterOnBackInvokedCallback(back)
        backOn = on
    }

    // ── The interface home uses ─────────────────────────────────────────────────────────────────────

    /** Re-read hidden apps and badges, re-rank and re-sort, on the loader thread; then ask Jharvis about apps that only
     *  have a guess (his answer reloads). A changed layout waits until it's shut. */
    fun load() {
        val app = context.applicationContext
        val g = ++loadGen
        Loader.run {
            Icons.sync(app)
            val hidden = HiddenApps.all(app)
            val all = Apps.get(app).filter { it.key !in hidden }
            val byKey = all.associateBy { it.key }
            val pick = AppGroups.groups(app, all)
            val got = AppGroups.arrange(LaunchCounts.ranked(app, all.map { it.key }), pick)
                .map { (n, keys) -> Group(n, keys.mapNotNull { byKey[it] }) }
            for (gr in got) for (e in gr.apps.take(7)) Icons.load(app, e)  // what the cards show is drawn before they are
            val b = AppBadges.read()
            Loader.main.post {
                if (g != loadGen) return@post
                badges = b
                val cur = pending ?: groups
                val same = cur.size == got.size && cur.indices.all { i ->
                    cur[i].name == got[i].name && cur[i].apps.map { it.key } == got[i].apps.map { it.key }
                }
                if (!same) { if (progress <= 0.001f || MenuChange.recent()) { groups = got; pending = null; build() } else pending = got }
                showBadges()
            }
            AppGroups.ask(app, all) { load() }
            for (gr in got) for (e in gr.apps.drop(7)) Loader.run { Icons.load(app, e) }  // one job each, so a menu can cut in
        }
    }

    /** Called once as an open starts: a sort that arrived while shut is laid out before the cards rise. */
    fun opening() {
        pending?.let { groups = it; pending = null; build() }
    }

    /** Group shut, back at the top. */
    fun reset() {
        backWanted(false)
        detail.snap(0f)
        scroll.scrollTo(0, 0)
    }

    /** True when a drag here belongs to the layout (it scrolls, or a group is open), so home must leave it alone. */
    fun ownsTouch(x: Float, y: Float): Boolean {
        if (progress < 0.95f) return false
        if (detail.target > 0f) return true
        return y >= topClear() && scroll.canScrollVertically(-1)  // at the top a drag down closes; Home leaves drags up to the list
    }
}
