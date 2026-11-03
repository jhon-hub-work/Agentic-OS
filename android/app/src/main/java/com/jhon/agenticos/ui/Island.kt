package com.jhon.agenticos.ui

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.jhon.agenticos.R
import com.jhon.agenticos.core.Snooze
import kotlin.math.abs

/** What's playing, as the island shows it. */
class NowPlaying(val title: String, val artist: String, val art: Drawable?, val playing: Boolean,
    val playPause: () -> Unit, val next: () -> Unit, val previous: () -> Unit)

/** A notification, as the island shows it. */
class Alert(val key: String, val pkg: String, val icon: Drawable?, val title: String, val text: String, val open: PendingIntent?)

/** The iOS Dynamic Island, ported from 27launch: a black pill over every app, around the camera hole.
 *  idle: a small pill hugging the camera. music: wider, cover art left, level bars right; tap opens title, artist and
 *  the three controls. alert: grows out of the pill for 4 s (tap opens it, swipe up hides it, hold for more text);
 *  alerts that arrive meanwhile wait their turn; held open it offers "Mute for 1 hour" and "Mute for today" for that app
 *  (onMute stores it; TalkBack gets the same two as actions). charging: a short "Charging" flash.
 *  Width, height and corners morph on one spring each, so a new state catches the pill mid-flight from where it is.
 *  Once it settles the window is exactly the pill, so it never blocks touches meant for the app below. */
class Island(private val c: Context, private val onMute: (pkg: String, until: Long) -> Unit) {
    private enum class Mode { IDLE, MUSIC, MUSIC_OPEN, ALERT, ALERT_OPEN, CHARGING }

    private val look = Look(c)
    private val wm = c.getSystemService(WindowManager::class.java)
    private val displays = c.getSystemService(DisplayManager::class.java)
    private val still = reducedMotion(c)
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var mode = Mode.IDLE
    private var music: NowPlaying? = null
    private var alert: Alert? = null
    private val queue = ArrayDeque<Alert>()  // alerts that came in while one was showing
    private var level = 0
    private var idleH = look.px(34)
    private var added = false
    private var landscape = false
    private var fullscreen = false
    private var locked = false  // the keyguard is up: no notification text over the lock screen
    private var rotation: DisplayManager.DisplayListener? = null
    private val collapse = Runnable { endAlert() }
    private val nextAlert = Runnable { queue.removeFirstOrNull()?.let(::present) }
    private var downY = 0f
    private var held = false
    private val hold = Runnable { held = true; pill.tick(); show(Mode.ALERT_OPEN); timer() }

    private val shape = GradientDrawable().apply { setColor(Color.BLACK) }  // black in the light theme too, as on iOS
    private val pill = FrameLayout(c).apply {
        background = shape
        clipToOutline = true  // the outline comes from the rounded background, so content is cut to the pill
        setOnTouchListener { _, e -> touch(e) }
        setOnClickListener { onTap() }
    }
    private val root = FrameLayout(c).apply {
        addView(pill, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) when (mode) {
                Mode.MUSIC_OPEN -> show(Mode.MUSIC)
                Mode.ALERT_OPEN -> endAlert()
                else -> Unit
            }
            false
        }
        setOnApplyWindowInsetsListener { _, i ->
            // an app hid the status bar (a film, a game): step aside. Only counted when this window has a status bar at all.
            val bar = WindowInsets.Type.statusBars()
            val full = i.getInsetsIgnoringVisibility(bar).top > 0 && !i.isVisible(bar)
            if (full != fullscreen) { fullscreen = full; applyHidden() }
            i
        }
    }
    private val params = WindowManager.LayoutParams(1, 1,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,  // the only window allowed above Samsung's status bar
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = "Agentic OS island"
    }

    private val w = Spring(1f, 0.5f) { resize() }
    private val h = Spring(1f, 0.5f) { resize() }
    private val squish = Spring(1f, 0.0005f) { pill.scaleX = it; pill.scaleY = it }
    private val lift = Spring(0f, 0.5f) { pill.translationY = it }

    init { w.onRest = ::settled; h.onRest = ::settled }

    fun attach() {
        landscape = sideways()
        place()
        w.snap(look.px(IDLE_W).toFloat()); h.snap(idleH.toFloat())
        params.width = look.px(IDLE_W); params.height = idleH
        try { wm.addView(root, params) } catch (_: Exception) { return }  // service being switched off
        added = true
        rotation = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(id: Int) {
                if (id != Display.DEFAULT_DISPLAY || sideways() == landscape) return  // refresh-rate changes land here too
                landscape = !landscape; applyHidden()
            }
            override fun onDisplayAdded(id: Int) = Unit
            override fun onDisplayRemoved(id: Int) = Unit
        }.also { displays.registerDisplayListener(it, Handler(Looper.getMainLooper())) }
        applyHidden()
    }

    fun detach() {
        rotation?.let(displays::unregisterDisplayListener); rotation = null
        listOf(collapse, nextAlert, hold).forEach(pill::removeCallbacks)
        listOf(w, h, squish, lift).forEach(Spring::stop)
        if (added) runCatching { wm.removeViewImmediate(root) }
        added = false
    }

    /** Null when nothing is playing (or the player went away). */
    fun setMusic(now: NowPlaying?) {
        music = now
        when {
            mode == Mode.ALERT || mode == Mode.ALERT_OPEN || mode == Mode.CHARGING -> Unit  // finishes first, then rests on the music
            mode == Mode.MUSIC_OPEN && now != null && !hidden() -> {  // new title or play state: swap the words, keep the shape moving
                pill.removeAllViews()
                pill.addView(openMusic(now), FrameLayout.LayoutParams(wide(), look.px(170), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            }
            else -> show(rest())
        }
    }

    /** The phone locked or unlocked. Locked: whatever alert shows goes, waiting ones are dropped, new ones never pop
     *  (the system's own lock-screen notifications have them, under its privacy setting). Music and charging stay. */
    fun locked(on: Boolean) {
        if (on == locked) return
        locked = on
        if (!on) return
        if (mode == Mode.MUSIC_OPEN) show(Mode.MUSIC)  // the open panel would sit under the lock card and catch its touches
        queue.clear()
        if (alert != null) endAlert()
    }

    fun alert(a: Alert) {
        if (!added || hidden() || locked || alert?.key == a.key) return  // sideways or full screen: the system's own banner has it
        if (alert != null || queue.isNotEmpty()) {
            queue.removeAll { it.key == a.key }
            queue.addLast(a)
            if (queue.size > QUEUE_MAX) queue.removeFirst()  // a burst: the oldest waiting ones go
            return
        }
        present(a)
    }

    /** Plugged in: widen briefly to "Charging" and the level. Never covers music or a notification. */
    fun charging(percent: Int) {
        if (mode != Mode.IDLE || hidden()) return
        level = percent
        show(Mode.CHARGING); timer()
    }

    private fun present(a: Alert) {
        if (hidden()) { queue.clear(); return }
        alert = a
        show(Mode.ALERT); pill.tick(); timer()
    }

    private fun endAlert() {
        listOf(collapse, hold, nextAlert).forEach(pill::removeCallbacks)
        alert = null
        show(rest())
        if (queue.isNotEmpty()) pill.postDelayed(nextAlert, GAP_MS)  // back to the pill for a beat, then the next one
    }

    private fun timer() {
        pill.removeCallbacks(collapse)
        pill.postDelayed(collapse, when (mode) { Mode.ALERT -> ALERT_MS; Mode.ALERT_OPEN -> OPEN_MS; Mode.CHARGING -> CHARGE_MS; else -> return })
    }

    private fun rest() = if (music != null) Mode.MUSIC else Mode.IDLE

    private fun onTap() {
        when (mode) {
            Mode.MUSIC -> show(Mode.MUSIC_OPEN)
            Mode.MUSIC_OPEN -> show(Mode.MUSIC)
            Mode.ALERT, Mode.ALERT_OPEN -> { alert?.open?.let(::send); endAlert() }
            Mode.CHARGING -> endAlert()
            Mode.IDLE -> Unit
        }
    }

    /** Press squishes with a tick; an alert follows the finger up (rubber band) and goes if pulled far enough. */
    private fun touch(e: MotionEvent): Boolean {
        if (mode == Mode.IDLE) return false
        val alerting = mode == Mode.ALERT || mode == Mode.ALERT_OPEN
        val dy = e.rawY - downY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.rawY; held = false
                pill.tick()
                if (!still) squish.to(0.96f, response = 0.16f, damping = 1f)
                pill.removeCallbacks(collapse)  // nothing times out under a finger
                if (mode == Mode.ALERT) pill.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(dy) > slop) pill.removeCallbacks(hold)
                if (alerting && !still) lift.snap(rubberband(minOf(0f, dy), h.value))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pill.removeCallbacks(hold)
                if (!still) { squish.to(1f, response = 0.38f, damping = 0.55f); lift.to(0f, RESPONSE, 1f) }
                val up = e.actionMasked == MotionEvent.ACTION_UP
                when {
                    alerting && up && dy < -look.px(24) -> endAlert()  // swiped up: away
                    up && !held && abs(dy) < slop -> pill.performClick()
                    else -> timer()
                }
            }
        }
        return true
    }

    /** Android 14+ blocks a background service from opening apps unless the sender opts in. */
    private fun send(p: PendingIntent) {
        val opts = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
        runCatching { p.send(c, 0, null, null, null, null, opts) }
    }

    private fun show(target: Mode, animate: Boolean = !still) {
        mode = target
        if (!added || hidden()) return  // drawn when it's back
        val wide = wide()
        val v = when (target) {
            Mode.IDLE -> null
            Mode.MUSIC -> music?.let(::compactMusic)
            Mode.MUSIC_OPEN -> music?.let(::openMusic)
            Mode.ALERT -> alert?.let { alertView(it, false) }
            Mode.ALERT_OPEN -> alert?.let { alertView(it, true) }
            Mode.CHARGING -> chargingView()
        }
        val tw = when (target) { Mode.IDLE -> look.px(IDLE_W); Mode.MUSIC -> look.px(MUSIC_W); Mode.CHARGING -> wide - look.px(40); else -> wide }
        val th = when (target) {
            Mode.MUSIC_OPEN -> look.px(170)
            Mode.ALERT -> look.px(76)
            Mode.ALERT_OPEN -> v?.let {  // as tall as the words need, within reason
                it.measure(View.MeasureSpec.makeMeasureSpec(wide, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
                it.measuredHeight.coerceIn(look.px(76), look.px(300))
            } ?: look.px(76)
            else -> idleH
        }
        // old words blur away fast while the shape moves; new ones arrive once it has room, at their final size, cut by the pill
        val old = (0 until pill.childCount).map(pill::getChildAt)
        if (animate) old.forEach { o -> o.animate().alpha(0f).setStartDelay(0).setDuration(90).withEndAction { pill.removeView(o) }.start() }
        else pill.removeAllViews()
        v?.let {
            pill.addView(it, FrameLayout.LayoutParams(tw, th, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            if (animate) {
                it.alpha = 0f; it.scaleX = 0.92f; it.scaleY = 0.92f
                it.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(90).setDuration(420).setInterpolator(SpringCurve(0.8f)).start()
            }
        }
        if (!animate) { w.snap(tw.toFloat()); h.snap(th.toFloat()); setWindow(tw, th); return }
        // full width while it moves, with room under it for the spring's overshoot; shrunk to the pill when it settles
        setWindow(screenW(), maxOf(params.height, maxOf(h.value.toInt(), th) + look.px(16)))
        w.to(tw.toFloat(), RESPONSE, DAMPING)
        h.to(th.toFloat(), RESPONSE, DAMPING)
    }

    private fun resize() {
        val lp = pill.layoutParams
        lp.width = w.value.toInt().coerceAtLeast(1); lp.height = h.value.toInt().coerceAtLeast(1)
        pill.layoutParams = lp
        shape.cornerRadius = minOf(h.value / 2, look.px(44).toFloat())  // corners follow the height: a round pill small, a soft card open
        pill.invalidateOutline()
    }

    private fun settled() {
        if (w.velocity == 0f && h.velocity == 0f && w.value == w.target && h.value == h.target) setWindow(w.target.toInt(), h.target.toInt())
    }

    private fun setWindow(width: Int, height: Int) {
        if (!added || (params.width == width && params.height == height)) return
        params.width = width; params.height = height
        runCatching { wm.updateViewLayout(root, params) }
    }

    private fun hidden() = landscape || fullscreen

    /** Sideways or full screen: the pill goes and lets touches through; whatever was popping is dropped. */
    private fun applyHidden() {
        val hide = hidden()
        pill.visibility = if (hide) View.INVISIBLE else View.VISIBLE  // the pill, not the window: a hidden window stops hearing about insets
        val off = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        params.flags = if (hide) params.flags or off else params.flags and off.inv()
        if (added) runCatching { wm.updateViewLayout(root, params) }
        if (hide) {
            listOf(collapse, nextAlert, hold).forEach(pill::removeCallbacks)
            alert = null; queue.clear(); mode = rest()
        } else { place(); show(mode, animate = false) }
    }

    /** On the camera: centred on the hole, nudged down a little (Jhon found the edge too close, in 27launch). */
    private fun place() {
        if (landscape) return
        val m = wm.currentWindowMetrics
        // only a hole in the top quarter counts: while the display settles the metrics can hand back the other rotation's
        val cam = m.windowInsets.displayCutout?.boundingRects.orEmpty().firstOrNull { !it.isEmpty && it.top < m.bounds.height() / 4 }
        idleH = maxOf(look.px(34), (cam?.let { minOf(it.width(), it.height()) } ?: 0) + look.px(12))
        val bar = m.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
        params.y = (maxOf(0, (cam?.centerY() ?: (idleH / 2)) - idleH / 2) + look.px(DROP)).coerceAtMost(bar + look.px(DROP))
        if (added) runCatching { wm.updateViewLayout(root, params) }
    }

    private fun sideways() = displays.getDisplay(Display.DEFAULT_DISPLAY).rotation.let { it == Surface.ROTATION_90 || it == Surface.ROTATION_270 }
    private fun screenW() = wm.currentWindowMetrics.bounds.width()
    private fun wide() = screenW() - look.px(24)

    private fun compactMusic(n: NowPlaying) = FrameLayout(c).apply {
        val s = idleH - look.px(10)
        addView(art(n.art, s, s / 2f), FrameLayout.LayoutParams(s, s, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = look.px(6) })
        addView(bars(n.playing), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = look.px(14) })
        contentDescription = "Playing ${n.title}"
    }

    private fun openMusic(n: NowPlaying) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(look.px(18), look.px(18), look.px(18), look.px(10))
        val s = look.px(56)
        addView(LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(art(n.art, s, look.px(12).toFloat()), LinearLayout.LayoutParams(s, s))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(look.px(14), 0, 0, 0)
                addView(line(n.title, 17f, Color.WHITE, true))
                if (n.artist.isNotEmpty()) addView(line(n.artist, 15f, DIM))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        })
        addView(LinearLayout(c).apply {
            gravity = Gravity.CENTER
            addView(control(android.R.drawable.ic_media_previous, "Previous", n.previous))
            addView(control(if (n.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (n.playing) "Pause" else "Play", n.playPause))
            addView(control(android.R.drawable.ic_media_next, "Next", n.next))
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private fun alertView(a: Alert, open: Boolean): View {
        val head = LinearLayout(c).apply {
            gravity = if (open) Gravity.TOP else Gravity.CENTER_VERTICAL
            if (!open) setPadding(look.px(14), 0, look.px(18), 0)
            val s = look.px(44)
            addView(art(a.icon, s, look.px(10).toFloat()), LinearLayout.LayoutParams(s, s))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(look.px(12), 0, 0, 0)
                addView(line(a.title, 15f, Color.WHITE, true))
                if (a.text.isNotEmpty()) addView(line(a.text, 13f, DIM).apply { maxLines = if (open) 6 else 1 })
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            contentDescription = "${a.title}. ${a.text}"
            muteActions(this, a)
        }
        if (!open) return head
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(look.px(14), look.px(16), look.px(14), look.px(14))
            addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(LinearLayout(c).apply {
                addView(muteButton("Mute for 1 hour") { mute(a, System.currentTimeMillis() + Snooze.HOUR_MS) }, LinearLayout.LayoutParams(0, look.px(48), 1f))
                addView(muteButton("Mute for today") { mute(a, Snooze.endOfDay(System.currentTimeMillis())) },
                    LinearLayout.LayoutParams(0, look.px(48), 1f).apply { marginStart = look.px(8) })
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = look.px(14) })
        }
    }

    private fun muteButton(label: String, act: () -> Unit) = look.text(label, 14f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        background = look.round(Color.argb(46, 255, 255, 255), 24)
        contentDescription = "$label, this app"
        look.press(this, 0.96f)
        setOnClickListener { act() }
    }

    /** TalkBack can't hold the pill, so the two mutes are also actions on the alert. */
    private fun muteActions(v: View, a: Alert) {
        v.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.island_mute_hour, "Mute this app for 1 hour"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.island_mute_day, "Mute this app for today"))
            }
            override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean = when (action) {
                R.id.island_mute_hour -> { mute(a, System.currentTimeMillis() + Snooze.HOUR_MS); true }
                R.id.island_mute_day -> { mute(a, Snooze.endOfDay(System.currentTimeMillis())); true }
                else -> super.performAccessibilityAction(host, action, args)
            }
        }
    }

    /** That app goes quiet on the island until then: this alert closes and its waiting ones are dropped. */
    private fun mute(a: Alert, until: Long) {
        if (alert?.key != a.key) return  // a late tap on an alert that already went
        pill.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        queue.removeAll { it.pkg == a.pkg }
        onMute(a.pkg, until)
        endAlert()
    }

    private fun chargingView() = FrameLayout(c).apply {
        addView(line("Charging", 15f, Color.WHITE, true),
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = look.px(20) })
        addView(LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(line("$level%", 15f, OK, true))
            addView(ChargingGlyph(c, level), LinearLayout.LayoutParams(look.px(31), look.px(14)).apply { marginStart = look.px(7) })
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = look.px(18) })
    }

    private fun art(d: Drawable?, size: Int, corner: Float) = ImageView(c).apply {
        setImageDrawable(d)
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable().apply { setColor(Color.parseColor("#22344B")); cornerRadius = corner }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, corner)
        }
        clipToOutline = true
        layoutParams = ViewGroup.LayoutParams(size, size)
    }

    /** Three level bars in Glacier; they pulse while playing (still with reduced motion). */
    private fun bars(playing: Boolean) = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        listOf(0.5f, 1f, 0.7f).forEach { s ->
            val bar = View(c).apply {
                background = look.round(look.glacier, 2)
                pivotY = look.px(8).toFloat(); scaleY = s
            }
            addView(bar, LinearLayout.LayoutParams(look.px(3), look.px(16)).apply { marginStart = look.px(3) })
            if (playing && !still) bar.post { pulse(bar, s) }
        }
    }

    private fun pulse(bar: View, s: Float) {
        if (!bar.isAttachedToWindow) return
        bar.animate().scaleY(if (bar.scaleY < 0.6f) 1f else 0.35f).setDuration((330 + s * 150).toLong()).withEndAction { pulse(bar, s) }.start()
    }

    private fun control(icon: Int, label: String, act: () -> Unit) = ImageView(c).apply {
        setImageResource(icon); setColorFilter(Color.WHITE)
        val p = look.px(10); setPadding(p, p, p, p)
        contentDescription = label
        layoutParams = LinearLayout.LayoutParams(look.px(56), look.px(56)).apply { marginStart = look.px(12); marginEnd = look.px(12) }
        look.press(this)
        setOnClickListener { act() }
    }

    private fun line(s: String, size: Float, color: Int, bold: Boolean = false) =
        look.text(s, size, color, bold).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }

    /** The small battery beside "Charging": dim shell, bright charge, a tiny nub. */
    private class ChargingGlyph(c: Context, private val level: Int) : View(c) {
        private val d = c.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        override fun onDraw(canvas: Canvas) {
            val bw = width - 3 * d
            val corner = 4 * d
            paint.color = Color.argb(0x59, Color.red(OK), Color.green(OK), Color.blue(OK))
            r.set(0f, 0f, bw, height.toFloat()); canvas.drawRoundRect(r, corner, corner, paint)
            r.set(bw + d, height / 2f - 2.5f * d, width.toFloat(), height / 2f + 2.5f * d); canvas.drawRoundRect(r, d, d, paint)
            paint.color = OK
            canvas.save()
            canvas.clipRect(0f, 0f, bw * level.coerceIn(0, 100) / 100f, height.toFloat())
            r.set(0f, 0f, bw, height.toFloat()); canvas.drawRoundRect(r, corner, corner, paint)
            canvas.restore()
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val RESPONSE = 0.45f
        const val DAMPING = 0.75f
        const val IDLE_W = 96  // dp: compact Android take on the iPhone's pill (Apple doesn't publish its size)
        const val MUSIC_W = 210
        const val DROP = 6  // dp below the camera's centre line
        const val ALERT_MS = 4000L
        const val OPEN_MS = 8000L  // held open: time to read the longer text
        const val CHARGE_MS = 3000L
        const val GAP_MS = 350L
        const val QUEUE_MAX = 5
        // the island is a dark surface, so it takes DESIGN.md's night tokens whatever the theme
        val DIM = Color.parseColor("#A9B8C8")
        val OK = Color.parseColor("#4CC38A")
    }
}
