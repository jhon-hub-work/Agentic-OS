package com.jhon.agenticos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The dock's Apps button now opens the orbit, not a drawer, so it draws one: a core, two hairline rings and a few
 *  apps riding them, in the accent. Light lines, like the rest of the glyphs. */
class OrbitIcon(c: Context, private val look: Look) : View(c) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.25f * c.resources.displayMetrics.density
        color = look.ink3; alpha = 150
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.glacier }

    init { background = look.glassCard(16) }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f
        val inner = r * 0.36f; val outer = r * 0.62f
        canvas.drawCircle(cx, cy, inner, ring)
        canvas.drawCircle(cx, cy, outer, ring)
        canvas.drawCircle(cx, cy, r * 0.13f, dot)  // the core
        // apps on the rings at fixed angles (degrees), the outer ones a touch smaller
        for ((ringR, deg, size) in listOf(Triple(inner, -40.0, 0.085f), Triple(outer, 25.0, 0.075f), Triple(outer, 200.0, 0.075f))) {
            val a = Math.toRadians(deg)
            canvas.drawCircle(cx + ringR * cos(a).toFloat(), cy + ringR * sin(a).toFloat(), r * size, dot)
        }
    }
}
