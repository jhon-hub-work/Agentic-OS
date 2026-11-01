package com.jhon.agenticos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The Agentic OS core: the dashboard's Glacier dot wave (web/src/WaveField.tsx) as a perspective dot field on
 *  Canvas, a soft Glacier glow behind it. Still when idle: it draws the settled frame and stops its frame callback.
 *  stir() rolls it for about 2 s, pulse() sends one swell out from the middle, a finger dragged over it ripples the
 *  dots from where it touches, compact folds the field into a small orb of the same dots (the orbit's centre),
 *  level (a voice, 0..1) lifts and rolls the waves while someone talks. Colours come from Look, so a theme's accent
 *  and ground carry through. */
class WaveCore(c: Context, private val look: Look) : View(c) {
    /** Jharvis reachable. false: grey dots (ink 3), calmer waves. */
    var live = true
        set(v) {
            if (field == v) return
            field = v
            if (still) { grey = if (v) 0f else 1f; invalidate() } else wake()
        }

    /** 0 = the full wave in the middle of home, 1 = folded into the small orb at the orbit's centre (home drives it). */
    var compact = 0f
        set(v) { val n = v.coerceIn(0f, 1f); if (n != field) { field = n; invalidate() } }

    /** A voice's loudness, 0..1 (Jharvis's overlay feeds Jhon's mic level): the waves lift and travel with it. */
    var level = 0f
        set(v) { field = v.coerceIn(0f, 1f); if (field > 0f) wake() }

    private val still = reducedMotion(c)
    private val n = WaveMath.COLS * WaveMath.ROWS
    private val gx = FloatArray(n)
    private val gz = FloatArray(n)
    private val orb = FloatArray(n * 3)          // tilted unit-sphere point for each grid dot
    private val front = BooleanArray(n)
    private val pts = FloatArray(n * 2)          // screen positions, row-major, row 0 nearest
    private val frontPts = FloatArray(n * 2)
    private val backPts = FloatArray(n * 2)
    private val rowShader = arrayOfNulls<Shader>(WaveMath.ROWS)
    private val rowSize = FloatArray(WaveMath.ROWS)

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val greyDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        colorFilter = PorterDuffColorFilter(look.ink3, PorterDuff.Mode.SRC_IN)  // keeps each dot's fade, swaps the colour
    }
    private val orbDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    // projection, set on size
    private var f = 1f; private var cx = 0f; private var horizon = 0f; private var cy = 0f
    private var orbR = 1f; private var orbSize = 1f; private var glowR = 1f

    // motion state
    private var phase = 1.7f       // wave phase; it only advances while stirred, so idle = a settled frame
    private var energy = 0f
    private var voice = 0f         // level, eased: quick to rise, slower to fall
    private var stirLeft = 0f
    private var clock = 0f         // seconds the loop has run, for swell and ripple ages
    private var pulseAt = -10f
    private var grey = 0f
    private val rx = FloatArray(RIPPLES); private val rz = FloatArray(RIPPLES); private val rt = FloatArray(RIPPLES) { -10f }
    private var nextRipple = 0
    private var fingerX = 0f; private var fingerZ = 0f; private var down = false; private var bump = 0f
    private var lastEmitAt = -10f; private var lastEmitX = 0f; private var lastEmitZ = 0f
    private var downX = 0f; private var downY = 0f; private var dragging = false
    private val slop = ViewConfiguration.get(c).scaledTouchSlop

    private var running = false
    private var last = 0L
    private val frame = Choreographer.FrameCallback { step(it) }

    init {
        contentDescription = "Open the dashboard"
        isClickable = true
        for (r in 0 until WaveMath.ROWS) for (k in 0 until WaveMath.COLS) {
            val i = r * WaveMath.COLS + k
            gx[i] = WaveMath.gridX(k); gz[i] = WaveMath.gridZ(r)
        }
        WaveMath.orb(WaveMath.ROWS, WaveMath.COLS, orb)
        for (i in 0 until n) front[i] = orb[i * 3 + 2] > 0f
    }

    /** Something new needs him: one swell rolls out through the dots. */
    fun pulse() { if (still) return; pulseAt = clock; wake() }

    /** A short burst of waves (about 2 s), then the field eases back to still. */
    fun stir() { if (still) return; stirLeft = 2f; wake() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val wf = w.toFloat(); val hf = h.toFloat()
        f = min(wf * 0.27f, hf * 0.62f)
        cx = wf / 2f; cy = hf / 2f
        horizon = hf * 0.54f - WaveMath.CAM_H * f / (WaveMath.NEAR + WaveMath.DEPTH / 2f)  // mid-depth sits just below centre
        orbR = min(wf, hf) * 0.34f
        orbSize = look.px(2) * 0.8f
        glowR = min(wf * 0.48f, hf * 0.62f)
        glow.shader = RadialGradient(cx, cy, glowR, intArrayOf(alpha(look.glacier, 0x40), alpha(look.glacier, 0x16), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        // the dashboard's slate, navy and pale ice, from the theme: ink 3 at the left, navy at the right, far rows
        // fading toward the ground (pale ice on a light theme, into the dark on a dark one)
        val slate = look.ink3; val navy = look.navy; val pale = mix(look.glacier, look.ground, 0.75f)
        val base = look.px(3).toFloat()
        for (r in 0 until WaveMath.ROWS) {
            val z = WaveMath.gridZ(r)
            val far = r / (WaveMath.ROWS - 1f)
            val x0 = WaveMath.screenX(-WaveMath.HALF_W, z, cx, f); val x1 = WaveMath.screenX(WaveMath.HALF_W, z, cx, f)
            // the dashboard's dots: slate at the left edge, Glacier in the middle, navy at the right, edges faded;
            // far rows paler and fainter, the nearest and farthest rows fade out so the field has no hard rim
            val a = (0.45f + 0.55f * (1 - far)) * smooth(min(far, 1 - far) / 0.2f)
            fun tone(col: Int) = mix(col, pale, far * 0.7f)
            rowShader[r] = LinearGradient(x0, 0f, x1, 0f,
                intArrayOf(alpha(tone(slate), 0), alpha(tone(mix(slate, look.glacier, 0.6f)), (255 * a).toInt()),
                    alpha(tone(look.glacier), (255 * a).toInt()), alpha(tone(mix(look.glacier, navy, 0.4f)), (255 * a).toInt()),
                    alpha(tone(navy), 0)),
                floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f), Shader.TileMode.CLAMP)
            rowSize[r] = maxOf(base * WaveMath.NEAR / (z + WaveMath.NEAR), look.px(1) * 0.9f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        val e = smooth(compact)
        val amp = (1f + energy * 1.3f + voice * 1.8f) * (1f - grey * 0.45f)
        val since = clock - pulseAt
        var nf = 0; var nb = 0
        for (i in 0 until n) {
            val x = gx[i]; val z = gz[i]
            var y = WaveMath.height(x, z, phase, amp)
            if (since < 3f) y += WaveMath.swell(hypot(x, z * 2f - WaveMath.DEPTH), since)
            for (k in 0 until RIPPLES) {
                val age = clock - rt[k]
                if (age < 2.5f) y += WaveMath.ripple(hypot(x - rx[k], z - rz[k]), age)
            }
            if (bump > 0.003f) { val dx = x - fingerX; val dz = z - fingerZ; y += bump * 0.9f * exp(-(dx * dx + dz * dz) / 1.2f) }
            var sx = WaveMath.screenX(x, z, cx, f)
            var sy = WaveMath.screenY(y, z, horizon, f)
            if (e > 0f) {
                val rr = orbR * (1f + 0.05f * y)  // the orb keeps the wave in it as a gentle swell of its surface
                val ox = cx + orb[i * 3] * rr; val oy = cy - orb[i * 3 + 1] * rr
                sx += (ox - sx) * e; sy += (oy - sy) * e
                if (front[i]) { frontPts[nf++] = sx; frontPts[nf++] = sy } else { backPts[nb++] = sx; backPts[nb++] = sy }
            }
            pts[i * 2] = sx; pts[i * 2 + 1] = sy
        }

        // glow behind: dimmer when Jharvis can't be reached, brighter for a moment with a swell, smaller as an orb
        val boost = if (since < 1.5f) (1 - since / 1.5f) * 0.6f else 0f
        glow.alpha = (255 * ((1f - grey * 0.65f) * (1f + boost + voice * 0.5f) * (0.8f + 0.2f * energy)).coerceIn(0f, 1f)).toInt()
        canvas.save()
        val gs = 1f - 0.45f * e
        canvas.scale(gs, gs, cx, cy)
        canvas.drawCircle(cx, cy, glowR, glow)
        canvas.restore()

        val ink = mix(look.glacier, look.ink3, grey)
        if (e > 0f) {
            orbDot.color = ink; orbDot.alpha = (255 * e * 0.35f).toInt(); orbDot.strokeWidth = orbSize * 0.75f
            canvas.drawPoints(backPts, 0, nb, orbDot)
        }
        if (e < 1f) {
            val wa = 1f - e
            dot.alpha = (255 * wa * (1 - grey)).toInt(); greyDot.alpha = (255 * wa * grey).toInt()
            for (r in WaveMath.ROWS - 1 downTo 0) {  // far rows first, near dots land on top
                val size = rowSize[r] + (orbSize - rowSize[r]) * e
                if (dot.alpha > 0) { dot.shader = rowShader[r]; dot.strokeWidth = size; canvas.drawPoints(pts, r * WaveMath.COLS * 2, WaveMath.COLS * 2, dot) }
                if (greyDot.alpha > 0) { greyDot.shader = rowShader[r]; greyDot.strokeWidth = size; canvas.drawPoints(pts, r * WaveMath.COLS * 2, WaveMath.COLS * 2, greyDot) }
            }
        }
        if (e > 0f) {
            orbDot.color = ink; orbDot.alpha = (255 * e).toInt(); orbDot.strokeWidth = orbSize
            canvas.drawPoints(frontPts, 0, nf, orbDot)
        }
    }

    // ── touch: a finger dragged over the field ripples it 1:1; a tap is still the view's click ──────────────

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; dragging = false
                if (!still) { down = true; finger(e.x, e.y); emit(); wake() }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.x - downX, e.y - downY) > slop) {
                    dragging = true
                    // a drag isn't a tap: cancel the pending click and long press
                    val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                    super.onTouchEvent(cancel); cancel.recycle()
                }
                if (!still) {
                    finger(e.x, e.y)
                    if (clock - lastEmitAt > 0.09f || hypot(fingerX - lastEmitX, fingerZ - lastEmitZ) > 0.8f) emit()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { down = false; if (!still) wake() }
        }
        if (!dragging) super.onTouchEvent(e)
        return true
    }

    private fun finger(sx: Float, sy: Float) {
        fingerZ = WaveMath.groundZ(sy, horizon, f)
        fingerX = WaveMath.groundX(sx, fingerZ, cx, f).coerceIn(-WaveMath.HALF_W, WaveMath.HALF_W)
    }

    private fun emit() {
        rx[nextRipple] = fingerX; rz[nextRipple] = fingerZ; rt[nextRipple] = clock
        nextRipple = (nextRipple + 1) % RIPPLES
        lastEmitAt = clock; lastEmitX = fingerX; lastEmitZ = fingerZ
    }

    // ── the frame loop: runs only while something is moving, then stops ─────────────────────────────────

    private fun wake() {
        if (running || still || !isAttachedToWindow) return
        running = true; last = 0L
        fastFrames(true)  // a Canvas animation: ask for 120 Hz while it runs (API 35)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun step(now: Long) {
        if (!running) return
        val dt = if (last == 0L) 1f / 60 else ((now - last) / 1e9f).coerceIn(0f, 1f / 20)
        last = now
        clock += dt
        if (stirLeft > 0f) { stirLeft -= dt; energy += (1f - energy) * min(1f, dt * 5f) }
        else { energy -= energy * min(1f, dt * 1.8f); if (energy < 0.003f) energy = 0f }
        voice += (level - voice) * min(1f, dt * (if (level > voice) 18f else 5f))
        if (level == 0f && voice < 0.003f) voice = 0f
        phase += dt * (energy * 1.1f + voice * 1.6f)  // waves travel only while stirred or spoken to, then slow to a stop
        bump += ((if (down) 1f else 0f) - bump) * min(1f, dt * (if (down) 14f else 5f))
        if (!down && bump < 0.003f) bump = 0f
        val g = if (live) 0f else 1f
        grey += (g - grey) * min(1f, dt * 4f)
        if (abs(g - grey) < 0.004f) grey = g
        invalidate()
        var rippling = false
        for (k in 0 until RIPPLES) if (clock - rt[k] < 2.5f) rippling = true
        val busy = stirLeft > 0f || energy > 0f || level > 0f || voice > 0f || clock - pulseAt < 3f || rippling || down || bump > 0f || grey != g
        if (busy) Choreographer.getInstance().postFrameCallback(frame) else { running = false; fastFrames(false) }
    }

    private fun stop() { if (running) { running = false; Choreographer.getInstance().removeFrameCallback(frame); fastFrames(false) } }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) { stop(); down = false } else if (stirLeft > 0f || energy > 0f || level > 0f || voice > 0f || grey != (if (live) 0f else 1f)) wake()
    }

    private companion object {
        const val RIPPLES = 6
        fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3 - 2 * x) }
        fun alpha(c: Int, a: Int) = (c and 0xFFFFFF) or (a.coerceIn(0, 255) shl 24)
        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(s: Int) = ((a shr s and 0xFF) + ((b shr s and 0xFF) - (a shr s and 0xFF)) * t).toInt() shl s
            return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
        }
    }
}

/** The wave's geometry, pure so it can be unit tested. World units: x across, z into the screen (0 = nearest row),
 *  y up. The camera sits CAM_H above the field, NEAR in front of its first row. Heights are the dashboard's sines. */
internal object WaveMath {
    const val COLS = 44
    const val ROWS = 24
    const val HALF_W = 11f
    const val DEPTH = 12f
    const val NEAR = 5f
    const val CAM_H = 5f

    fun gridX(col: Int) = (col / (COLS - 1f) - 0.5f) * 2f * HALF_W
    fun gridZ(row: Int) = row / (ROWS - 1f) * DEPTH

    /** WaveField.tsx waveY at amp (1 = settled, more while stirred). */
    fun height(x: Float, z: Float, t: Float, amp: Float) =
        (sin(x * 0.55f + t * 0.9f) * 0.35f + sin(x * 1.3f - t * 1.4f + z * 0.8f) * 0.18f * amp + cos(z * 1.1f + t * 0.6f) * 0.12f) *
            (0.75f + amp * 0.25f) * 1.8f

    /** One swell travelling out from the middle (WaveField's pulse), gone after 3 s. */
    fun swell(d: Float, age: Float) =
        if (age >= 3f) 0f else sin(d * 2f - age * 9f) * exp(-age * 1.4f) * exp(-abs(d - age * 4.5f) * 0.8f) * 0.9f

    /** A small ring from a fingertip, gone after 2.5 s. */
    fun ripple(d: Float, age: Float) =
        if (age >= 2.5f) 0f else sin(d * 3f - age * 10f) * exp(-age * 1.8f) * exp(-abs(d - age * 4f) * 1.1f) * 0.45f

    fun screenX(x: Float, z: Float, cx: Float, f: Float) = cx + x * f / (z + NEAR)
    fun screenY(y: Float, z: Float, horizon: Float, f: Float) = horizon + (CAM_H - y) * f / (z + NEAR)

    /** Where a screen point lands on the flat field (y = 0): inverse of screenY, clamped to the field. */
    fun groundZ(sy: Float, horizon: Float, f: Float): Float {
        val dy = sy - horizon
        if (dy <= 1e-3f) return DEPTH
        return (CAM_H * f / dy - NEAR).coerceIn(0f, DEPTH)
    }
    fun groundX(sx: Float, z: Float, cx: Float, f: Float) = (sx - cx) * (z + NEAR) / f

    /** A Fibonacci sphere of rows * cols points, tilted 0.3 rad toward the viewer, written so grid dot (row, col) gets
     *  a matching place: row 0 (nearest) wraps the bottom of the orb, the middle column faces front. So the wave
     *  folds into the orb in one piece instead of dots crossing. out = x, y, z per dot (row-major). */
    fun orb(rows: Int, cols: Int, out: FloatArray) {
        val n = rows * cols
        val golden = (Math.PI * (3 - sqrt(5.0))).toFloat()
        val px = FloatArray(n); val py = FloatArray(n); val pz = FloatArray(n)
        for (i in 0 until n) {  // y falls with i, so each run of `cols` points is one latitude band, top first
            val y = 1f - 2f * (i + 0.5f) / n
            val r = sqrt(1f - y * y)
            px[i] = cos(i * golden) * r; py[i] = y; pz[i] = sin(i * golden) * r
        }
        val cosT = cos(0.3f); val sinT = sin(0.3f)
        for (band in 0 until rows) {
            val idx = (band * cols until (band + 1) * cols).sortedBy { atan2(px[it], pz[it]) }  // left (back) to front to right (back)
            val row = rows - 1 - band
            for (col in 0 until cols) {
                val p = idx[col]; val o = (row * cols + col) * 3
                out[o] = px[p]
                out[o + 1] = py[p] * cosT - pz[p] * sinT
                out[o + 2] = py[p] * sinT + pz[p] * cosT
            }
        }
    }
}
