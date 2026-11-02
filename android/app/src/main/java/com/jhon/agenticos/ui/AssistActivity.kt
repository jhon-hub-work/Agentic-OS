package com.jhon.agenticos.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import com.jhon.agenticos.ListenService
import com.jhon.agenticos.MainActivity
import java.util.function.Consumer
import kotlin.math.abs

/** Side button held (once Agentic OS is the digital assistant): Jharvis listens, no "Jharvis" needed. The app he's
 *  in frosts over in ice, Jharvis's light glows in from the screen's edges and breathes with his voice, the wave at
 *  the bottom rolls with it, and his words then Jharvis's answer materialize in large calm type. ListenService (in
 *  :core) does the listening and speaking and tells this screen where the turn is (ListenService.STATE). Tap
 *  anywhere or Back closes it; Jharvis keeps listening for the window either way. */
class AssistActivity : Activity() {
    companion object {
        /** Home hides its own orb while this overlay is up (true), so only one orb shows over a frosted Home. Main thread. */
        var showing: ((Boolean) -> Unit)? = null
        /** Home's orb while Home is on screen showing it, else null: this overlay's orb starts there and glides down, so
         *  the two read as one orb. Main thread. */
        var homeOrb: (() -> JharvisOrb?)? = null
        private const val HAND_OVER_MS = 48L  // after our first frame: the window is on screen before Home's orb goes
    }

    private enum class Phase { LISTENING, HEARING, THINKING, SPEAKING, DONE, OFFLINE }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var look: Look
    private lateinit var root: FrameLayout
    private lateinit var veil: View
    private lateinit var glow: JharvisGlow
    private lateinit var wave: JharvisOrb
    private lateinit var stage: LinearLayout
    private lateinit var status: TextView
    private lateinit var said: TextView
    private lateinit var reply: TextView
    private lateinit var presence: Spring
    private lateinit var glide: Spring   // our orb: 0 = sitting on Home's orb, 1 = at its spot at the bottom
    private var home: JharvisOrb? = null // Home's orb when we opened over it (null: ours rises from below)
    private var handed = false           // Home's orb has stepped out and ours carries on
    private val screenAt = IntArray(2)
    private var phase = Phase.LISTENING
    private var still = false
    private var blurOn = true
    private var blurPx = -1
    private var leaving = false
    private var heard = false            // said shows his words (not the opening hint)
    private var listenUntil = 0L         // the service takes words without "Jharvis" until then

    private val rx = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = on(i) }
    private val blurChanged = Consumer<Boolean> { on -> blurOn = on; veilColour(); arrive(presence.value) }
    private val stirLoop = object : Runnable { override fun run() { wave.stir(); main.postDelayed(this, 1800) } }
    private val nothingSaid = Runnable { if (phase == Phase.LISTENING) dismiss() }
    private val handOver = Runnable {
        if (!handed && !leaving) {
            handed = true
            showing?.invoke(true)  // Home's orb goes on the same frame ours starts to move
            wave.live = true       // a grey (PC off) Home orb lights up on its way down, as the overlay's always has
            glide.to(1f, response = 0.5f, damping = 1f)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, MainActivity::class.java))  // the app asks for the mic the first time it opens
            finish(); return
        }
        ListenService.now(this)  // allowed: this screen is visible
        look = Look(this)
        still = reducedMotion(this)

        window.setDecorFitsSystemWindows(false)
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        window.setDimAmount(0f)  // our own ice veil instead of the theme's grey dim
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.isNavigationBarContrastEnforced = false

        home = if (still) null else homeOrb?.invoke()
        build()
        setContentView(root)
        if (home == null) showing?.invoke(true)
        else if (Build.VERSION.SDK_INT >= 34) {  // our orb is Home's: the window appears and leaves in place, no slide
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else inPlace()
        if (Color.luminance(look.ground) > 0.5f) window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)

        presence = Spring(0f, 0.002f) { arrive(it) }
        glide = Spring(0f, 0.0005f) { placeOrb() }
        home?.let { h ->
            wave.match(h)
            wave.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeOrb() }
            // the overlay's first frame shows our orb exactly over Home's; then Home's steps out and ours glides down
            root.viewTreeObserver.registerFrameCommitCallback { main.postDelayed(handOver, HAND_OVER_MS) }
            main.postDelayed(handOver, 400)  // in case no frame commit is reported
        }
        blurOn = windowManager.isCrossWindowBlurEnabled
        windowManager.addCrossWindowBlurEnabledListener(mainExecutor, blurChanged)  // battery saver can switch blur off
        veilColour()
        registerReceiver(rx, IntentFilter(ListenService.STATE), RECEIVER_NOT_EXPORTED)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { dismiss() }

        listenUntil = System.currentTimeMillis() + 10_000
        root.post {
            root.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
            presence.to(1f, response = 0.55f)
            wave.pulse()
        }
        main.postDelayed(nothingSaid, 10_000)
        main.postDelayed({ dismiss() }, 120_000)  // never left over an app indefinitely
    }

    private fun build() {
        root = FrameLayout(this).apply {
            contentDescription = "Jharvis. Tap to close."
            setOnClickListener { dismiss() }
        }
        veil = View(this)
        glow = JharvisGlow(this, look)
        wave = JharvisOrb(this, look).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; alpha = 0f }
        status = look.text("Listening", 15f, look.ink3).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        said = look.text("Say what you need.", 26f, look.ink3).apply { big(3) }
        reply = look.text("", 28f, look.ink).apply { big(7); visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        stage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            alpha = 0f
            listOf(status, said, reply).forEach(look::halo)  // over any app, bright or dark: the ground behind the letters
            addView(status)
            addView(said, LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.px(10) })
            addView(reply, LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.px(18) })
        }
        val waveH = look.px(210)  // the orb is 0.4 of this across
        root.addView(veil, FrameLayout.LayoutParams(-1, -1))
        root.addView(glow, FrameLayout.LayoutParams(-1, -1))
        root.addView(stage, FrameLayout.LayoutParams(-1, -1))
        root.addView(wave, FrameLayout.LayoutParams(-1, waveH, Gravity.BOTTOM))
        root.setOnApplyWindowInsetsListener { _, insets ->  // edge to edge: text clear of the bars and cutout, wave above the gesture bar
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            (stage.layoutParams as FrameLayout.LayoutParams).setMargins(bars.left + look.px(28),
                bars.top + (resources.displayMetrics.heightPixels * 0.2f).toInt(), bars.right + look.px(28), bars.bottom + waveH + look.px(20))
            (wave.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + look.px(4)
            stage.requestLayout(); wave.requestLayout()
            insets
        }
    }

    /** Large calm type: tight leading for big sizes, long text ends with an ellipsis instead of running into the wave. */
    private fun TextView.big(lines: Int) {
        setLineSpacing(0f, 1.12f); maxLines = lines; ellipsize = android.text.TextUtils.TruncateAt.END
    }

    // ── where the turn is ─────────────────────────────────────────────────────────────────────────────

    private fun on(i: Intent) {
        if (leaving) return
        when (i.getStringExtra("state")) {
            "level" -> if (phase == Phase.LISTENING || phase == Phase.HEARING) i.getFloatExtra("level", 0f).let { glow.level = it; wave.level = it }
            "hearing" -> if (phase == Phase.LISTENING) to(Phase.HEARING)
            "listening" -> if (phase == Phase.HEARING || phase == Phase.THINKING) to(Phase.LISTENING)  // a click, or nothing for him
            "thinking" -> {
                if (phase == Phase.LISTENING || phase == Phase.HEARING) {
                    to(Phase.THINKING)
                    root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)  // he stopped talking: got it
                }
                i.getStringExtra("text")?.let { showSaid(it) }
            }
            "speaking" -> { to(Phase.SPEAKING); i.getStringExtra("text")?.let { showReply(it, look.ink) }; wave.pulse() }
            "done" -> if (i.getBooleanExtra("more", false)) {
                listenUntil = System.currentTimeMillis() + 12_000  // Jharvis asked him something: listen for the answer
                to(Phase.LISTENING)
            } else to(Phase.DONE)
            "offline" -> to(Phase.OFFLINE)
        }
    }

    private fun to(p: Phase) {
        if (phase == p) return
        phase = p
        glow.mode = when (p) {
            Phase.LISTENING, Phase.HEARING -> JharvisGlow.Mode.LISTEN
            Phase.THINKING -> JharvisGlow.Mode.THINK
            Phase.SPEAKING -> JharvisGlow.Mode.SPEAK
            Phase.DONE, Phase.OFFLINE -> JharvisGlow.Mode.REST
        }
        if (p != Phase.LISTENING && p != Phase.HEARING) { glow.level = 0f; wave.level = 0f }
        main.removeCallbacks(stirLoop)
        if (p == Phase.THINKING || p == Phase.SPEAKING) main.post(stirLoop)
        main.removeCallbacks(nothingSaid)
        when (p) {
            Phase.LISTENING -> {
                setStatus(if (reply.visibility == View.VISIBLE) "Listening for your answer" else "Listening")
                main.postDelayed(nothingSaid, (listenUntil - System.currentTimeMillis()).coerceAtLeast(1500))
            }
            Phase.HEARING -> setStatus("Hearing you")
            Phase.THINKING -> setStatus("Thinking")
            Phase.SPEAKING -> setStatus("Jharvis")
            Phase.DONE -> { setStatus("Done"); main.postDelayed({ dismiss() }, 2500) }
            Phase.OFFLINE -> {
                setStatus("No answer")
                showReply("Your PC isn't answering right now. Try again in a moment.", look.ink2)
                main.postDelayed({ dismiss() }, 4500)
            }
        }
    }

    private fun setStatus(s: String) {
        if (status.text.toString() == s) return
        if (still) { status.text = s; return }
        status.animate().cancel()
        status.animate().alpha(0f).setDuration(90).withEndAction {
            status.text = s
            status.animate().alpha(1f).setDuration(200).setInterpolator(SpringCurve(1f)).start()
        }.start()
    }

    private fun showSaid(s: String) {
        if (heard && said.text.toString() == s) return
        heard = true
        if (reply.visibility == View.VISIBLE) reply.visibility = View.GONE  // a new question after a follow-up
        materialize(said, s, look.ink)
    }

    private fun showReply(s: String, colour: Int) {
        if (heard) said.animate().alpha(0.45f).setDuration(320).setInterpolator(SpringCurve(1f)).start()  // his words step back
        else said.visibility = View.GONE  // the opening hint has nothing left to say
        materialize(reply, s, colour)
    }

    /** Materialize, don't fade: scale 0.92 to 1, alpha and an 8 dp blur clearing together. Remove animations: alpha only. */
    private fun materialize(v: TextView, s: String, colour: Int) {
        v.animate().cancel()
        v.text = s; v.setTextColor(colour); v.visibility = View.VISIBLE
        v.pivotX = 0f; v.pivotY = 0f
        val blur = look.px(8).toFloat()
        Spring(0f, 0.002f) { p ->
            v.alpha = p
            if (!still) { val k = 0.92f + 0.08f * p; v.scaleX = k; v.scaleY = k; v.blur((1 - p) * blur) }
        }.to(1f, response = 0.5f)
    }

    // ── arriving and leaving: one spring, the same path both ways ────────────────────────────────────

    private fun arrive(p: Float) {
        veil.alpha = p
        glow.presence = p
        stage.alpha = p
        if (home == null) wave.alpha = p
        if (!still && home == null) {
            val k = 0.92f + 0.08f * p
            wave.scaleX = k; wave.scaleY = k
            wave.translationY = (1 - p) * look.px(28)
        }
        // the app behind frosts as the overlay arrives (a fixed frost under Remove animations); set only when it moves
        val r = if (blurOn && p > 0.02f) (look.px(30) * (if (still) 1f else p)).toInt() else 0
        if (r != blurPx && (abs(r - blurPx) >= 2 || r == 0)) {
            blurPx = r
            window.attributes = window.attributes.apply { blurBehindRadius = r }
        }
    }

    /** Our orb between Home's orb (glide 0) and its own spot at the bottom (1): centre and size ride one spring, so it
     *  reads as Home's orb gliding down and back. Only when we opened over Home; arrive() moves it otherwise. */
    private fun placeOrb() {
        val h = home ?: return
        if (wave.width == 0) return
        val from = h.lensOnScreen()
        root.getLocationOnScreen(screenAt)
        val own = OrbMath.LENS * minOf(wave.width, wave.height)
        val g = glide.value; val q = 1 - g
        val k = (from[2] * q + own * g) / own
        wave.translationX = (from[0] - screenAt[0] - (wave.left + wave.width / 2f)) * q
        wave.translationY = (from[1] - screenAt[1] - (wave.top + wave.height / 2f)) * q
        wave.scaleX = k; wave.scaleY = k; wave.zoom = k
        wave.alpha = 1f
    }

    /** Ice over the app behind: lighter when the system frosts it, nearly solid when blur is off. */
    private fun veilColour() {
        val a = if (blurOn) 0.62f else 0.88f
        veil.setBackgroundColor((look.ground and 0xFFFFFF) or ((255 * a).toInt() shl 24))
    }

    private fun dismiss() {
        if (leaving) return
        leaving = true
        main.removeCallbacksAndMessages(null)
        val h = home
        if (h == null) {
            showing?.invoke(false)  // Home's orb comes back as this one sinks away
            presence.onRest = { finish() }
        } else {
            // ours glides back up onto Home's spot; there Home's orb shows again on the same frame and we go in place
            wave.live = h.live
            glide.onRest = { h.match(wave); showing?.invoke(false); finish(); inPlace() }
            glide.to(0f, response = 0.5f, damping = 1f)
        }
        presence.to(0f, response = 0.32f)
    }

    @Suppress("DEPRECATION")  // overridePendingTransition: only the API 33 path
    private fun inPlace() { if (Build.VERSION.SDK_INT < 34) overridePendingTransition(0, 0) }

    // switched away (Jharvis opened an app, or he pressed Home): the overlay goes with it
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isFinishing) finish()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::presence.isInitialized) {
            showing?.invoke(false)
            presence.stop(); glide.stop()
            try { unregisterReceiver(rx) } catch (_: IllegalArgumentException) {}
            windowManager.removeCrossWindowBlurEnabledListener(blurChanged)
        }
        super.onDestroy()
    }
}
