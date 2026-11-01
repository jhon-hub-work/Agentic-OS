package com.jhon.agenticos.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.Canvas
import android.graphics.Insets
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.UserHandle
import android.provider.AlarmClock
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.jhon.agenticos.MainActivity
import com.jhon.agenticos.core.Link
import com.jhon.agenticos.core.Mode
import com.jhon.agenticos.core.Modes
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/** Pure ring arithmetic, kept apart so it can be tested on the JVM. */
internal object OrbitMath {
    /** How many apps each ring holds: inner rings fill first, each up to its slots. */
    fun fill(count: Int, slots: IntArray): IntArray {
        var left = count
        return IntArray(slots.size) { i -> min(left, slots[i]).also { left -= it } }
    }

    /** Slots a ring of radius r has room for: icons a pitch apart, capped at the ring's share. */
    fun slots(r: Float, pitch: Float, cap: Int) = min(cap, floor(2 * PI * r / pitch).toInt()).coerceAtLeast(1)

    /** The rest angle (a whole number of slots) nearest to a: where a spin settles. */
    fun snap(a: Float, pitch: Float) = (a / pitch).roundToInt() * pitch

    /** An angle step folded into -180..180, so a drag across the 180° line doesn't jump a whole turn. */
    fun wrap(d: Float): Float { var x = d % 360f; if (x > 180f) x -= 360f; if (x < -180f) x += 360f; return x }
}

/** How often and how lately each app is opened. The rings follow KISS-style frecency over the last 300 launches,
 *  weighted toward this part of the day and the current mode ("apps for the moment"); the older decayed counts
 *  (half-life 14 days) break ties, so history saved before still counts. Keys are app keys (see AppKey). */
object LaunchCounts {
    private const val HALF_LIFE_MS = 14 * 86_400_000.0
    internal const val HISTORY = 300  // KISS: the last N x 30 launches, N = 10

    internal class Launch(val key: String, val time: Long, val bucket: Int, val mode: String)

    internal fun decayed(score: Float, at: Long, now: Long) =
        if (score <= 0f) 0f else (score * 0.5.pow((now - at).coerceAtLeast(0) / HALF_LIFE_MS)).toFloat()

    /** The hour as one of six 4-hour parts of the day; 0 = midnight to 4 AM, which holds his core hours (1 to 4 AM). */
    internal fun bucket(hour: Int) = hour.coerceIn(0, 23) / 4

    internal fun parse(s: String?): List<Launch> = s.orEmpty().lineSequence().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 4 || p[0].isEmpty()) null else Launch(p[0], p[1].toLongOrNull() ?: return@mapNotNull null, p[2].toIntOrNull() ?: 0, p[3])
    }.toList()

    internal fun format(h: List<Launch>) = h.joinToString("\n") { "${it.key}\t${it.time}\t${it.bucket}\t${it.mode}" }

    /** Each launch (newest first) counts by how recent it is, 1 down to almost 0, and counts double when it happened in
     *  this part of the day, and again when in the same mode. */
    internal fun frecency(h: List<Launch>, bucket: Int, mode: String): Map<String, Float> {
        val out = HashMap<String, Float>()
        h.take(HISTORY).forEachIndexed { i, l ->
            val moment = 1f + (if (l.bucket == bucket) 1f else 0f) + (if (l.mode == mode) 1f else 0f)
            out[l.key] = (out[l.key] ?: 0f) + (HISTORY - i).toFloat() / HISTORY * moment
        }
        return out
    }

    /** Most frecent first, then the old decayed counts; never-used ones keep the order they came in (the seed: dock,
     *  then A to Z). */
    internal fun order(pkgs: List<String>, now: Long, freq: Map<String, Float> = emptyMap(), saved: (String) -> Pair<Float, Long>): List<String> {
        val old = pkgs.associateWith { val (s, t) = saved(it); decayed(s, t, now) }
        return pkgs.withIndex().sortedWith(compareByDescending<IndexedValue<String>> { freq[it.value] ?: 0f }
            .thenByDescending { old[it.value] ?: 0f }.thenBy { it.index }).map { it.value }
    }

    private fun hourOf(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.HOUR_OF_DAY)
    private fun modeNow(t: Long) = try { Modes.current(t).name } catch (_: Exception) { "OFF" }

    fun bump(c: Context, pkg: String) {
        val app = c.applicationContext
        val at = System.currentTimeMillis()
        Loader.run {  // first read of the prefs file is disk: never on the main thread
            val p = app.getSharedPreferences("launches", Context.MODE_PRIVATE)
            val s = decayed(p.getFloat("s:$pkg", 0f), p.getLong("t:$pkg", at), at) + 1f
            val h = (listOf(Launch(pkg, at, bucket(hourOf(at)), modeNow(at))) + parse(p.getString("h", null))).take(HISTORY)
            p.edit().putFloat("s:$pkg", s).putLong("t:$pkg", at).putString("h", format(h)).apply()
        }
    }

    /** Call off the main thread. */
    fun ranked(c: Context, pkgs: List<String>): List<String> {
        val p = c.getSharedPreferences("launches", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val freq = frecency(parse(p.getString("h", null)), bucket(hourOf(now)), modeNow(now))
        return order(pkgs, now, freq) { p.getFloat("s:$it", 0f) to p.getLong("t:$it", 0L) }
    }
}

/** All apps on rings around the core (the view's centre, left clear for home's compact JharvisOrb). Most used on the
 *  inner ring, labelled; three more rings outward, the last partly off screen. Drag a ring to spin it; a flick carries
 *  on and settles an icon onto a slot with a tick. progress (home's spring) flies the icons out of the core, inner
 *  ring first, and folds them back the same way. Search swaps the rings for result rows.
 *  onLaunch gets an app key (AppKey): a plain package for main-profile apps with one launcher activity. Locked apps
 *  have already passed their lock prompt when onLaunch runs. */
class OrbitView(c: Context, private val look: Look, private val onLaunch: (icon: View, pkg: String) -> Unit,
                private val onClose: () -> Unit) : FrameLayout(c) {

    private inner class Ring(val index: Int, val cap: Int) {
        val layer = FrameLayout(context)
        var r = 0f; var slots = 1; var pitch = 360f
        var base = FloatArray(0)           // each icon's rest angle, degrees (0 = 3 o'clock, clockwise)
        val cells = ArrayList<View>()
        var cellW = 0; var cellH = 0
        var blurNow = 0f
        var ticked = true; var crossSign = 0f
        var slot = 0; var crossedAt = 0L
        val spin: Spring = Spring(0f, 0.05f) { v ->
            // the icon lands on its slot the first time the spin crosses it (a bounce), or when a calm one stops
            if (!ticked && (v - spin.target) * crossSign < 0f) { ticked = true; Haptics.snap(this@OrbitView) }
            crossing(this, v)
            layoutRing(this)
        }.apply { onRest = { if (!ticked) { ticked = true; Haptics.snap(this@OrbitView) } } }
    }

    private val still = reducedMotion(c)
    /** Press and hold an icon: its app menu (home supplies what Add to dock and a change mean). */
    var menuActions: AppMenu.Actions? = null
    private var hiddenList: List<AppEntry> = emptyList()  // hidden apps: off the rings, still in search
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private val iconPx = look.px(54)
    private val rings = listOf(Ring(0, 8), Ring(1, 14), Ring(2, 20), Ring(3, Int.MAX_VALUE))
    private val ringsLayer = FrameLayout(c)
    private val box = EditText(c)
    private val clear = Glyph(c, look, cross = true)
    private val searchPill = LinearLayout(c)
    private val allApps = look.text("All apps", 15f, look.ink, true)
    private val results = ScrollView(c)
    private val list = LinearLayout(c)
    private val nameTag = look.text("", 13f, look.ink)

    private var apps: List<AppEntry> = emptyList()   // most used first; the rings take the front of it
    private var pending: List<AppEntry>? = null      // a newer list that waits until the orbit is shut, so nothing jumps
    private val pool get() = pending ?: apps
    private var builtVersion = -1                     // Apps.version the rings were built from (an update redraws icons)
    private var loadGen = 0; private var fetchGen = 0  // newer loads and lookups win; older answers are dropped
    private var badges: Map<String, AppBadges.Badge> = emptyMap()
    private val badgeViews = ArrayList<Triple<View, TextView, AppEntry>>()  // ring cells whose pill follows badges.json
    private var pages: List<SmartSearch.Page> = emptyList()
    private var contacts: List<SmartSearch.Contact> = emptyList()
    private var events: List<SmartSearch.Event> = emptyList()
    private var cuts: List<Cut> = emptyList()
    private var pins: List<SmartSearch.Pin> = emptyList()
    private var recent: List<String> = emptyList()
    private var shownKeys: Set<String> = emptySet()
    private var askNote: Pair<String, String>? = null  // (query, what happened) for the Ask Jharvis row
    private var askBusy = false
    private var wantIme = false
    @Volatile private var capacity = 90
    private var cx = 0f; private var cy = 0f; private var coreR = 0f
    private var bars = Insets.NONE; private var ime = 0
    private var pillBlur = 0f; private var allBlur = 0f; private var layerBlur = 0f

    /** 0 = folded into the core, 1 = open. Home's spring drives it. */
    var progress = 0f
        set(v) { field = v; applyProgress() }

    private val search: Spring = Spring(0f, 0.001f) { applySearch() }

    init {
        addView(ringsLayer, LayoutParams(-1, -1))
        for (r in rings) ringsLayer.addView(r.layer, LayoutParams(-1, -1))

        list.orientation = LinearLayout.VERTICAL
        results.apply {
            isVerticalScrollBarEnabled = false; clipToPadding = false
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(look.px(24))  // rows dissolve under the search pill
            addView(list, LayoutParams(-1, -2))
            visibility = INVISIBLE
        }
        addView(results, LayoutParams(-1, -1))

        box.apply {
            hint = "Search"; textSize = 17f
            setTextColor(look.ink); setHintTextColor(look.ink3); typeface = look.sans
            background = null; isSingleLine = true; setPadding(0, 0, 0, 0)
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_GO) { openTop(); true } else false }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { onQuery(s?.toString().orEmpty()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            })
            // an empty field with the keyboard up shows recent searches and new apps; letting go brings the rings back
            setOnFocusChangeListener { _, _ -> if (text.isNullOrBlank()) emptyState() }
        }
        clear.apply {
            contentDescription = "Clear search"; visibility = INVISIBLE
            isClickable = true; look.press(this, 0.86f); setOnClickListener { box.setText("") }
        }
        searchPill.apply {
            gravity = Gravity.CENTER_VERTICAL
            background = look.glassCard(99)
            setPadding(look.px(18), 0, 0, 0)
            addView(Glyph(c, look, cross = false), LinearLayout.LayoutParams(look.px(18), look.px(18)))
            addView(box, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = look.px(10) })
            addView(clear, LinearLayout.LayoutParams(look.px(48), look.px(48)))
            setOnClickListener { showIme() }  // a tap anywhere on the pill, not just on the text
        }
        addView(searchPill, LayoutParams(-1, look.px(50), Gravity.TOP))

        allApps.apply {
            gravity = Gravity.CENTER
            setPadding(look.px(22), 0, look.px(22), 0)
            background = look.glassCard(99)
            isClickable = true; look.press(this, 0.94f)
            setOnClickListener { v -> context.startActivity(Intent(context, DrawerActivity::class.java), Apps.options(v)) }  // in Home's task: launchFrom's NEW_TASK joins the dashboard's, so Back lands there
        }
        addView(allApps, LayoutParams(-2, look.px(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        nameTag.apply {
            background = look.glassCard(99)
            setPadding(look.px(12), look.px(6), look.px(12), look.px(6))
            visibility = INVISIBLE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO  // the icon already says its name
        }
        addView(nameTag, LayoutParams(-2, -2))

        visibility = INVISIBLE
        isFocusableInTouchMode = true  // somewhere for focus to go when the search field lets go, so it can't grab it back
        setOnApplyWindowInsetsListener { _, i -> useInsets(i); i }
        // the keyboard slides: the result list's bottom follows it frame by frame
        setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onProgress(i: WindowInsets, running: MutableList<WindowInsetsAnimation>): WindowInsets { useInsets(i); return i }
            override fun onEnd(a: WindowInsetsAnimation) { rootWindowInsets?.let { useInsets(it) } }
        })
    }

    /** An app was installed, removed, updated or paused: re-rank (only that package was re-read). */
    private val appsChanged: () -> Unit = { load() }

    /** :core wrote new counts: read them off the main thread, then update the pills in place. */
    private val badgeRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Loader.run {
                val b = AppBadges.read()
                Loader.main.post {
                    badges = b; showBadges()
                    if (search.target > 0f) render(box.text.toString().trim())
                }
            }
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
        removeCallbacks(fetchMore)
        loadGen++; fetchGen++  // answers still on their way are dropped
        super.onDetachedFromWindow()
    }

    private fun useInsets(i: WindowInsets) {
        val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val k = i.getInsets(WindowInsets.Type.ime()).bottom
        if (b == bars && k == ime) return
        bars = b; ime = k
        (searchPill.layoutParams as LayoutParams).apply { topMargin = b.top + look.px(8); leftMargin = b.left + look.px(16); rightMargin = b.right + look.px(16) }
        (allApps.layoutParams as LayoutParams).bottomMargin = b.bottom + look.px(16)
        results.setPadding(b.left + look.px(16), b.top + look.px(70), b.right + look.px(16), max(k, b.bottom) + look.px(16))
        requestLayout()
    }

    // ── Geometry and building the rings ─────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f; cy = h / 2f
        val u = min(w, h) / 2f
        // inner ring clears the core and its labels; each gap leaves room for an icon (and the inner labels)
        val r1 = max(look.px(100).toFloat(), 0.48f * u)
        val r2 = r1 + max(look.px(74).toFloat(), 0.36f * u)
        val r3 = r2 + max(look.px(62).toFloat(), 0.30f * u)
        val radii = floatArrayOf(r1, r2, r3, r3 + (r3 - r2))
        for (r in rings) {
            r.r = radii[r.index]
            r.slots = OrbitMath.slots(r.r, look.px(64).toFloat(), r.cap)  // 54 dp icons, 10 dp apart at least
            r.pitch = 360f / r.slots
        }
        coreR = r1 - look.px(46)
        capacity = rings.sumOf { it.slots }
        build()
    }

    private fun build() {
        if (width == 0) return
        badgeViews.clear()
        val counts = OrbitMath.fill(apps.size, IntArray(rings.size) { rings[it].slots })
        var at = 0
        for (ring in rings) {
            ring.layer.removeAllViews(); ring.cells.clear()
            val k = counts[ring.index]
            val offset = -90f + if (ring.index % 2 == 1) ring.pitch / 2 else 0f  // neighbouring rings sit between each other
            // a full ring runs clockwise from 12 o'clock; a part-filled one gathers across the top, where it's seen
            ring.base = FloatArray(k) { j -> offset + (if (k == ring.slots) j.toFloat() else j - (k - 1) / 2f) * ring.pitch }
            val labelled = ring.index == 0
            ring.cellW = if (labelled) look.px(76) else iconPx
            ring.cellH = if (labelled) iconPx + look.px(20) else iconPx
            for (j in 0 until k) {
                val cell = makeCell(apps[at + j], labelled, ring.cellW)
                ring.layer.addView(cell, LayoutParams(ring.cellW, ring.cellH))
                ring.cells += cell
            }
            at += k
            ring.ticked = true; ring.spin.snap(0f); ring.slot = 0
        }
        applyProgress()
    }

    /** An icon with its count pill at the top right corner (the pill may hang past the icon's edge). */
    private fun iconWithPill(a: AppEntry, size: Int): Triple<FrameLayout, ImageView, TextView> {
        val img = ImageView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        Icons.cached(a.key)?.let { img.setImageBitmap(it) } ?: Icons.into(img, a.key)
        if (a.suspended) img.alpha = 0.5f  // paused (Digital Wellbeing or work apps off): the icon itself is greyed too
        val pill = AppBadges.pill(look)
        val frame = FrameLayout(context).apply {
            clipChildren = false
            addView(img, LayoutParams(size, size))
            addView(pill, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = -look.px(5); rightMargin = -look.px(7) })
        }
        return Triple(frame, img, pill)
    }

    private fun makeCell(app: AppEntry, labelled: Boolean, w: Int): View {
        val (frame, img, pill) = iconWithPill(app, iconPx)
        val cell: View = if (!labelled) frame else LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            addView(frame, LinearLayout.LayoutParams(iconPx, iconPx))
            addView(look.text(app.label, 12f, look.ink).apply {
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(look.px(2), look.px(4), look.px(2), 0)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, -2))
        }
        badgeViews += Triple(cell, pill, app)
        showBadge(cell, pill, app)
        return cell.apply {
            pivotX = w / 2f; pivotY = iconPx / 2f  // scale around the icon, not the label
            isClickable = true; look.press(this, 0.88f)
            setOnClickListener { launch(img, app) }
            setOnLongClickListener { menuActions?.let { a -> AppMenu.show(img, app.key, look, a) } ?: showTag(this, app.label); true }
            visibility = INVISIBLE
        }
    }

    private fun count(a: AppEntry) = if (a.main) badges[a.pkg]?.count ?: 0 else 0  // badges.json counts the main profile

    private fun showBadge(cell: View, pill: TextView, a: AppEntry, newWord: Boolean = false) {
        val n = count(a)
        pill.text = if (n > 0) AppBadges.word(n) else "New"
        pill.visibility = if (n > 0 || newWord) VISIBLE else GONE
        cell.contentDescription = a.label + when {
            n == 1 -> ", 1 notification"; n > 1 -> ", $n notifications"; newWord -> ", new"; else -> ""
        } + if (a.suspended) ", paused" else ""
    }

    private fun showBadges() { for ((cell, pill, a) in badgeViews) showBadge(cell, pill, a) }

    /** Through the lock prompt when the app is locked. Plain packages go to home (it bumps LaunchCounts and plays its
     *  launch); other profiles and second launcher activities open here, since home's launch only knows packages. */
    private fun launch(from: View, e: AppEntry) {
        rememberQuery()
        val go = { onLaunch(from, e.key) }
        val act = context as? Activity
        if (act == null) go() else LockedApps.launch(act, e.key, e.label, go)
    }

    // ── Frames: fly-out, spin, search depth ─────────────────────────────────────────────────────────

    private fun applyProgress() {
        val p = progress
        val shown = p > 0.001f
        if (!shown) pending?.let { apps = it; pending = null; build(); return }
        if (shown && wantIme) { wantIme = false; showIme() }  // a hidden field can't take focus: wait until it shows
        for (r in rings) layoutRing(r)
        // the pills arrive once the rings are mostly out: same materialize in and out
        val a = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
        pillBlur = materialize(searchPill, a, -1, pillBlur)
        allBlur = materialize(allApps, a * (1 - search.value.coerceIn(0f, 1f)), 1, allBlur)
    }

    /** Scale 0.92 to 1, alpha and blur together, drifting in from the side it lives on. */
    private fun materialize(v: View, a: Float, side: Int, last: Float): Float {
        v.alpha = a
        val s = if (still) 1f else 0.92f + 0.08f * a; v.scaleX = s; v.scaleY = s  // Remove animations: a cross-fade only
        v.translationY = if (still) 0f else side * look.px(12) * (1 - a)
        val vis = if (a > 0.02f) VISIBLE else INVISIBLE
        if (v.visibility != vis) v.visibility = vis
        return if (still) 0f else setBlur(v, look.px(8) * (1 - a), last)
    }

    /** Blur only when the radius really changed (each change builds a RenderEffect). */
    private fun setBlur(v: View, r: Float, last: Float): Float {
        val q = (r * 2).roundToInt() / 2f
        if (q != last) v.blur(q)
        return q
    }

    /** A light tick each time a spinning ring (under the finger or carried by a flick) passes a slot, at most every 40 ms. */
    private fun crossing(ring: Ring, v: Float) {
        val s = floor(v / ring.pitch).toInt()
        if (s == ring.slot) return
        ring.slot = s
        val now = SystemClock.uptimeMillis()
        if ((spinning || !ring.ticked) && now - ring.crossedAt >= 40) { ring.crossedAt = now; Haptics.crossing(this) }
    }

    private fun layoutRing(ring: Ring) {
        val n = ring.cells.size
        if (n == 0) return
        val p = progress
        val delay = 0.07f * ring.index
        val ang = ring.spin.value
        val w = width.toFloat(); val h = height.toFloat()
        val halfW = ring.cellW / 2f; val halfIcon = iconPx / 2f
        for (j in 0 until n) {
            // each icon leaves the core along its own angle: inner ring first, then a slight sweep round the ring
            val t = if (still) 1f else ((p - delay - 0.05f * j / n) / 0.74f).coerceIn(0f, 1f)
            val rad = Math.toRadians((ring.base[j] + ang).toDouble())
            val x = cx + ring.r * t * cos(rad).toFloat()
            val y = cy + ring.r * t * sin(rad).toFloat()
            val v = ring.cells[j]
            v.translationX = x - halfW; v.translationY = y - halfIcon
            val s = 0.5f + 0.5f * t; v.scaleX = s; v.scaleY = s
            v.alpha = if (still) p else min(1f, t * 1.6f)
            // drawn only where it can be seen (the outer ring runs off the screen's sides)
            val on = p > 0.001f && x + halfW > 0 && x - halfW < w && y - halfIcon + ring.cellH > 0 && y - halfIcon < h
            val vis = if (on) VISIBLE else INVISIBLE
            if (v.visibility != vis) v.visibility = vis
        }
        if (!still) {
            val t = ((p - delay - 0.025f) / 0.74f).coerceIn(0f, 1f)
            ring.blurNow = setBlur(ring.layer, look.px(8) * (1 - t), ring.blurNow)
        }
    }

    /** Searching: the rings blur and recede behind the results, like a sheet pushes home back. */
    private fun applySearch() {
        val s = search.value.coerceIn(0f, 1.05f)
        val k = 1 - 0.08f * s
        ringsLayer.scaleX = k; ringsLayer.scaleY = k
        ringsLayer.alpha = (1 - 0.75f * s).coerceIn(0f, 1f)
        if (!still) layerBlur = setBlur(ringsLayer, look.px(28) * s.coerceIn(0f, 1f), layerBlur)
        results.alpha = s.coerceIn(0f, 1f)
        results.translationY = (1 - s) * look.px(16)
        val vis = if (s > 0.01f) VISIBLE else INVISIBLE
        if (results.visibility != vis) results.visibility = vis
        val a = ((progress - 0.35f) / 0.65f).coerceIn(0f, 1f)
        allBlur = materialize(allApps, a * (1 - s.coerceIn(0f, 1f)), 1, allBlur)
    }

    // ── Touch: spin a ring, tap the core to close ───────────────────────────────────────────────────

    private var grab: Ring? = null
    private var spinning = false
    private var centreTap = false
    private var pid = -1
    private var downX = 0f; private var downY = 0f
    private var startAngle = 0f; private var lastTouch = 0f; private var turned = 0f
    private var vt: VelocityTracker? = null

    /** Which ring a point is on, if the orbit is open and not searching. Home can use it to leave ring drags alone. */
    fun onRing(x: Float, y: Float): Boolean = ringAt(x, y) != null

    private fun ringAt(x: Float, y: Float): Ring? {
        if (progress < 0.95f || search.target > 0f) return null
        val d = hypot(x - cx, y - cy)
        if (d < coreR) return null
        var best: Ring? = null; var bestD = Float.MAX_VALUE
        for (r in rings) if (r.cells.isNotEmpty() && abs(d - r.r) < bestD) { bestD = abs(d - r.r); best = r }
        return best?.takeIf { bestD < look.px(40) }
    }

    private fun angleAt(x: Float, y: Float) = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()

    override fun onInterceptTouchEvent(e: MotionEvent) = track(e)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (track(e)) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                centreTap = progress > 0.5f && search.target == 0f && hypot(e.x - cx, e.y - cy) < coreR
                if (centreTap) tick()
                return centreTap || grab != null  // empty space stays home's (a drag down there closes the orbit)
            }
            MotionEvent.ACTION_MOVE -> if (centreTap && hypot(e.x - downX, e.y - downY) > slop) centreTap = false
            MotionEvent.ACTION_UP -> if (centreTap) { centreTap = false; onClose() }
            MotionEvent.ACTION_CANCEL -> centreTap = false
        }
        return true
    }

    private fun track(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; spinning = false; pid = e.getPointerId(0)
                vt?.recycle(); vt = VelocityTracker.obtain().apply { addMovement(e) }
                val g = ringAt(e.x, e.y) ?: run { grab = null; return false }
                grab = g
                parent?.requestDisallowInterceptTouchEvent(true)  // a drag on a ring is the ring's, never home's swipe
                if (abs(g.spin.value - g.spin.target) > 0.05f || abs(g.spin.velocity) > 1f) { begin(g); return true }  // caught mid-spin
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val g = grab ?: return false
                val i = e.findPointerIndex(pid).takeIf { it >= 0 } ?: return spinning
                if (!spinning && hypot(e.getX(i) - downX, e.getY(i) - downY) > slop) begin(g)
                if (spinning) { turn(e.getX(i), e.getY(i)); return true }
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == pid) return finish(e, e.actionIndex)
            MotionEvent.ACTION_UP -> return finish(e, e.findPointerIndex(pid).coerceAtLeast(0))
            MotionEvent.ACTION_CANCEL -> return finish(null, 0)
        }
        return spinning
    }

    private fun begin(g: Ring) {
        spinning = true; hideTag()
        g.spin.stop(); g.ticked = true
        startAngle = g.spin.value; lastTouch = angleAt(downX, downY); turned = 0f
    }

    /** 1:1 with the finger, from where it grabbed: only the change in angle moves the ring. */
    private fun turn(x: Float, y: Float) {
        val a = angleAt(x, y)
        turned += OrbitMath.wrap(a - lastTouch); lastTouch = a
        grab?.spin?.snap(startAngle + turned)
    }

    private fun finish(e: MotionEvent?, i: Int): Boolean {
        val was = spinning
        val g = grab
        if (spinning && g != null) {
            var w = 0f
            if (e != null) {
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000)
                val dx = e.getX(i) - cx; val dy = e.getY(i) - cy
                val vx = vt?.getXVelocity(pid) ?: 0f; val vy = vt?.getYVelocity(pid) ?: 0f
                w = Math.toDegrees(((dx * vy - dy * vx) / max(dx * dx + dy * dy, 1f)).toDouble()).toFloat()  // degrees a second
            }
            if (still) { g.spin.snap(OrbitMath.snap(g.spin.value, g.pitch)); Haptics.snap(this) }
            else {
                val to = OrbitMath.snap(g.spin.value + project(w, 0.995f), g.pitch)  // land where the flick was heading
                val flick = abs(w) > 90f
                g.ticked = false; g.crossSign = sign(g.spin.value - to)
                g.spin.to(to, response = if (flick) 0.6f else 0.45f, damping = if (flick) 0.8f else 1f, velocity = w)
            }
        }
        spinning = false; grab = null
        vt?.recycle(); vt = null
        return was
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) hideTag()
        return super.dispatchTouchEvent(e)
    }

    /** Long-press: the app's name in a small glass tag above its icon, gone when the finger lifts. */
    private fun showTag(cell: View, label: String) {
        nameTag.text = label
        nameTag.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val tw = nameTag.measuredWidth; val th = nameTag.measuredHeight
        nameTag.translationX = (cell.translationX + cell.width / 2f - tw / 2f).coerceIn(look.px(8).toFloat(), (width - tw - look.px(8)).toFloat().coerceAtLeast(0f))
        val above = cell.translationY - th - look.px(8)
        nameTag.translationY = if (above > bars.top) above else cell.translationY + cell.height + look.px(8)
        nameTag.pivotX = tw / 2f; nameTag.pivotY = th.toFloat()
        nameTag.visibility = VISIBLE
        nameTag.animate().cancel()
        if (still) { nameTag.alpha = 1f; return }
        nameTag.alpha = 0f; nameTag.scaleX = 0.92f; nameTag.scaleY = 0.92f
        nameTag.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(SpringCurve(1f)).start()
    }

    private fun hideTag() {
        if (nameTag.visibility != VISIBLE) return
        nameTag.animate().cancel()
        if (still) { nameTag.visibility = INVISIBLE; return }
        nameTag.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(200).setInterpolator(SpringCurve(1f))
            .withEndAction { nameTag.visibility = INVISIBLE }.start()
    }

    // ── Search ──────────────────────────────────────────────────────────────────────────────────────

    private fun onQuery(raw: String) {
        clear.visibility = if (raw.isEmpty()) INVISIBLE else VISIBLE
        val q = raw.trim()
        removeCallbacks(fetchMore)
        if (q.isEmpty()) { contacts = emptyList(); events = emptyList(); askNote = null; emptyState(); return }
        setSearching(true)
        render(q)
        postDelayed(fetchMore, 120)  // contacts and calendar wait for a pause in typing
    }

    /** Field empty: with the keyboard up it shows new apps and recent searches (if any); otherwise the rings. */
    private fun emptyState() {
        setSearching(box.hasFocus() && (recent.isNotEmpty() || newApps().isNotEmpty()))
        render("")
    }

    private fun newApps(): List<AppEntry> {
        val now = System.currentTimeMillis()
        return pool.filter { isNew(it, now) }.sortedByDescending { it.installed }.take(4)
    }

    private fun isNew(a: AppEntry, now: Long) = now - a.installed in 0 until 3 * 86_400_000L

    private val fetchMore = Runnable {
        val q = box.text.toString().trim()
        if (q.isEmpty()) return@Runnable
        val app = context.applicationContext
        val g = ++fetchGen
        Loader.first {
            val k = SmartSearch.contacts(app, q)
            val ev = SmartSearch.events(app, q)
            Loader.main.post { if (g == fetchGen && box.text.toString().trim() == q) { contacts = k; events = ev; render(q) } }  // drop answers for an old query
        }
    }

    private fun setSearching(on: Boolean) {
        val to = if (on) 1f else 0f
        if (search.target == to) return
        if (still) search.snap(to) else search.to(to, response = 0.45f, damping = 1f)
        backWanted(on)
    }

    /** Rebuilds the rows; only rows that weren't there a keystroke ago spring in. */
    private fun render(q: String) {
        list.removeAllViews()
        val now = HashSet<String>()
        var fresh = 0
        fun arriving(v: View, key: String) { now += key; if (key !in shownKeys) arrive(v, fresh++) }
        fun header(t: String) = look.text(t, 13f, look.ink3).apply { setPadding(look.px(16), look.px(10), look.px(16), look.px(2)) }
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = look.glassCard(22)
            setPadding(0, look.px(6), 0, look.px(6))
        }
        fun iconRow(apps: List<AppEntry>, tag: String, markNew: Boolean) {
            if (apps.isEmpty()) return
            list.addView(LinearLayout(context).apply {
                for (a in apps) {
                    val cell = resultApp(a, markNew)
                    addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
                    arriving(cell, tag + a.key)
                }
                repeat(4 - apps.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = look.px(14) })
        }

        if (q.isEmpty()) {
            if (search.target == 0f) { shownKeys = now; return }
            val added = newApps()
            if (added.isNotEmpty()) list.addView(header("Recently installed").apply { setPadding(look.px(4), 0, 0, look.px(6)) }.also { arriving(it, "h:n") })
            iconRow(added, "n:", true)
            if (recent.isNotEmpty()) {
                inner.addView(header("Recent searches").also { arriving(it, "h:r") })
                for (r in recent) {
                    val row = row(r, null, null) { box.setText(r); box.setSelection(box.text.length) }
                    inner.addView(row); arriving(row, "r:$r")
                }
                val wipe = row("Clear recent searches", null, null) {
                    recent = emptyList(); Loader.run { SmartSearch.forget(context.applicationContext) }; emptyState()
                }.apply { (getChildAt(0) as LinearLayout).getChildAt(0).let { (it as TextView).setTextColor(look.ink3) } }
                inner.addView(wipe); arriving(wipe, "r:clear")
                list.addView(sheet(inner), LinearLayout.LayoutParams(-1, -2))
            }
            shownKeys = now
            return
        }

        val hits = SmartSearch.rank(q, pool + hiddenList) { it.label }.take(4)  // hidden apps are found only by name
        iconRow(hits, "a:", false)

        // one floating sheet (double bezel) for everything else
        SmartSearch.quick(q)?.let { k ->
            val row = quickRow(k)
            inner.addView(row); arriving(row, "q:$k")
        }
        val found = shortcutHits(q, hits.firstOrNull())
        if (found.isNotEmpty()) {
            inner.addView(header("Shortcuts").also { arriving(it, "h:k") })
            for (h in found) { inner.addView(h.second); arriving(h.second, h.first) }
        }
        if (contacts.isNotEmpty()) {
            inner.addView(header("Contacts").also { arriving(it, "h:c") })
            for (k in contacts) {
                val call = look.text("Call", 15f, look.ink, true).apply {
                    gravity = Gravity.CENTER; minHeight = look.px(48); minWidth = look.px(64)
                    setPadding(look.px(16), 0, look.px(16), 0)
                    background = look.round(look.glass, 14, look.glassEdge)
                    contentDescription = "Call ${k.name}"
                    isClickable = true; look.press(this, 0.9f)
                    setOnClickListener { v -> rememberQuery(); context.launchFrom(v, SmartSearch.dial(k)) }  // opens the dialler, never calls by itself
                }
                val row = row(k.name, k.number.ifEmpty { null }, call) { v -> rememberQuery(); context.launchFrom(v, SmartSearch.openContact(k)) }
                inner.addView(row); arriving(row, "c:${k.id}")
            }
        }
        if (events.isNotEmpty()) {
            inner.addView(header("Calendar").also { arriving(it, "h:e") })
            for (e in events) {
                val row = row(e.title, e.whenText, null) { v -> rememberQuery(); context.launchFrom(v, SmartSearch.openEvent(e)) }
                inner.addView(row); arriving(row, "e:${e.id}:${e.begin}")
            }
        }
        val pagesFound = SmartSearch.matchPages(q, pages).take(3)
        if (pagesFound.isNotEmpty()) {
            inner.addView(header("Settings").also { arriving(it, "h:s") })
            for (pg in pagesFound) {
                val row = row(pg.label, null, null) { v -> rememberQuery(); context.launchFrom(v, Intent(pg.action)) }
                inner.addView(row); arriving(row, "s:" + pg.action)
            }
        }
        if (SmartSearch.score(q, "Hidden apps") in 0..1 || SmartSearch.score(q, "Locked apps") in 0..1) {
            val row = row("Hidden and locked apps", "Asks for your screen lock", null) { v ->
                context.startActivity(Intent(context, HiddenAppsActivity::class.java), Apps.options(v))  // Home's task, as All apps
            }
            inner.addView(row); arriving(row, "hidden")
        }
        val note = askNote?.takeIf { it.first == q }?.second
        val ask = row("Ask Jharvis: “$q”", note, null) { v -> ask(q, v) }
        inner.addView(ask); arriving(ask, "ask")

        list.addView(sheet(inner), LinearLayout.LayoutParams(-1, -2))
        shownKeys = now
    }

    /** The outer shell of the double bezel around a frosted core. */
    private fun sheet(inner: View) = FrameLayout(context).apply {
        background = look.shell()
        setPadding(look.px(6), look.px(6), look.px(6), look.px(6))
        addView(inner, LayoutParams(-1, -2))
    }

    private fun quickRow(k: SmartSearch.Quick): View = when (k) {
        is SmartSearch.Quick.Timer -> row("Start a ${SmartSearch.timerWords(k.seconds)} timer", null, null) { v -> startTimer(v, k.seconds) }
        is SmartSearch.Quick.Answer -> row(k.text, "Tap to copy", null) { copy(k.text) }.apply { contentDescription = "Equals ${k.text}. Tap to copy." }
        is SmartSearch.Quick.SetMode -> row(if (k.mode == Mode.OFF) "Turn modes off" else "Turn on ${k.mode.label}", null, null) { setMode(k.mode) }
    }

    private fun startTimer(from: View, seconds: Int) {
        rememberQuery()
        try { context.startActivity(SmartSearch.timer(seconds).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), Apps.options(from)) }
        catch (_: SecurityException) {  // needs SET_ALARM in the manifest: open the clock's timers instead
            try { context.startActivity(Intent(AlarmClock.ACTION_SHOW_TIMERS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { toast("Couldn't start the timer") }
        } catch (_: Exception) { toast("Couldn't start the timer") }
    }

    private fun copy(text: String) {
        rememberQuery()
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Answer", text))
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    private fun setMode(m: Mode) {
        rememberQuery()
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        Loader.run {
            val ok = try { Modes.set(m); true } catch (_: Exception) { false }
            Loader.main.post {
                if (!ok) { toast("Couldn't change the mode"); return@post }
                toast(if (m == Mode.OFF) "Modes off" else "${m.label} on")
                menuActions?.changed()  // the dock follows the mode
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()

    /** Pinned shortcuts and app shortcuts whose names match, then the top app's own shortcuts; four at most. */
    private fun shortcutHits(q: String, top: AppEntry?): List<Pair<String, View>> {
        val hidden = hiddenList.mapTo(HashSet()) { it.pkg }
        val out = LinkedHashMap<String, View>()
        val me = android.os.Process.myUserHandle()
        val key = SmartSearch.key(q)
        for (p in pins) if (out.size < 4 && SmartSearch.scoreKey(key, p.label) in 0..2) out.getOrPut("p:${p.pkg}/${p.id}") { cutRow(p.pkg, p.id, p.label, me, p) }
        val mine = if (top != null && SmartSearch.scoreKey(key, top.label) == 0) cuts.filter { it.pkg == top.pkg && it.user == top.user }.take(3) else emptyList()
        for (k in mine + cuts.filter { it.pkg !in hidden && SmartSearch.scoreKey(key, it.label) in 0..2 }) {
            if (out.size >= 4) break
            out.getOrPut("k:${k.pkg}/${k.id}/${k.user.hashCode()}") { cutRow(k.pkg, k.id, k.label, k.user, null) }
        }
        return out.entries.map { it.key to it.value }
    }

    private fun cutRow(pkg: String, id: String, label: String, user: UserHandle, pin: SmartSearch.Pin?): View {
        val owner = pool.firstOrNull { it.pkg == pkg && it.user == user } ?: hiddenList.firstOrNull { it.pkg == pkg && it.user == user }
        val img = ImageView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        owner?.let { Icons.into(img, it.key) }
        return row(label, owner?.label, null, img) { v -> startCut(img, pkg, id, user, label) }.apply {
            contentDescription = label + (owner?.let { ", ${it.label}" } ?: "")
            if (pin != null) setOnLongClickListener {
                AppMenu.showPin(img, pin, look) { pins = pins.filterNot { it.pkg == pin.pkg && it.id == pin.id }; render(box.text.toString().trim()) }
                true
            }
        }
    }

    private fun startCut(from: View, pkg: String, id: String, user: UserHandle, label: String) {
        rememberQuery()
        val go = {
            try { context.getSystemService(LauncherApps::class.java)?.startShortcut(pkg, id, Apps.bounds(from), Apps.options(from), user); Unit }
            catch (_: Exception) { toast("Couldn't open that shortcut") }  // disabled, or the app went, since search read it
        }
        val act = context as? Activity
        if (act != null && LockedApps.anyLocked(context, pkg)) LockedApps.ask(act, "Open $label", go) else go()
    }

    /** Something was opened from a search: keep the query among the last eight. */
    private fun rememberQuery() {
        val q = box.text.toString().trim()
        if (q.isEmpty()) return
        recent = SmartSearch.remembered(recent, q)
        val app = context.applicationContext
        Loader.run { SmartSearch.remember(app, q) }
    }

    private fun row(title: String, sub: String?, trailing: View?, leading: View? = null, onTap: (View) -> Unit) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = look.px(56)
        setPadding(look.px(16), look.px(6), look.px(10), look.px(6))
        leading?.let { addView(it, LinearLayout.LayoutParams(look.px(32), look.px(32)).apply { rightMargin = look.px(12) }) }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(look.text(title, 16f, look.ink).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            sub?.let { addView(look.text(it, 13f, look.ink3).apply { maxLines = 2 }) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        trailing?.let { addView(it, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = look.px(8) }) }
        isClickable = true; look.press(this, 0.97f)
        setOnClickListener { onTap(this) }
    }

    private fun resultApp(a: AppEntry, markNew: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        setPadding(0, look.px(6), 0, look.px(4))
        val (frame, img, pill) = iconWithPill(a, look.px(56))
        addView(frame, LinearLayout.LayoutParams(look.px(56), look.px(56)))
        addView(look.text(a.label, 12f, look.ink).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(look.px(2), look.px(6), look.px(2), 0)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, -2))
        showBadge(this, pill, a, markNew || isNew(a, System.currentTimeMillis()))
        isClickable = true; look.press(this, 0.9f)
        setOnClickListener { launch(img, a) }
        setOnLongClickListener { menuActions?.let { m -> AppMenu.show(img, a.key, look, m) }; menuActions != null }
    }

    /** A row materializes: rises a little, scale 0.96 to 1, alpha and blur together, one after another. */
    private fun arrive(v: View, i: Int) {
        if (still) return
        val b = look.px(8).toFloat()
        v.alpha = 0f; v.translationY = look.px(18).toFloat(); v.scaleX = 0.96f; v.scaleY = 0.96f; v.blur(b)
        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(min(i, 6) * 35L).setDuration(460).setInterpolator(SpringCurve(1f))
            .setUpdateListener { a -> v.blur(b * (1 - a.animatedFraction)) }
            .withEndAction { v.blur(0f) }.start()
    }

    /** Go on the keyboard: a timer or mode first, then the best app, the first person, the first setting, else ask. */
    private fun openTop() {
        val q = box.text.toString().trim()
        if (q.isEmpty()) return
        when (val k = SmartSearch.quick(q)) {
            is SmartSearch.Quick.Timer -> { startTimer(box, k.seconds); return }
            is SmartSearch.Quick.SetMode -> { setMode(k.mode); return }
            is SmartSearch.Quick.Answer -> { copy(k.text); return }
            null -> {}
        }
        SmartSearch.rank(q, pool) { it.label }.firstOrNull()?.let { launch(box, it); return }
        contacts.firstOrNull()?.let { rememberQuery(); context.launchFrom(box, SmartSearch.openContact(it)); return }
        SmartSearch.matchPages(q, pages).firstOrNull()?.let { rememberQuery(); context.launchFrom(box, Intent(it.action)); return }
        ask(q, box)
    }

    /** Queued (so it survives the PC being off), sent now if it can be; the dashboard shows the reply. */
    private fun ask(q: String, from: View) {
        if (askBusy) return
        askBusy = true
        rememberQuery()
        from.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        Loader.net {
            val online: Boolean? = try { SmartSearch.ask(q) } catch (_: Exception) { null }
            Loader.main.post {
                askBusy = false
                if (!isAttachedToWindow) return@post
                if (online == true) context.launchFrom(from, Intent(context, MainActivity::class.java).setData(Uri.parse("${Link.ORIGIN}/#/home")))
                else {
                    askNote = q to if (online == null) "Couldn't save that. Try again." else "Saved. Jharvis gets it when the PC is back."
                    if (box.text.toString().trim() == q) render(q)
                }
            }
        }
    }

    private fun showIme() {
        box.requestFocus()
        box.post { box.windowInsetsController?.show(WindowInsets.Type.ime()) }
        // One UI sometimes ignores show() before the window has focus: ask once more, the old way
        box.postDelayed({
            if (box.hasFocus() && rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true)
                context.getSystemService(InputMethodManager::class.java)?.showSoftInput(box, 0)
        }, 160)
    }

    private fun hideIme() {
        wantIme = false
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(box.windowToken, 0)
        if (!requestFocus()) box.clearFocus()
    }

    // Back while searching clears the search and lets the field go (the rings come back); home's own Back closes the orbit
    private var backOn = false
    private fun backOut() { box.setText(""); hideIme(); emptyState() }
    private val back: OnBackInvokedCallback = if (Build.VERSION.SDK_INT >= 34) object : OnBackAnimationCallback {
        override fun onBackProgressed(e: BackEvent) { if (!still) search.snap(1f - 0.3f * e.progress) }  // results ease back with the gesture
        override fun onBackCancelled() { if (!still) search.to(1f, response = 0.35f, damping = 1f) }
        override fun onBackInvoked() = backOut()
    } else OnBackInvokedCallback { backOut() }

    private fun backWanted(on: Boolean) {
        if (on == backOn) return
        val d = findOnBackInvokedDispatcher() ?: return
        if (on) d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT + 1, back) else d.unregisterOnBackInvokedCallback(back)
        backOn = on
    }

    // ── The interface home uses ─────────────────────────────────────────────────────────────────────

    /** Re-rank apps and re-read hidden apps, badges, pins and recent searches, all on the loader thread. The full app
     *  list is read once per process and then kept live by the LauncherApps callback, so this is cheap on every
     *  resume. A changed order waits until the orbit is shut. */
    fun load() {
        val app = context.applicationContext
        val cap = capacity
        val g = ++loadGen
        val needPages = pages.isEmpty()
        Loader.run {
            Icons.sync(app)
            val all = Apps.get(app)
            val ver = Apps.version
            val hidden = HiddenApps.all(app)
            val byKey = all.associateBy { it.key }
            val dock = dockApps(app).filter { it in byKey && it !in hidden }
            val order = LaunchCounts.ranked(app, dock + all.map { it.key }.filter { it !in dock && it !in hidden })
            val got = order.mapNotNull { byKey[it] }
            val hid = all.filter { it.key in hidden }
            for (e in got.take(cap)) Icons.load(app, e)  // the rings' icons are drawn before the rings are laid out
            val pg = if (needPages) SmartSearch.resolvable(app) else null
            val b = AppBadges.read()
            val k = Apps.shortcuts(app)
            val pn = SmartSearch.pins(app)
            val rq = SmartSearch.recent(app)
            SmartSearch.prepare(all.map { it.label } + k.map { it.label } + pn.map { it.label })  // so typing only compares
            Loader.main.post {
                if (g != loadGen) return@post
                hiddenList = hid; badges = b; cuts = k; pins = pn; recent = rq
                if (pg != null) pages = pg
                val cur = pool
                val same = ver == builtVersion && cur.size == got.size && cur.indices.all { cur[it].key == got[it].key }
                if (!same) {
                    builtVersion = ver
                    if (progress <= 0.001f) { apps = got; pending = null; build() } else pending = got
                }
                showBadges()
            }
            for (e in got.drop(cap) + hid) Loader.run { Icons.load(app, e) }  // the rest, one job each, so a menu or lookup can cut in
        }
    }

    /** Called once as an open starts: anything that arrived while it was shut is laid out before the icons fly. */
    fun opening() {
        pending?.let { apps = it; pending = null; build() }
        hideTag()
    }

    /** Open with the keyboard up in the search field (pull down on home). */
    fun openSearch() { opening(); if (progress > 0.001f) showIme() else wantIme = true }

    /** Clear search, rings back to rest. */
    fun reset() {
        removeCallbacks(fetchMore)
        box.setText("")
        hideIme(); hideTag()
        search.snap(0f); backWanted(false)
        contacts = emptyList(); events = emptyList(); askNote = null
        results.scrollTo(0, 0)
        for (r in rings) { r.ticked = true; r.spin.snap(0f); r.slot = 0 }
    }

    /** The dock's apps seed the inner ring before there is any launch history. */
    private fun dockApps(c: Context): List<String> {
        val prefs = c.getSharedPreferences("aos", Context.MODE_PRIVATE)
        val pm = c.packageManager
        val defaults = listOf(Intent(Intent.ACTION_DIAL),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        return defaults.indices.mapNotNull { s ->
            prefs.getString("dock$s", null) ?: try { pm.resolveActivity(defaults[s], 0)?.activityInfo?.packageName?.takeIf { it != "android" } } catch (_: Exception) { null }
        }.distinct()
    }
}

/** Thin line glyphs for the search pill: a magnifier, or the clear cross. Paths built once per size. */
private class Glyph(c: Context, look: Look, private val cross: Boolean) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.75f * c.resources.displayMetrics.density; color = look.ink3
    }
    private val path = Path()
    private val d = c.resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        path.reset()
        val mx = w / 2f; val my = h / 2f
        if (cross) {
            val a = 5f * d
            path.moveTo(mx - a, my - a); path.lineTo(mx + a, my + a)
            path.moveTo(mx + a, my - a); path.lineTo(mx - a, my + a)
        } else {
            val s = min(w, h).toFloat()
            path.addCircle(mx - s * 0.08f, my - s * 0.08f, s * 0.32f, Path.Direction.CW)
            path.moveTo(mx + s * 0.16f, my + s * 0.16f); path.lineTo(mx + s * 0.44f, my + s * 0.44f)
        }
    }

    override fun onDraw(canvas: Canvas) { canvas.drawPath(path, paint) }
}
