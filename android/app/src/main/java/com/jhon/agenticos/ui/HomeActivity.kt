package com.jhon.agenticos.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import com.jhon.agenticos.ListenService
import com.jhon.agenticos.MainActivity
import com.jhon.agenticos.core.Crash
import com.jhon.agenticos.core.Digest
import com.jhon.agenticos.core.Link
import com.jhon.agenticos.core.Mode
import com.jhon.agenticos.core.Modes
import com.jhon.agenticos.core.Quiet
import com.jhon.agenticos.core.Refused
import com.jhon.agenticos.core.atomicWrite
import com.jhon.agenticos.core.osHttp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The home screen. Works fully with the PC off: dock, orbit, glance, widgets and the health strip need nothing from
 *  it; the panels show the last copy it had, dated. Two pages: widgets on the left (swipe right), home in the middle:
 *  top row, glance, the Agentic OS wave, Jharvis's panels over its lower edge, the dock. The orbit (all apps) is an
 *  overlay above it; one spring opens it and pushes everything else back while the wave glides to its centre. */
class HomeActivity : Activity(), PanelStack.Host {
    private lateinit var look: Look
    private lateinit var root: HomeRoot
    private lateinit var frost: FrostView        // the wallpaper: goes quiet under the orbit and the widgets page
    private lateinit var stage: FrameLayout      // both pages: what lands, out of a blur, after an app closes
    private lateinit var home: HomePage
    private lateinit var back: LinearLayout      // top row, glance, dock: what the orbit pushes back
    private lateinit var topRow: LinearLayout
    private lateinit var strip: LinearLayout
    private lateinit var glance: GlanceView
    private lateinit var dockShell: FrameLayout
    private lateinit var wave: JharvisOrb
    private lateinit var modeChip: ModeChip
    private lateinit var panels: PanelStack
    private lateinit var widgets: WidgetsPage
    private lateinit var orbit: OrbitView        // always here: it is also search, whatever the layout
    private lateinit var layout: AppLayout       // how all apps show (Settings, App layout): the orbit itself, or another
    private var layoutId = Layouts.ORBIT
    private lateinit var orb: Spring    // all apps: 0 shut, 1 open, follows the finger
    private lateinit var which: Spring  // what that spring opens: 0 the layout, 1 the orbit's search (a layout's search field swaps over)
    private lateinit var page: Spring   // 0 home, 1 the widgets page
    private lateinit var enter: Spring  // 0 = just back from an app, 1 = settled (the home screen "lands")
    private var still = false           // phone set to remove animations
    private var appearance = 0          // Appearance.version this screen was built with
    private var away = true             // so the very first show lands too
    private var edgeL = 0; private var edgeR = 0  // where Android's Back gesture lives: page swipes start inside these
    private var waveK = 1f                        // the orb's scale depth() last set (the corner shrink)
    private var barTop = 0; private var barRight = 0  // the status bar and cutout: the orb's corner stays clear of them
    private val slots = ArrayList<Slot>()
    private var dockGen = 0
    private val main = Handler(Looper.getMainLooper())
    private var pc = Checks.Pc.UNKNOWN
    private var needsCount = -1
    private var lastSig = ""
    private var last: JSONObject? = null                // what's on screen, so a tap can redraw without the disk
    private val decided = HashMap<String, String>()     // approval id -> what he tapped, until the PC stops listing it
    private val closedHere = HashSet<String>()          // panels he closed, hidden until the PC stops listing them
    private var queued = emptySet<String>()             // paths still in the send queue: survives a restart
    private val cache by lazy { File(Link.dir, "home_cache.json") }
    private val tick = object : Runnable {
        override fun run() { refresh(); main.postDelayed(this, 20_000) }
    }
    // one named thread per job, shut down with Home: disk + package lookups in order, PC reads (up to 3 x 8 s) apart
    private val io: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "home-io") }
    private val net: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "home-net") }
    private val inFlight = AtomicBoolean(false)  // a PC cycle is running: a 20 s tick skips instead of stacking
    private val again = AtomicBoolean(false)     // a tap queued something mid-cycle: run once more after it
    private var badges: Map<String, Int> = emptyMap()
    private val badgeRx = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = readBadges() }
    private var digestShown = ""                 // the digest text already handed to the panels
    private val unpush = Runnable { if (!isDestroyed && enter.target < 1f) enter.to(1f, response = 0.55f, damping = 1f) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // first, before anything that could crash again: 3 crashes in a minute means safe mode
        if (Crash.ui.safe()) {
            startActivity(Intent(this, SafeModeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish(); return
        }
        look = Look(this)
        appearance = Appearance.version(this)
        // Appearance, Phone wallpaper: the system wallpaper shows through a clear window, FrostView only veils it
        val phoneWall = Appearance.prefs(this).getString(Appearance.WALL, null) == Appearance.SYSTEM
        if (phoneWall) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        setContentView(build())
        // after setContentView: the window has no decor (and no insets controller) before it
        window.statusBarColor = Color.TRANSPARENT
        look.bars(window)
        if (phoneWall) window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        registerBack()
        AssistActivity.showing = assistShowing
        AssistActivity.homeOrb = assistOrb
    }

    /** The side-button overlay has its own orb: Home's steps out while it's up, so only one shows. Instant: over Home
     *  the overlay's orb sits exactly on this one at the swap (it glides from here and back). */
    private val assistShowing: (Boolean) -> Unit = { up ->
        wave.animate().cancel()
        wave.alpha = if (up) 0f else 1f
    }

    /** Home's orb for the overlay to start from: only while Home is on screen with its orb in plain view. */
    private val assistOrb: () -> JharvisOrb? = {
        wave.takeIf { !away && it.isShown && it.width > 0 && home.alpha > 0.99f && stage.alpha > 0.99f }
    }

    override fun onStart() {
        super.onStart()
        if (!::look.isInitialized) return
        widgets.start()
        registerReceiver(badgeRx, IntentFilter(BADGES), RECEIVER_NOT_EXPORTED)
        readBadges()
    }

    override fun onResume() {
        super.onResume()
        if (!::look.isInitialized) return
        if (appearance != Appearance.version(this)) { recreate(); return }  // changed in Appearance: rebuild in the new look
        // the phone wallpaper changed under us: the ink that suits it may have too
        if (Appearance.theme(this).id != look.theme.id || Appearance.bare(this, look.theme).id != look.bare.id) { recreate(); return }
        // Android only lets a mic service start from a visible app: Home showing is the restart point
        if (ListenService.wanted(this) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            ListenService.start(this)
        if (!getSharedPreferences("aos", MODE_PRIVATE).getBoolean("setup_done", false))
            startActivity(Intent(this, SetupActivity::class.java))
        showCached()
        settleChange()
        fillDock()
        if (Layouts.current(this) != layoutId) useLayout(Layouts.current(this))  // changed in Settings: at once
        orbit.load()
        if (layout !== orbit) layout.load()
        // back from an app (or a see-through one that only paused Home): the home screen comes forward out of a blur
        if (away || enter.target < 1f) {
            away = false
            if (still) enter.snap(1f) else enter.to(1f, response = 0.55f, damping = 1f)
        }
        main.removeCallbacks(tick); main.post(tick)
    }

    override fun onPause() { super.onPause(); main.removeCallbacks(tick); main.removeCallbacks(unpush); stopDockPick() }

    override fun onStop() {
        super.onStop()
        if (!::look.isInitialized) return
        widgets.stop()
        try { unregisterReceiver(badgeRx) } catch (_: IllegalArgumentException) {}
        away = true
        enter.snap(0f)
        closeOrbit(instant = true)
        AppMenu.dismiss(); stopDockPick(); LayoutPicker.dismiss(); panels.collapse()  // stop after dismiss: it runs a pending "Add to dock"
    }

    override fun onDestroy() {
        super.onDestroy()
        if (AssistActivity.showing === assistShowing) AssistActivity.showing = null
        if (AssistActivity.homeOrb === assistOrb) AssistActivity.homeOrb = null
        main.removeCallbacksAndMessages(null)
        io.shutdown()       // lets a queued send-queue write finish
        net.shutdownNow()   // a PC read in flight is abandoned
    }

    // Home pressed: like Launcher3, animate shut only when Home was already in front with focus; coming back from an
    // app it just is at rest, instantly
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!::look.isInitialized) return
        AppMenu.dismiss(); stopDockPick(); LayoutPicker.dismiss(); panels.collapse()  // stop after dismiss: it runs a pending "Add to dock"
        if (HomeMath.alreadyOnHome(hasWindowFocus(), intent.flags)) closeTop() else { closeOrbit(instant = true); page.snap(0f) }
    }

    private fun build(): View {
        still = reducedMotion(this)
        root = HomeRoot(this).apply { contentDescription = "Home" }  // TalkBack finds its actions here
        frost = FrostView(this, look, home = true)
        root.addView(frost, FrameLayout.LayoutParams(-1, -1))
        stage = FrameLayout(this)
        root.addView(stage, FrameLayout.LayoutParams(-1, -1))
        home = HomePage(this)
        stage.addView(home, FrameLayout.LayoutParams(-1, -1))

        back = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        home.addView(back, FrameLayout.LayoutParams(-1, -1))
        strip = LinearLayout(this).apply {
            setOnLongClickListener { startActivity(Intent(context, SetupActivity::class.java)); true }
        }
        // the health chips scroll sideways when several are red, so every one stays tappable
        topRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(strip) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ModeChip(context, look) { fillDock(); checkDigest() }.also { modeChip = it }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = look.px(8) })
            addView(GearIcon(context, look).apply {
                contentDescription = "Settings"
                isClickable = true; look.press(this, 0.88f)
                background = look.wallGlass(99)
                setOnClickListener { startActivity(Intent(context, SetupActivity::class.java)) }  // Settings pushes in from the right (its own transition)
            }, LinearLayout.LayoutParams(look.px(48), look.px(48)))
        }
        back.addView(topRow, LinearLayout.LayoutParams(-1, -2))
        glance = GlanceView(this, look)
        back.addView(glance, LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.px(12) })
        back.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        // the dock: a glass plate sitting in a glass tray (double bezel, concentric radii 28 and 22)
        val dock = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = look.wallGlass(22)
            setPadding(look.px(6), look.px(10), look.px(6), look.px(8))
        }
        dockShell = FrameLayout(this).apply {
            background = look.shell()
            setPadding(look.px(6), look.px(6), look.px(6), look.px(6))
            addView(dock, FrameLayout.LayoutParams(-1, -2))
        }
        back.addView(dockShell, LinearLayout.LayoutParams(-1, -2))
        buildDock(dock)

        wave = JharvisOrb(this, look).apply {
            contentDescription = "Talk to Jharvis"
            isClickable = true; look.press(this, 0.95f)
            setOnClickListener { if ((layout.view as? MercuryView)?.tapOrb() != true) { pulse(); openOS("#/home", this) } }  // Mercury with a deck: its tray (TalkBack's double-tap lands here)
            setOnLongClickListener { openOS("#/home", this); true }
        }
        home.addView(wave, FrameLayout.LayoutParams(-1, -1))
        panels = PanelStack(this, look, this)
        home.addView(panels, FrameLayout.LayoutParams(-1, -1))

        widgets = WidgetsPage(this, look)
        stage.addView(widgets, FrameLayout.LayoutParams(-1, -1))
        orbit = OrbitView(this, look, { icon, pkg -> launchApp(icon, pkg) }, { closeOrbit() }).apply { menuActions = menu; onSearchLeft = { searchLeft() } }
        root.addView(orbit, FrameLayout.LayoutParams(-1, -1))
        layoutId = Layouts.current(this)
        layout = makeLayout(layoutId)

        root.setOnApplyWindowInsetsListener { _, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            back.setPadding(i.left + look.px(16), i.top + look.px(8), i.right + look.px(16), i.bottom + look.px(12))
            barTop = i.top; barRight = i.right
            val g = insets.getInsets(WindowInsets.Type.systemGestures())
            edgeL = max(look.px(24), g.left); edgeR = max(look.px(24), g.right)
            insets  // not consumed: the orbit (its keyboard), the widgets and the panels read their own
        }

        orb = Spring(0f, 0.0005f) { depth() }.apply {
            onRest = { if (target == 0f) { orbit.reset(); if (layout !== orbit) layout.reset(); hideKeyboard() } }
        }
        which = Spring(0f, 0.001f) { depth() }
        page = Spring(0f, 0.0005f) { depth() }
        enter = Spring(0f, 0.001f) { depth() }
        depth()
        return root
    }

    /** One place decides where everything sits: all apps push the home back (scale, blur, fade) while the wave
     *  shrinks and glides to the orbit's centre (the sphere's too: it sits inside the globe), or with another layout to
     *  the top right corner, out of its way; the widgets page slides in from the left over a dimming home; and both
     *  pages land out of a blur after an app closes. */
    private fun depth() {
        val o = orb.value.coerceAtLeast(0f); val oc = min(o, 1f)
        val s = 1 - 0.08f * oc
        back.scaleX = s; back.scaleY = s; panels.scaleX = s; panels.scaleY = s
        back.alpha = 1 - 0.7f * oc; panels.alpha = 1 - oc
        // the panels fade right out under the open orbit, so their full-screen blur is dropped once they can't be seen
        if (!still) { val r = look.px(28) * oc; back.blur(r); panels.blur(if (oc >= 0.99f) 0f else r) }
        val ow = if (layout === orbit) 1f else which.value.coerceIn(0f, 1f)  // the orbit's share of what's open
        val mid = if (layoutId == Layouts.SPHERE || layoutId == Layouts.TYPE || layoutId == Layouts.SPLINE || layoutId == Layouts.MERCURY || layoutId == Layouts.HORIZON) 1f else ow  // the orb's pull to the centre: the sphere, the type, the spline, the mercury and the horizon keep it there
        val corner = (1 - mid) * oc  // 1 = the orb waits in the corner (0.3 of its size) while a layout is open
        // Typographic Matrix, Fluid Spline, Mercury Flow and Horizon Core: where he dragged the orb (and, the type framing a name, how
        // small it is); gone as it shuts or search covers it
        val tv = layout.view as? TypeView; val sv = layout.view as? SplineView; val mv = layout.view as? MercuryView; val hv = layout.view as? HorizonView
        val tk = if (tv != null || sv != null || mv != null || hv != null) oc * (1 - ow) else 0f
        val ox = tv?.orbX ?: sv?.orbX ?: mv?.orbX ?: hv?.orbX ?: 0f; val oy = tv?.orbY ?: sv?.orbY ?: mv?.orbY ?: hv?.orbY ?: 0f
        val k = (1 - CORNER_SHRINK * corner) * (1 - tk * (1 - (tv?.orbK ?: 1f)))
        wave.compact = oc
        // only when it changes: at home the orb's own press squish owns its scale. zoom: hairlines keep their width
        if (k != waveK) { waveK = k; wave.scaleX = k; wave.scaleY = k; wave.zoom = k }
        val rc = cornerRadius()
        val gx = root.width / 2f + (1 - mid) * (HomeMath.cornerX(root.width.toFloat(), barRight.toFloat(), rc, look.px(16).toFloat()) - root.width / 2f)
        val gy = root.height / 2f + (1 - mid) * (HomeMath.cornerY(root.height.toFloat(), barTop.toFloat(), rc, look.px(2).toFloat()) - root.height / 2f)
        wave.translationX = oc * (gx - (wave.left + wave.width / 2f)) + tk * ox
        wave.translationY = oc * (gy - (wave.top + wave.height / 2f)) + tk * oy
        wave.translationZ = if (oc > 0.01f) 1f else 0f  // over the panels on its way to the centre
        topRow.alpha = 1 - corner  // the chips step out of the corner the orb takes
        orbit.progress = o * ow
        orbit.visibility = if (o * ow <= 0.002f && !(orb.target > 0f && (layout === orbit || which.target > 0f))) View.INVISIBLE else View.VISIBLE
        if (layout !== orbit) {
            layout.progress = o * (1 - ow)
            val v = if (o * (1 - ow) <= 0.002f && !(orb.target > 0f && which.target < 1f)) View.INVISIBLE else View.VISIBLE
            if (layout.view.visibility != v) layout.view.visibility = v
        }

        val p = page.value; val pc = p.coerceIn(0f, 1f); val w = root.width.toFloat()
        widgets.translationX = (p - 1) * w
        widgets.visibility = if (p <= 0.001f && page.target == 0f) View.INVISIBLE else View.VISIBLE
        home.translationX = p * w * 0.3f
        home.scaleX = 1 - 0.04f * pc; home.scaleY = home.scaleX
        home.alpha = 1 - pc
        home.visibility = if (pc >= 0.999f) View.INVISIBLE else View.VISIBLE
        if (!still) home.blur(look.px(16) * pc)
        frost.focus = max(oc, pc)  // the photo calms under app labels and widgets, clears again as they leave

        val e = enter.value.coerceIn(0f, 1.05f)
        stage.scaleX = 1 + 0.1f * (1 - e); stage.scaleY = stage.scaleX
        stage.alpha = (0.15f + 0.85f * e).coerceIn(0f, 1f)
        if (!still) stage.blur(look.px(26) * (1 - e).coerceAtLeast(0f))
        // an app launched from the orbit pushes it away too; it's shut again by the time Home lands
        orbit.scaleX = stage.scaleX; orbit.scaleY = stage.scaleX
        orbit.alpha = stage.alpha
        if (!still) orbit.blur(look.px(26) * (1 - e).coerceAtLeast(0f))
        // the other layouts too (no blur here: Honeycomb blurs itself as it opens, and they're shut when Home lands)
        if (layout !== orbit) { layout.view.scaleX = stage.scaleX; layout.view.scaleY = stage.scaleX; layout.view.alpha = stage.alpha }
    }

    /** The orb's lens radius once it sits in the corner (px). */
    private fun cornerRadius() = min(wave.width, wave.height) * OrbMath.LENS * 0.45f * (1 - CORNER_SHRINK)

    /** Swipe up, the dock's Apps, "Open apps": the chosen layout. Pull down, or a layout's search field: the orbit on
     *  search. Asked while a layout is open, the orbit's search takes over in place, on its own spring. */
    private fun openOrbit(search: Boolean = false) {
        stopDockPick()
        if (search) searchFromLayout = layout !== orbit && orb.target > 0f && which.target < 1f  // a layout was open under it
        val to = if (search || layout === orbit) 1f else 0f
        if (orb.target == 0f && orb.value < 0.02f) { which.snap(to); (if (to == 1f) orbit else layout).opening() }
        else if (which.target != to) { (if (to == 1f) orbit else layout).opening(); if (still) which.snap(to) else which.to(to, response = 0.5f, damping = 1f) }
        if (search) orbit.openSearch()
        if (still) orb.snap(1f) else orb.to(1f, response = 0.55f, damping = 1f)
    }

    private var searchFromLayout = false

    /** The orbit's search ended with the field empty: true when Home took it (see HomeMath.searchBack). */
    private fun searchLeft(): Boolean {
        if (orb.target == 0f || (layout !== orbit && which.target < 1f)) return false
        return when (HomeMath.searchBack(layout === orbit, searchFromLayout)) {
            HomeMath.BACK_TO_LAYOUT -> { layout.opening(); if (still) which.snap(0f) else which.to(0f, response = 0.5f, damping = 1f); true }
            HomeMath.BACK_HOME -> { closeOrbit(); true }
            else -> false
        }
    }

    private fun closeOrbit(instant: Boolean = false) {
        if (instant || still) { orb.snap(0f); orbit.reset(); if (layout !== orbit) layout.reset(); hideKeyboard() }
        else orb.to(0f, response = 0.45f, damping = 1f)
    }

    /** The chosen layout, added under the orbit (so search shows over it). The orbit itself needs nothing added. */
    private fun makeLayout(id: String): AppLayout =
        Layouts.make(id, this, look, orbit, { icon, key -> launchApp(icon, key) }, { closeOrbit() }, { openOrbit(search = true) }).also {
            it.menuActions = menu
            (it.view as? SphereView)?.onOrb = { x, y -> orbAt(it.view, x, y).also { on -> if (on) wave.performClick() } }
            (it.view as? TypeView)?.apply { onOrbTap = { wave.performClick() }; onOrbChanged = { depth() } }  // it moves the orb; Home draws it
            (it.view as? SplineView)?.apply { onOrbTap = { wave.performClick() }; onOrbChanged = { depth() } }  // the thread's head: same
            (it.view as? MercuryView)?.apply { onOrbTap = { wave.performClick() }; onOrbChanged = { depth() } }  // the dense drop: same (a deck's tap is its tray)
            (it.view as? HorizonView)?.apply { onOrbTap = { wave.performClick() }; onOrbChanged = { depth() } }  // the vanishing point: same
            if (it !== orbit) { root.addView(it.view, root.indexOfChild(orbit), FrameLayout.LayoutParams(-1, -1)); root.requestApplyInsets() }
        }

    /** The sphere draws over the orb at its centre, so it hands a missed tap here: on the orb's lens? */
    private fun orbAt(v: View, x: Float, y: Float): Boolean {
        val a = IntArray(2); val b = IntArray(2)
        v.getLocationInWindow(a); wave.getLocationInWindow(b)
        return wave.onLens(x + a[0] - b[0], y + a[1] - b[1])
    }

    /** A new layout, picked here or in Settings: swapped in while all apps are shut, so it shows on the next open. */
    private fun useLayout(id: String) {
        if (id == layoutId) return
        closeOrbit(instant = true)
        if (layout !== orbit) root.removeView(layout.view)
        layoutId = id
        layout = makeLayout(id)
        if (layout !== orbit) layout.load()
        depth()
    }

    /** Long press on empty frost (or TalkBack's "Change app layout"): the five layouts on a glass sheet. */
    private fun pickLayout() = LayoutPicker.show(this, look, layoutId) { id -> Layouts.set(this, id); useLayout(id) }

    private fun showPage(to: Float) { if (still) page.snap(to) else page.to(to, response = 0.5f, damping = 1f) }

    /** What Back and Home close: the orbit first, then the widgets page. Nothing sits behind Home itself. */
    private fun openSpring(): Spring? = when {
        orb.target > 0f || orb.value > 0.02f -> orb
        page.target > 0f || page.value > 0.02f -> page
        else -> null
    }

    private fun closeTop() {
        if (dockPick != null) { stopDockPick(); return }  // Back cancels "Add to dock" first
        when (openSpring()) { orb -> closeOrbit(); page -> showPage(0f); else -> {} }
    }

    /** Predictive Back: the orbit or the widgets page eases a little way shut as the back swipe grows, closes when he
     *  lets go, and springs back if he changes his mind. */
    private fun registerBack() {
        if (Build.VERSION.SDK_INT < 34) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { closeTop() }
            return
        }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, object : OnBackAnimationCallback {
            private var s: Spring? = null
            private var from = 0f
            override fun onBackStarted(e: BackEvent) { s = openSpring()?.also { it.stop(); from = it.value } }
            override fun onBackProgressed(e: BackEvent) { if (!still) s?.snap(from * (1 - 0.3f * e.progress)) }
            override fun onBackCancelled() { val sp = s ?: return; s = null; if (still) sp.snap(from) else sp.to(from, response = 0.35f, damping = 1f) }
            override fun onBackInvoked() { s = null; closeTop() }
        })
    }

    private fun hideKeyboard() = getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)

    /** Lays out the home page: Jharvis's orb in the middle (its view about 42% of the height, centred a little above
     *  the middle, never over the glance), the panels from just under its lens down to the dock. */
    inner class HomePage(c: Context) : FrameLayout(c) {
        private var waveTop = 0; private var panelTop = 0; private var panelBottom = 0

        override fun onMeasure(ws: Int, hs: Int) {
            super.onMeasure(ws, hs)
            val w = measuredWidth; val h = measuredHeight
            val wh = (h * 0.42f).toInt()
            val header = back.paddingTop + topRow.measuredHeight + glance.measuredHeight + look.px(12)
            waveTop = max((h * 0.45f).toInt() - wh / 2, header - (wh * 0.06f).toInt())
            panelBottom = h - back.paddingBottom - dockShell.measuredHeight - look.px(12)
            panelTop = min(waveTop + (wh * 0.72f).toInt(), panelBottom)  // the lens ends at 0.70: panels never cover it
            wave.measure(exactly(w), exactly(wh))
            panels.measure(exactly(max(0, w - back.paddingLeft - back.paddingRight)), exactly(panelBottom - panelTop))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            wave.layout(0, waveTop, width, waveTop + wave.measuredHeight)
            panels.layout(back.paddingLeft, panelTop, back.paddingLeft + panels.measuredWidth, panelBottom)
        }

        private fun exactly(px: Int) = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)
    }

    /** Swipe up anywhere a list doesn't need the finger: all apps open glued to it (1:1 from where it was grabbed),
     *  rubber-band past open, and on release go where the flick was heading. Pull down from the top 12%: the
     *  notification shade; from the rest of the upper half: the orbit opens on search. Double tap on empty frost:
     *  lock; press and hold there: pick the app layout. Swipe right (away from the Back edge): the widgets page, same
     *  physics; left goes back. Any of them can be caught mid-flight. While a layout is open the orb waits in the top
     *  corner, still a button (the sphere keeps it at its centre and hands taps on it back). TalkBack swallows
     *  swipes, so each is also an accessibility action here. */
    inner class HomeRoot(c: Context) : FrameLayout(c) {
        private val slop = ViewConfiguration.get(c).scaledTouchSlop
        private val tapSlop = ViewConfiguration.get(c).scaledDoubleTapSlop
        private var downX = 0f; private var downY = 0f; private var start = 0f
        private var mode = NONE
        private var side = false
        private var vt: VelocityTracker? = null
        private val hit = Rect()
        private val at = IntArray(2); private val waveAt = IntArray(2)
        private var lastTap = 0L; private var lastTapX = 0f; private var lastTapY = 0f
        private var orbTap = false  // this touch began on the orb in the corner: a tap on it is the orb's own
        // held still on empty frost, nothing open: the app layout sheet (a double tap stays lock: its taps are short)
        private val hold = Runnable {
            if (mode == NONE && openSpring() == null && !under { (it.isClickable || it.isLongClickable) && (it !== wave || onOrbLens()) }) {
                lastTap = 0L
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                stopDockPick()
                pickLayout()
            }
        }

        override fun onInterceptTouchEvent(e: MotionEvent) = track(e, true)

        // reaches here only when nothing under the finger took it
        override fun onTouchEvent(e: MotionEvent): Boolean = track(e, false) || e.actionMasked == MotionEvent.ACTION_DOWN

        override fun performClick(): Boolean { super.performClick(); return true }

        private fun tapped(e: MotionEvent) {
            if (dockPick != null) { stopDockPick(); lastTap = 0L; return }  // a tap elsewhere cancels "Add to dock", and isn't half a double tap
            if (openSpring() != null) { lastTap = 0L; return }
            if (HomeMath.doubleTap(lastTap, lastTapX, lastTapY, e.downTime, e.x, e.y, ViewConfiguration.getDoubleTapTimeout().toLong(), tapSlop.toFloat())) {
                lastTap = 0L; global("lock")
            } else { lastTap = e.eventTime; lastTapX = e.x; lastTapY = e.y }
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            for ((id, label) in listOf(A_APPS to "Open apps", A_SEARCH to "Search apps", A_WIDGETS to "Show widgets", A_HOME to "Back to home",
                    A_LAYOUT to "Change app layout"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(id, label))
        }

        override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
            when (action) {
                A_APPS -> { showPage(0f); openOrbit() }
                A_SEARCH -> { showPage(0f); openOrbit(search = true) }
                A_WIDGETS -> { closeOrbit(); showPage(1f) }
                A_HOME -> { closeOrbit(); showPage(0f) }
                A_LAYOUT -> { closeOrbit(instant = true); showPage(0f); pickLayout() }
                else -> return super.performAccessibilityAction(action, args)
            }
            return true
        }

        private fun travel() = (height * 0.42f).coerceAtLeast(1f)
        private fun moving(s: Spring) = s.value in 0.02f..0.98f && abs(s.target - s.value) > 0.001f

        private fun grab(s: Spring, m: Int) { s.stop(); start = s.value; side = start > 0.5f; mode = m }

        private fun track(e: MotionEvent, intercepting: Boolean): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; mode = NONE; orbTap = false
                    vt?.recycle(); vt = VelocityTracker.obtain().apply { addMovement(e) }
                    removeCallbacks(hold)
                    // grabbed mid-flight: catch it where it is
                    if (moving(orb)) { grab(orb, ORBIT); return true }
                    if (moving(page)) { grab(page, PAGE); return true }
                    // the orb in the corner sits under the layout: Home takes the touch for it
                    if (onCornerOrb(e.x, e.y)) { orbTap = true; if (!intercepting) wave.tick(); return true }  // ticks once: DOWN comes here twice
                    if (openSpring() == null) postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    val dx = e.x - downX; val dy = e.y - downY
                    if (abs(dx) > slop || abs(dy) > slop) { removeCallbacks(hold); orbTap = false }
                    if (mode == NONE && (abs(dx) > slop || abs(dy) > slop)) {
                        mode = decide(dx, dy, intercepting)
                        when (mode) {
                            ORBIT -> {
                                if (orb.value < 0.02f && orb.target == 0f) { which.snap(if (layout === orbit) 1f else 0f); layout.opening() }
                                grab(orb, ORBIT)
                            }
                            PAGE -> grab(page, PAGE)
                            SEARCH -> { tick(); openOrbit(search = true) }
                            SHADE -> global("shade")
                        }
                        if (mode == ORBIT || mode == PAGE) { stopDockPick(); downX = e.x; downY = e.y; parent?.requestDisallowInterceptTouchEvent(true) }
                    }
                    when (mode) {
                        ORBIT -> {
                            val t = travel()
                            var p = start + (downY - e.y) / t
                            if (p > 1f) p = 1f + rubberband((p - 1f) * t, t) / t
                            orb.snap(p.coerceAtLeast(0f)); cross(p)
                            return true
                        }
                        PAGE -> {
                            val w = width.toFloat().coerceAtLeast(1f)
                            var p = start + (e.x - downX) / w
                            if (p > 1f) p = 1f + rubberband((p - 1f) * w, w) / w
                            if (p < 0f) p = rubberband(p * w, w) / w
                            page.snap(p); cross(p)
                            return true
                        }
                        SEARCH, SHADE -> return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(hold)
                    if (orbTap) { orbTap = false; if (e.actionMasked == MotionEvent.ACTION_UP && mode == NONE) wave.performClick() }
                    // seen here too while a child (the panels' scroll) holds the touch: a tap with nothing clickable
                    // under it is a tap on empty frost
                    val tap = e.actionMasked == MotionEvent.ACTION_UP && mode == NONE && abs(e.x - downX) < slop && abs(e.y - downY) < slop &&
                        e.eventTime - e.downTime < ViewConfiguration.getLongPressTimeout() && !under { it.isClickable && (it !== wave || onOrbLens()) }
                    if (tap) { performClick(); tapped(e) }
                    val was = mode == ORBIT || mode == PAGE || mode == SEARCH || mode == SHADE
                    vt?.computeCurrentVelocity(1000)
                    if (mode == ORBIT) {
                        val v = -(vt?.yVelocity ?: 0f) / travel()
                        val to = HomeMath.settle(orb.value, v)
                        if (to == 0f) hideKeyboard()
                        // a bounce only when a flick carried it open
                        if (still) orb.snap(to) else orb.to(to, response = if (to == 1f) 0.5f else 0.42f,
                            damping = if (to == 1f && abs(v) > 1.5f) 0.8f else 1f, velocity = v)
                    } else if (mode == PAGE) {
                        val v = (vt?.xVelocity ?: 0f) / width.toFloat().coerceAtLeast(1f)
                        val to = HomeMath.settle(page.value, v)
                        if (still) page.snap(to) else page.to(to, response = 0.46f, damping = if (abs(v) > 1.5f) 0.84f else 1f, velocity = v)
                    }
                    mode = NONE
                    vt?.recycle(); vt = null
                    return was
                }
            }
            return mode == ORBIT || mode == PAGE || mode == SEARCH || mode == SHADE || orbTap
        }

        /** Which gesture this is, once the finger has moved past slop. LOCKED = not ours, leave it to the child. */
        private fun decide(dx: Float, dy: Float, intercepting: Boolean): Int {
            val vertical = abs(dy) > abs(dx) * 1.3f; val horizontal = abs(dx) > abs(dy) * 1.3f
            if (!vertical && !horizontal) return NONE  // still diagonal: wait for a clearer direction
            // all apps open: a drag the layout scrolls or pans with is its own; a drag down anywhere else closes it.
            // The orbit also keeps every drag that began on one of its buttons, as before.
            if (orb.target > 0f) {
                val open: AppLayout = if (layout === orbit || which.target > 0f) orbit else layout
                val owns = open.ownsTouch(downX, downY) || (open === orbit && intercepting)
                return if (vertical && dy > 0 && !owns) ORBIT else LOCKED
            }
            if (page.target > 0f) return if (horizontal && dx < 0 && downX < width - edgeR && !under { it.canScrollHorizontally(1) }) PAGE else LOCKED
            if (vertical && dy < 0 && !under { it.canScrollVertically(1) }) return ORBIT
            if (vertical && dy > 0 && HomeMath.inShadeZone(downY, height.toFloat())) return SHADE
            if (vertical && dy > 0 && downY < height / 2f && !under { it.canScrollVertically(-1) }) return SEARCH
            // panels may have their own sideways swipes (approve, dismiss): theirs, not the page's
            if (horizontal && dx > 0 && downX > edgeL && !under { it.canScrollHorizontally(-1) || panels.isCard(it) }) return PAGE
            return LOCKED
        }

        /** Crossed the halfway line: a click you can feel, each way. */
        private fun cross(p: Float) {
            val now = p > 0.5f
            if (now == side) return
            side = now
            performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK)
        }

        /** The orb waiting in the corner while a layout is open: a thumb-sized circle around its lens. */
        private fun onCornerOrb(x: Float, y: Float): Boolean {
            if (layout === orbit || layoutId == Layouts.SPHERE || layoutId == Layouts.TYPE || layoutId == Layouts.SPLINE || layoutId == Layouts.MERCURY || layoutId == Layouts.HORIZON || orb.target < 1f || orb.value < 0.9f || which.target > 0f) return false
            val r = max(cornerRadius() * 1.3f, look.px(24).toFloat())
            val dx = x - (wave.left + wave.width / 2f + wave.translationX); val dy = y - (wave.top + wave.height / 2f + wave.translationY)
            return dx * dx + dy * dy <= r * r
        }

        /** The orb's view spans the width, but only its lens is a button (JharvisOrb.onLens). */
        private fun onOrbLens(): Boolean {
            getLocationInWindow(at); wave.getLocationInWindow(waveAt)
            return wave.onLens(downX + at[0] - waveAt[0], downY + at[1] - waveAt[1])
        }

        /** Does anything under where the finger landed pass the test (a list that can still scroll that way)? */
        private fun under(test: (View) -> Boolean): Boolean {
            getLocationInWindow(at)
            return hits(this, (downX + at[0]).toInt(), (downY + at[1]).toInt(), test)
        }

        private fun hits(v: View, x: Int, y: Int, test: (View) -> Boolean): Boolean {
            if (v.visibility != View.VISIBLE || !v.getGlobalVisibleRect(hit) || !hit.contains(x, y)) return false
            if (v !== this && test(v)) return true
            if (v is ViewGroup) for (k in v.childCount - 1 downTo 0) if (hits(v.getChildAt(k), x, y, test)) return true
            return false
        }
    }

    // ── PanelStack.Host ─────────────────────────────────────────────────────────────────────────────

    /** Answered while the PC is off: queued, and the PC refuses it if the request expired meanwhile. */
    override fun approve(id: String, yes: Boolean) {
        decided[id] = (if (yes) "Approved" else "Declined") + if (pc == Checks.Pc.OK) ". Sending." else ". Sends when the PC is back."
        last?.let { draw(it, pc == Checks.Pc.OK) }
        send("/api/jharvis/approvals/$id", JSONObject().put("approve", yes).toString())
    }

    override fun closePanel(id: String) {
        if (id == "all") last?.optJSONArray("panels")?.let { p -> for (k in 0 until p.length()) p.optJSONObject(k)?.optString("id")?.let { closedHere += it } }
        else closedHere += id
        send("/api/jharvis/panels/$id", "", "DELETE")
    }

    override fun open(link: String, from: View) = openOS(link, from)

    // a push, in Home's own task: launchFrom's NEW_TASK put it in the dashboard's task, so Back could land there
    override fun openLog(from: View) = startActivity(Intent(this, ActivityLogActivity::class.java))

    /** Into the send queue off the main thread (it's a file), then a refresh flushes it. */
    private fun send(path: String, json: String, method: String = "POST") = on(io) {
        try { Link.queue.add(path, json, method = method) } catch (_: Exception) {}
        main.post { if (!isDestroyed) refresh(soon = true) }
    }

    /** Runs a job on one of Home's threads; after onDestroy the executor refuses it, and that's fine. A bug in a job
     *  must not take Home down, so it's caught here. */
    private fun on(e: ExecutorService, job: () -> Unit) {
        try { e.execute { try { job() } catch (_: Exception) {} } } catch (_: RejectedExecutionException) {}
    }

    // ── PC data: read off the main thread, drawn on it ──────────────────────────────────────────────

    /** soon = something was just queued: if a cycle is already running, run one more right after it. */
    private fun refresh(soon: Boolean = false) {
        drawStrip()
        on(io) { Watchdog.check(this) }  // IPC and a file read: never on the main thread
        checkDigest()
        if (!inFlight.compareAndSet(false, true)) { if (soon) again.set(true); return }
        on(net) { try { pcCycle() } finally {
            inFlight.set(false)
            if (again.getAndSet(false)) main.post { if (!isDestroyed) refresh() }
        } }
    }

    private fun pcCycle() {
        val got = try {
            val approvals = JSONObject(String(osHttp("/api/jharvis/approvals", timeoutMs = 8000)))
            val home = JSONObject(String(osHttp("/api/jharvis/home", timeoutMs = 8000)))
            // on its own: a PC without panels yet still shows approvals and needs
            val panels = try { JSONObject(String(osHttp("/api/jharvis/panels", timeoutMs = 8000))).optJSONArray("panels") } catch (_: Exception) { null }
            val all = JSONObject().put("approvals", approvals.optJSONArray("approvals") ?: JSONArray())
                .put("home", home).put("panels", panels ?: JSONArray()).put("at", System.currentTimeMillis())
            try { atomicWrite(cache, all.toString()) } catch (_: Exception) {}
            Checks.Pc.OK to all
        } catch (e: Refused) { (if (e.code == 401 || e.code == 403) Checks.Pc.UNPAIRED else Checks.Pc.OFF) to null }
        catch (_: Exception) { Checks.Pc.OFF to null }
        try { Link.queue.flush() } catch (_: Exception) {}  // a tap made while the PC was off goes now
        val q = try { Link.queue.waiting().toSet() } catch (_: Exception) { emptySet() }
        main.post {
            if (isDestroyed) return@post
            pc = got.first; queued = q
            got.second?.let { draw(it, fresh = true) } ?: showCached()
            drawStrip()
        }
    }

    /** The last copy: from memory when there is one, else from the disk off the main thread. */
    private fun showCached() {
        last?.let { draw(it, fresh = pc == Checks.Pc.OK); return }
        on(io) {
            val c = try { JSONObject(cache.readText()) } catch (_: Exception) { null }
            val q = try { Link.queue.waiting().toSet() } catch (_: Exception) { emptySet() }
            main.post {
                if (isDestroyed || last != null) return@post
                queued = q
                draw(c ?: JSONObject().put("at", 0L), fresh = c != null && pc == Checks.Pc.OK)  // at 0: never heard from the PC
            }
        }
    }

    /** Back to no mode with chats held meanwhile: the panels show what came in, and closing that card marks it seen.
     *  :core stays the only writer of digest.json (chats muted while the card shows aren't lost). */
    private fun checkDigest() = on(io) {
        if (Modes.current() != Mode.OFF || Quiet.on()) return@on  // Sleep keeps the mode at Off: no card until it ends too
        val items = Digest.unseen()
        if (items.length() == 0) return@on
        val text = items.toString()
        val newest = (0 until items.length()).maxOf { items.optJSONObject(it)?.optLong("time") ?: 0L }
        main.post {
            if (isDestroyed || text == digestShown) return@post
            digestShown = text
            panels.showDigest(items) { on(io) { try { Digest.seen(newest) } catch (_: Exception) {} } }
        }
    }

    /** CORE's per-app notification counts, off the main thread; drawn on the dock. */
    private fun readBadges() = on(io) {
        val m = HomeMath.badgeCounts(try { File(Link.dir, "badges.json").readText() } catch (_: Exception) { "" })
        main.post { if (!isDestroyed) { badges = m; showBadges() } }
    }

    private fun draw(data: JSONObject, fresh: Boolean) {
        last = data
        val home = data.optJSONObject("home") ?: JSONObject()
        val approvals = data.optJSONArray("approvals") ?: JSONArray()
        val all = data.optJSONArray("panels") ?: JSONArray()
        wave.live = fresh && home.optBoolean("online", false)

        // mark taps he already made, so a panel says so instead of asking again (extra "answered" field)
        val listed = HashSet<String>()
        for (k in 0 until approvals.length()) {
            val a = approvals.optJSONObject(k) ?: continue
            val id = a.optString("id"); listed += id
            val said = decided[id] ?: if ("/api/jharvis/approvals/$id" in queued) "Answered. Sends when the PC is back." else null
            if (said != null) a.put("answered", said) else a.remove("answered")
        }
        val shown = JSONArray()
        val ids = HashSet<String>()
        for (k in 0 until all.length()) {
            val p = all.optJSONObject(k) ?: continue
            ids += p.optString("id")
            if (p.optString("id") !in closedHere) shown.put(p)
        }
        if (fresh) { decided.keys.retainAll(listed); closedHere.retainAll(ids) }  // the PC moved on: forget the taps

        panels.show(home, approvals, shown, fresh, data.optLong("at"))

        // the wave answers what happened: a swell when something new needs him, a short stir for any other change
        val count = HomeMath.needsCount(home, approvals)
        val sig = HomeMath.sig(home, approvals, shown)
        if (fresh && needsCount >= 0 && count > needsCount) wave.pulse()
        else if (lastSig.isNotEmpty() && sig != lastSig) wave.stir()
        if (fresh) needsCount = count
        lastSig = sig
    }

    // ── Health strip ────────────────────────────────────────────────────────────────────────────────

    /** Green collapses to one "All good"; anything red shows on its own and opens its fix. Words, never colour alone. */
    // the checks are binder calls and beat-file reads: run on io, only the chips are made on the main thread
    private fun drawStrip() {
        val p = pc
        on(io) {
            val checks = Checks.strip(this, p)
            main.post {
                if (isDestroyed) return@post
                strip.removeAllViews()
                val shown = checks.filter { it.ok != true }
                if (shown.none { it.ok == false }) strip.addView(chip("All good", look.ok, null))
                for (c in shown) strip.addView(chip(c.word, if (c.ok == false) look.bad else look.ink2, if (c.ok == false) c else null))
            }
        }
    }

    private fun chip(word: String, tint: Int, check: Check?) = look.text(word, 13f, tint, true).apply {
        gravity = Gravity.CENTER
        setPadding(look.px(16), 0, look.px(16), 0)
        background = look.wallGlass(99)
        layoutParams = LinearLayout.LayoutParams(-2, look.px(48)).apply { rightMargin = look.px(8) }  // 48 dp touch target
        if (check != null) { isClickable = true; look.press(this); setOnClickListener { check.fix(this@HomeActivity) } }
        contentDescription = word
    }

    // ── Dock ────────────────────────────────────────────────────────────────────────────────────────

    private class Slot(val icon: ImageView, val label: TextView, val box: View, val badge: TextView) {
        var pkg = ""; var key = ""; var launch: Intent? = null; var name: String? = null  // key: the app key menus, locks and counts use
    }
    private class DockApp(val pkg: String, val key: String, val launch: Intent?, val label: String?, val icon: Drawable?)

    private fun slotBox(icon: View, label: TextView, badge: TextView? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        minimumHeight = look.px(48)
        addView(FrameLayout(context).apply {
            addView(icon, FrameLayout.LayoutParams(-1, -1))
            badge?.let { addView(it, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)) }
        }, LinearLayout.LayoutParams(look.px(54), look.px(54)))
        addView(label.apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(0, look.px(4), 0, 0) })
        isClickable = true; look.press(this, 0.88f)
    }

    /** Built once; fillDock() swaps icons and labels in place, so a resume never flashes an empty dock. */
    private fun buildDock(row: LinearLayout) {
        for (slot in 0 until 4) {
            val icon = ImageView(this)
            val label = look.text("", 12f, look.ink2)
            val badge = badgeView()
            val box = slotBox(icon, label, badge)
            val s = Slot(icon, label, box, badge)
            box.setOnClickListener {
                dockPick?.let { pkg -> placeInDock(slot, pkg); return@setOnClickListener }
                val l = s.launch
                if (l != null) LockedApps.launch(this, s.key, s.name) { LaunchCounts.bump(this, s.key); go(icon, l) } else pickFor(slot)
            }
            // hold an app: its menu (change app, remove from dock, shortcuts, hide, info, delete); hold an empty spot: pick an
            // app for it; in pick mode a hold places, like a tap
            box.setOnLongClickListener {
                dockPick?.let { pkg -> placeInDock(slot, pkg); return@setOnLongClickListener true }
                if (s.launch != null) AppMenu.show(icon, s.key, look, menu, dockSlot = slot) else pickFor(slot)
                true
            }
            slots += s
            row.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        }
        row.addView(slotBox(OrbitIcon(this, look), look.text("Apps", 12f, look.ink2)).apply {
            contentDescription = "Apps"
            setOnClickListener { openOrbit() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    /** A count on the icon's corner: a small pill with an accent hairline, never a bare dot. */
    private fun badgeView() = look.text("", 11f, look.ink, true).apply {
        gravity = Gravity.CENTER; maxLines = 1
        minWidth = look.px(18); minHeight = look.px(18)
        setPadding(look.px(5), 0, look.px(5), 0)
        background = look.round(look.withAlpha(look.ground, 245), 99, look.glacier)
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO  // the slot reads it out instead
    }

    /** Each spot from its app and pick mode: an empty or hidden spot is gone (the others spread across the row), and
     *  while an app is being placed every spot shows, lit, as "Here". */
    private fun paintDock() {
        val picking = dockPick != null
        showBadges()
        for (s in slots) {
            s.box.visibility = if (HomeMath.dockShows(s.launch != null, picking)) View.VISIBLE else View.GONE
            s.box.background = if (picking) look.round(look.withAlpha(look.glacier, 46), 18, look.glacier) else null
            s.label.text = if (picking) "Here" else s.name ?: ""
        }
    }

    private fun showBadges() {
        for (s in slots) {
            val n = if (s.launch != null) badges[s.pkg] ?: 0 else 0
            s.badge.text = HomeMath.badgeText(n)
            s.badge.visibility = if (n > 0) View.VISIBLE else View.GONE
            // here, not in paintDock: a badge arriving in pick mode repaints only this
            s.box.contentDescription = if (dockPick != null) "Put it here" + (s.name?.let { ", in place of $it" } ?: "")
                else s.name?.let { HomeMath.slotSays(it, n) } ?: "Pick an app"
        }
    }

    /** Which app sits in each slot: his pick ("" = he removed it; one dock for every mode), else a default. Package
     *  lookups are IPC, so they run off the main thread. */
    private fun fillDock() {
        val gen = ++dockGen
        on(io) {
            val prefs = getSharedPreferences("aos", MODE_PRIVATE)
            val pm = packageManager
            val defaults by lazy {
                listOf(Intent(Intent.ACTION_DIAL),
                    Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
                    Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                    .map { pm.resolveActivity(it, 0)?.activityInfo?.packageName?.takeIf { p -> p != "android" } ?: "" }
            }
            val hidden = HiddenApps.all(this@HomeActivity)
            val got = (0 until 4).map { slot ->
                val pkg = prefs.getString("dock$slot", null) ?: defaults[slot]
                // a hidden app stays off the dock: its spot goes, like a removed one. Hidden holds app keys, and an app
                // with two launcher icons is keyed by its component, so the dock's package is matched through its key.
                val key = if (pkg.isEmpty()) pkg else AppKey.forPackage(this@HomeActivity, pkg)
                val launch = pkg.takeIf { it.isNotEmpty() && key !in hidden && it !in hidden }?.let { pm.getLaunchIntentForPackage(it) }
                val label = launch?.let { try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { null } }
                val icon = launch?.let { try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null } }
                DockApp(pkg, key, launch, label, icon)
            }
            main.post {
                if (gen != dockGen || isDestroyed) return@post
                got.forEachIndexed { k, a ->
                    val s = slots[k]
                    s.pkg = a.pkg; s.key = a.key; s.launch = a.launch; s.name = a.label
                    if (a.icon != null) { s.icon.background = null; s.icon.setImageDrawable(a.icon) }
                    else { s.icon.setImageDrawable(null); s.icon.background = look.round(Color.TRANSPARENT, 16, look.ink3) }
                }
                paintDock()
            }
        }
    }

    /** What the app menu's actions mean on Home. */
    private val menu = object : AppMenu.Actions {
        override fun addToDock(pkg: String) = startDockPick(pkg)
        override fun changeDock(slot: Int) = pickFor(slot)
        override fun removeFromDock(slot: Int) { saveDock(HomeMath.dockPlace(slots.map { it.pkg }, slot, "")); fillDock() }
        override fun changed() { MenuChange.at = SystemClock.uptimeMillis(); orbit.load(); if (layout !== orbit) layout.load(); fillDock(); modeChip.refresh() }
    }

    /** "Add to dock": the orbit steps aside, the dock's spots light up, the next tap on one places the app there.
     *  Anything else (a tap elsewhere, leaving Home, 8 s) cancels. */
    private var dockPick: String? = null
    private val endDockPick = Runnable { stopDockPick() }

    private fun startDockPick(pkg: String) {
        closeOrbit(instant = true)  // at once: a spring still settling would take the first tap on "Here" as a grab
        dockGen++                   // a fill already under way would paint over the "Here" spots
        dockPick = pkg
        paintDock()
        slots.firstOrNull()?.box?.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        main.removeCallbacks(endDockPick); main.postDelayed(endDockPick, 8000)
    }

    private fun stopDockPick() {
        if (dockPick == null) return
        dockPick = null
        main.removeCallbacks(endDockPick)
        paintDock()
        fillDock()
    }

    private fun placeInDock(slot: Int, pkg: String) {
        saveDock(HomeMath.dockPlace(slots.map { it.pkg }, slot, pkg))
        slots[slot].box.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        // not stopDockPick(): its paint would hide an empty spot before the fill brings it back, so the row jumps twice
        dockPick = null; main.removeCallbacks(endDockPick); fillDock()
    }

    /** Writes only the spots that changed ("" = removed), so a spot still on its default keeps following it. */
    private fun saveDock(next: List<String>) {
        val e = getSharedPreferences("aos", MODE_PRIVATE).edit()
        next.forEachIndexed { k, p -> if (p != slots[k].pkg) e.putString("dock$k", p) }
        e.apply()
    }

    private var changing = -1  // the spot the picker is choosing for, until Home is back

    /** "Change app" (or an empty spot): the A to Z picker writes "dock$slot" itself. */
    private fun pickFor(slot: Int) {
        changing = slot
        startActivity(Intent(this, DrawerActivity::class.java).putExtra(DrawerActivity.SLOT, slot))
    }

    /** Back from the picker: an app it put here that already sat in another spot moves, never shows twice. */
    private fun settleChange() {
        val slot = changing.also { changing = -1 }
        if (slot < 0) return
        getSharedPreferences("aos", MODE_PRIVATE).getString("dock$slot", null)?.let { saveDock(HomeMath.dockPlace(slots.map { s -> s.pkg }, slot, it)) }
    }

    /** An app from the orbit or search, by app key (work apps and apps with two launcher activities too). */
    private fun launchApp(icon: View, key: String) {
        LaunchCounts.bump(this, key)
        push()
        Apps.start(this, icon, key)
    }

    /** The dashboard grows out of whatever was tapped (the wave, a panel). */
    private fun openOS(link: String, from: View) =
        go(from, Intent(this, MainActivity::class.java).setData(Uri.parse("${Link.ORIGIN}/${link.removePrefix("/")}")))

    /** A launch: at the same moment Home is pushed away behind it (scale to 1.1, blur), 0.35 s like the system's own
     *  open; it lands again at 0.55 s on return. If nothing opened (Home never paused), it comes back on its own. */
    private fun go(from: View, intent: Intent) { push(); launchFrom(from, intent) }

    /** Home steps back as the app grows out of its icon; it lands on resume, or after 600 ms if nothing opened. */
    private fun push() {
        if (still) return
        enter.to(0f, response = 0.35f, damping = 1f)
        main.removeCallbacks(unpush); main.postDelayed(unpush, 600)
    }

    /** Double tap to lock and the top pull-down for the shade go to the island (an accessibility service). With it
     *  off they do nothing: say so once, with a way to turn it on. */
    private fun global(a: String) {
        root.tick()
        sendBroadcast(Intent(GLOBAL).setPackage(packageName).putExtra("a", a))
        if (!gestureHinted) on(io) { if (!Checks.islandOn(this)) main.post { if (!isDestroyed) hintGestures() } }
    }

    private fun hintGestures() {
        if (gestureHinted) return
        gestureHinted = true
        val hint = look.text("Turn on Agentic OS island in Accessibility to use this gesture", 13f, look.ink2).apply {
            gravity = Gravity.CENTER; minHeight = look.px(48)
            setPadding(look.px(18), look.px(10), look.px(18), look.px(10))
            background = look.glassCard(99)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            isClickable = true; look.press(this)
        }
        // materialize: scale 0.92 to 1, alpha, blur 8 dp to 0 together; leave the same way
        val s = Spring(0f, 0.001f) { v ->
            hint.alpha = v; hint.scaleX = 0.92f + 0.08f * v; hint.scaleY = hint.scaleX
            if (!still) hint.blur(look.px(8) * (1 - v))
        }
        s.onRest = { if (s.target == 0f) root.removeView(hint) }
        val leave = Runnable { if (still) root.removeView(hint) else s.to(0f, response = 0.35f, damping = 1f) }
        hint.setOnClickListener {
            main.removeCallbacks(leave); leave.run()
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
        }
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = back.paddingTop + topRow.height + look.px(8)
            leftMargin = look.px(16); rightMargin = look.px(16)
        })
        if (still) s.snap(1f) else { s.snap(0f); s.to(1f, response = 0.45f, damping = 1f) }
        main.postDelayed(leave, 5000)
    }

    private companion object {
        const val NONE = 0; const val ORBIT = 1; const val PAGE = 2; const val SEARCH = 3; const val LOCKED = 4; const val SHADE = 5
        const val BADGES = "com.jhon.agenticos.BADGES"
        const val GLOBAL = "com.jhon.agenticos.GLOBAL"
        // accessibility custom actions: ids outside the framework (0x01...) and app (0x7f...) resource ranges
        const val A_APPS = 0x7E0A0001; const val A_SEARCH = 0x7E0A0002; const val A_WIDGETS = 0x7E0A0003; const val A_HOME = 0x7E0A0004
        const val A_LAYOUT = 0x7E0A0005
        const val CORNER_SHRINK = 1 - 0.3f / 0.45f  // with compact's 0.45, the orb in the corner is 0.3 of its size
        var gestureHinted = false  // once per process: shown again after a restart if the island is still off
    }
}

/** Home's pure decisions, kept apart so they can be unit tested. */
object HomeMath {
    const val BACK_TO_RINGS = 0; const val BACK_TO_LAYOUT = 1; const val BACK_HOME = 2

    /** Where search goes when he leaves it with the field empty (keyboard closed, Back). The orbit as his layout: its
     *  rings, as always. Another layout: back to it if he came from its search field, else to Home (Jhon, 2 Nov:
     *  pull down, Back, and Orbit showed although his layout was Honeycomb). */
    fun searchBack(layoutIsOrbit: Boolean, fromLayout: Boolean) = when {
        layoutIsOrbit -> BACK_TO_RINGS
        fromLayout -> BACK_TO_LAYOUT
        else -> BACK_HOME
    }

    /** Where a released drag goes: open (1) or shut (0), judged by where the flick was heading, not where it let go.
     *  pos and velocity in the same units (0..1 and 0..1 per second). */
    fun settle(pos: Float, velocity: Float) = if (pos + project(velocity) > 0.5f) 1f else 0f

    /** Launcher3's test: Home pressed while Home was already in front with focus (not brought back from an app). */
    fun alreadyOnHome(focused: Boolean, flags: Int) = focused && (flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0

    /** Where the orb waits while another layout is open: the top right corner, gap in from the edge (and the
     *  cutout or bars on that side), its radius r there. */
    fun cornerX(w: Float, right: Float, r: Float, gap: Float) = w - right - gap - r

    /** Its centre height: in the middle of the band from the status bar to the top 10% the layouts keep clear, and
     *  never under the status bar. */
    fun cornerY(h: Float, top: Float, r: Float, gap: Float) = max(top + gap + r, (top + 0.10f * h) / 2f)

    /** A pull down that starts in the top 12% of the screen opens the shade; lower down it stays search. */
    fun inShadeZone(downY: Float, height: Float) = downY < height * 0.12f

    /** The second tap of a double tap: its down within the timeout of the first tap's up (and not a bounce under
     *  40 ms, like GestureDetector), near the first. prevUp 0 = no first tap. */
    fun doubleTap(prevUp: Long, px: Float, py: Float, down: Long, x: Float, y: Float, timeout: Long, slop: Float): Boolean {
        val dt = down - prevUp
        return prevUp > 0L && dt in 40L..timeout && (x - px) * (x - px) + (y - py) * (y - py) < slop * slop
    }

    /** badges.json ({"pkg": {"count": n, ...}}) to counts above zero; anything unreadable is no badges. */
    fun badgeCounts(json: String): Map<String, Int> {
        val o = try { JSONObject(json) } catch (_: Exception) { return emptyMap() }
        val m = HashMap<String, Int>()
        for (k in o.keys()) { val n = o.optJSONObject(k)?.optInt("count") ?: 0; if (n > 0) m[k] = n }
        return m
    }

    fun badgeText(n: Int) = if (n > 99) "99+" else n.toString()

    /** The dock after pkg goes into slot ("" removes what is there): an app already in another spot moves there,
     *  so it never shows twice. */
    fun dockPlace(dock: List<String>, slot: Int, pkg: String) = dock.mapIndexed { k, p -> if (k == slot) pkg else if (p == pkg) "" else p }

    /** A dock spot shows only with an app in it, or while an app is being placed (then every spot lights as "Here"). */
    fun dockShows(hasApp: Boolean, picking: Boolean) = hasApp || picking

    /** What TalkBack says for a dock app. */
    fun slotSays(label: String, n: Int) = when {
        n <= 0 -> label
        n == 1 -> "$label, 1 notification"
        else -> "$label, $n notifications"
    }

    /** How many things need him: approvals, the urgent item and the next list. */
    fun needsCount(home: JSONObject, approvals: JSONArray) =
        approvals.length() + (if (home.optJSONObject("urgent") != null) 1 else 0) + (home.optJSONArray("next")?.length() ?: 0)

    /** What counts as "something changed" for the wave: needs, approvals, panels, agents and whether Jharvis is up.
     *  Leaves out timestamps and local marks, so a quiet 20 s refresh keeps the wave still. */
    fun sig(home: JSONObject, approvals: JSONArray, panels: JSONArray) = buildString {
        append(home.optString("needs_sig")).append('|').append(home.optBoolean("online")).append('|')
        home.optJSONArray("agents")?.let { a ->
            for (k in 0 until a.length()) a.optJSONObject(k)?.let { append(it.optString("name")).append(':').append(it.optString("status")).append(',') }
        }
        append('|')
        for (k in 0 until approvals.length()) append(approvals.optJSONObject(k)?.optString("id")).append(',')
        append('|')
        for (k in 0 until panels.length()) panels.optJSONObject(k)?.let { append(it.optString("id")).append(it.optString("title")).append(it.optJSONArray("lines")) }
    }
}

/** The Settings button's gear, drawn (the old system wrench looked dated): a solid gear like iOS's, eight
 *  rounded teeth, a clean hole, in ink 2. */
private class GearIcon(c: Context, private val look: Look) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.ink2 }
    private var gear: android.graphics.Path? = null
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val r = minOf(w, h) * 0.24f  // a 23 dp gear in the 48 dp button
        val cx = w / 2f; val cy = h / 2f
        val path = android.graphics.Path().apply { addCircle(cx, cy, r * 0.74f, android.graphics.Path.Direction.CW) }
        val tooth = android.graphics.RectF(cx - r * 0.18f, cy - r, cx + r * 0.18f, cy - r * 0.6f)
        for (k in 0 until 8) path.op(android.graphics.Path().apply {
            addRoundRect(tooth, r * 0.09f, r * 0.09f, android.graphics.Path.Direction.CW)
            transform(android.graphics.Matrix().apply { setRotate(k * 45f, cx, cy) })
        }, android.graphics.Path.Op.UNION)
        path.op(android.graphics.Path().apply { addCircle(cx, cy, r * 0.32f, android.graphics.Path.Direction.CW) }, android.graphics.Path.Op.DIFFERENCE)
        gear = path
    }
    override fun onDraw(canvas: Canvas) { gear?.let { canvas.drawPath(it, p) } }
}
