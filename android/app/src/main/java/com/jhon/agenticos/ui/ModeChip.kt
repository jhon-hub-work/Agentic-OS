package com.jhon.agenticos.ui

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.jhon.agenticos.R
import com.jhon.agenticos.core.Car
import com.jhon.agenticos.core.Mode
import com.jhon.agenticos.core.ModeState
import com.jhon.agenticos.core.ModeTile
import com.jhon.agenticos.core.Modes
import com.jhon.agenticos.core.Shift
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** The mode chip in home's top row: the mode's name in Glacier, or a faint "Mode" when Off. A tap grows a small
 *  glass menu out of the chip. mode.json is read and written off the main thread; onChange runs after any switch,
 *  including ones made by the Quick Settings tile or the clock (core hours, the 3 h Driving end). */
class ModeChip(c: Context, private val look: Look, private val onChange: () -> Unit) : LinearLayout(c) {
    private data class Snap(val state: ModeState, val mode: Mode, val hours: String?, val car: Car?)

    private val word = look.text("Mode", 14f, look.ink3, bold = true).apply {
        gravity = Gravity.CENTER; setPadding(look.px(14), 0, look.px(14), 0)
        background = look.glassCard(17)
    }
    private val still = reducedMotion(c)
    private val arrive = Spring(1f) { p -> word.alpha = p; if (!still) { word.scaleX = 0.92f + 0.08f * p; word.scaleY = word.scaleX; word.blur(look.px(8) * (1 - p)) } }
    private var snap: Snap? = null
    private var menu: Menu? = null
    private var visible = false
    private val tick = Runnable { refresh() }  // core hours and the Driving cap end with no event to hear
    private var io: ExecutorService? = null  // one at a time, so a write and the read after it stay in order; lives while attached

    init {
        gravity = Gravity.CENTER
        minimumHeight = look.px(48); minimumWidth = look.px(48)
        addView(word, LayoutParams(-2, look.px(34)))
        look.press(this, 0.92f)
        setOnClickListener { if (menu?.open == true) menu?.close() else open() }
        contentDescription = "Mode"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (io == null) io = Executors.newSingleThreadExecutor { Thread(it, "mode-chip").apply { isDaemon = true } }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        io?.shutdown(); io = null  // a queued write still finishes
        super.onDetachedFromWindow()
    }

    /** Disk and Bluetooth work, off the main thread; dropped once the chip has left the screen. */
    private fun bg(job: () -> Unit) {
        io?.execute { try { job() } catch (t: Throwable) { Log.w("Modes", "mode chip job failed", t) } }
    }

    override fun onWindowVisibilityChanged(v: Int) {
        super.onWindowVisibilityChanged(v)
        visible = v == VISIBLE
        if (visible) refresh() else { removeCallbacks(tick); menu?.close() }
    }

    // pulling the shade down and back (the tile lives there) hands focus back to home
    // and so does the Nearby devices prompt, a dialog over home
    override fun onWindowFocusChanged(has: Boolean) { super.onWindowFocusChanged(has); if (has) { refresh(); menu?.promptAnswered() } }

    /** Read the mode again: Home calls it when search changed the mode (no focus change to hear). */
    fun refresh() = bg { val s = load(); post { show(s) } }

    private fun load(): Snap {
        val state = Modes.state()
        val shift = try { Shift.load() } catch (_: Exception) { null }
        val now = System.currentTimeMillis()
        val hours = shift?.let { "${clock.format(it.start)} to ${clock.format(it.end)}" }
        return Snap(state, Modes.resolve(state, now, if (state.auto) shift else null), hours, Modes.savedCar())
    }

    private fun show(s: Snap) {
        val was = snap?.mode
        snap = s
        removeCallbacks(tick)
        if (visible) postDelayed(tick, 30_000)
        menu?.bind(s)
        if (was == s.mode) return
        word.text = if (s.mode == Mode.OFF) "Mode" else s.mode.label
        word.setTextColor(if (s.mode == Mode.OFF) look.ink3 else look.glacier)
        contentDescription = "Mode, ${s.mode.label}"
        if (was == null) return  // first read: nothing changed, it just arrived
        if (still) arrive.snap(1f) else { arrive.snap(0f); arrive.to(1f, response = 0.42f) }
        onChange()
    }

    // a disk that refuses leaves the menu showing what is really on: the read after the write is the truth
    private fun write(w: () -> Unit) = bg { try { w() } catch (_: Exception) {}; val s = load(); post { show(s) } }
    private fun pick(m: Mode) = write { Modes.set(m) }
    private fun follow(on: Boolean) = write { Modes.follow(on) }
    private fun silence(on: Boolean) = write { Modes.silence(on) }
    private fun saveCar(c: Car?) = write { Modes.saveCar(c) }

    private fun open() {
        val root = rootView.findViewById<ViewGroup>(android.R.id.content) ?: return
        val m = menu ?: Menu(context).also { menu = it }
        snap?.let { m.bind(it) }
        m.show(root)
    }

    /** The glass menu: double bezel (shell + core), anchored under the chip, grows from it and shrinks back into it. */
    private inner class Menu(c: Context) : FrameLayout(c) {
        var open = false; private set
        private val shell = LinearLayout(c)
        private val core = LinearLayout(c)
        private val rows = HashMap<Mode, Row>()
        private val followRow: Row
        private val toggle = Toggle(c)
        private val silenceToggle = Toggle(c)
        private val silenceRow: Row
        private val carRow: Row
        private val cars = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        private val carScroll = object : ScrollView(c) {  // a long list of paired devices scrolls instead of leaving the screen
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(look.px(52 * 5), MeasureSpec.AT_MOST))
        }.apply { addView(cars); visibility = GONE; isVerticalScrollBarEnabled = false }
        private var waitingForPrompt = false
        private var tileRow: View? = null
        private var squeeze = 0f  // predictive Back: the menu shrinks toward the chip as the gesture grows
        private var p = 0f
        private val spring = Spring(0f) { p = it; frame() }
        private val back: OnBackInvokedCallback = if (Build.VERSION.SDK_INT >= 34) object : OnBackAnimationCallback {
            override fun onBackProgressed(e: BackEvent) { squeeze = e.progress; frame() }
            override fun onBackCancelled() { squeeze = 0f; frame() }
            override fun onBackInvoked() = close()
        } else OnBackInvokedCallback { close() }

        init {
            shell.orientation = LinearLayout.VERTICAL
            shell.background = look.shell()
            shell.setPadding(look.px(6), look.px(6), look.px(6), look.px(6))
            shell.elevation = look.px(18).toFloat()
            shell.outlineSpotShadowColor = look.shade(255)
            shell.accessibilityPaneTitle = "Mode"
            shell.isClickable = true  // a tap on the glass between rows is not a tap outside
            core.orientation = LinearLayout.VERTICAL
            core.background = look.glassCard(22)
            core.setPadding(look.px(6), look.px(6), look.px(6), look.px(6))
            shell.addView(core, LinearLayout.LayoutParams(-1, -2))
            for (m in Mode.values()) rows[m] = Row(m.label, check = true).also { r ->
                r.setOnClickListener { choose(m) }
                core.addView(r, LinearLayout.LayoutParams(-1, -2))
            }
            core.addView(View(c).apply { setBackgroundColor(look.inkA(20)) },
                LinearLayout.LayoutParams(-1, look.px(1)).apply { setMargins(look.px(14), look.px(6), look.px(14), look.px(6)) })
            followRow = switchRow("Night shift follows my core hours", toggle) { follow(it) }
            silenceRow = switchRow("Silence other notifications too", silenceToggle) { silence(it) }
            silenceRow.sub("Not just chats, while a mode is on")
            carRow = Row("Car", check = false).also { r ->
                r.setOnClickListener { carTapped() }
                core.addView(r, LinearLayout.LayoutParams(-1, -2))
            }
            core.addView(carScroll, LinearLayout.LayoutParams(-1, -2))
            val prefs = c.getSharedPreferences("aos", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("mode_tile_added", false)) tileRow = Row("Add to Quick Settings", check = false).also { r ->
                r.setOnClickListener { addTile() }
                core.addView(r, LinearLayout.LayoutParams(-1, -2))
            }
            addView(shell)
            // a tap anywhere outside closes it (and does nothing else), like an iPhone menu
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN && open) close(); true }
        }

        fun bind(s: Snap) {
            val now = System.currentTimeMillis()
            for ((m, r) in rows) {
                val on = m == s.mode
                val until = s.state.until.takeIf { on && s.state.manual == m && it in (now + 1) until Long.MAX_VALUE }
                    ?.let { " until " + clock.format(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())) }
                r.sub(when (m) {
                    Mode.OFF -> null
                    Mode.NIGHT -> "Chats arrive quietly" + (until ?: "")
                    Mode.DRIVING -> "Group chats muted" + (until ?: ", ends after 3 h")
                })
                r.picked(on)
            }
            followRow.sub(s.hours ?: "Core hours not set")
            if (toggle.on != s.state.auto) toggle.set(s.state.auto, animate = open)
            if (silenceToggle.on != s.state.dnd) silenceToggle.set(s.state.dnd, animate = open)
            if (!waitingForPrompt) carRow.sub(s.car?.let { "Driving starts when ${it.name} connects" } ?: "Pick your car's Bluetooth")
        }

        /** A row with a switch on the right; the row speaks as one switch to TalkBack. */
        private fun switchRow(title: String, t: Toggle, flip: (Boolean) -> Unit) = Row(title, check = false).also { r ->
            r.addView(t, LinearLayout.LayoutParams(look.px(46), look.px(28)).apply { marginStart = look.px(12) })
            r.setOnClickListener {
                val on = !t.on
                t.set(on, animate = true)
                r.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) (if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF) else HapticFeedbackConstants.CONFIRM)
                flip(on)
            }
            r.accessibilityDelegate = object : AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(v: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(v, info)
                    info.className = "android.widget.Switch"; info.isCheckable = true; info.isChecked = t.on
                }
            }
            core.addView(r, LinearLayout.LayoutParams(-1, -2))
        }

        // the car picker: his paired devices, cars first. Nearby devices is asked for here, the first time it's needed.
        private fun carTapped() {
            if (carScroll.visibility == VISIBLE) { carScroll.visibility = GONE; return }
            val a = context as? Activity ?: return
            if (a.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) { listCars(); return }
            val prefs = a.getSharedPreferences("aos", Context.MODE_PRIVATE)
            if (prefs.getBoolean("car_bt_asked", false) && !a.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
                // Android stopped showing the prompt (two no's): the app's settings page is the only way left
                try { a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.packageName, null))) }
                catch (_: Exception) { carRow.sub("Allow Nearby devices for Agentic OS in Settings") }
                return
            }
            prefs.edit().putBoolean("car_bt_asked", true).apply()
            waitingForPrompt = true
            a.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 43)
        }

        /** Home got focus back from the prompt: list the devices, or say calmly what still works. */
        fun promptAnswered() {
            if (!waitingForPrompt) return
            waitingForPrompt = false
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) { if (open) listCars() }
            else carRow.sub("Without Nearby devices, turn Driving on here by hand")
        }

        private fun listCars() = bg {
            val found: List<Car>? = try {
                val ad = context.getSystemService(BluetoothManager::class.java)?.adapter
                if (ad == null || !ad.isEnabled) null
                else ad.bondedDevices.orEmpty().map { d ->
                    val kind = d.bluetoothClass?.deviceClass
                    val car = kind == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || kind == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
                    Car(d.address, d.name?.takeIf { it.isNotBlank() } ?: d.address) to car
                }.sortedWith(compareBy({ !it.second }, { it.first.name.lowercase() })).map { it.first }
            } catch (_: SecurityException) { null }  // permission taken back meanwhile
            post { fillCars(found) }
        }

        private fun fillCars(list: List<Car>?) {
            if (!open) return
            cars.removeAllViews()
            val saved = snap?.car
            fun add(r: Row) = cars.addView(r, LinearLayout.LayoutParams(-1, -2))
            fun note(s: String) = add(Row(s, check = false).apply { isClickable = false; isFocusable = true })
            when {
                list == null -> note("Turn on Bluetooth to see your devices")
                list.isEmpty() -> note("No paired devices yet. Pair your car in Bluetooth settings.")
                else -> list.forEach { car ->
                    add(Row(car.name, check = true).also { r ->
                        r.tag = car.address
                        r.picked(car.address.equals(saved?.address, ignoreCase = true))
                        r.setOnClickListener { chooseCar(car) }
                    })
                }
            }
            if (saved != null) add(Row("No car", check = false).also { r -> r.sub("Stop switching Driving from Bluetooth"); r.setOnClickListener { chooseCar(null) } })
            carScroll.scrollTo(0, 0)
            carScroll.visibility = VISIBLE
            if (!still) for (k in 0 until cars.childCount) cars.getChildAt(k).apply {
                alpha = 0f; translationY = -look.px(6).toFloat()
                animate().alpha(1f).translationY(0f).setStartDelay(22L * k).setDuration(380).setInterpolator(SpringCurve(1f)).start()
            }
            post { cars.getChildAt(0)?.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
        }

        private fun chooseCar(c: Car?) {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            for (k in 0 until cars.childCount) (cars.getChildAt(k) as? Row)?.let { r -> if (r.tag != null) r.picked(r.tag == c?.address) }
            saveCar(c)
            postDelayed({ carScroll.visibility = GONE }, 200)  // long enough to see the check land
        }

        fun show(root: ViewGroup) {
            if (parent == null) root.addView(this, ViewGroup.LayoutParams(-1, -1))
            // anchor under the chip, kept 16 dp inside the screen edges
            val at = IntArray(2); val rootAt = IntArray(2)
            this@ModeChip.getLocationInWindow(at); root.getLocationInWindow(rootAt)
            val w = minOf(look.px(288), root.width - look.px(32))
            val chipX = at[0] - rootAt[0]; val chipY = at[1] - rootAt[1]
            val left = (if (chipX + this@ModeChip.width / 2 > root.width / 2) chipX + this@ModeChip.width - w else chipX)
                .coerceIn(look.px(16), maxOf(look.px(16), root.width - look.px(16) - w))
            shell.layoutParams = LayoutParams(w, -2).apply { leftMargin = left; topMargin = chipY + this@ModeChip.height - look.px(2) }
            shell.pivotX = (chipX + this@ModeChip.width / 2f - left); shell.pivotY = 0f
            if (!open) {
                open = true; squeeze = 0f
                findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, back)
                spring.onRest = null
                if (p < 0.01f) cascade()
                spring.to(1f, response = 0.45f, damping = 1f)
                post { core.getChildAt(0)?.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
            }
        }

        fun close() {
            if (!open) return
            open = false
            findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(back)
            spring.onRest = { carScroll.visibility = GONE; (parent as? ViewGroup)?.removeView(this) }  // opens fresh next time
            spring.to(0f, response = 0.3f, damping = 1f)
            this@ModeChip.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
        }

        /** Materialize: scale 0.92 to 1, alpha and an 8 dp blur clearing together. Remove animations: alpha only. */
        private fun frame() {
            shell.alpha = p
            setBackgroundColor(look.withAlpha(look.ground, (p * 110).toInt()))  // a veil of the ground settles over home: focus on the menu
            if (still) return
            val s = (0.92f + 0.08f * p) * (1 - 0.08f * squeeze)
            shell.scaleX = s; shell.scaleY = s
            shell.blur(look.px(8) * (1 - p))
        }

        // the rows settle in one after another as the glass arrives (items that arrive together)
        private fun cascade() {
            if (still) return
            for (k in 0 until core.childCount) core.getChildAt(k).apply {
                animate().cancel(); alpha = 0f; translationY = -look.px(6).toFloat()
                animate().alpha(1f).translationY(0f).setStartDelay(30L + 22L * k).setDuration(380).setInterpolator(SpringCurve(1f)).start()
            }
        }

        private fun choose(m: Mode) {
            if (snap?.mode != m) {
                for ((k, r) in rows) r.picked(k == m)
                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                pick(m)
            }
            postDelayed({ close() }, 200)  // long enough to see the check land
        }

        private fun addTile() {
            val sbm = context.getSystemService(StatusBarManager::class.java) ?: return
            sbm.requestAddTileService(ComponentName(context, ModeTile::class.java), "Mode",
                Icon.createWithResource(context, R.drawable.ic_mode_night), context.mainExecutor) { result ->
                if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                    context.getSharedPreferences("aos", Context.MODE_PRIVATE).edit().putBoolean("mode_tile_added", true).apply()
                    tileRow?.let { core.removeView(it) }; tileRow = null
                }
            }
        }
    }

    /** One 52 dp menu row: title, an optional line under it, and the picked mark or a control on the right. */
    private inner class Row(title: String, check: Boolean) : LinearLayout(context) {
        private val name = look.text(title, 16f, look.ink)
        private val line = look.text("", 13f, look.ink3).apply { visibility = GONE }
        private val mark = if (check) ImageView(context).apply {
            setImageResource(R.drawable.ic_mode_check); setColorFilter(look.ink); alpha = 0f; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        } else null

        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = look.px(52); setPadding(look.px(14), look.px(6), look.px(12), look.px(6))
            isClickable = true
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(name); addView(line)
            }, LayoutParams(0, -2, 1f))
            mark?.let { addView(it, LayoutParams(look.px(20), look.px(20)).apply { marginStart = look.px(12) }) }
            look.press(this, 0.97f)
        }

        fun sub(s: String?) { line.text = s.orEmpty(); line.visibility = if (s == null) GONE else VISIBLE }

        fun picked(on: Boolean) {
            name.typeface = if (on) look.medium else look.sans
            background = if (on) look.round(look.withAlpha(look.accent, 36), 16) else null
            mark?.animate()?.alpha(if (on) 1f else 0f)?.setDuration(if (still) 0 else 160)?.start()
            isSelected = on
            stateDescription = if (mark != null) (if (on) "On" else null) else null
        }
    }

    /** An on/off switch: knob slides on a spring, track tints to Glacier. Paints and rect are made once. */
    private inner class Toggle(c: Context) : View(c) {
        var on = false; private set
        private var pos = 0f
        private val spring = Spring(0f) { pos = it; invalidate() }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG)
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(look.px(2).toFloat(), 0f, look.px(1).toFloat(), look.shade(50)) }
        private val r = RectF()
        private val off = look.inkA(46)

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }  // the row speaks for it

        fun set(v: Boolean, animate: Boolean) {
            on = v
            if (animate && !still) spring.to(if (v) 1f else 0f, response = 0.35f, damping = 0.86f) else spring.snap(if (v) 1f else 0f)
        }

        override fun onDraw(cv: Canvas) {
            val h = height.toFloat(); val w = width.toFloat()
            track.color = mix(off, look.glacier, pos.coerceIn(0f, 1f))
            r.set(0f, 0f, w, h); cv.drawRoundRect(r, h / 2, h / 2, track)
            val rad = h / 2 - look.px(2)
            cv.drawCircle(h / 2 + (w - h) * pos, h / 2, rad, knob)
        }
    }

    private companion object {
        val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
        fun mix(a: Int, b: Int, t: Float) = Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(), (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())
    }
}
