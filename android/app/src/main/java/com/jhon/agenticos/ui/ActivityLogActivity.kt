package com.jhon.agenticos.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jhon.agenticos.R
import com.jhon.agenticos.core.Link
import com.jhon.agenticos.core.atomicWrite
import com.jhon.agenticos.core.osHttp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/** "What Jharvis did today": what Jharvis and the OS did since midnight in Manila, newest first, as one iOS-style
 *  grouped timeline on the frost. Scheduler runs fold into one line per job. Undo only where an existing endpoint
 *  truly reverses it (Unmute, Put back, Turn off), sent through the queue so it also works with the PC off. */
class ActivityLogActivity : Activity() {
    private lateinit var look: Look
    private lateinit var list: LinearLayout  // the sections, rebuilt on each answer
    private lateinit var note: TextView      // "As of 9:40..." when the PC is off
    private var still = false
    private var loading = false
    private var shownKeys: Set<String>? = null  // null until the first draw: everything materializes once
    private val status = HashMap<String, String>()  // row key -> what his undo tap did, until the PC's answer shows it
    private val hairline get() = look.hairline
    private val cache by lazy { File(Link.dir, "activity_cache.json") }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        look = Look(this)
        still = reducedMotion(this)
        pushTransition()

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val big = look.text("Today", 34f, look.ink, true).apply { letterSpacing = -0.025f; setPadding(look.px(20), 0, look.px(20), 0) }
        val date = look.text(DateUtils.formatDateTime(this, System.currentTimeMillis(),
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR), 15f, look.ink3)
            .apply { setPadding(look.px(20), look.px(2), look.px(20), 0) }
        note = look.text("", 13f, look.warn).apply { setPadding(look.px(20), look.px(12), look.px(20), 0); visibility = View.GONE }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(big); column.addView(date); column.addView(note)
        column.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.px(8) })
        column.addView(look.text("Only actions that went through the OS are listed.", 13f, look.ink3)
            .apply { setPadding(look.px(32), look.px(16), look.px(32), 0) })

        val scroll = ScrollView(this).apply { clipToPadding = false; isVerticalScrollBarEnabled = false; addView(column) }
        val fill = ColorDrawable(look.bar)
        val hair = View(this).apply { setBackgroundColor(hairline) }
        val small = look.text("Today", 17f, look.ink, true).apply { gravity = Gravity.CENTER }
        val back = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumWidth = look.px(48); setPadding(look.px(6), 0, look.px(12), 0)
            contentDescription = "Back to Home"
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_set_back); imageTintList = ColorStateList.valueOf(look.navy) },
                LinearLayout.LayoutParams(look.px(24), look.px(24)))
            addView(look.text("Home", 17f, look.navy))
            look.press(this, 0.92f); setOnClickListener { finish() }
        }
        val bar = FrameLayout(this).apply {
            background = fill
            addView(hair, FrameLayout.LayoutParams(-1, 1, Gravity.BOTTOM))
            addView(small, FrameLayout.LayoutParams(-2, look.px(52), Gravity.CENTER_HORIZONTAL))
            addView(back, FrameLayout.LayoutParams(-2, look.px(48), Gravity.CENTER_VERTICAL).apply { leftMargin = look.px(4) })
        }
        setContentView(FrameLayout(this).apply {
            addView(FrostView(context, look))
            addView(scroll)
            addView(bar, FrameLayout.LayoutParams(-1, -2))
            setOnApplyWindowInsetsListener { _, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                bar.setPadding(i.left, i.top, i.right, 0)
                scroll.setPadding(i.left, i.top + look.px(52), i.right, i.bottom + look.px(32))
                WindowInsets.CONSUMED
            }
        })
        look.bars(window)

        // iOS large title: fades as it slides under the bar, then the bar frosts in and the small title rises into it
        fun collapse(y: Int) {
            val span = big.height.coerceAtLeast(1).toFloat()
            val t = (y / span).coerceIn(0f, 1f)
            big.alpha = 1 - t; date.alpha = 1 - t
            fill.alpha = (t * 255).toInt(); hair.alpha = t
            val s = ((y - span * 0.6f) / (span * 0.4f)).coerceIn(0f, 1f)
            small.alpha = s; small.translationY = (1 - s) * look.px(6)
        }
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> collapse(y) }
        collapse(0)
    }

    override fun onResume() { super.onResume(); refresh() }

    @Suppress("DEPRECATION")  // overridePendingTransition: only the API 33 path
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(R.anim.set_pop_in, R.anim.set_pop_out)
    }

    /** iOS push, the same as Settings: in from the right over Home, back reverses it. */
    @Suppress("DEPRECATION")
    private fun pushTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.set_push_in, R.anim.set_push_out)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.set_pop_in, R.anim.set_pop_out)
        } else overridePendingTransition(R.anim.set_push_in, R.anim.set_push_out)
    }

    // ── data: cache first, then the PC, all off the main thread ─────────────────────────────────────

    private fun refresh() {
        if (loading) return
        loading = true
        val first = shownKeys == null
        Thread {
            val zone = zone()
            val cached = try { JSONObject(cache.readText()) } catch (_: Exception) { null }
            val cachedToday = cached != null && ActivityLog.sameDay(cached.optLong("at"), System.currentTimeMillis(), zone)
            if (first && cachedToday) post { draw(ActivityLog.read(cached!!.optJSONArray("rows")), null) }
            val fresh = try {
                val raw = fetch()
                val rows = ActivityLog.rows(raw, zone, System.currentTimeMillis(), { DateFormat.getTimeFormat(this).format(it) }, ::appName)
                try { atomicWrite(cache, JSONObject().put("at", System.currentTimeMillis()).put("rows", ActivityLog.write(rows)).toString()) } catch (_: Exception) {}
                rows
            } catch (_: Exception) { null }
            val waiting = try { Link.queue.waiting().toSet() } catch (_: Exception) { emptySet() }
            post {
                loading = false
                when {
                    fresh != null -> draw(fresh, null, waiting)
                    cached != null -> draw(if (cachedToday) ActivityLog.read(cached.optJSONArray("rows")) else emptyList(), cached.optLong("at"), waiting)
                    else -> draw(emptyList(), 0L, waiting)
                }
            }
        }.start()
    }

    private fun post(r: () -> Unit) = runOnUiThread { if (!isDestroyed) r() }

    /** Owner's day (home caches owner.timezone); Manila when nothing says otherwise. */
    private fun zone(): ZoneId = try {
        ZoneId.of(JSONObject(File(Link.dir, "home_cache.json").readText()).getJSONObject("home").getJSONObject("owner").getString("timezone"))
    } catch (_: Exception) { ZoneId.of("Asia/Manila") }

    /** The audit is the one source that must answer; the others fall back to empty so one slow route can't hide the day. */
    private fun fetch(): JSONObject {
        fun get(path: String) = String(osHttp(path, timeoutMs = 8000))
        fun opt(path: String, pick: (String) -> Any?) = try { pick(get(path)) } catch (_: Exception) { null }
        val raw = JSONObject().put("audit", JSONObject(get("/api/audit?limit=500")).optJSONArray("entries"))
        for (s in listOf("approved", "denied", "expired"))
            raw.put(s, opt("/api/jharvis/approvals?status=$s") { JSONObject(it).optJSONArray("approvals") })
        raw.put("messages", opt("/api/jharvis/messages?limit=300") { JSONObject(it).optJSONArray("messages") })
        raw.put("mutes", opt("/api/phone/mutes") { JSONObject(it).optJSONArray("mutes") })
        raw.put("tidy", opt("/api/tidy") { JSONObject(it).optJSONArray("cards") })
        raw.put("jobs", opt("/api/scheduler/jobs") { JSONArray(it) })
        return raw
    }

    private fun appName(pkg: String) = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }

    // ── drawing ─────────────────────────────────────────────────────────────────────────────────────

    /** staleAt: null = fresh from the PC; 0 = never had an answer; otherwise when the shown copy was made. */
    private fun draw(rows: List<ActivityLog.Row>, staleAt: Long?, waiting: Set<String> = emptySet()) {
        note.visibility = if (staleAt == null) View.GONE else View.VISIBLE
        if (staleAt != null) note.text = if (staleAt == 0L) "Nothing yet. This fills in once the PC answers."
            else "As of " + DateUtils.formatDateTime(this, staleAt, DateUtils.FORMAT_SHOW_TIME or
                (if (DateUtils.isToday(staleAt)) 0 else DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)) +
                ". The PC isn't answering, so this may be old."
        // fresh: the PC's answer is the truth, so only undos still in the queue keep a line (they survive a restart;
        // mutes share one path, so only this visit's taps know which chat)
        if (staleAt == null) status.clear()
        for (r in rows) r.undo?.let { u -> if (u.path != "/api/phone/mutes" && u.path in waiting) status[r.key] = PENDING }

        val before = shownKeys
        shownKeys = rows.map { it.key }.toSet()
        list.removeAllViews()
        var order = 0
        // first draw: everything arrives in turn; later draws: only rows that weren't there, the rest stay put
        val arrive = { v: View -> materialize(v, 60L + 45L * minOf(order++, 8)) }
        val isNew = { key: String -> before != null && key !in before }
        val today = rows.filter { !it.muted }
        val muted = rows.filter { it.muted }
        if (today.isEmpty() && staleAt == null) list.addView(look.text("Nothing yet today.", 15f, look.ink2)
            .apply { setPadding(look.px(20), look.px(16), look.px(20), 0) })
        if (today.isNotEmpty()) section(today, isNew, arrive).also { if (before == null) arrive(it) }
        if (muted.isNotEmpty()) {
            val head = look.text("Chats Jharvis keeps muted", 13f, look.ink3).apply { setPadding(look.px(32), look.px(22), look.px(32), look.px(1)) }
            list.addView(head)
            if (before == null) arrive(head)
            section(muted, isNew, arrive).also { if (before == null) arrive(it) }
        }
    }

    /** One group: the dashboard's double bezel (a white shell holding the frosted core) with hairline-separated rows. */
    private fun section(rows: List<ActivityLog.Row>, isNew: (String) -> Boolean, arrive: (View) -> Unit): View {
        val core = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = look.glassCard(22); clipToOutline = true  // row highlights keep the rounded corners
            rows.forEachIndexed { i, r ->
                if (i > 0) addView(View(context).apply { setBackgroundColor(hairline) },
                    LinearLayout.LayoutParams(-1, 1).apply { leftMargin = look.px(if (r.muted) 18 else 82) })
                addView(row(r).also { if (isNew(r.key)) arrive(it) })
            }
        }
        val shell = FrameLayout(this).apply {
            background = look.shell()
            setPadding(look.px(6), look.px(6), look.px(6), look.px(6))
            addView(core)
        }
        list.addView(shell, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = look.px(10); rightMargin = look.px(10); topMargin = look.px(10) })
        return shell
    }

    private fun row(r: ActivityLog.Row): View {
        val text = look.text(r.text, 16f, look.ink)
        val receipt = look.text(r.receipt, 13f, look.ink2).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; visibility = if (r.receipt.isEmpty()) View.GONE else View.VISIBLE
            setPadding(0, look.px(2), 0, 0)
        }
        val said = look.text(status[r.key].orEmpty(), 13f, look.ink2, true).apply {
            visibility = if (status[r.key] == null) View.GONE else View.VISIBLE; setPadding(0, look.px(4), 0, 0)
        }
        val middle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(text); addView(receipt); addView(said) }
        val v = object : LinearLayout(this) {
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {  // highlight on the touch-down frame, as iOS
                if (isClickable && e.actionMasked == MotionEvent.ACTION_DOWN) isPressed = true
                return super.dispatchTouchEvent(e)
            }
        }.apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = look.px(56)
            setPadding(look.px(18), look.px(12), look.px(12), look.px(12))
            if (!r.muted) addView(look.text(if (r.at > 0) DateFormat.getTimeFormat(context).format(r.at) else "", 13f, look.ink3).apply {
                fontFeatureSettings = "tnum"; gravity = Gravity.TOP
            }, LinearLayout.LayoutParams(look.px(64), -2).apply { gravity = Gravity.TOP; topMargin = look.px(2) })
            addView(middle, LinearLayout.LayoutParams(0, -2, 1f))
            contentDescription = listOf(r.text, r.receipt).filter { it.isNotEmpty() }.joinToString(". ")
        }
        r.undo?.takeIf { status[r.key] == null }?.let { u ->
            lateinit var b: View
            b = look.button(u.label, false) { undo(r, u, b, said) }
            v.addView(b, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = look.px(10) })
        }
        if (r.receipt.length > 60) {  // long receipts open in place: the exact args are only useful whole
            v.isClickable = true
            v.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(look.pressed))
                setExitFadeDuration(250)
            }
            look.press(v, 0.985f)
            v.setOnClickListener {
                if (!still) TransitionManager.beginDelayedTransition(list, ChangeBounds().setDuration(420).setInterpolator(SpringCurve(1f)))
                receipt.maxLines = if (receipt.maxLines == 2) Int.MAX_VALUE else 2
            }
        }
        return v
    }

    /** The undo goes through the send queue like an approval: sent now when the PC answers, later when it doesn't.
     *  The button dissolves and a plain line says what happened; the next answer from the PC shows the real state. */
    private fun undo(r: ActivityLog.Row, u: ActivityLog.Undo, button: View, said: TextView) {
        button.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        button.isEnabled = false
        status[r.key] = u.done
        said.text = u.done; said.visibility = View.VISIBLE
        if (still) button.visibility = View.GONE
        else button.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(240).setInterpolator(SpringCurve(1f))
            .withEndAction { button.visibility = View.GONE }.start()
        Thread {
            val left = try {
                Link.queue.add(u.path, u.body, 24 * 3600_000L, u.method)
                Link.queue.flush(); Link.queue.waiting().contains(u.path)
            } catch (_: Exception) { true }
            post {
                if (left) { status[r.key] = PENDING; said.text = PENDING }
                refresh()
            }
        }.start()
    }

    /** Arrive as a material: scale, opacity and blur together, on a critically damped spring curve. */
    private fun materialize(v: View, delay: Long) {
        if (still) return
        val lift = look.px(14).toFloat(); val soft = look.px(8).toFloat()
        v.alpha = 0f; v.scaleX = 0.92f; v.scaleY = 0.92f; v.translationY = lift; v.blur(soft)
        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay; duration = 640; interpolator = SpringCurve(1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                v.alpha = t; val s = 0.92f + 0.08f * t; v.scaleX = s; v.scaleY = s
                v.translationY = (1 - t) * lift; v.blur((1 - t) * soft)
            }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { v.blur(0f) } })
        }.start()
    }

    private companion object { const val PENDING = "Sends when the PC is back." }
}

/** Pure part of the log (unit tested): the PC's raw answers in, plain rows out. No Android here. */
internal object ActivityLog {
    class Undo(val label: String, val done: String, val path: String, val body: String, val method: String = "POST")
    class Row(val key: String, val at: Long, val text: String, val receipt: String = "", val undo: Undo? = null, val muted: Boolean = false)

    /** Scheduler noise: one line per job however often it ran. */
    private val FOLD = setOf("job_run", "skill_run", "scheduler_run", "jobs_run", "session_round")
    /** Shown from a better source: approvals have the full args, tidy cards have the undo. */
    private val SKIP = setOf("jharvis_approval", "tidy_done")

    fun ms(iso: String): Long = try { OffsetDateTime.parse(iso).toInstant().toEpochMilli() } catch (_: Exception) { 0L }

    fun sameDay(a: Long, b: Long, zone: ZoneId) =
        a > 0 && Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

    private fun JSONObject.s(k: String) = if (isNull(k)) "" else optString(k).trim()
    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
    private fun words(s: String) = s.split(' ').joinToString(" ") { cap(it) }
    private fun human(tool: String) = tool.replace('_', ' ').trim()

    /** A preview made readable: no markdown, one line, and nothing at all when it's raw JSON. */
    fun clean(s: String): String {
        val t = s.trim()
        if (t.startsWith("{") || t.startsWith("[")) return ""
        val one = t.replace(Regex("[*#`]+"), "").replace(Regex("\\s+"), " ").trim()
        return if (one.length > 160) one.take(157).trimEnd() + "..." else one
    }

    private fun objects(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }

    fun rows(raw: JSONObject, zone: ZoneId, now: Long, time: (Long) -> String, appName: (String) -> String): List<Row> {
        fun today(at: Long) = sameDay(at, now, zone)
        val out = ArrayList<Row>()
        val jobs = objects(raw.optJSONArray("jobs"))

        // audit: folded runs plus one plain sentence per action
        val audit = objects(raw.optJSONArray("audit")).filter { today(ms(it.s("timestamp"))) }
        val jobPreviews = audit.filter { it.s("action") == "job_run" }.map { it.s("output_preview") }.filter { it.isNotEmpty() }.toSet()
        class Fold(val name: String, var count: Int, var first: Long, var last: Long, var lastEntry: JSONObject)
        val folds = LinkedHashMap<String, Fold>()
        for (e in audit) {
            val action = e.s("action"); val at = ms(e.s("timestamp"))
            if (action in SKIP) continue
            if (action in FOLD) {
                // a scheduled job runs a skill: the skill's own line says the same thing again
                if (action == "skill_run" && e.s("output_preview") in jobPreviews) continue
                val (key, name) = when (action) {
                    "job_run" -> "job:${e.s("job")}" to e.s("job").ifEmpty { "A schedule" }
                    "skill_run", "scheduler_run" -> "skill:${e.s("skill")}" to cap(e.s("skill").ifEmpty { "A" }) + " skill"
                    "jobs_run" -> "jobs" to "Job search"
                    else -> "session:${e.s("session")}" to "A session"
                }
                val f = folds.getOrPut(key) { Fold(name, 0, at, at, e) }
                f.count++; if (at < f.first) f.first = at
                if (at >= f.last) { f.last = at; f.lastEntry = e }
                continue
            }
            out += sentence(e, action, at, jobs) ?: continue
        }
        for ((key, f) in folds) {
            val rounds = key.startsWith("session:")
            val text = when {
                rounds -> if (f.count > 1) "${f.name} went ${f.count} rounds" else "${f.name} went a round"
                f.count > 1 -> "${f.name} ran ${f.count} times"
                else -> "${f.name} ran"
            }
            val last = clean(f.lastEntry.s("output_preview")).ifEmpty { f.lastEntry.s("agents").replace(",", ", ") }
            val receipt = listOfNotNull(if (f.count > 1) "First at ${time(f.first)}" else null, last.takeIf { it.isNotEmpty() })
            out += Row("fold:$key", f.last, text, receipt.joinToString(". "))
        }

        // approvals: what Jharvis asked to do and what he said, with the exact args as the receipt
        for ((status, word) in listOf("approved" to "You approved", "denied" to "You declined", "expired" to "")) {
            for (a in objects(raw.optJSONArray(status))) {
                val at = ms(a.s(if (status == "expired") "asked" else "decided").ifEmpty { a.s("asked") })
                if (!today(at)) continue
                val tool = human(a.s("tool"))
                val text = if (status == "expired") "${cap(tool)} request expired unanswered" else "$word $tool"
                val args = a.opt("args")?.takeIf { it != JSONObject.NULL }?.toString().orEmpty().takeIf { it != "{}" }.orEmpty()
                out += Row("approval:${a.s("id")}", at, text, args)
            }
        }

        // what Jharvis opened on the phone: open[] on his messages is the receipt
        for (m in objects(raw.optJSONArray("messages"))) {
            val opens = m.optJSONArray("open") ?: continue
            if (m.s("role") != "jharvis" || opens.length() == 0) continue
            val at = ms(m.s("timestamp"))
            if (!today(at)) continue
            val first = describe(opens.optString(0))
            val more = opens.length() - 1
            out += Row("msg:${m.s("id")}", at, if (more > 0) "$first and $more more" else first, clean(m.s("text")))
        }

        // folder tidy: Put back while the card still has its moves
        for (c in objects(raw.optJSONArray("tidy"))) {
            val at = (c.optDouble("decided_ts", 0.0) * 1000).toLong()
            if (!today(at)) continue
            val rel = c.s("rel").ifEmpty { c.s("path") }
            val id = c.s("id")
            val moved = (c.optJSONArray("moves")?.length() ?: 0) > 0
            when (c.s("status")) {
                "done" -> out += Row("tidy:$id", at, "Tidied $rel", c.s("pick"),
                    if (moved) Undo("Put back", "Put back.", "/api/tidy/$id/undo", "{}") else null)
                "undone" -> out += Row("tidy:$id", at, "Put $rel back where it was")
                "kept" -> out += Row("tidy:$id", at, "Left $rel as it is")
            }
        }

        out.sortByDescending { it.at }
        // no time and no audit entry, so these are "still muted", not "muted today": their own group
        val mutes = raw.optJSONArray("mutes")
        for (k in 0 until (mutes?.length() ?: 0)) {
            val conv = mutes!!.optString(k).takeIf { it.isNotEmpty() } ?: continue
            val pkg = conv.substringBefore('|'); val title = conv.substringAfter('|', conv)
            out += Row("mute:$conv", 0L, title, if (pkg != conv) appName(pkg) else "",
                Undo("Unmute", "Unmuted.", "/api/phone/mutes", JSONObject().put("conv", conv).put("muted", false).toString()), muted = true)
        }
        return out
    }

    /** One audit action as a plain sentence; null drops it. */
    private fun sentence(e: JSONObject, action: String, at: Long, jobs: List<JSONObject>): Row? {
        val agent = cap(e.s("agent").takeIf { it != "all" && it != "owner" }.orEmpty())
        val preview = clean(e.s("msg_preview").ifEmpty { e.s("output_preview") })
        val by = if (agent.isNotEmpty()) "by $agent" else ""
        fun row(text: String, receipt: String = preview, undo: Undo? = null) = Row("audit:${e.s("id").ifEmpty { "$action$at" }}", at, text, receipt, undo)
        return when (action) {
            "job_created" -> {
                val name = e.s("job")
                val job = jobs.firstOrNull { it.s("name") == name }
                when {
                    job == null -> row("Created the schedule $name", "Deleted since")
                    job.optBoolean("enabled", true) -> row("Created the schedule $name", "On now",
                        Undo("Turn off", "Turned off.", "/api/scheduler/jobs/${job.s("id")}", JSONObject().put("enabled", false).toString(), "PATCH"))
                    else -> row("Created the schedule $name", "Off now")
                }
            }
            "job_toggled" -> {
                val name = jobs.firstOrNull { it.s("id") == e.s("job_id") }?.s("name").orEmpty()
                row(if (e.optBoolean("enabled")) "Turned a schedule on" else "Turned a schedule off", name)
            }
            "job_deleted" -> row("Deleted a schedule", "")
            "job_triggered" -> row("Ran a schedule by hand", e.s("skill"))
            "job_decision" -> {
                val d = e.s("decision")
                val word = when (d) { "approved" -> "Approved"; "skip", "skipped" -> "Skipped"; "applied" -> "Marked as applied"; else -> cap(d) }
                row("$word the job ${e.s("job")}".trim(), "")
            }
            "job_apply" -> row("Applied to ${e.s("job")}".trim())
            "chat_message" -> row(if (agent.isNotEmpty()) "Chat with $agent" else "Chat message")
            "chat_open" -> row(if (agent.isNotEmpty()) "Opened a chat with $agent" else "Opened a chat", "")
            "ticket_open" -> row("Opened a ticket", listOf(preview, by).filter { it.isNotEmpty() }.joinToString(", "))
            "ticket_plan" -> row("Planned a ticket")
            "ticket_fix" -> row("Worked on a ticket")
            "self_review" -> row(if (agent.isNotEmpty()) "$agent reviewed the OS" else "The OS was reviewed")
            "settings_updated" -> row("Settings changed", "")
            "tidy_undo" -> row("Put $preview back where it was", "")
            "crew_posted" -> row("Posted $preview".trim(), by)
            "crew_created" -> row("Created the crew $preview".trim(), by)
            "kanban_task_created" -> row("Added a task", e.s("title"))
            "meeting_started" -> row("Started a meeting", e.s("agents").replace(",", ", "))
            "team_run" -> row("A team run finished", e.optInt("hops").takeIf { it > 0 }?.let { "$it hops" }.orEmpty())
            "reply_unverified" -> row(if (agent.isNotEmpty()) "A reply from $agent couldn't be checked" else "A reply couldn't be checked")
            "skill_installed", "skill_install_anyway" -> row("Installed the skill ${e.s("skill")}".trim(), by)
            "skill_generated" -> row("Made the skill ${e.s("name")}".trim(), clean(e.s("description")))
            "secret_set" -> row("Saved a key", preview)
            "plugin_on" -> row("Turned on a plugin", preview)
            "agent_added" -> row("Added the agent $agent".trim(), "")
            "agent_login" -> row("$agent signed in".trim(), "")
            "connection_added" -> row("Connected ${e.s("name")}".trim(), e.s("agents"))
            else -> row(cap(human(action)), listOf(preview, by).filter { it.isNotEmpty() }.joinToString(", "))
        }
    }

    /** One entry of a Jharvis message's open[] as what happened on the phone. */
    fun describe(o: String): String {
        fun dec(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
        return when {
            o.startsWith("sms:") -> "Texted " + words(dec(o.removePrefix("sms:").substringBefore('?')))
            o.startsWith("call:") || o.startsWith("tel:") -> "Called " + words(dec(o.substringAfter(':')))
            o.startsWith("app:") -> "Opened " + words(o.removePrefix("app:"))
            o.startsWith("http") -> "Opened " + o.substringAfter("://").substringBefore('/').removePrefix("www.")
            else -> "Opened $o"
        }
    }

    fun write(rows: List<Row>) = JSONArray().apply {
        for (r in rows) put(JSONObject().put("key", r.key).put("at", r.at).put("text", r.text).put("receipt", r.receipt).put("muted", r.muted)
            .put("undo", r.undo?.let { JSONObject().put("label", it.label).put("done", it.done).put("path", it.path).put("body", it.body).put("method", it.method) }))
    }

    fun read(a: JSONArray?) = objects(a).map { o ->
        Row(o.s("key"), o.optLong("at"), o.s("text"), o.s("receipt"),
            o.optJSONObject("undo")?.let { Undo(it.s("label"), it.s("done"), it.s("path"), it.optString("body"), it.s("method").ifEmpty { "POST" }) },
            o.optBoolean("muted"))
    }
}
