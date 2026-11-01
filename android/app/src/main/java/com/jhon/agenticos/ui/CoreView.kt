package com.jhon.agenticos.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.provider.Settings
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The Agentic OS core: rings of Glacier dots. Still when idle (no idle looping), one pulse when something new
 *  needs him, grey when Jharvis can't be reached. */
class CoreView(c: Context, private val look: Look) : View(c) {
    var live = true
        set(v) { field = v; invalidate() }
    private var swell = 0f
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        contentDescription = "Talk to Jharvis"
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f * 0.92f
        dot.color = if (live) look.glacier else look.ink3
        for (ring in 0 until 5) {
            val rr = r * (0.36f + ring * 0.16f) * (1 + swell * (0.02f + ring * 0.015f))
            val n = 10 + ring * 7
            dot.alpha = (255 * (1f - ring * 0.16f)).toInt()
            val size = look.px(3) * (1.25f - ring * 0.1f)
            for (k in 0 until n) {
                val a = 2 * Math.PI * k / n + ring * 0.3
                canvas.drawCircle(cx + (rr * cos(a)).toFloat(), cy + (rr * sin(a)).toFloat(), size, dot)
            }
        }
    }

    /** One breath out and back; skipped when the phone asks for less motion. */
    fun pulse() {
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        if (scale == 0f) return
        ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 900
            addUpdateListener { swell = it.animatedValue as Float; invalidate() }
            start()
        }
    }
}
