package com.jhon.agenticos.ui

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Jharvis's core, the "Night lens": a dark navy glass orb with a Glacier halo around it, a glowing core and a tilted
 *  sphere of dots inside, over a soft contact shadow. The lens is dark on every theme so it reads on any wallpaper
 *  (bright photo, dark photo, frost light, dark themes); the accent, amber and grey come from Look. Still when idle:
 *  it draws the settled frame and stops its frame callback. stir() turns the dots and shimmers the core for about
 *  2 s, pulse() sends one amber ring out from the rim, level (a voice, 0..1) breathes the halo and core, compact
 *  shrinks it to 0.45 of its size (drawn centred; Home moves the view), live = false greys it out. */
class JharvisOrb(c: Context, private val look: Look) : View(c) {
    /** Jharvis reachable. false: grey dots, no halo or core, faint rim. */
    var live = true
        set(v) {
            if (field == v) return
            field = v
            if (still) { greyT = if (v) 0f else 1f; invalidate() } else wake()
        }

    /** 0 = full size in the middle of home, 1 = shrunk to 0.45 at the orbit's centre (home drives it). */
    var compact = 0f
        set(v) { val n = v.coerceIn(0f, 1f); if (n != field) { field = n; invalidate() } }

    /** A voice's loudness, 0..1 (Jharvis's overlay feeds Jhon's mic level): the halo and core breathe with it. */
    var level = 0f
        set(v) { field = v.coerceIn(0f, 1f); if (field > 0f) wake() }

    private val still = reducedMotion(c)
    private val dp = c.resources.displayMetrics.density
    private val n = OrbMath.DOTS
    private val sphere = FloatArray(n * 3)
    private val band = Array(BANDS) { FloatArray(n * 2) }   // screen points per depth band, back (0) to front
    private val bandN = IntArray(BANDS)
    private val accent = OrbMath.lift(look.glacier)          // a near-black accent (Graphite) would vanish on the lens
    private val grey = OrbMath.lift(look.ink3)
    private val bandLive = IntArray(BANDS) { OrbMath.mix(accent, ICE, OrbMath.bandMid(it, BANDS).let { t -> t * t }) }

    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
    private val lens = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val spec = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0x8CFFFFFF.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = look.warn }
    private val specOval = RectF()

    // geometry, set on size
    private var cx = 0f; private var cy = 0f; private var r = 1f

    // motion state
    private var yaw = 0.6f
    private var energy = 0f        // stir, eased: turns the dots and shimmers the core
    private var voice = 0f         // level, eased: quick to rise, slower to fall
    private var stirLeft = 0f
    private var clock = 0f         // seconds the loop has run, for the ring's age and the shimmer
    private var pulseAt = -10f
    private var greyT = 0f         // 0 live .. 1 off, linear over FADE_S; drawn through smooth()

    private var running = false
    private var last = 0L
    private val frame = Choreographer.FrameCallback { step(it) }

    init {
        contentDescription = "Talk to Jharvis"
        isClickable = true
        OrbMath.sphere(n, sphere)
    }

    /** Something new needs him: one amber ring grows out of the rim and fades. Once per call. */
    fun pulse() { if (still) return; pulseAt = clock; wake() }

    /** The dots turn and the core shimmers for about 2 s, then ease back to still. */
    fun stir() { if (still) return; stirLeft = 2f; wake() }

    /** Only the lens takes a touch: the view spans home's width, and a tap beside the orb is empty frost (a double tap
     *  there locks), not "open the dashboard". View coordinates; a little reach past the rim for a thumb. */
    fun onLens(x: Float, y: Float): Boolean {
        val reach = r * (1f - 0.55f * compact) * 1.15f
        val dx = x - cx; val dy = y - cy
        return dx * dx + dy * dy <= reach * reach
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean =
        if (e.actionMasked == MotionEvent.ACTION_DOWN && !onLens(e.x, e.y)) false else super.dispatchTouchEvent(e)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f; cy = h / 2f
        r = min(w, h) * OrbMath.LENS          // halo, shadow and ring all stay inside min side / 2 (tested)
        shadow.shader = RadialGradient(cx, cy + r * 0.22f, r * 1.5f, 0x4D080E1A, 0x00080E1A, Shader.TileMode.CLAMP)
        // halo: flat accent under the lens, fading out to its base radius; paint alpha and a canvas scale breathe it
        val hb = OrbMath.haloRadius(0f)
        halo.shader = RadialGradient(cx, cy, r * hb, intArrayOf(accent, accent, OrbMath.alpha(accent, 0)),
            floatArrayOf(0f, 0.85f / hb, 1f), Shader.TileMode.CLAMP)
        // the lens: lit from the upper left, nearly opaque navy glass
        lens.shader = RadialGradient(cx - r * 0.2f, cy - r * 0.25f, r * 1.25f, 0xD1223A58.toInt(), 0xDB0A111E.toInt(), Shader.TileMode.CLAMP)
        core.shader = RadialGradient(cx, cy, r * OrbMath.coreRadius(0f, 0f, 0f),
            intArrayOf(0xD9FFFFFF.toInt(), OrbMath.alpha(accent, 0x8C), OrbMath.alpha(accent, 0)),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        specOval.set(cx - r * 0.9f, cy - r * 0.9f, cx + r * 0.9f, cy + r * 0.9f)
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        val g = OrbMath.smooth(greyT); val on = 1f - g
        val s = 1f - 0.55f * compact
        canvas.save()
        canvas.scale(s, s, cx, cy)
        canvas.drawCircle(cx, cy, r * 1.5f, shadow)  // contact shadow, a little below: holds the orb on bright photos

        if (on > 0f) {
            val hk = OrbMath.haloRadius(voice) / OrbMath.haloRadius(0f) * (1f - 0.25f * compact)
            halo.alpha = (255 * OrbMath.haloAlpha(voice) * on).toInt()
            canvas.save(); canvas.scale(hk, hk, cx, cy)
            canvas.drawCircle(cx, cy, r * OrbMath.haloRadius(0f), halo)
            canvas.restore()
        }
        canvas.drawCircle(cx, cy, r, lens)
        if (on > 0f) {
            val ck = OrbMath.coreRadius(voice, energy, clock) / OrbMath.coreRadius(0f, 0f, 0f)
            core.alpha = (255 * on).toInt()
            canvas.save(); canvas.scale(ck, ck, cx, cy)
            canvas.drawCircle(cx, cy, r * OrbMath.coreRadius(0f, 0f, 0f), core)
            canvas.restore()
        }

        // the dots: rotate, tilt, sort into depth bands, then one drawPoints per band, back first
        val cyw = cos(yaw); val syw = sin(yaw); val sc = r * 0.72f
        bandN.fill(0)
        for (i in 0 until n) {
            val px = sphere[i * 3]; val py = sphere[i * 3 + 1]; val pz = sphere[i * 3 + 2]
            val x = px * cyw + pz * syw; val z = -px * syw + pz * cyw
            val y2 = py * COS_T - z * SIN_T; val z2 = py * SIN_T + z * COS_T
            val wob = if (voice > 0f) 1f + 0.04f * voice * sin(clock * 6f + px * 5f) else 1f
            val b = OrbMath.band(z2, BANDS)
            val k = bandN[b]
            band[b][k] = cx + x * sc * wob; band[b][k + 1] = cy + y2 * sc * wob
            bandN[b] = k + 2
        }
        for (b in 0 until BANDS) {
            if (bandN[b] == 0) continue
            val t = OrbMath.bandMid(b, BANDS)
            dot.color = OrbMath.mix(bandLive[b], grey, g)
            dot.alpha = (255 * ((0.18f + 0.8f * t) * on + (0.12f + 0.4f * t) * g)).toInt()
            dot.strokeWidth = dp * (1f + 2.6f * t + 0.4f / s)
            canvas.drawPoints(band[b], 0, bandN[b], dot)
        }

        rim.color = OrbMath.mix(accent, grey, g)
        rim.alpha = (255 * (0.85f * on + 0.35f * g)).toInt()
        rim.strokeWidth = dp * 1.4f / s  // hairlines stay hairlines when compact
        canvas.drawCircle(cx, cy, r, rim)
        spec.strokeWidth = dp * 2f / s
        canvas.drawArc(specOval, 201.6f, 46.8f, false, spec)  // small white catch-light, upper left

        val age = clock - pulseAt
        val ra = OrbMath.ringAlpha(age)
        if (ra > 0f) {
            ring.alpha = (255 * ra).toInt(); ring.strokeWidth = dp * 2.2f / s
            canvas.drawCircle(cx, cy, r * OrbMath.ringRadius(age), ring)
        }
        canvas.restore()
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
        yaw += dt * (1.6f * energy * energy + 0.25f * voice)  // turns only while stirred or spoken to
        if (yaw > TAU) yaw -= TAU
        val g = target()
        greyT = if (greyT < g) min(g, greyT + dt / OrbMath.FADE_S) else maxOf(g, greyT - dt / OrbMath.FADE_S)
        invalidate()
        val busy = stirLeft > 0f || energy > 0f || level > 0f || voice > 0f || OrbMath.ringAlpha(clock - pulseAt) > 0f || greyT != g
        if (busy) Choreographer.getInstance().postFrameCallback(frame) else { running = false; fastFrames(false) }
    }

    private fun target() = if (live) 0f else 1f

    private fun stop() { if (running) { running = false; Choreographer.getInstance().removeFrameCallback(frame); fastFrames(false) } }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) stop()
        else if (stirLeft > 0f || energy > 0f || level > 0f || voice > 0f || OrbMath.ringAlpha(clock - pulseAt) > 0f || greyT != target()) wake()
    }

    private companion object {
        const val BANDS = 6
        const val TAU = (2 * Math.PI).toFloat()
        const val ICE = 0xFFEAF6FF.toInt()  // pale ice the front dots turn toward
        val COS_T = cos(OrbMath.TILT); val SIN_T = sin(OrbMath.TILT)
    }
}

/** The orb's geometry and curves, pure so they can be unit tested. Radii are in lens radii (R = LENS * min side). */
internal object OrbMath {
    const val DOTS = 200
    const val LENS = 0.2f          // lens radius as a fraction of the view's min side
    const val TILT = 0.35f         // the sphere leans toward the viewer
    const val RING_S = 1.2f        // pulse ring lifetime
    const val FADE_S = 0.25f       // live <-> off

    /** A Fibonacci sphere of n unit points, x y z each. */
    fun sphere(n: Int, out: FloatArray) {
        val golden = (Math.PI * (3 - sqrt(5.0))).toFloat()
        for (i in 0 until n) {
            val y = 1f - 2f * (i + 0.5f) / n
            val rr = sqrt(1f - y * y)
            out[i * 3] = cos(i * golden) * rr; out[i * 3 + 1] = y; out[i * 3 + 2] = sin(i * golden) * rr
        }
    }

    /** Depth band for a point's z (-1 back .. 1 front). */
    fun band(z: Float, bands: Int) = (((z + 1f) / 2f) * bands).toInt().coerceIn(0, bands - 1)
    /** A band's middle depth, 0 back .. 1 front. */
    fun bandMid(b: Int, bands: Int) = (b + 0.5f) / bands

    /** The amber ring: out from the rim to 1.9 R with an ease-out, fading as it goes; nothing outside 0..RING_S. */
    fun ringRadius(age: Float): Float { val p = (age / RING_S).coerceIn(0f, 1f); val q = 1f - p; return 1f + 0.9f * (1f - q * q * q) }
    fun ringAlpha(age: Float) = if (age < 0f || age >= RING_S) 0f else (1f - age / RING_S) * 0.95f

    fun haloRadius(voice: Float) = 1.55f + 0.35f * voice
    fun haloAlpha(voice: Float) = 0.42f + 0.25f * voice
    fun coreRadius(voice: Float, shimmer: Float, t: Float) = (0.42f + 0.18f * voice) * (1f + 0.06f * shimmer * sin(t * 9f))

    /** The farthest anything reaches from the centre, in R: halo at full voice, ring, shadow (centre 0.22 R low, 1.5 R). */
    fun reach() = maxOf(haloRadius(1f), ringRadius(RING_S), 0.22f + 1.5f)

    /** A colour too dark to read on the navy lens, pulled halfway to ice; light colours pass through. */
    fun lift(c: Int): Int {
        val lum = (0.2126f * (c shr 16 and 0xFF) + 0.7152f * (c shr 8 and 0xFF) + 0.0722f * (c and 0xFF)) / 255f
        return if (lum < 0.5f) mix(c, 0xFFEAF6FF.toInt(), 0.5f) else (c or (0xFF shl 24))
    }

    fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3 - 2 * x) }
    fun alpha(c: Int, a: Int) = (c and 0xFFFFFF) or (a.coerceIn(0, 255) shl 24)
    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((a shr s and 0xFF) + ((b shr s and 0xFF) - (a shr s and 0xFF)) * t).toInt() shl s
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }
}
