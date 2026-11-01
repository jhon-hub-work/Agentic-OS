package com.jhon.agenticos.ui

import android.content.Context
import android.provider.Settings
import android.view.Choreographer
import kotlin.math.PI
import kotlin.math.abs

/** Apple's spring (Designing Fluid Interfaces): two numbers instead of mass/stiffness. response = how fast it gets
 *  there (s), damping = 1 no overshoot, < 1 bounces. Always moves from where it is now with the speed it has, so a
 *  finger can grab it mid-flight and a release hands its velocity straight in. */
class Spring(
    var value: Float,
    private val epsilon: Float = 0.001f,  // "close enough" in this value's units (0.001 of a 0..1 fraction, 0.5 px...)
    private val apply: (Float) -> Unit,
) : Choreographer.FrameCallback {
    var velocity = 0f; private set
    var target = value; private set
    private var k = 0f; private var c = 0f
    private var last = 0L
    private var running = false
    var onRest: (() -> Unit)? = null

    fun to(target: Float, response: Float = 0.35f, damping: Float = 1f, velocity: Float? = null) {
        this.target = target
        k = ((2 * PI / response) * (2 * PI / response)).toFloat()
        c = (4 * PI * damping / response).toFloat()
        velocity?.let { this.velocity = it }
        if (!running) { running = true; last = 0L; Choreographer.getInstance().postFrameCallback(this) }
    }

    /** Follow the finger exactly (no spring), remembering nothing but the position. */
    fun snap(v: Float) { stop(); value = v; target = v; velocity = 0f; apply(v) }

    fun stop() { if (running) { running = false; Choreographer.getInstance().removeFrameCallback(this) } }

    override fun doFrame(now: Long) {
        if (!running) return
        var dt = if (last == 0L) 1f / 120 else ((now - last) / 1e9f).coerceIn(0f, 1f / 30)
        last = now
        while (dt > 0f) {  // small steps keep stiff springs stable on a slow frame
            val h = minOf(dt, 1f / 480)
            velocity += (-k * (value - target) - c * velocity) * h
            value += velocity * h
            dt -= h
        }
        if (abs(value - target) < epsilon && abs(velocity) < epsilon * 10) {
            value = target; velocity = 0f; running = false; apply(value); onRest?.invoke(); return
        }
        apply(value)
        Choreographer.getInstance().postFrameCallback(this)
    }
}

/** Apple's momentum projection: where a flick would come to rest (decelerationRate 0.998 = scroll feel). */
fun project(velocityPerSec: Float, rate: Float = 0.998f) = velocityPerSec / 1000f * rate / (1 - rate)

/** Phone set to "Remove animations": springs snap, blurs stay off, a cross-fade at most. */
fun reducedMotion(c: Context) = Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** Past an edge the content follows less and less (rubber band) instead of stopping dead. */
fun rubberband(over: Float, size: Float, k: Float = 0.55f) = over * size * k / (size + k * abs(over))
