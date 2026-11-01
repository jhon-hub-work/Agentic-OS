package com.jhon.agenticos.ui

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.view.TouchDelegate
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.jhon.agenticos.core.Shift
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executors

/** At a glance, straight on the frost (no card): date, weather for his city, the next event, his core hours. Ticks
 *  every 30 s only while its window shows; disk, network and the calendar are read on its own thread. Long press opens
 *  GlancePrefsActivity. Lines materialize in turn the first time they have something to say. */
class GlanceView(c: Context, private val look: Look) : LinearLayout(c) {
    private class Ev(val id: Long, val title: String, val begin: Long, val end: Long)

    private val date = line(28f, look.ink, true).apply { letterSpacing = -0.022f }
    private val weather = line(17f, look.ink2)
    private val event = line(17f, look.ink)
    private val hours = line(15f, look.ink2)
    private val still = reducedMotion(c)
    private val datePattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
    private val shown = HashSet<View>()
    private val hit = Rect()
    private var revealAt = 0L
    // filled from the IO thread, read on main
    private var now: Weather.Now? = null
    private var shift: Shift? = Shift.DEFAULT
    private var next: Ev? = null
    private var calOk = false
    private var pulledAt = 0L
    private var weatherTried = 0L
    private var pulling = false
    private var ready = false  // the data lines wait for the first read, so nothing swaps text mid-arrival
    private val tick = object : Runnable {
        override fun run() { refresh(); postDelayed(this, 30_000) }
    }

    init {
        orientation = VERTICAL
        setPadding(look.px(4), look.px(4), look.px(4), look.px(4))
        addView(date)
        addView(weather, LayoutParams(-1, -2).apply { topMargin = look.px(6) })
        addView(event, LayoutParams(-2, -2).apply { topMargin = look.px(4) })
        addView(hours, LayoutParams(-1, -2).apply { topMargin = look.px(4) })
        listOf(date, weather, event, hours).forEach { it.visibility = GONE }
        setOnLongClickListener { openPrefs(); true }
        event.apply {
            look.press(this, 0.97f)
            setOnClickListener { tapEvent() }
            setOnLongClickListener { openPrefs(); true }
        }
        accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, "Change city and hours"))
            }
        }
    }

    private fun line(size: Float, color: Int, bold: Boolean = false) = look.text("", size, color, bold).apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        look.legible(this)
    }

    override fun onWindowVisibilityChanged(v: Int) {
        super.onWindowVisibilityChanged(v)
        removeCallbacks(tick)
        if (v == VISIBLE) { pulledAt = 0L; post(tick) }  // back on screen: re-read everything once
    }

    // the calendar prompt is a dialog over home, so the window never hides: catch the answer when focus comes back
    override fun onWindowFocusChanged(has: Boolean) {
        super.onWindowFocusChanged(has)
        if (has && calGranted() != calOk) { pulledAt = 0L; refresh() }
    }

    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    // the event line is one text row tall; its touch area grows to 48 dp and the full width
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        touchDelegate = if (event.visibility != VISIBLE) null else {
            val grow = ((look.px(48) - event.height) / 2).coerceAtLeast(0)
            hit.set(0, event.top - grow, width, event.bottom + grow)
            TouchDelegate(Rect(hit), event)
        }
    }

    private fun refresh() {
        val t = System.currentTimeMillis()
        set(date, DateFormat.format(datePattern, t))
        if (t - pulledAt > 5 * 60_000L) pull(t)
        if (!ready) return
        set(weather, weatherLine(t))
        set(event, eventLine(t))
        val on = shift?.isOn(Instant.ofEpochMilli(t)) == true
        hours.setTextColor(if (on) look.ink else look.ink2); hours.typeface = if (on) look.medium else look.sans
        set(hours, shift?.line(Instant.ofEpochMilli(t)))
    }

    /** Off the main thread: his hours, cached weather (fetched again when over 30 min old), the next event. */
    private fun pull(t: Long) {
        if (pulling) return
        pulling = true; pulledAt = t
        val app = context.applicationContext
        val cal = calGranted()
        val city = Weather.place(app).name
        val mayFetch = t - weatherTried > 5 * 60_000L || now?.city != city
        if (mayFetch) weatherTried = t
        io.execute {
            try {
                val s = Shift.load()
                val cached = Weather.cached(app)
                val ev = if (cal) nextEvent(app, t) else null
                post { shift = s; now = cached; next = ev; calOk = cal; ready = true; refresh() }
                if (mayFetch && (cached == null || t - cached.at > Weather.FRESH_MS)) Weather.fetch(app)?.let { post { now = it; refresh() } }
            } catch (e: Throwable) { android.util.Log.w("Glance", "glance read failed", e) }  // a throw here would take Home down
            finally { post { pulling = false } }
        }
    }

    private fun weatherLine(t: Long): CharSequence {
        val city = Weather.place(context).name
        val w = now?.takeIf { it.city == city && t - it.at < DateUtils.DAY_IN_MILLIS }
        weather.setTextColor(if (w == null) look.ink3 else look.ink2)
        if (w == null) return if (now?.city == city) "$city, weather out of date" else "$city, no weather yet"  // always named
        val s = SpannableStringBuilder().append("${w.temp}°", ForegroundColorSpan(look.ink), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            .append(" ${Weather.words(w.code)} in $city")
        when {
            t - w.at > 2 * DateUtils.HOUR_IN_MILLIS -> s.append(", as of ${DateFormat.getTimeFormat(context).format(w.at)}")
            (w.rain ?: 0) >= 30 -> s.append(", rain ${w.rain}%")
            w.hi != null -> s.append(", high ${w.hi}°")
        }
        return s
    }

    private fun eventLine(t: Long): CharSequence? {
        val allow = !calGranted()
        event.setTextColor(if (allow) look.navy else look.ink); event.typeface = if (allow) look.medium else look.sans
        if (allow) return "Allow calendar"
        val e = next?.takeIf { it.end > t } ?: return null
        val title = e.title.ifBlank { "Event" }
        val time = DateFormat.getTimeFormat(context)
        val text = when {
            e.begin <= t -> "$title now, until ${time.format(e.end)}"
            e.begin - t < DateUtils.HOUR_IN_MILLIS -> "$title in ${Shift.span(Duration.ofMillis(e.begin - t))}"
            DateUtils.isToday(e.begin) -> "$title at ${time.format(e.begin)}"
            else -> "$title tomorrow at ${time.format(e.begin)}"
        }
        event.contentDescription = "$text. Opens the event."
        return text
    }

    private fun set(v: TextView, s: CharSequence?) {
        if (s == null) { v.visibility = GONE; return }
        if (v.text.toString() != s.toString()) v.text = s
        v.visibility = VISIBLE
        if (shown.add(v)) materialize(v)
    }

    /** Arrive as a material: scale 0.92 to 1, alpha and an 8 dp blur clearing together, each line a beat after the last. */
    private fun materialize(v: View) {
        val t = SystemClock.uptimeMillis()
        val at = maxOf(t, revealAt + 70); revealAt = at
        v.alpha = 0f
        if (still) { v.animate().alpha(1f).setStartDelay(at - t).setDuration(180).start(); return }
        val r = look.px(8).toFloat()
        v.pivotX = 0f; v.pivotY = 0f  // grows from its first letter
        v.scaleX = 0.92f; v.scaleY = 0.92f; v.blur(r)
        val s = Spring(0f) { f -> v.alpha = f; val k = 0.92f + 0.08f * f; v.scaleX = k; v.scaleY = k; v.blur((1 - f) * r) }
        postDelayed({ s.to(1f, response = 0.6f, damping = 1f) }, at - t)
    }

    private fun tapEvent() {
        val a = context as? Activity ?: return
        if (!calGranted()) { askCalendar(a); return }
        val e = next ?: return
        a.launchFrom(event, Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end))
    }

    private fun openPrefs() = context.startActivity(Intent(context, GlancePrefsActivity::class.java))

    private fun calGranted() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    companion object {
        private val io = Executors.newSingleThreadExecutor { Thread(it, "glance").apply { isDaemon = true } }

        /** The system prompt; once Android stops showing it (two no's), the app's settings page instead. The resume
         *  or focus change afterwards re-reads, so no result callback is needed. */
        fun askCalendar(a: Activity) {
            val p = a.getSharedPreferences("aos", Context.MODE_PRIVATE)
            if (p.getBoolean("glance_cal_asked", false) && !a.shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR))
                a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.packageName, null)))
            else {
                p.edit().putBoolean("glance_cal_asked", true).apply()
                a.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 41)
            }
        }

        /** The first event in the next 24 h that's visible, not declined, not all day and not over. */
        private fun nextEvent(c: Context, t: Long): Ev? = try {
            val cols = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.SELF_ATTENDEE_STATUS,
                CalendarContract.Instances.VISIBLE)
            CalendarContract.Instances.query(c.contentResolver, cols, t, t + DateUtils.DAY_IN_MILLIS)?.use { cur ->
                var best: Ev? = null
                while (cur.moveToNext()) {
                    if (cur.getInt(4) == 1 || cur.getInt(6) == 0 || cur.getInt(5) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    val b = cur.getLong(2); val e = cur.getLong(3)
                    if (e > t && (best == null || b < best.begin)) best = Ev(cur.getLong(0), cur.getString(1).orEmpty(), b, e)
                }
                best
            }
        } catch (_: Exception) { null }
    }
}
