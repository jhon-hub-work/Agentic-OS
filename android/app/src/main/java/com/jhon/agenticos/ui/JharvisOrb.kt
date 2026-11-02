package com.jhon.agenticos.ui

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Jharvis's core: an accent-tinted glass orb lit from the upper left (a strong soft sheen cut off by the rim with a long
 *  glint in it, a faint twin glint lower right, a light Fresnel band all round, brightest upper left, a floor bounce on
 *  the bottom rim past a dark gap), clear in the middle so the halo and wallpaper show through, thick at its edge and
 *  deepest at the lower right, with a halo spilling over the rim, a glowing core inside, a sphere of dots around that
 *  core (the back half tiny, hazy and pulled in, the front half large and bright, bunched at the rim like seen through
 *  glass) and a tight shadow under it with the light it focuses; the accent, amber and grey come from Look. Still when idle:
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

    /** The view is scaled by this from outside (Jharvis's overlay grows its orb to Home's size and glides it down):
     *  dots and hairlines keep their on-screen width, so at the hand-over it draws exactly like Home's. */
    var zoom = 1f
        set(v) { if (v != field) { field = v; invalidate() } }

    private val still = reducedMotion(c)
    private val dp = c.resources.displayMetrics.density
    private val n = OrbMath.DOTS
    private val sphere = FloatArray(n * 3)
    // screen points per (depth band, light level): band b back (0) to front, level l dim (0) to fully lit
    private val bin = Array(BANDS * LEVELS) { FloatArray(n * 2) }
    private val binN = IntArray(BANDS * LEVELS)
    private val accent = OrbMath.lift(look.glacier)          // a near-black accent (Graphite) would vanish on the lens
    private val grey = OrbMath.lift(look.ink3)
    private val tint = OrbMath.vivid(accent)                  // the glass's own colour: a muted accent (Steel) gets some chroma
    // back dots sink to a hazy mist, the middle keeps the accent, only the front band turns near white
    private val bandLive = IntArray(BANDS) { val t = OrbMath.bandMid(it, BANDS); OrbMath.mix(OrbMath.mix(accent, MIST, (1f - t) * 0.6f), ICE, t * t * t) }
    private val deep = OrbMath.mix(accent, NAVY, 0.8f)      // what an unlit dot sinks toward
    private val deepGrey = OrbMath.mix(grey, NAVY, 0.8f)

    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
    private val lightGround = !look.bare.dark            // a light frost or photo: the halo steps back
    private val lens = Paint(Paint.ANTI_ALIAS_FLAG)      // the glass body, accent tinted, the wallpaper showing through
    private val lensGrey = Paint(Paint.ANTI_ALIAS_FLAG)  // the same body in grey, under the fade to "away"
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val contact = Paint(Paint.ANTI_ALIAS_FLAG)   // neutral dark oval just under the ball, darkest at its core
    private val caustic = Paint(Paint.ANTI_ALIAS_FLAG)   // the light the glass focuses, an accent glow inside the shadow
    private val apart = Paint(Paint.ANTI_ALIAS_FLAG)     // a soft dark ring just outside the rim, lifting the small orb off the icons
    private val shell = Paint(Paint.ANTI_ALIAS_FLAG)     // the glass thickens over its outer 15%, deepest at the lower right
    private val bounce = Paint(Paint.ANTI_ALIAS_FLAG)    // floor light, a thin crescent on the bottom rim past a dark gap
    private val fresnel = Paint(Paint.ANTI_ALIAS_FLAG)   // thin light band all round, like light bending in glass
    private val fresnelHi = Paint(Paint.ANTI_ALIAS_FLAG) // the same band, offset: brightest at the upper left
    private val rimGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS } // the halo spilling over the rim
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)     // broad soft specular oval, upper left, cut off by the rim
    private val glint = Paint(Paint.ANTI_ALIAS_FLAG)     // small crisp window glint inside the sheen, long along the curve
    private val glint2 = Paint(Paint.ANTI_ALIAS_FLAG)    // its faint twin at the lower right
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = look.warn }
    private var gx = 0f; private var gy = 0f

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
        OrbMath.cloud(n, sphere)
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

    /** The lens on screen through every transform above it: centre x, centre y, radius (px). */
    fun lensOnScreen(): FloatArray {
        val m = Matrix(); transformMatrixToGlobal(m)
        val p = floatArrayOf(cx, cy); m.mapPoints(p)
        return floatArrayOf(p[0], p[1], m.mapRadius(r * (1f - 0.55f * compact)))
    }

    /** Take on another orb's pose at once (where the dots face, grey or lit), so a hand-over between the two shows no
     *  jump: Jharvis's overlay and Home trade their orb this way. */
    fun match(o: JharvisOrb) { yaw = o.yaw; greyT = o.greyT; live = o.live; invalidate() }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean =
        if (e.actionMasked == MotionEvent.ACTION_DOWN && !onLens(e.x, e.y)) false else super.dispatchTouchEvent(e)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f; cy = h / 2f
        r = min(w, h) * OrbMath.LENS          // halo, shadow and ring all stay inside min side / 2 (tested)
        val clamp = Shader.TileMode.CLAMP
        fun a(c: Int, al: Int) = OrbMath.alpha(c, al)
        fun oval(sx: Float, sy: Float, rot: Float, x: Float, y: Float, cols: IntArray, stops: FloatArray) =
            RadialGradient(0f, 0f, 1f, cols, stops, clamp).apply { setLocalMatrix(Matrix().apply { setScale(sx, sy); postRotate(rot); postTranslate(x, y) }) }
        // a crescent of edge light hugging the rim, thickest (0.09 R) where (ux, uy) points, gone a quarter turn away:
        // a gradient centred 0.06 R the other way, clear inside 0.97 R of its centre, brightest at the rim
        // off: how far the gradient sits back from the rim (thickness), k: its radius over R (arc length)
        fun crescent(ux: Float, uy: Float, c: Int, al: Int, off: Float = 0.06f, k: Float = 1.06f) =
            RadialGradient(cx - ux * r * off, cy - uy * r * off, r * k, intArrayOf(a(c, 0), a(c, 0), a(c, al)),
                floatArrayOf(0f, 0.97f / k, 1f), clamp)
        // shadow: neutral, tight under the ball, a dark core right where it touches
        contact.shader = oval(r * 0.7f, r * OrbMath.SHADOW_H, 0f, cx, cy + r * OrbMath.SHADOW_Y,
            intArrayOf(0x58000000, 0x30000000, 0x10000000, 0x00000000), floatArrayOf(0f, 0.25f, 0.6f, 1f))
        caustic.shader = oval(r * 0.26f, r * 0.05f, 0f, cx + r * CAUSTIC_X, cy + r * CAUSTIC_Y,
            OrbMath.mix(tint, ICE, 0.35f).let { intArrayOf(a(it, 0xC0), a(it, 0x50), a(it, 0)) }, floatArrayOf(0f, 0.45f, 1f))
        apart.shader = RadialGradient(cx, cy + r * 0.03f, r * APART, intArrayOf(0, 0, 0x30000000, 0),
            floatArrayOf(0f, 0.95f / APART, 1f / APART, 1f), clamp)
        // halo: hugs the ball, with a long soft tail so its edge never draws a hairline; paint alpha and a canvas scale breathe it
        val hb = OrbMath.haloRadius(0f)
        halo.shader = RadialGradient(cx, cy, r * hb, intArrayOf(accent, accent, a(accent, 0x60), a(accent, 0x1A), a(accent, 0)),
            floatArrayOf(0f, 0.85f / hb, 1f / hb, 1.15f / hb, 1f), clamp)
        // the body: tinted glass, light and clear at the upper left (the halo and wallpaper show through), denser and
        // deeper toward the lower right
        fun body(t: Int) = RadialGradient(cx - r * 0.35f, cy - r * 0.42f, r * 1.5f,
            intArrayOf(a(OrbMath.mix(t, ICE, 0.22f), 0x80), a(OrbMath.mix(t, ICE, 0.06f), 0x96),
                a(OrbMath.mix(t, NAVY, 0.35f), 0xAD), a(OrbMath.mix(t, NAVY, 0.62f), 0xC8)),
            floatArrayOf(0f, 0.35f, 0.7f, 1f), clamp)
        lens.shader = body(tint); lensGrey.shader = body(grey)
        // every glass layer below is drawn as the lens circle, so the circle itself clips it to the ball
        val sk = 1.107f  // centred up and left by 0.106 R: clear at the lit rim, thickest at the lower right
        shell.shader = RadialGradient(cx - r * 0.07f, cy - r * 0.08f, r * sk, intArrayOf(a(DEPTH, 0), a(DEPTH, 0), a(DEPTH, 0x50), a(DEPTH, 0x8C)),
            floatArrayOf(0f, 0.78f, 0.9f, 1f), clamp)
        bounce.shader = crescent(-0.2f, 0.98f, OrbMath.mix(tint, ICE, 0.7f), 0x80, 0.1f, 1.08f)
        fresnel.shader = RadialGradient(cx, cy, r, intArrayOf(a(ICE, 0), a(ICE, 0), a(ICE, 0x30), a(ICE, 0x14)),
            floatArrayOf(0f, 0.9f, 0.975f, 1f), clamp)
        fresnelHi.shader = crescent(-0.64f, -0.77f, ICE, 0x9C)
        rimGlow.shader = RadialGradient(cx, cy, r * RIM_GLOW, intArrayOf(a(accent, 0), a(accent, 0), a(accent, 0x40), a(accent, 0)),
            floatArrayOf(0f, 0.96f / RIM_GLOW, 1f / RIM_GLOW, 1f), clamp)
        // a strong soft oval, flattened along the curve, its top cut off by the rim
        sheen.shader = oval(r * 0.5f, r * 0.36f, -38f, cx - r * 0.4f, cy - r * 0.5f,
            intArrayOf(0x80FFFFFF.toInt(), 0x6CFFFFFF, 0x2CFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 0.8f, 1f))
        gx = cx - r * OrbMath.GLINT_X; gy = cy - r * OrbMath.GLINT_Y
        glint.shader = oval(r * GLINT, r * GLINT / 2.2f, -38f, gx, gy,
            intArrayOf(0xF0FFFFFF.toInt(), 0xB0FFFFFF.toInt(), 0x30FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.35f, 0.7f, 1f))
        glint2.shader = oval(r * 0.07f, r * 0.026f, -40f, cx + r * GLINT2_X, cy + r * GLINT2_Y,
            intArrayOf(0x40FFFFFF, 0x1CFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f))
        // a soft bloom with a bright heart, added over the back dots
        core.shader = RadialGradient(cx + r * CORE_X, cy + r * CORE_Y, r * OrbMath.coreRadius(0f, 0f, 0f),
            intArrayOf(a(OrbMath.mix(tint, ICE, 0.7f), 0xE6), a(OrbMath.mix(tint, ICE, 0.3f), 0x90), a(tint, 0x30), a(tint, 0)),
            floatArrayOf(0f, 0.22f, 0.6f, 1f), clamp)
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        val g = OrbMath.smooth(greyT); val on = 1f - g
        val s = 1f - 0.55f * compact
        canvas.save()
        canvas.scale(s, s, cx, cy)

        if (on > 0f) {
            val hk = OrbMath.haloRadius(voice) / OrbMath.haloRadius(0f) * (1f - 0.25f * compact)
            // on a light ground it steps back at rest; small, it fades with size so it never outshines the ball
            val ground = if (lightGround) 0.5f + 0.5f * voice else 1f
            halo.alpha = (255 * OrbMath.haloAlpha(voice) * ground * s * on).toInt()
            canvas.save(); canvas.scale(hk, hk, cx, cy)
            canvas.drawCircle(cx, cy, r * OrbMath.haloRadius(0f), halo)
            canvas.restore()
        }
        // the shadow after the halo, which would add over it; the circle just covers its oval
        canvas.drawCircle(cx, cy + r * OrbMath.SHADOW_Y, r * 0.82f, contact)
        if (on > 0f) { caustic.alpha = (255 * on).toInt(); canvas.drawCircle(cx + r * CAUSTIC_X, cy + r * CAUSTIC_Y, r * 0.28f, caustic) }
        if (compact > 0f) { apart.alpha = (255 * compact).toInt(); canvas.drawCircle(cx, cy + r * 0.03f, r * APART, apart) }
        if (g > 0f) canvas.drawCircle(cx, cy, r, lensGrey)
        if (on > 0f) { lens.alpha = (255 * on).toInt(); canvas.drawCircle(cx, cy, r, lens) }
        // small over the icon globe, frostier: the icons behind would show through as mush
        if (compact > 0f && on > 0f) { lens.alpha = (128 * on * compact).toInt(); canvas.drawCircle(cx, cy, r, lens) }
        canvas.drawCircle(cx, cy, r, shell)

        // the dots: rotate, tilt, bin by depth band and by how much light reaches them, then one drawPoints per bin
        val cyw = cos(yaw); val syw = sin(yaw); val sc = r * OrbMath.CLOUD
        binN.fill(0)
        for (i in 0 until n) {
            if (!OrbMath.keep(i, compact)) continue      // small, fewer dots: at 0.45 scale all of them is speckle
            val px = sphere[i * 3]; val py = sphere[i * 3 + 1]; val pz = sphere[i * 3 + 2]
            val x = px * cyw + pz * syw; val z = -px * syw + pz * cyw
            val y2 = py * COS_T - z * SIN_T; val z2 = py * SIN_T + z * COS_T
            val wob = if (voice > 0f) 1f + 0.04f * voice * sin(clock * 6f + px * 5f) else 1f
            // front of the ball nearer, back farther; seen through the glass, the back pulls in, the front bunches at the rim
            val d = sc * wob * OrbMath.depthScale(z2) * OrbMath.refract(sqrt(x * x + y2 * y2), z2)
            val sx = cx + x * d; val sy = cy + y2 * d
            val gdx = (sx - gx) / r; val gdy = (sy - gy) / r
            val v = OrbMath.light(x * d / r, y2 * d / r) * OrbMath.edgeFade(sqrt(x * x + y2 * y2)) *
                OrbMath.glintClear(sqrt(gdx * gdx + gdy * gdy))
            if (v < 0.1f) continue                     // wrapped away at the cloud's edge, or under the glint
            val k = OrbMath.band(z2, BANDS) * LEVELS + min(LEVELS - 1, (v * LEVELS).toInt())
            val m = binN[k]
            bin[k][m] = sx; bin[k][m + 1] = sy
            binN[k] = m + 2
        }
        dots(canvas, 0, BANDS / 2, g, on, s)           // the back half, behind the core

        if (on > 0f) {
            // small and dim at rest, deep inside toward the lower right; voice and stir swell and brighten it
            val ck = OrbMath.coreRadius(voice, energy, clock) / OrbMath.coreRadius(0f, 0f, 0f)
            core.alpha = (255 * on * (0.45f + 0.55f * maxOf(voice, energy))).toInt()
            val kx = cx + r * CORE_X; val ky = cy + r * CORE_Y
            canvas.save(); canvas.scale(ck, ck, kx, ky)
            canvas.drawCircle(kx, ky, r * OrbMath.coreRadius(0f, 0f, 0f), core)
            canvas.restore()
        }
        dots(canvas, BANDS / 2, BANDS, g, on, s)       // the front half, in front of it

        // the glass surface, over the dots so they sit inside the ball
        bounce.alpha = (255 * (0.35f + 0.65f * on)).toInt()
        canvas.drawCircle(cx, cy, r, bounce)
        canvas.drawCircle(cx, cy, r, fresnel)
        canvas.drawCircle(cx, cy, r, fresnelHi)
        if (on > 0f) { rimGlow.alpha = (255 * on * (if (lightGround) 0.7f else 1f)).toInt(); canvas.drawCircle(cx, cy, r * RIM_GLOW, rimGlow) }
        canvas.drawCircle(cx, cy, r, sheen)
        canvas.drawCircle(gx, gy, r * GLINT, glint)
        if (compact < 1f) { glint2.alpha = (255 * (1f - compact)).toInt(); canvas.drawCircle(cx + r * GLINT2_X, cy + r * GLINT2_Y, r * 0.07f, glint2) }

        val age = clock - pulseAt
        val ra = OrbMath.ringAlpha(age)
        if (ra > 0f) {
            ring.alpha = (255 * ra).toInt(); ring.strokeWidth = dp * 2.2f / s / zoom
            canvas.drawCircle(cx, cy, r * OrbMath.ringRadius(age), ring)
        }
        canvas.restore()
    }

    /** Draws the binned dots of bands from until to: back ones half the size and dim, front ones full and bright. */
    private fun dots(canvas: Canvas, from: Int, to: Int, g: Float, on: Float, s: Float) {
        val sink = OrbMath.mix(deep, deepGrey, g)
        for (b in from until to) {
            val t = OrbMath.bandMid(b, BANDS)
            val ba = (0.1f + 0.85f * t * t) * on + (0.08f + 0.44f * t) * g  // back ~10%, front ~80%
            val base = OrbMath.mix(bandLive[b], grey, g)
            // back ~1/3 of front; small, the few dots left stay a readable size on screen (>= 1 dp)
            dot.strokeWidth = dp * maxOf((1.1f + 2.6f * t * sqrt(t)) * (1f - 0.35f * compact), 1f) / s / zoom
            for (l in 0 until LEVELS) {
                val k = b * LEVELS + l
                if (binN[k] == 0) continue
                val v = (l + 1f) / LEVELS
                dot.color = OrbMath.mix(base, sink, (1f - v) * 0.55f)
                dot.alpha = (255 * v * ba).toInt()
                canvas.drawPoints(bin[k], 0, binN[k], dot)
            }
        }
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
        const val LEVELS = 5                // light levels per band: 0.2, 0.4 .. 1
        const val GLINT = 0.11f             // glint half length, in R (2.2:1)
        const val GLINT2_X = 0.5f           // the faint twin glint, lower right, in R
        const val GLINT2_Y = 0.56f
        const val CORE_X = 0.06f            // the core sits a touch toward the lower right, deep in the ball
        const val CORE_Y = 0.08f
        const val CAUSTIC_X = 0.12f         // the caustic, inside the shadow on the side away from the light
        const val CAUSTIC_Y = 1.07f
        const val APART = 1.15f             // the small orb's separation ring, outer radius in R
        const val RIM_GLOW = 1.1f           // the halo's spill over the rim, outer radius in R
        const val MIST = 0xFFB4C0CC.toInt() // the haze far dots fade toward
        const val DEPTH = 0xFF07101B.toInt() // the thick glass at the rim
        const val TAU = (2 * Math.PI).toFloat()
        const val ICE = 0xFFEAF6FF.toInt()  // pale ice the front dots turn toward
        const val NAVY = 0xFF0B1626.toInt() // the glass's deep tone, mixed with the accent
        val COS_T = cos(OrbMath.TILT); val SIN_T = sin(OrbMath.TILT)
    }
}

/** The orb's geometry and curves, pure so they can be unit tested. Radii are in lens radii (R = LENS * min side). */
internal object OrbMath {
    const val DOTS = 140
    const val LENS = 0.2f          // lens radius as a fraction of the view's min side
    const val TILT = 0.35f         // the sphere leans toward the viewer
    const val RING_S = 1.2f        // pulse ring lifetime
    const val FADE_S = 0.25f       // live <-> off

    /** A Fibonacci sphere of n unit points, x y z each (the Sphere layout's globe uses it too: keep it even). */
    fun sphere(n: Int, out: FloatArray) {
        val golden = (Math.PI * (3 - sqrt(5.0))).toFloat()
        for (i in 0 until n) {
            val y = 1f - 2f * (i + 0.5f) / n
            val rr = sqrt(1f - y * y)
            out[i * 3] = cos(i * golden) * rr; out[i * 3 + 1] = y; out[i * 3 + 2] = sin(i * golden) * rr
        }
    }

    /** The orb's dots: the same sphere, each point nudged by a fixed hash (about a third of the spacing, none at the
     *  poles) so it reads as a loose cloud, not a lattice. */
    fun cloud(n: Int, out: FloatArray) {
        val golden = (Math.PI * (3 - sqrt(5.0))).toFloat()
        for (i in 0 until n) {
            val rr2 = 1f - (1f - 2f * (i + 0.5f) / n).let { it * it }  // squared ring radius: no y nudge at the poles
            val y = (1f - 2f * (i + 0.5f) / n + 0.07f * rr2 * jitter(i, 1)).coerceIn(-1f, 1f)
            val rr = sqrt(1f - y * y)
            val a = i * golden + 0.09f * jitter(i, 2) / maxOf(rr, 0.3f)
            out[i * 3] = cos(a) * rr; out[i * 3 + 1] = y; out[i * 3 + 2] = sin(a) * rr
        }
    }

    /** A fixed pseudo random -1..1 for dot i (salt picks the stream). */
    fun jitter(i: Int, salt: Int): Float { val v = sin(i * 12.9898f + salt * 78.233f) * 43758.547f; return 2f * (v - kotlin.math.floor(v)) - 1f }

    /** Seen through the glass, a dot's projected radius is scaled by this (q: 0 centre .. 1 limb, z: -1 back .. 1 front):
     *  the back half pulls toward the centre, the front magnifies in the middle and bunches up at the rim. */
    fun refract(q: Float, z: Float) = if (z >= 0f) 1f + 0.25f * z * (1f - q * q) else 1f - 0.28f * -z

    /** Perspective for a dot at depth z (-1 back .. 1 front): front dots spread wider, back ones huddle in. */
    fun depthScale(z: Float) = 1f + 0.08f * z

    const val CLOUD = 0.84f        // the dot sphere's radius, in R: its limb crowds up just inside the Fresnel band
    const val GLINT_X = 0.42f      // the glint's centre, up and left of the ball's, in R
    const val GLINT_Y = 0.5f

    /** Depth band for a point's z (-1 back .. 1 front). */
    fun band(z: Float, bands: Int) = (((z + 1f) / 2f) * bands).toInt().coerceIn(0, bands - 1)
    /** A band's middle depth, 0 back .. 1 front. */
    fun bandMid(b: Int, bands: Int) = (b + 0.5f) / bands

    /** The amber ring: out from the rim to 1.9 R with an ease-out, fading as it goes; nothing outside 0..RING_S. */
    fun ringRadius(age: Float): Float { val p = (age / RING_S).coerceIn(0f, 1f); val q = 1f - p; return 1f + 0.9f * (1f - q * q * q) }
    fun ringAlpha(age: Float) = if (age < 0f || age >= RING_S) 0f else (1f - age / RING_S) * 0.95f

    fun haloRadius(voice: Float) = 1.3f + 0.3f * voice
    fun haloAlpha(voice: Float) = 0.42f + 0.25f * voice
    fun coreRadius(voice: Float, shimmer: Float, t: Float) = (0.25f + 0.25f * voice) * (1f + 0.06f * shimmer * sin(t * 9f))

    const val SHADOW_Y = 1.06f     // the shadow's centre below the orb's, in R: just under the ball's bottom
    const val SHADOW_H = 0.12f     // and its half height

    /** The farthest anything reaches from the centre, in R: halo at full voice, ring, the shadow's lower edge. */
    fun reach() = maxOf(haloRadius(1f), ringRadius(RING_S), SHADOW_Y + SHADOW_H)

    /** Light reaching a point inside the ball, the same key as the body: 1 lit upper left, 0.45 at the lower right.
     *  x, y in lens radii from the centre. */
    fun light(x: Float, y: Float) = (0.725f - 0.335f * (0.64f * x + 0.77f * y)).coerceIn(0.45f, 1f)

    /** A dot fades over the outer 20% of the cloud's projected radius q (0 centre .. 1 limb), where the sphere's dots
     *  crowd together, so the cloud wraps away inside the glass instead of stopping at a hard ring. */
    fun edgeFade(q: Float) = 0.15f + 0.85f * smooth((1f - q) / 0.2f)

    /** Dots under the glint dim away (d: distance from the glint's centre, in R), so it stays the brightest thing. */
    fun glintClear(d: Float) = 0.15f + 0.85f * smooth((d - 0.08f) / 0.2f)

    /** Dot i still drawn at this compactness: all at 0, a spread dozen at 1 (small, more is speckle against the icons). */
    fun keep(i: Int, compact: Float) = (i * 0.618034f) % 1f >= 0.915f * compact

    /** A muted colour pushed away from its own grey until its chroma reaches about 80 (at most 1.8x), so the glass shows
     *  the accent even when it is a soft one (Steel); a vivid accent passes through, a true grey stays grey. */
    fun vivid(c: Int): Int {
        val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
        val chroma = maxOf(r, g, b) - minOf(r, g, b)
        val k = if (chroma == 0) 1f else (80f / chroma).coerceIn(1f, 1.8f)
        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
        fun ch(v: Int) = (lum + (v - lum) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

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
