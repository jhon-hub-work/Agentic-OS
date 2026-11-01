package com.jhon.agenticos.ui

import android.animation.ArgbEvaluator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.SweepGradient
import android.view.Choreographer
import android.view.RoundedCorner
import android.view.View
import android.widget.FrameLayout
import kotlin.math.min
import kotlin.math.sin

/** Jharvis's light: Glacier ice glowing in from the screen's own rounded edges. It swells with Jhon's voice while
 *  Jharvis listens, travels round the edge while he thinks, and breathes while he speaks, then dims to rest.
 *  Learned from 27launch's Siri light (full-resolution light, brightness eased toward the voice level), drawn in
 *  Jharvis's ice instead of a rainbow. Two layers: a wide halo blurred on the GPU, and a bright rim at the glass edge.
 *  Remove animations: a still glow whose strength shows the state, no frame loop. */
class JharvisGlow(c: Context, private val look: Look) : FrameLayout(c) {
    enum class Mode { LISTEN, THINK, SPEAK, REST }

    var mode = Mode.LISTEN
        set(v) { field = v; if (still) { energy = restingEnergy(v); redraw() } else wake() }

    /** His voice, 0..1. */
    var level = 0f
        set(v) { field = v.coerceIn(0f, 1f) }

    /** The overlay's arrival, 0..1: the light grows in from beyond the edge, and leaves the same way. */
    var presence = 0f
        set(v) { field = v.coerceIn(0f, 1f); if (still) redraw() else wake() }

    private val still = reducedMotion(c)
    private val path = Path()
    private val turn = Matrix()
    private var sweep: SweepGradient? = null
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var energy = restingEnergy(Mode.LISTEN)
    private var voice = 0f
    private var spin = 0f    // degrees a second the light travels round the edge
    private var angle = 0f
    private var clock = 0f

    private val haloLayer = Layer(c) { drawHalo(it) }.apply { blur(look.px(22).toFloat()) }
    private val rimLayer = Layer(c) { drawRim(it) }.apply { blur(look.px(2).toFloat()) }

    private var running = false
    private var last = 0L
    private val frame = Choreographer.FrameCallback { step(it) }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(haloLayer, LayoutParams(-1, -1))
        addView(rimLayer, LayoutParams(-1, -1))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        // the light follows the glass itself: the display's own corner radius when the phone reports it
        val r = rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: look.px(44).toFloat()
        path.reset()
        path.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, Path.Direction.CW)
        // ice tones from the theme's accent: Glacier, a near-white ice, a deeper Glacier toward navy; no rainbow
        val ice = mix(look.glacier, Color.WHITE, 0.7f)
        val deep = mix(look.glacier, look.navy, 0.35f)
        sweep = SweepGradient(w / 2f, h / 2f, intArrayOf(look.glacier, ice, look.glacier, deep, look.glacier, ice, look.glacier), null)
        halo.shader = sweep; rim.shader = sweep
    }

    private fun drawHalo(c: Canvas) {
        if (presence <= 0.002f) return
        aim()
        // the stroke sits on the screen edge, so only its inner half shows: light coming in from beyond the glass
        halo.strokeWidth = look.px(2) + (look.px(40) + look.px(72) * energy) * presence
        halo.alpha = (255 * (0.35f + 0.5f * energy) * presence).toInt().coerceIn(0, 255)
        c.drawPath(path, halo)
    }

    private fun drawRim(c: Canvas) {
        if (presence <= 0.002f) return
        aim()
        rim.strokeWidth = (look.px(4) + look.px(7) * energy) * presence
        rim.alpha = (255 * (0.55f + 0.45f * energy) * presence).toInt().coerceIn(0, 255)
        c.drawPath(path, rim)
    }

    private fun aim() { turn.setRotate(angle, width / 2f, height / 2f); sweep?.setLocalMatrix(turn) }

    private fun redraw() { haloLayer.invalidate(); rimLayer.invalidate() }

    // ── the frame loop: runs while the light is on screen and moving ─────────────────────────────────────

    private fun wake() {
        if (running || still || !isAttachedToWindow) return
        running = true; last = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun step(now: Long) {
        if (!running) return
        val dt = if (last == 0L) 1f / 60 else ((now - last) / 1e9f).coerceIn(0f, 1f / 20)
        last = now
        clock += dt
        voice += (level - voice) * min(1f, dt * (if (level > voice) 16f else 5f))
        val target = when (mode) {
            Mode.LISTEN -> 0.28f + 0.72f * voice            // breathes with his voice, still when he's quiet
            Mode.THINK -> 0.6f + 0.08f * sin(clock * 2.4f)  // working: a slow breath while the light travels
            Mode.SPEAK -> 0.42f + 0.38f * speech(clock)     // Jharvis talking
            Mode.REST -> 0.16f
        }
        energy += (target - energy) * min(1f, dt * 9f)
        val spinTo = when (mode) { Mode.LISTEN -> 60f * voice; Mode.THINK -> 120f; Mode.SPEAK -> 36f; Mode.REST -> 0f }
        spin += (spinTo - spin) * min(1f, dt * 3f)
        angle = (angle + spin * dt) % 360f
        redraw()
        val settled = mode == Mode.REST && spin < 0.5f && kotlin.math.abs(energy - target) < 0.002f
        if (settled || presence <= 0.002f && mode == Mode.REST) running = false
        else Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun stop() { if (running) { running = false; Choreographer.getInstance().removeFrameCallback(frame) } }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); wake() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) wake() else stop()
    }

    private class Layer(c: Context, private val paint: (Canvas) -> Unit) : View(c) {
        override fun onDraw(canvas: Canvas) = paint(canvas)
    }

    private companion object {
        fun restingEnergy(m: Mode) = when (m) { Mode.LISTEN -> 0.4f; Mode.THINK -> 0.6f; Mode.SPEAK -> 0.55f; Mode.REST -> 0.2f }
        fun mix(a: Int, b: Int, t: Float) = ArgbEvaluator().evaluate(t, a, b) as Int
        /** A speaking rhythm: syllables (about 1.7 a second) under a slower phrase swell, 0..1. */
        fun speech(t: Float) = ((0.5f + 0.5f * sin(t * 11f)) * (0.55f + 0.45f * sin(t * 2.7f + 1.3f))).coerceIn(0f, 1f)
    }
}
