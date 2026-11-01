/*
 * Liquid glass for Views. The refraction approach (a rounded-rect signed distance field; inside a band along the
 * edge, the backdrop is sampled further out along the field's normal with a circle profile, like light bent through
 * a thick rim) follows Kyant0/AndroidLiquidGlass, Copyright Kyant0, licensed under the Apache License, Version 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0). This file re-implements that idea for the View system; it does not
 * copy the library's Compose code.
 */
package com.jhon.agenticos.ui

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The numbers behind the glass, pure so they can be unit tested. */
internal object GlassMath {
    const val LEVELS = 8  // refraction strengths cached per size while the glass materializes

    /** Refraction band: a quarter of the shorter side, capped. */
    fun band(w: Float, h: Float, cap: Float) = min(0.25f * min(w, h), cap).coerceAtLeast(0f)

    /** How far (px) the rim reaches for the backdrop: twice the band. */
    fun amount(band: Float) = 2f * band

    /** Room around the panel the effect needs to read from: the refraction's reach plus the blur's. */
    fun margin(amount: Float, blur: Float) = ceil(amount + 3f * blur).toInt()

    fun level(reveal: Float) = (reveal.coerceIn(0f, 1f) * LEVELS).roundToInt()

    /** The shader's displacement, mirrored for tests: sd = signed distance to the edge (negative inside). */
    fun displacement(sd: Float, band: Float, amount: Float): Float {
        if (amount <= 0f || band <= 0f || sd > 0f || -sd >= band) return 0f
        val x = 1f + sd / band  // 1 at the rim, 0 where the band ends inside
        return (1f - sqrt(maxOf(1f - x * x, 0f))) * amount
    }
}

/** The AGSL refraction, compiled once per process. null = this device can't run it: callers keep the plain glass. */
internal object GlassShader {
    private var tried = false
    private var shader: RuntimeShader? = null

    fun get(): RuntimeShader? {
        if (!tried) { tried = true; shader = try { RuntimeShader(SRC) } catch (_: Throwable) { null } }
        return shader
    }

    private const val SRC = """
uniform shader content;
uniform float2 origin;
uniform float2 size;
uniform float radius;
uniform float band;
uniform float amount;

float sdRound(float2 p, float2 h, float r) {
    float2 q = abs(p) - h + float2(r);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    float2 h = size * 0.5;
    float2 p = coord - origin - h;
    float sd = sdRound(p, h, radius);
    if (amount <= 0.0 || sd > 0.0 || -sd >= band) { return content.eval(coord); }
    float2 n = float2(sdRound(p + float2(1.0, 0.0), h, radius) - sdRound(p - float2(1.0, 0.0), h, radius),
                      sdRound(p + float2(0.0, 1.0), h, radius) - sdRound(p - float2(0.0, 1.0), h, radius));
    float len = length(n);
    if (len < 0.0001) { return content.eval(coord); }
    float x = 1.0 + sd / band;
    float d = (1.0 - sqrt(max(1.0 - x * x, 0.0))) * amount;
    return content.eval(coord + (n / len) * d);
}
"""
}

/** One window's backdrop for every glass in it: the FrostView and JharvisOrb views, each recorded into its own
 *  RenderNode only when that view changed (the orb while it animates), and placed in window coordinates. A pre-draw
 *  check, allocation free, notices a changed source or a glass that moved and redraws only the glass that needs it. */
internal class GlassBackdrop private constructor(val root: View) : ViewTreeObserver.OnPreDrawListener {
    val node = RenderNode("glass-backdrop")
    private val sources = ArrayList<View>()
    private var nodes = arrayOf<RenderNode>()
    private var at = arrayOf<Matrix>()
    private var alphas = FloatArray(0)
    private var stale = BooleanArray(0)
    private var sizes = IntArray(0)
    private val users = ArrayList<LiquidGlass>()
    private var dirty = true
    private var observer: ViewTreeObserver? = null
    private val tmp = Matrix()
    private var rw = 0; private var rh = 0

    private fun add(g: LiquidGlass) {
        if (users.isEmpty()) observer = root.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        if (g !in users) users += g
        scan()
        dirty = true
    }

    fun leave(g: LiquidGlass) {
        users.remove(g)
        if (users.isNotEmpty()) return
        observer?.let { if (it.isAlive) it.removeOnPreDrawListener(this) }
        observer = null
        for (n in nodes) n.discardDisplayList()
        node.discardDisplayList()
        sources.clear(); nodes = arrayOf(); at = arrayOf(); alphas = FloatArray(0); stale = BooleanArray(0); sizes = IntArray(0)
        byRoot.remove(root)
    }

    /** The backdrop views, in drawing order. Re-read whenever a glass joins (Home may have rebuilt its tree). */
    private fun scan() {
        val found = ArrayList<View>()
        fun walk(v: View) {
            if (v is FrostView || v is JharvisOrb) found += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        if (found == sources) return
        for (n in nodes) n.discardDisplayList()
        sources.clear(); sources.addAll(found)
        nodes = Array(found.size) { RenderNode("glass-source") }
        at = Array(found.size) { Matrix() }
        alphas = FloatArray(found.size) { -1f }
        stale = BooleanArray(found.size) { true }
        sizes = IntArray(found.size * 2)
        dirty = true
    }

    override fun onPreDraw(): Boolean {
        try {
            if (snapshot()) dirty = true
            for (i in users.indices) { val g = users[i]; if (g.moved(root) || dirty) g.host.invalidate() }
        } catch (_: Throwable) {}
        return true
    }

    /** Reads where each source sits and whether it redrew; true when the backdrop must be recorded again. */
    private fun snapshot(): Boolean {
        var changed = false
        if (root.width != rw || root.height != rh) { rw = root.width; rh = root.height; changed = true }
        for (i in sources.indices) {
            val v = sources[i]
            val a = if (v.isAttachedToWindow && v.isShown) visibleAlpha(v) else 0f
            toRoot(v, root, tmp)
            if (v.isDirty || v.width != sizes[i * 2] || v.height != sizes[i * 2 + 1]) {
                stale[i] = true; changed = true; sizes[i * 2] = v.width; sizes[i * 2 + 1] = v.height
            }
            if (a != alphas[i] || tmp != at[i]) { changed = true; at[i].set(tmp); alphas[i] = a }
        }
        return changed
    }

    /** Records the backdrop again if anything behind the glass changed. false = there is nothing to show. */
    fun ensure(): Boolean {
        if (!dirty) return sources.isNotEmpty() && rw > 0
        dirty = false
        snapshot()
        if (sources.isEmpty() || rw == 0 || rh == 0) return false
        for (i in sources.indices) {
            if (!stale[i] || alphas[i] <= 0f) continue
            stale[i] = false
            val v = sources[i]; val n = nodes[i]
            n.setPosition(0, 0, v.width, v.height)
            n.setRenderEffect((v as? FrostView)?.softness?.takeIf { it > 0f }?.let { Blur.of(v.context, it) })
            val c = n.beginRecording()
            try { v.draw(c) } finally { n.endRecording() }
        }
        node.setPosition(0, 0, rw, rh)
        val rc = node.beginRecording()
        try {
            for (i in sources.indices) {
                val a = alphas[i]; if (a <= 0f) continue
                val save = rc.save()
                rc.concat(at[i])
                if (a < 1f) rc.saveLayerAlpha(0f, 0f, sources[i].width.toFloat(), sources[i].height.toFloat(), (a * 255).toInt())
                rc.drawRenderNode(nodes[i])
                rc.restoreToCount(save)
            }
        } finally { node.endRecording() }
        return true
    }

    companion object {
        private val byRoot = WeakHashMap<View, GlassBackdrop>()

        fun join(g: LiquidGlass): GlassBackdrop? {
            if (!g.host.isAttachedToWindow) return null
            val root = g.host.rootView ?: return null
            return byRoot.getOrPut(root) { GlassBackdrop(root) }.also { it.add(g) }
        }

        /** v's own coordinates to root's, through every parent's position, scroll and transform. */
        fun toRoot(v: View, root: View, out: Matrix) {
            out.reset()
            var cur: View = v
            while (cur !== root) {
                val m = cur.matrix
                if (!m.isIdentity) out.postConcat(m)
                val p = cur.parent as? View ?: break
                out.postTranslate((cur.left - p.scrollX).toFloat(), (cur.top - p.scrollY).toFloat())
                cur = p
            }
        }

        private fun visibleAlpha(v: View): Float {
            var a = 1f
            var cur: View? = v
            while (cur != null) { a *= cur.alpha; cur = cur.parent as? View }
            return a
        }
    }
}

/** Liquid glass for one surface (PLAN-PRODUCTION.md P1 6): the backdrop behind it, blurred 3 dp, bent along the rim
 *  by the refraction shader, saturated 1.5, then a tint and a 45 degree specular streak. reveal 0..1 ramps the
 *  refraction in, so a panel materializes. draw() returns false whenever the glass can't run (Remove animations,
 *  battery saver, no shader, software canvas, no backdrop): the caller draws its plain glass instead.
 *  The host calls attach() and detach() from its own window callbacks. */
class LiquidGlass(internal val host: View, look: Look) {
    private val still = reducedMotion(host.context)
    private val node = RenderNode("liquid-glass")
    private val outline = Outline()
    private val toRoot = Matrix(); private val seen = Matrix(); private val fromRoot = Matrix()
    private var placed = false
    private var backdrop: GlassBackdrop? = null
    private val effects = arrayOfNulls<RenderEffect>(GlassMath.LEVELS + 1)
    private var ew = 0f; private var eh = 0f; private var er = 0f
    private var shownFx: RenderEffect? = null
    private val blurPx = look.px(3).toFloat()
    private val cap = look.px(16).toFloat()
    private val shine = look.white(128)
    private val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }

    /** 0 = no refraction yet, 1 = full. Redraws only when the cached strength step changes. */
    var reveal = 1f
        set(v) {
            val c = v.coerceIn(0f, 1f)
            if (GlassMath.level(c) != GlassMath.level(field) || (c == 0f) != (field == 0f)) host.invalidate()
            field = c
        }

    fun attach() { if (backdrop == null && !still) backdrop = GlassBackdrop.join(this) }

    fun detach() {
        backdrop?.leave(this); backdrop = null
        node.discardDisplayList(); placed = false; shownFx = null
        effects.fill(null); ew = 0f
    }

    /** Re-reads where the host sits in its window; true when it moved since last time. */
    internal fun moved(root: View): Boolean {
        GlassBackdrop.toRoot(host, root, toRoot)
        if (placed && toRoot == seen) return false
        seen.set(toRoot); placed = true
        if (!seen.invert(fromRoot)) fromRoot.reset()
        return true
    }

    /** Draws the glass into rect (host coordinates): refraction, then tint (if any), then the streak. */
    fun draw(canvas: Canvas, rect: RectF, radius: Float, tint: Paint?): Boolean {
        if (still || broken || !canvas.isHardwareAccelerated) return false
        val bd = backdrop ?: return false
        val w = rect.width(); val h = rect.height()
        if (w < 2f || h < 2f || Blur.saver(host.context)) return false
        val sh = GlassShader.get() ?: return false
        return try {
            if (!bd.ensure()) return false
            moved(bd.root)
            val r = min(radius, min(w, h) / 2f)
            val band = GlassMath.band(w, h, cap)
            val amount = GlassMath.amount(band)
            val m = GlassMath.margin(amount, blurPx)
            if (w != ew || h != eh || r != er) {
                effects.fill(null); ew = w; eh = h; er = r
                val iw = ceil(w).toInt(); val ih = ceil(h).toInt()
                node.setPosition(0, 0, iw + 2 * m, ih + 2 * m)
                outline.setRoundRect(m, m, m + iw, m + ih, r)
                node.setOutline(outline); node.clipToOutline = true
                streak.shader = LinearGradient(0f, 0f, (w + h) / 2f, (w + h) / 2f,
                    intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, shine, Color.TRANSPARENT, Color.TRANSPARENT),
                    floatArrayOf(0f, 0.14f, 0.22f, 0.3f, 1f), Shader.TileMode.CLAMP)
            }
            val level = GlassMath.level(reveal)
            val fx = effects[level] ?: build(sh, w, h, r, m, band, amount * level / GlassMath.LEVELS).also { effects[level] = it }
            if (fx !== shownFx) { node.setRenderEffect(fx); shownFx = fx }
            val rc = node.beginRecording()
            try {
                rc.translate(m - rect.left, m - rect.top)
                rc.concat(fromRoot)
                rc.drawRenderNode(bd.node)
            } finally { node.endRecording() }
            canvas.save(); canvas.translate(rect.left - m, rect.top - m); canvas.drawRenderNode(node); canvas.restore()
            tint?.let { canvas.drawRoundRect(rect, r, r, it) }
            if (reveal > 0f) {
                streak.alpha = (255 * reveal).toInt()
                canvas.save(); canvas.translate(rect.left, rect.top); canvas.drawRoundRect(0f, 0f, w, h, r, r, streak); canvas.restore()
            }
            true
        } catch (_: Throwable) { broken = true; false }  // one failure turns the glass off for the process: plain glass is fine
    }

    private fun build(sh: RuntimeShader, w: Float, h: Float, r: Float, m: Int, band: Float, amount: Float): RenderEffect {
        // the effect keeps a copy of these uniforms, so one shader serves every glass
        sh.setFloatUniform("origin", m.toFloat(), m.toFloat())
        sh.setFloatUniform("size", w, h)
        sh.setFloatUniform("radius", r)
        sh.setFloatUniform("band", band)
        sh.setFloatUniform("amount", amount)
        val blur = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
        val bend = RenderEffect.createRuntimeShaderEffect(sh, "content")
        return RenderEffect.createChainEffect(saturate, RenderEffect.createChainEffect(bend, blur))  // inner runs first
    }

    private companion object {
        @Volatile var broken = false
        val saturate: RenderEffect = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) }))
    }
}

/** A container drawn as liquid glass (for the dock, or anything else that floats over the wave): the glass behind its
 *  children, a white 75% hairline where light catches the edge. Where the glass can't run it is the plain frosted
 *  glassCard. reveal ramps the refraction for a materialize. */
class GlassView(c: Context, private val look: Look, radiusDp: Int = 28) : FrameLayout(c) {
    private val glass = LiquidGlass(this, look)
    private val r = look.px(radiusDp).toFloat()
    private val rect = RectF()
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.white(64) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = look.px(1).toFloat(); color = look.white(191) }
    private val plain = look.glassCard(radiusDp)

    var reveal: Float
        get() = glass.reveal
        set(v) { glass.reveal = v }

    init { setWillNotDraw(false) }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); glass.attach() }
    override fun onDetachedFromWindow() { glass.detach(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        if (!glass.draw(canvas, rect, r, tint)) { plain.setBounds(0, 0, width, height); plain.draw(canvas); return }
        val i = look.px(1) / 2f
        rect.inset(i, i)
        canvas.drawRoundRect(rect, r - i, r - i, edge)
    }
}
