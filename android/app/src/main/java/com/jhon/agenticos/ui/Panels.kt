package com.jhon.agenticos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.jhon.agenticos.core.Link
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** "Needs you" as Jharvis's liquid-glass panels (web/src/pages/Home.tsx Glass, index.css .liquid-glass), stacked on
 *  the Z axis under the core like an iPhone notification stack: newest on top, up to three visible with depth. Tap
 *  the stack to fan it into a list, tap again (or outside, or Back) to stack it. Swipe a closable panel sideways to
 *  close it. New panels materialize in, gone ones dissolve out the same way, unchanged ones never move on a refresh. */
class PanelStack(c: Context, private val look: Look, private val host: Host) : FrameLayout(c) {
    interface Host {
        fun approve(id: String, yes: Boolean)
        fun closePanel(id: String)
        fun open(link: String, from: View)
        fun openLog(from: View)
    }

    private val still = reducedMotion(c)
    private val room = look.px(22)      // below each glass plate, for its shadow
    private val peek = look.px(10).toFloat()
    private val gap = look.px(12).toFloat()

    private var order = ArrayList<Card>()               // display order, 0 = top of the stack
    private val seen = HashMap<String, Long>()           // key -> when first shown; newest on top
    private var seq = 0L
    private val decided = HashMap<String, Boolean>()     // approval id -> what he tapped, until the PC stops listing it
    private val closedPanels = HashSet<String>()         // closed here, held hidden until the PC stops listing them
    private var needsHold: String? = null                // the Waiting on you content he closed, hidden until it changes
    private var waiting: Set<String> = emptySet()
    private var generation = 0
    private var fanned = false
    private var shrinking = false
    private var first = true
    private var last: Last? = null                       // the latest PC data, so the digest can re-render without it
    private var digest: Model? = null                    // the "While you worked" card, always on top
    private var onDigest: (() -> Unit)? = null
    private var digestOpen = false

    // sits straight on the wallpaper: its inks, not the theme's. No "PC isn't answering" line here: the strip's
    // "PC off since ..." chip already says it (Jhon, 3 Nov)
    private val empty = look.text("Nothing needs you", 15f, look.bare.ink3).apply {
        gravity = Gravity.CENTER; visibility = GONE; setPadding(0, look.px(14), 0, look.px(14))
        look.legible(this)
    }
    private val pill = look.text("What Jharvis did today", 15f, look.ink2).apply {
        gravity = Gravity.CENTER; minHeight = look.px(48); setPadding(look.px(22), 0, look.px(22), 0)
        background = look.wallGlass(99)
        isClickable = true; look.press(this, 0.95f)
        setOnClickListener { host.openLog(it) }
    }
    private val cascade = Cascade()
    private val column = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        addView(empty, LinearLayout.LayoutParams(-1, -2))
        addView(cascade, LinearLayout.LayoutParams(-1, -2))
        setOnClickListener { fan(false) }  // a tap beside the fanned list stacks it back
        isClickable = false
    }
    private val scroll = ScrollView(c).apply {
        isVerticalScrollBarEnabled = false; clipToPadding = false; isFillViewport = true; overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(look.px(24))
        addView(column)
    }

    init {
        clipChildren = false
        addView(scroll, LayoutParams(-1, -1))
    }

    /** The "While you worked" card after Night shift or Driving muted chats (REDESIGN.md Phase 2 "Digest"): one
     *  liquid-glass card on top of the stack, "14 messages from 5 chats"; a tap opens it into rows grouped by app, each
     *  row opening its app; swipe or x dismisses it and calls onDismiss (home clears the file). Empty items remove it. */
    fun showDigest(items: JSONArray, onDismiss: () -> Unit) {
        val list = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
        onDigest = if (list.isEmpty()) null else onDismiss
        digest = if (list.isEmpty()) null else Model(DIGEST_KEY, DIGEST, null, list, null).also { it.sig = items.toString() }
        if (digest == null) digestOpen = false
        val l = last
        if (l != null) render(l.home, l.approvals, l.panels, l.fresh, l.asOf, waiting)
        else render(JSONObject(), JSONArray(), JSONArray(), true, System.currentTimeMillis(), waiting)
    }

    /** Raw PC JSON in. waiting() reads the send queue from disk, so that hop is off the main thread. */
    fun show(home: JSONObject, approvals: JSONArray, panels: JSONArray, fresh: Boolean, asOf: Long) {
        val gen = ++generation
        try {
            io.execute {
                val w = try { Link.queue.waiting().toSet() } catch (_: Exception) { emptySet() }
                post { if (gen == generation && isAttachedToWindow) render(home, approvals, panels, fresh, asOf, w) }
            }
        } catch (_: Exception) {}
    }

    // one named worker for the queue read; it stops itself when idle, so nothing outlives the stack
    private val io = java.util.concurrent.ThreadPoolExecutor(0, 1, 5, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue()) { r -> Thread(r, "panels-io") }

    /** Stack the fanned list back (home can call this when he taps elsewhere). */
    fun collapse() = fan(false)

    /** A panel: it owns sideways swipes (close, or a rubber band), so home's page swipe leaves them alone. */
    fun isCard(v: View) = v is Card

    // ── data → models → diff ────────────────────────────────────────────────────────────────────────

    private class Last(val home: JSONObject, val approvals: JSONArray, val panels: JSONArray, val fresh: Boolean, val asOf: Long)

    private class Model(val key: String, val kind: Int, val ask: JSONObject?, val items: List<JSONObject>, val panel: JSONObject?) {
        var sig = ""
    }

    private fun render(home: JSONObject, approvals: JSONArray, panels: JSONArray, fresh: Boolean, asOf: Long, w: Set<String>) {
        waiting = w
        last = Last(home, approvals, panels, fresh, asOf)

        val models = ArrayList<Model>()
        val panelIds = HashSet<String>()
        for (k in 0 until panels.length()) {
            val p = panels.optJSONObject(k) ?: continue
            val id = p.optString("id"); if (id.isEmpty()) continue
            panelIds += id
            if (id in closedPanels) continue
            models += Model("panel:$id", PANEL, null, emptyList(), p).also { it.sig = PanelLogic.panelSig(p) }
        }
        val items = ArrayList<JSONObject>()
        home.optJSONObject("urgent")?.let { items += it }
        home.optJSONArray("next")?.let { a -> for (k in 0 until a.length()) a.optJSONObject(k)?.let { items += it } }
        val needsSig = items.joinToString("\n") { it.toString() }
        val closed = home.optBoolean("closed", false)
        if (fresh && closed) needsHold = null  // the PC has it now and reopens it when the list changes
        if (items.isNotEmpty() && !closed && needsHold != needsSig) models += Model("needs", NEEDS, null, items, null).also { it.sig = needsSig }
        val askIds = HashSet<String>()
        for (k in 0 until approvals.length()) {
            val a = approvals.optJSONObject(k) ?: continue
            val id = a.optString("id"); if (id.isEmpty()) continue
            askIds += id
            models += Model("ask:$id", ASK, a, emptyList(), null).also { it.sig = askSig(it) }
        }
        if (fresh) { decided.keys.retainAll(askIds); closedPanels.retainAll(panelIds) }

        // newest on top; in one batch, asks above Waiting on you above Jharvis's panels (they were added in that order)
        for (m in models) if (m.key !in seen) seen[m.key] = ++seq
        val keys = models.mapTo(HashSet()) { it.key }
        seen.keys.retainAll(keys)
        models.sortByDescending { seen[it.key] }
        digest?.let { models.add(0, it) }  // the digest stays on top whatever arrives

        val byKey = order.associateBy { it.model.key }
        val added = ArrayList<Card>()
        val next = ArrayList<Card>()
        for (m in models) {
            val card = byKey[m.key]
            if (card == null) next += Card(m).also { added += it; cascade.addView(it) }
            else { if (card.model.sig != m.sig) card.bind(m) else card.model = m; next += card }
        }
        for (card in order) if (card !in next) card.dissolve()
        order = next
        empty.visibility = if (order.isEmpty()) VISIBLE else GONE
        if (fanned && order.size < 2) fan(false)
        cascade.reorder()
        // new panels materialize in one after another; on first show with nothing changing, no fanfare is needed
        added.forEachIndexed { i, card -> card.arrive(if (first) 60L + 60L * i else 120L + 70L * i) }
        // something new needs him: felt as it lands (not on the first show, so opening Home never buzzes)
        if (!first) added.firstOrNull()?.let { c -> c.postDelayed({ if (c.isAttachedToWindow) Haptics.arrive(c) }, 120L) }
        first = false
    }

    private fun askSig(m: Model) = PanelLogic.askSig(m.ask!!, status(m.ask))

    private fun status(a: JSONObject): String? = a.optString("id").let { PanelLogic.status(it, decided[it], waiting) }

    // ── fan out / stack back ────────────────────────────────────────────────────────────────────────

    private fun fan(on: Boolean) {
        if (on == fanned || (on && order.size < 2)) return
        fanned = on
        column.isClickable = on
        if (on) { shrinking = false; removeCallbacks(shrunk); registerBack() }
        else { shrinking = true; postDelayed(shrunk, if (still) 0L else 560L); unregisterBack(); scroll.smoothScrollTo(0, 0) }
        if (!on) openDigest(false)
        cascade.requestLayout()
    }

    /** Open or close the digest's rows; with other cards under it, the stack fans out with it. */
    private fun openDigest(on: Boolean) {
        if (digestOpen == on) return
        digestOpen = on
        order.firstOrNull { it.model.kind == DIGEST }?.let { it.bind(it.model) }
        if (order.size >= 2) fan(on)
        else { if (on) registerBack() else if (!fanned) unregisterBack(); cascade.reorder() }
    }

    private val shrunk = Runnable { shrinking = false; cascade.requestLayout() }

    private var backOn = false
    private val backScale = Spring(1f, 0.0005f) { cascade.scaleX = it; cascade.scaleY = it }
    private val back: OnBackInvokedCallback = if (Build.VERSION.SDK_INT >= 34) object : OnBackAnimationCallback {
        // predictive Back: the fanned list leans back as the gesture grows, then stacks
        override fun onBackProgressed(e: BackEvent) { if (!still) backScale.snap(1f - 0.05f * e.progress) }
        override fun onBackInvoked() { springTo(backScale, 1f, 0.4f); fan(false); openDigest(false) }
        override fun onBackCancelled() { springTo(backScale, 1f, 0.4f) }
    } else OnBackInvokedCallback { fan(false); openDigest(false) }

    private fun registerBack() {
        if (backOn) return
        findOnBackInvokedDispatcher()?.let { it.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back); backOn = true }
    }

    private fun unregisterBack() {
        if (!backOn) return
        findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(back); backOn = false
    }

    override fun onDetachedFromWindow() { unregisterBack(); super.onDetachedFromWindow() }

    private fun springTo(s: Spring, v: Float, response: Float, damping: Float = 1f, velocity: Float? = null) {
        if (still) s.snap(v) else if (s.target != v || velocity != null) s.to(v, response, damping, velocity)
    }

    // ── the stack: every card laid out at the top, placed by springs on translation and scale ────────────

    private inner class Cascade : ViewGroup(context) {
        private var drawSeq = IntArray(0)
        val pillY = Spring(0f, 0.5f) { pill.translationY = it }

        init { clipChildren = false; isChildrenDrawingOrderEnabled = true; addView(pill) }

        private fun glass(card: Card) = (card.measuredHeight - room).toFloat()

        private fun extent(fan: Boolean): Float {
            if (order.isEmpty()) return 0f
            if (!fan) return glass(order[0]) + peek * (min(order.size, 3) - 1)
            var y = 0f
            for (card in order) y += glass(card) + gap
            return y - gap
        }

        private fun pillTop(fan: Boolean) = if (order.isEmpty()) 0f else extent(fan) + look.px(18)

        override fun onMeasure(ws: Int, hs: Int) {
            val w = MeasureSpec.getSize(ws)
            val exact = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
            val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            for (i in 0 until childCount) {
                val v = getChildAt(i)
                if (v === pill) v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), free) else v.measure(exact, free)
            }
            // while stacking back the list keeps its fanned height until the cards have landed, so nothing below jumps
            val h = max(pillTop(fanned || shrinking), pillTop(false)) + pill.measuredHeight + look.px(8)
            setMeasuredDimension(w, h.toInt())
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            for (i in 0 until childCount) {
                val v = getChildAt(i)
                if (v === pill) { val x = (w - v.measuredWidth) / 2; v.layout(x, 0, x + v.measuredWidth, v.measuredHeight) }
                else v.layout(0, 0, w, v.measuredHeight)
            }
            place()
        }

        /** Where every card should be for the current order and fan state; springs carry them there. */
        fun place() {
            val topH = if (order.isEmpty()) 0f else glass(order[0])
            var y = 0f
            order.forEachIndexed { k, card ->
                val g = glass(card)
                if (fanned) card.go(y, 1f, 1f, 0f, 1f, inert = false, depth = 0f)
                else if (k == 0) card.go(0f, 1f, 1f, 0f, 1f, inert = false, depth = 0f)
                else {
                    val kk = min(k, 3)
                    // behind: a blank plate the top card's height, its bottom peeking out below, smaller and softer
                    card.go(topH + kk * peek - g, 1f - 0.05f * kk, 0f, max(0f, g - topH), if (k < 3) 1f else 0f,
                        inert = true, depth = look.px(3) * kk.toFloat())
                }
                y += g + gap
            }
            springTo(pillY, pillTop(fanned), 0.5f)
        }

        fun reorder() { rebuildDrawOrder(); requestLayout(); invalidate() }

        private fun rebuildDrawOrder() {
            // leaving cards first, then the stack from the bottom up so the top card draws (and takes touches) last
            val seqList = ArrayList<Int>(childCount)
            for (i in 0 until childCount) { val v = getChildAt(i); if (v is Card && v !in order) seqList += i }
            for (k in order.indices.reversed()) indexOfChild(order[k]).takeIf { it >= 0 }?.let { seqList += it }
            seqList += indexOfChild(pill)
            drawSeq = seqList.toIntArray()
        }

        override fun getChildDrawingOrder(count: Int, i: Int): Int =
            if (drawSeq.size == count) drawSeq[i] else i

        override fun onViewRemoved(child: View?) { super.onViewRemoved(child); rebuildDrawOrder() }
        override fun onViewAdded(child: View?) { super.onViewAdded(child); rebuildDrawOrder() }
    }

    // ── a liquid-glass panel ────────────────────────────────────────────────────────────────────────

    private inner class Card(var model: Model) : FrameLayout(context) {
        private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(look.px(20), look.px(18), look.px(20), look.px(18)) }
        private val close: View?
        private var plateTop = 0f
        private var inert = false
        private var depth = 0f
        private var leaving = false
        private var placed = false
        // presented values, each carried by its own spring so any of them can be interrupted mid-flight
        private var ty = 0f; private var sc = 1f; private var ca = 1f; private var pt = 0f; private var sh = 1f
        private var pres = 0f; private var tx = 0f; private var shownBlur = 0f
        private val tyS = Spring(0f, 0.5f) { ty = it; present() }
        private val scS = Spring(1f, 0.0005f) { sc = it; present() }
        private val caS = Spring(1f, 0.002f) { ca = it; present() }
        private val ptS = Spring(0f, 0.5f) { pt = it; present() }
        private val shS = Spring(1f, 0.002f) { sh = it; present() }
        private val presS = Spring(0f, 0.002f) { pres = it; present() }
        private val txS = Spring(0f, 0.5f) { tx = it; present() }

        // the glass, drawn (no allocations in onDraw: paints, path and shaders are made on size change)
        private val r = look.px(28).toFloat()
        private val rect = RectF()
        private val path = Path()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val streak = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = look.px(1).toFloat(); color = look.white(191) }
        private val hi = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = look.px(1).toFloat() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = look.px(2) * 0.75f; color = look.withAlpha(look.navy, 70) }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.cardTint }  // over a wallpaper only
        private val glass = LiquidGlass(this, look)  // real refraction over the wave; the drawn fill where it can't run

        init {
            setWillNotDraw(false); clipChildren = false; clipToPadding = false
            setPadding(0, 0, 0, room)
            addView(body, LayoutParams(-1, -2))
            close = if (model.kind == ASK) null else CloseX().also { x ->
                addView(x, LayoutParams(look.px(48), look.px(48), Gravity.TOP or Gravity.END).apply { topMargin = look.px(6); rightMargin = look.px(6) })
            }
            isClickable = true; look.press(this, 0.98f)
            setOnClickListener { if (model.kind == DIGEST) openDigest(!digestOpen) else fan(!fanned) }
            alpha = 0f
            bind(model)
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); glass.attach() }
        override fun onDetachedFromWindow() { glass.detach(); super.onDetachedFromWindow() }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            if (model.kind == DIGEST) info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
                if (digestOpen) "Hide the chats" else "Show the chats"))
        }

        val closable get() = model.kind != ASK

        fun bind(m: Model) {
            model = m
            body.removeAllViews()
            when (m.kind) {
                ASK -> bindAsk(m.ask!!)
                NEEDS -> bindNeeds(m.items)
                DIGEST -> bindDigest(m.items)
                else -> bindPanel(m.panel!!)
            }
            close?.contentDescription = when (m.kind) { NEEDS -> "Close Waiting on you"; DIGEST -> "Dismiss While you worked"; else -> "Close " + m.panel?.optString("title").orEmpty() }
        }

        private fun heading(s: String, size: Float = 16f) = look.text(s, size, look.ink, true).apply {
            if (closable) setPadding(0, 0, look.px(40), 0)
        }

        private fun bindAsk(a: JSONObject) {
            val id = a.optString("id")
            body.addView(look.text("Jharvis asks", 13f, look.ink3))
            val plain = PanelLogic.plain(a)
            body.addView(heading(plain?.what ?: PanelLogic.humanise(a.optString("tool")), 17f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.px(2) })
            if (plain != null) plainLines(body, plain)  // why and if he says no, in plain words; the exact request folded away
            else {
                // an older card: all of it, selectable, since an approval is only as good as what he could read before tapping
                val args = PanelLogic.argsText(a.opt("args"))
                if (args.isNotEmpty()) body.addView(code(args), LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.px(10) })
            }
            val done = status(a)
            if (done != null) {
                body.addView(look.text(done, 15f, look.ink2).apply { minHeight = look.px(48); gravity = Gravity.CENTER_VERTICAL },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.px(6) })
                return
            }
            body.addView(LinearLayout(context).apply {
                fun decide(yes: Boolean) {
                    if (yes) Haptics.confirm(this) else Haptics.reject(this)
                    decided[id] = yes
                    host.approve(id, yes)
                    bind(Model(model.key, ASK, a, emptyList(), null).also { it.sig = askSig(it) })
                }
                addView(look.button("Approve", true) { decide(true) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(View(context), LinearLayout.LayoutParams(look.px(10), 1))
                addView(look.button("Decline", false) { decide(false) }, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.px(14) })
        }

        private fun bindNeeds(items: List<JSONObject>) {
            body.addView(heading("Waiting on you"))
            for (i in items) body.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, look.px(8), 0, look.px(4))
                val problem = i.optString("kind") == "problem"
                val plain = PanelLogic.plain(i)
                addView(look.text(plain?.what ?: i.optString("title"), 15f, if (problem) look.bad else look.ink))
                if (plain != null) plainLines(this, plain)
                else i.optString("detail").takeIf { it.isNotEmpty() }?.let { addView(look.text(it, 13f, look.ink3).apply { setPadding(0, look.px(2), 0, 0) }) }
                val link = i.optString("link")
                if (link.isNotEmpty()) {
                    addView(look.text(i.optString("action").ifEmpty { "Open" }, 15f, look.accentInk, true).apply {
                        minHeight = look.px(48); gravity = Gravity.CENTER_VERTICAL
                    })
                    isClickable = true; look.press(this, 0.97f)
                    setOnClickListener { host.open(link, this) }
                } else if (i.optString("action").isNotEmpty()) {
                    addView(look.text(i.optString("action"), 13f, if (problem) look.bad else look.ink2).apply { setPadding(0, look.px(4), 0, 0) })
                }
            })
        }

        /** The plain lines under a heading: why, then what happens if he says no, then a closed Details row that opens
         *  onto the exact technical text. */
        private fun plainLines(into: LinearLayout, p: PanelLogic.Plain) {
            for (line in listOf(p.why, PanelLogic.ifNoLine(p.ifNo))) if (line.isNotEmpty())
                into.addView(look.text(line, 15f, look.ink2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.px(6) })
            if (p.details.isEmpty()) return
            val box = code(p.details).apply { visibility = GONE }
            into.addView(look.text("Show details", 15f, look.ink2, true).apply {
                minHeight = look.px(48); gravity = Gravity.CENTER_VERTICAL
                isClickable = true; look.press(this, 0.97f)
                setOnClickListener {
                    val open = box.visibility != VISIBLE
                    box.visibility = if (open) VISIBLE else GONE
                    text = if (open) "Hide details" else "Show details"
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.px(4) })
            into.addView(box, LinearLayout.LayoutParams(-1, -2))
        }

        /** Technical text as itself: monospace, selectable, on a light plate. */
        private fun code(s: String) = look.text(s, 13f, look.ink).apply {
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
            background = look.round(look.white(115), 16)
            setPadding(look.px(12), look.px(10), look.px(12), look.px(10))
        }

        private fun bindDigest(items: List<JSONObject>) {
            body.addView(heading("While you worked"))
            body.addView(look.text(PanelLogic.digestSummary(items), 15f, look.ink2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.px(4) })
            if (!digestOpen) return
            var k = 0
            for ((app, list) in PanelLogic.digestGroups(items, DIGEST_ROWS)) {
                body.addView(look.text(app, 13f, look.ink3), LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.px(14) })
                for (i in list) body.addView(digestRow(app, i, k++), LinearLayout.LayoutParams(-1, -2))
            }
            val more = PanelLogic.digestChats(items) - k
            if (more > 0) body.addView(look.text(if (more == 1) "1 more chat" else "$more more chats", 13f, look.ink3),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.px(8) })
        }

        /** One chat: its name and time, the latest line under it; a tap opens its app. Rows cascade in. */
        private fun digestRow(app: String, i: JSONObject, k: Int) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = look.px(48); setPadding(0, look.px(6), 0, look.px(6))
            val title = i.optString("title"); val line = i.optString("line")
            addView(LinearLayout(context).apply {
                addView(look.text(title, 15f, look.ink).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, -2, 1f))
                val t = i.optLong("time")
                if (t > 0) addView(look.text(android.text.format.DateFormat.getTimeFormat(context).format(Date(t)), 13f, look.ink3)
                    .apply { setPadding(look.px(8), 0, 0, 0) })
            }, LinearLayout.LayoutParams(-1, -2))
            if (line.isNotEmpty()) addView(look.text(line, 13f, look.ink3).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, look.px(2), 0, 0)
            })
            contentDescription = "$title. $line. Opens $app"
            isClickable = true; look.press(this, 0.97f)
            val pkg = i.optString("pkg")
            setOnClickListener { v ->
                val open = {
                    val launch = try { context.packageManager.getLaunchIntentForPackage(pkg) } catch (_: Exception) { null }
                    if (launch != null) context.launchFrom(v, launch)
                    else try { Toast.makeText(context, "Couldn't open that", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                }
                val act = context as? android.app.Activity
                if (act != null) LockedApps.launch(act, pkg, app, open) else open()  // a locked app asks first here too
            }
            if (!still) {
                alpha = 0f; translationY = look.px(8).toFloat()
                animate().alpha(1f).translationY(0f).setStartDelay(30L * min(k, 10)).setDuration(450).setInterpolator(SpringCurve(1f)).start()
            }
        }

        private fun bindPanel(p: JSONObject) {
            body.addView(heading(p.optString("title")))
            val lines = p.optJSONArray("lines") ?: JSONArray()
            for (k in 0 until lines.length()) body.addView(look.text(lines.optString(k), 15f, look.ink2),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.px(if (k == 0) 10 else 6) })
        }

        /** Spring to a place in the stack. A card shown for the first time starts there (it materializes, it doesn't fly). */
        fun go(y: Float, s: Float, content: Float, plate: Float, shade: Float, inert: Boolean, depth: Float) {
            if (leaving) return
            this.inert = inert
            body.importantForAccessibility = if (inert) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else IMPORTANT_FOR_ACCESSIBILITY_AUTO
            close?.importantForAccessibility = body.importantForAccessibility
            if (!placed) {
                placed = true
                tyS.snap(y); scS.snap(s); caS.snap(content); ptS.snap(plate); shS.snap(shade)
            } else {
                springTo(tyS, y, 0.5f); springTo(scS, s, 0.5f); springTo(caS, content, if (content > ca) 0.45f else 0.35f)
                springTo(ptS, plate, 0.5f); springTo(shS, shade, 0.4f)
            }
            this.depth = if (still) 0f else depth
            present()
        }

        fun arrive(delay: Long) {
            if (still) { presS.snap(1f); return }
            postDelayed({ if (!leaving) presS.to(1f, 0.55f) }, delay)
        }

        /** Leave the way it came: scale back to 0.92, fade, blur up, then go. */
        fun dissolve() {
            if (leaving) return
            leaving = true; isClickable = false; isEnabled = false
            presS.onRest = { (parent as? ViewGroup)?.removeView(this) }
            if (still) { presS.snap(0f); presS.onRest?.invoke() } else presS.to(0f, 0.4f)
        }

        private fun present() {
            translationX = tx; translationY = ty
            val s = sc * (0.92f + 0.08f * pres)
            scaleX = s; scaleY = s
            val swipe = if (width > 0) min(1f, abs(tx) / width) else 0f
            alpha = (sh * pres * (1f - swipe * 0.7f)).coerceIn(0f, 1f)
            body.alpha = ca; close?.alpha = ca
            glass.reveal = pres  // the refraction ramps in as the blur clears: it materializes
            if (pt != plateTop) { plateTop = pt; invalidate() }
            val b = if (still) 0f else max(depth, look.px(8) * (1f - pres))
            if (abs(b - shownBlur) > 0.5f || (b == 0f && shownBlur != 0f)) { shownBlur = b; blur(b) }
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val g = (h - room).toFloat()
            pivotX = w / 2f; pivotY = g
            // .liquid-glass: clear glass catching the light (a little more fill than the PC: no backdrop blur here)
            fill.shader = LinearGradient(0f, 0f, w * 0.55f, g, intArrayOf(look.white(92), look.white(28), if (look.night) look.white(56) else Color.argb(56, 220, 238, 250)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            streak.shader = RadialGradient(w * 0.2f, -g * 0.1f, max(w * 0.6f, 1f), look.white(140), Color.TRANSPARENT, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(1f, 0.5f, w * 0.2f, -g * 0.1f) })
            }
            hi.shader = LinearGradient(0f, 0f, 0f, look.px(28).toFloat(), look.white(242), look.white(0), Shader.TileMode.CLAMP)
            // the deep soft shadow (0 24 50 -24): an oval of ink under the bottom edge, never under the glass itself
            shadow.shader = RadialGradient(w / 2f, g, max(w * 0.46f, 1f), look.shade(60), Color.TRANSPARENT, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(1f, room * 1.1f / max(w * 0.46f, 1f), w / 2f, g) })
            }
        }

        override fun onDraw(canvas: Canvas) {
            val g = (height - room).toFloat()
            rect.set(0f, plateTop, width.toFloat(), g)
            path.reset(); path.addRoundRect(rect, r, r, Path.Direction.CW)
            canvas.save(); canvas.clipOutPath(path)
            canvas.drawRect(0f, g - room, width.toFloat(), g + room, shadow)
            canvas.restore()
            // the top card (or every card, fanned) is real liquid glass with the fill as a lighter tint; plates behind
            // stay drawn, so only the visible panels pay for the effect
            // over a wallpaper the theme's tint goes under the fill, so the card's ink reads whatever the picture
            fill.alpha = 150
            val tinted = look.cardTint != 0
            if (inert || !glass.draw(canvas, rect, r, if (tinted) tint else fill)) {
                if (tinted) canvas.drawRoundRect(rect, r, r, tint)
                fill.alpha = 255; canvas.drawRoundRect(rect, r, r, fill)
            } else if (tinted) canvas.drawRoundRect(rect, r, r, fill)
            canvas.save(); canvas.clipPath(path); canvas.translate(0f, plateTop)
            canvas.drawRect(0f, 0f, width.toFloat(), g - plateTop, streak)
            canvas.restore()
            canvas.drawRoundRect(rect, r, r, edge)
            val inset = look.px(1).toFloat()
            canvas.save(); canvas.translate(0f, plateTop)
            rect.set(inset, inset, width - inset, g - plateTop - inset)
            canvas.drawRoundRect(rect, r - inset, r - inset, hi)
            if (model.kind == ASK) { rect.inset(inset, inset); canvas.drawRoundRect(rect, r - 2 * inset, r - 2 * inset, ring) }  // an ask stands out, like the PC's navy edge
            canvas.restore()
        }

        // ── swipe sideways to close: 1:1, rubber band when it can't close, momentum decides ─────────────────

        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f; private var downY = 0f; private var grabTx = 0f
        private var dragging = false; private var crossed = false
        private var vt: VelocityTracker? = null

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean = inert || track(e)

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val wasDragging = dragging
            val drag = if (inert) false else track(e)
            if (drag && !wasDragging) {  // just turned into a swipe: drop the press and the pending click
                val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                super.onTouchEvent(cancel); cancel.recycle()
            }
            return if (drag || wasDragging) true else super.onTouchEvent(e)
        }

        private fun track(e: MotionEvent): Boolean {
            if (leaving) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; dragging = false; crossed = false
                    txS.stop(); grabTx = tx  // caught mid-flight: carry on from where it is
                    vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) }
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && abs(dx) > slop && abs(dx) > abs(dy) * 1.3f) {
                        dragging = true; downX = e.rawX  // from here on it follows exactly
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        val raw = grabTx + (e.rawX - downX)
                        val w = max(width, 1).toFloat()
                        txS.snap(if (closable) raw else rubberband(raw, w))
                        val over = closable && abs(tx) > w * 0.4f
                        if (over != crossed) {
                            crossed = over
                            if (over && Build.VERSION.SDK_INT >= 34) performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE) else tick()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
                        val vx = vt?.xVelocity ?: 0f
                        val w = max(width, 1).toFloat()
                        val landing = tx + project(vx)
                        if (closable && e.actionMasked == MotionEvent.ACTION_UP && abs(landing) > w * 0.5f) fling(sign(landing), vx)
                        else springTo(txS, 0f, 0.45f, if (abs(vx) > 600f) 0.8f else 1f, vx)  // a flick back bounces a touch
                        dragging = false
                        vt?.recycle(); vt = null
                        return true
                    }
                    vt?.recycle(); vt = null
                }
            }
            return dragging
        }

        private fun fling(dir: Float, vx: Float) {
            closeIt(fly = true)
            txS.onRest = { (parent as? ViewGroup)?.removeView(this) }
            if (still) { txS.snap(dir * width * 1.2f); txS.onRest?.invoke() } else txS.to(dir * width * 1.2f, 0.35f, 1f, vx)
        }

        /** Close x or swipe: gone here at once (the rest of the stack closes up), the PC is told through home's queue. */
        fun closeIt(fly: Boolean = false) {
            val id = when (model.kind) { DIGEST -> DIGEST_KEY; NEEDS -> "needs"; else -> model.panel?.optString("id").orEmpty() }
            if (id.isEmpty()) return
            when (model.kind) { DIGEST -> { digest = null; digestOpen = false }; NEEDS -> needsHold = model.sig; else -> closedPanels += id }
            order.remove(this)
            if (fly) { leaving = true; isClickable = false; isEnabled = false } else dissolve()
            empty.visibility = if (order.isEmpty()) VISIBLE else GONE
            if (fanned && order.size < 2) fan(false)
            cascade.reorder()
            if (model.kind != DIGEST) { host.closePanel(id); return }
            if (!fanned) unregisterBack()
            val done = onDigest; onDigest = null
            try { done?.invoke() } catch (_: Exception) {}
        }

        /** The quiet x: ink 3, 12 dp drawn inside a 48 dp target. */
        private inner class CloseX : View(context) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.ink3; strokeWidth = look.px(2) * 0.75f; strokeCap = Paint.Cap.ROUND }
            init { isClickable = true; look.press(this, 0.85f); setOnClickListener { closeIt() } }
            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f; val cy = height / 2f; val a = look.px(6).toFloat()
                canvas.drawLine(cx - a, cy - a, cx + a, cy + a, p); canvas.drawLine(cx - a, cy + a, cx + a, cy - a, p)
            }
        }
    }

    private companion object {
        const val ASK = 0
        const val NEEDS = 1
        const val PANEL = 2
        const val DIGEST = 3
        const val DIGEST_KEY = "digest"
        const val DIGEST_ROWS = 40  // chats shown when opened; the rest is a count (the file holds up to 200)
    }
}

/** The pure parts of the panels, unit tested. */
internal object PanelLogic {
    /** "os_run_agent" -> "Run agent", "sendMessage" -> "Send message". */
    fun humanise(tool: String): String {
        val words = tool.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").split('_', '-', ' ', '.').filter { it.isNotBlank() }.map { it.lowercase() }
        val w = if (words.size > 1 && words[0] == "os") words.drop(1) else words
        return w.joinToString(" ").replaceFirstChar { it.uppercase() }.ifEmpty { "Something" }
    }

    /** The full request: a command as itself, anything else as indented JSON (like the PC shows it). */
    fun argsText(args: Any?): String = when (args) {
        null, JSONObject.NULL -> ""
        is JSONObject -> when {
            args.length() == 0 -> ""
            args.opt("command") is String -> args.getString("command")
            else -> args.toString(2).replace("\\/", "/")
        }
        is JSONArray -> if (args.length() == 0) "" else args.toString(2).replace("\\/", "/")
        else -> args.toString().takeIf { it != "{}" }.orEmpty()
    }

    /** After a tap: queued and the PC is away, or on its way. null = not answered yet. */
    fun status(id: String, decided: Boolean?, waiting: Set<String>): String? = when {
        "/api/jharvis/approvals/$id" in waiting -> "Answered. Sends when the PC is back."
        decided == true -> "Approved. Sending."
        decided == false -> "Declined. Sending."
        else -> null
    }

    fun askSig(a: JSONObject, status: String?) = a.optString("id") + "\n" + a.optString("tool") + "\n" + argsText(a.opt("args")) + "\n" +
        status.orEmpty() + "\n" + plain(a)?.let { listOf(it.what, it.why, it.ifNo, it.details).joinToString("\n") }.orEmpty()

    /** The plain words an approval or a need carries for Jhon (what / why / if_no) and the technical text for its
     *  closed Details row (details, else the request itself). null = an older card without them: shown as before. */
    class Plain(val what: String, val why: String, val ifNo: String, val details: String)

    fun plain(o: JSONObject): Plain? {
        fun str(k: String) = if (o.isNull(k)) "" else o.optString(k).trim()
        val what = str("what")
        if (what.isEmpty()) return null
        return Plain(what, str("why"), str("if_no"), str("details").ifEmpty { argsText(o.opt("args")) })
    }

    /** What a no costs, worded like the dashboard: "If you say no: nothing is sent." An acronym or "I" keeps its capital. */
    fun ifNoLine(s: String): String {
        if (s.isEmpty()) return ""
        val keep = s.length > 1 && !s[1].isLowerCase()  // "PC ...", "I won't ..."
        return "If you say no: " + if (keep) s else s[0].lowercaseChar() + s.substring(1)
    }

    fun panelSig(p: JSONObject) = p.optString("title") + "\n" + (p.optJSONArray("lines")?.toString() ?: "")

    private fun chatKey(i: JSONObject) = i.optString("pkg") + "|" + i.optString("title")

    fun digestChats(items: List<JSONObject>) = items.mapTo(HashSet()) { chatKey(it) }.size

    /** The digest card's line. Each item is one chat (pkg + title); an optional "count" says how many messages it
     *  held. Without counts the message total isn't known, so it names chats only. */
    fun digestSummary(items: List<JSONObject>): String {
        val chats = digestChats(items)
        val msgs = items.sumOf { maxOf(1, it.optInt("count", 1)) }
        fun n(k: Int, one: String) = if (k == 1) "1 $one" else "$k ${one}s"
        return when {
            chats == 0 -> "Nothing new"
            msgs > chats -> n(msgs, "message") + " from " + n(chats, "chat")
            else -> "New messages in " + n(chats, "chat")
        }
    }

    /** At most `limit` chats, newest first, grouped by app (the app with the newest chat first). */
    fun digestGroups(items: List<JSONObject>, limit: Int): List<Pair<String, List<JSONObject>>> {
        val latest = LinkedHashMap<String, JSONObject>()
        for (i in items) { val k = chatKey(i); val old = latest[k]; if (old == null || i.optLong("time") >= old.optLong("time")) latest[k] = i }
        val newest = latest.values.sortedByDescending { it.optLong("time") }.take(limit)
        val groups = LinkedHashMap<String, MutableList<JSONObject>>()
        for (i in newest) groups.getOrPut(i.optString("pkg")) { ArrayList() } += i
        return groups.values.map { list -> list[0].optString("app").ifEmpty { list[0].optString("pkg") } to list }
    }
}
