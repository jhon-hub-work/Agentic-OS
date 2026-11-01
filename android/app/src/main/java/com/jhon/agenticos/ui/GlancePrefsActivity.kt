package com.jhon.agenticos.ui

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.jhon.agenticos.R
import com.jhon.agenticos.core.Shift
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.Executors

/** At a glance settings, in the same iOS look as SetupActivity: the weather city (Open-Meteo search), his core hours
 *  and days, calendar access. Opened by a long press on the glance lines; pushes in from the right. */
class GlancePrefsActivity : Activity() {
    private lateinit var look: Look
    private val io = Executors.newSingleThreadExecutor()  // one thread: saves land in order, searches answer in order
    private val hairline get() = look.hairline
    private var shift = Shift.DEFAULT
    private lateinit var hoursNote: android.widget.TextView
    private val refresh = mutableListOf<() -> Unit>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        look = Look(this)
        pushTransition()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val big = look.text("At a glance", 34f, look.ink, true).apply { letterSpacing = -0.025f; setPadding(look.px(20), 0, look.px(20), look.px(4)) }
        column.addView(big)
        val cascade = mutableListOf<View>()
        fun add(v: View, lp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2)) { column.addView(v, lp); cascade += v }
        fun header(s: String) = add(look.text(s, 13f, look.ink3).apply { setPadding(look.px(32), look.px(22), look.px(32), look.px(7)) })
        fun footer(s: String) = look.text(s, 13f, look.ink3).apply { setPadding(look.px(32), look.px(7), look.px(32), 0) }.also { add(it) }
        fun card(group: LinearLayout) = add(group, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = look.px(16); rightMargin = look.px(16) })

        // weather: the city he picked, and a search that lists places as he types
        val city = value(Weather.place(this).name)
        val query = EditText(this).apply {
            hint = "Search for a city"; textSize = 17f; setTextColor(look.ink); setHintTextColor(look.ink3); typeface = look.sans
            background = null; setSingleLine(); minHeight = look.px(52); setPadding(look.px(16), 0, look.px(16), 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS; imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val results = group()
        header("Weather")
        card(group(row("City", city), query))
        val note = "Weather from Open-Meteo, checked every 30 minutes while Home is on screen."
        val status = footer(note)
        add(results, LinearLayout.LayoutParams(-1, -2).apply { setMargins(look.px(16), look.px(10), look.px(16), 0) })
        results.visibility = View.GONE
        val search = Runnable {
            val q = query.text.toString().trim()
            status.text = "Searching"
            bg {
                val found = try { Weather.search(q) } catch (_: Exception) { null }
                runOnUiThread {
                    if (query.text.toString().trim() != q) return@runOnUiThread  // he kept typing: a newer search is coming
                    status.text = when { found == null -> "Can't reach the internet. Try again in a moment."; found.isEmpty() -> "No places called $q."; else -> note }
                    results.removeAllViews()
                    found.orEmpty().forEachIndexed { i, p ->
                        if (i > 0) results.addView(rule())
                        results.addView(row(p.name, null, p.where.ifBlank { null }) {
                            bg { Weather.setPlace(this, p) }
                            city.text = p.name
                            results.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            query.text.clear(); query.clearFocus()
                            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(query.windowToken, 0)
                        })
                    }
                    val show = !found.isNullOrEmpty()
                    if (show && results.visibility != View.VISIBLE) {
                        results.alpha = 0f; results.translationY = look.px(8).toFloat()
                        results.animate().alpha(1f).translationY(0f).setDuration(if (reducedMotion(this)) 0 else 420).setInterpolator(SpringCurve(1f)).start()
                    }
                    results.visibility = if (show) View.VISIBLE else View.GONE
                }
            }
        }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query.removeCallbacks(search)
                if (s.toString().trim().length >= 2) query.postDelayed(search, 350)
                else { results.visibility = View.GONE; status.text = note }
            }
        })
        query.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH && query.text.isNotBlank()) { query.removeCallbacks(search); search.run() }
            true
        }

        // core hours: start, end, and the days they run
        val time = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), if (DateFormat.is24HourFormat(this)) "Hm" else "hm"))
        val starts = value(""); val ends = value("")
        header("Core hours")
        val hoursCard = group(
            row("Starts", starts) { pick(shift.start) { shift = shift.copy(start = it); save() } },
            row("Ends", ends) { pick(shift.end) { shift = shift.copy(end = it); save() } })
        DayOfWeek.values().forEach { d ->
            val check = ImageView(this).apply { setImageResource(R.drawable.ic_glance_check); imageTintList = ColorStateList.valueOf(look.navy) }
            val name = d.getDisplayName(TextStyle.FULL, Locale.getDefault())
            hoursCard.addView(rule())
            val r = row(name, check) { shift = shift.copy(days = if (d in shift.days) shift.days - d else shift.days + d); save() }
            hoursCard.addView(r)
            refresh += {
                val on = d in shift.days
                check.visibility = if (on) View.VISIBLE else View.INVISIBLE
                r.contentDescription = "$name, ${if (on) "on" else "off"}"
            }
        }
        card(hoursCard)
        hoursNote = footer("")
        refresh += {
            starts.text = time.format(shift.start); ends.text = time.format(shift.end)
            val zone = shift.zone.id.substringAfterLast('/').replace('_', ' ')
            hoursNote.text = "Your fixed hours, in $zone time. The rest of your day stays flexible. Home counts down to them " +
                "and shows the time left while they run."
        }

        // calendar: the next event on Home needs read access
        val access = value("")
        header("Calendar")
        card(group(row("Calendar access", access) {
            if (calendarAllowed()) startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            else GlanceView.askCalendar(this)
        }))
        footer("Home shows your next event in the coming 24 hours. Tap it there to open it.")
        refresh += { access.text = if (calendarAllowed()) "Allowed" else "Off"; access.setTextColor(look.ink3) }

        val scroll = ScrollView(this).apply { clipToPadding = false; addView(column) }
        val fill = ColorDrawable(look.bar)
        val hair = View(this).apply { setBackgroundColor(hairline) }
        val small = look.text("At a glance", 17f, look.ink, true).apply { gravity = Gravity.CENTER }
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
                val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                bar.setPadding(i.left, i.top, i.right, 0)
                scroll.setPadding(i.left, i.top + look.px(52), i.right, maxOf(i.bottom, ime) + look.px(32))
                WindowInsets.CONSUMED
            }
        })
        look.bars(window)

        // iOS large-title collapse, 1:1 with the scroll (same as Settings)
        fun collapse(y: Int) {
            val span = big.height.coerceAtLeast(1).toFloat()
            val t = (y / span).coerceIn(0f, 1f)
            big.alpha = 1 - t
            fill.alpha = (t * 255).toInt(); hair.alpha = t
            val s = ((y - span * 0.6f) / (span * 0.4f)).coerceIn(0f, 1f)
            small.alpha = s; small.translationY = (1 - s) * look.px(6)
        }
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> collapse(y) }
        collapse(0)

        if (state == null && !reducedMotion(this)) cascade.forEachIndexed { i, v ->
            v.alpha = 0f; v.translationY = look.px(20).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(90L + i * 35).setDuration(620).setInterpolator(SpringCurve(0.8f)).start()
        }

        refresh.forEach { it() }
        bg { val s = Shift.load(); runOnUiThread { s?.let { shift = it }; refresh.forEach { it() } } }  // cleared = edit from the default
    }

    override fun onResume() { super.onResume(); if (::look.isInitialized) refresh.forEach { it() } }  // back from the permission screen

    override fun onDestroy() { io.shutdown(); super.onDestroy() }  // queued saves still finish

    // a search still waiting on its 350 ms, or a time picked after Back, must not post to a shut executor
    // and a throw on the worker (an odd city answer, a full disk) is logged, never an app crash
    private fun bg(job: () -> Unit) {
        if (!io.isShutdown) io.execute { try { job() } catch (t: Throwable) { android.util.Log.w("Glance", "glance settings job failed", t) } }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(R.anim.set_pop_in, R.anim.set_pop_out)
    }

    @Suppress("DEPRECATION")
    private fun pushTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.set_push_in, R.anim.set_push_out)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.set_pop_in, R.anim.set_pop_out)
        } else overridePendingTransition(R.anim.set_push_in, R.anim.set_push_out)
    }

    private fun calendarAllowed() = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun pick(t: LocalTime, done: (LocalTime) -> Unit) =
        TimePickerDialog(this, { _, h, m -> done(LocalTime.of(h, m)) }, t.hour, t.minute, DateFormat.is24HourFormat(this)).show()

    private fun save() {
        refresh.forEach { it() }
        val s = shift
        bg { try { Shift.save(s) } catch (_: IOException) { runOnUiThread { hoursNote.text = "Couldn't save your hours. Try again." } } }
    }

    private fun value(s: String) = look.text(s, 17f, look.ink3)

    private fun rule() = View(this).apply { setBackgroundColor(hairline); layoutParams = LinearLayout.LayoutParams(-1, 1).apply { leftMargin = look.px(16) } }

    private fun group(vararg rows: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = look.glassCard(16); clipToOutline = true
        rows.forEachIndexed { i, r -> if (i > 0) addView(rule()); addView(r) }
    }

    /** One row: title (and a smaller line under it), then a value or mark on the right. tap = null: info only. */
    private fun row(title: String, trailing: View?, sub: String? = null, tap: (() -> Unit)? = null): View {
        val r = object : LinearLayout(this) {
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {  // highlight on touch-down, not after the scroll delay
                if (tap != null && e.actionMasked == MotionEvent.ACTION_DOWN) isPressed = true
                return super.dispatchTouchEvent(e)
            }
        }.apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = look.px(52); setPadding(look.px(16), look.px(8), look.px(14), look.px(8))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(look.text(title, 17f, look.ink))
                sub?.let { addView(look.text(it, 13f, look.ink3)) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            trailing?.let { addView(it, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = look.px(8) }) }
            if (tap != null) {
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(look.pressed))
                    setExitFadeDuration(250)
                }
                look.press(this, 0.985f); setOnClickListener { tap() }
            }
        }
        return r
    }
}
