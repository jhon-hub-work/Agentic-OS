package com.jhon.agenticos.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** "App layout": every way to show all apps, each with a small drawn preview, on a glass sheet that rises from
 *  the bottom of any screen (Home's long press on empty frost, Settings). A pick applies at once (onPick), then the
 *  sheet leaves the way it came. Back, or a tap outside, closes it; predictive Back shrinks it as the swipe grows. */
object LayoutPicker {
    private var open: Sheet? = null  // one sheet at a time

    fun show(act: Activity, look: Look, current: String, onPick: (String) -> Unit) {
        if (act.isFinishing || act.isDestroyed) return
        dismiss()
        open = Sheet(act, look, current, onPick).also { it.open() }
    }

    /** Home stopped or the Home button: gone at once, back callback with it. */
    fun dismiss() { open?.let { it.unregisterBack(); it.remove() }; open = null }

    private class Sheet(val act: Activity, val look: Look, var current: String, val onPick: (String) -> Unit) {
        val still = reducedMotion(act)
        val decor = act.window.decorView as ViewGroup
        val overlay = FrameLayout(act)
        val scrim = View(act)
        val shell = FrameLayout(act)
        val tiles = ArrayList<Pair<String, LinearLayout>>()
        var back = 0f
        var closing = false
        var blurNow = -1f
        val spring = Spring(0f, 0.001f) { render(it) }
        val backCb: OnBackInvokedCallback =
            if (Build.VERSION.SDK_INT >= 34) object : OnBackAnimationCallback {
                override fun onBackProgressed(e: BackEvent) { back = e.progress; render(spring.value) }
                override fun onBackCancelled() { back = 0f; render(spring.value) }
                override fun onBackInvoked() = close()
            } else OnBackInvokedCallback { close() }

        fun open() {
            scrim.setBackgroundColor(look.shade(46))
            scrim.contentDescription = "Close app layout"
            scrim.setOnClickListener { close() }
            overlay.addView(scrim, FrameLayout.LayoutParams(-1, -1))

            val core = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = look.wallGlass(22)  // holds its ink over any wallpaper
                isClickable = true               // taps between tiles never reach the scrim
                setPadding(look.px(16), look.px(18), look.px(16), look.px(16))
                addView(look.text("App layout", 22f, look.ink, true).apply { setPadding(look.px(4), 0, look.px(4), 0) })
                addView(look.text("How your apps show when you swipe up", 13f, look.ink3).apply {
                    setPadding(look.px(4), look.px(2), look.px(4), look.px(14))
                })
                for (row in Layouts.IDS.chunked(2)) addView(LinearLayout(act).apply {
                    weightSum = 2f; gravity = Gravity.CENTER_HORIZONTAL  // an odd last tile stays tile-sized, centred
                    row.forEachIndexed { i, id ->
                        addView(tile(id), LinearLayout.LayoutParams(0, -2, 1f).apply { if (i == 1) leftMargin = look.px(10) })
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (row.first() == Layouts.IDS.first()) 0 else look.px(10) })
            }
            shell.apply {
                background = look.shell(28)
                setPadding(look.px(6), look.px(6), look.px(6), look.px(6))
                elevation = look.px(24).toFloat()
                outlineSpotShadowColor = look.ink; outlineAmbientShadowColor = look.ink
                accessibilityPaneTitle = "App layout"
                // five tiles can outgrow a short screen or a large font: the sheet stops below the status bar and scrolls
                addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(core, FrameLayout.LayoutParams(-1, -2)) },
                    FrameLayout.LayoutParams(-1, -2))
            }
            val ins = decor.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            overlay.addView(shell, FrameLayout.LayoutParams(min(decor.width, look.px(440)) - look.px(24), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (ins?.bottom ?: 0) + look.px(12); topMargin = (ins?.top ?: 0) + look.px(12)
            })
            overlay.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) { spring.stop(); unregisterBack() }  // the screen went away
            })
            decor.addView(overlay, FrameLayout.LayoutParams(-1, -1))
            shell.post { shell.pivotX = shell.width / 2f; shell.pivotY = shell.height.toFloat() }  // rises out of the bottom edge
            act.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, backCb)
            if (still) spring.snap(1f) else { render(0f); spring.to(1f, response = 0.45f, damping = 1f) }
        }

        /** One choice: its preview, its name, one line on what it is. The one in use wears the accent hairline. */
        fun tile(id: String) = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(look.px(10), look.px(10), look.px(10), look.px(12))
            addView(Preview(act, look, id), LinearLayout.LayoutParams(-1, look.px(92)))
            addView(look.text(Layouts.name(id), 15f, look.ink, true).apply { setPadding(look.px(2), look.px(10), look.px(2), 0) })
            addView(look.text(Layouts.about(id), 12f, look.ink3).apply { maxLines = 2; setPadding(look.px(2), look.px(2), look.px(2), 0) })
            isClickable = true; look.press(this, 0.96f)
            setOnClickListener { pick(id, this) }
            tiles += id to this
            mark(id, this)
        }

        fun mark(id: String, t: LinearLayout) {
            val on = id == current
            t.background = if (on) look.round(look.withAlpha(look.glacier, 36), 18, look.glacier) else look.round(look.inkA(12), 18)
            t.contentDescription = "${Layouts.name(id)}. ${Layouts.about(id)}" + if (on) ". In use" else ""
            t.isSelected = on
        }

        fun pick(id: String, from: View) {
            if (closing) return
            from.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            current = id
            for ((k, t) in tiles) mark(k, t)
            onPick(id)
            close()
        }

        fun render(raw: Float) {
            val p = raw.coerceIn(0f, 1f)
            scrim.alpha = p
            shell.alpha = p
            if (still) return
            val s = (0.92f + 0.08f * raw) * (1 - 0.1f * back)
            shell.scaleX = s; shell.scaleY = s
            shell.translationY = (1 - raw) * look.px(24)
            val b = ((look.px(8) * (1 - p)) * 2).toInt() / 2f  // a new blur only when the radius really changed
            if (b != blurNow) { blurNow = b; shell.blur(b) }
        }

        /** Same path out as in. */
        fun close() {
            if (closing) return
            closing = true
            unregisterBack()
            scrim.setOnClickListener(null); scrim.isClickable = false  // taps fall through while it dissolves
            if (still) { remove(); return }
            spring.onRest = { remove() }
            spring.to(0f, response = 0.36f, damping = 1f)
        }

        fun remove() { spring.stop(); (overlay.parent as? ViewGroup)?.removeView(overlay); if (open === this) open = null }

        fun unregisterBack() = try { act.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCb) } catch (_: Exception) {}
    }
}

// Typographic Matrix's tile: each line's bar widths (of the tile's width) and thickness (of its size)
private val TYPE_BARS = arrayOf(floatArrayOf(0.34f, 0.40f), floatArrayOf(0.26f, 0.2f, 0.28f), floatArrayOf(0.16f, 0.2f, 0.12f),
    floatArrayOf(0.12f, 0.16f, 0.1f, 0.14f, 0.12f), floatArrayOf(0.1f, 0.08f, 0.12f, 0.09f, 0.11f, 0.1f))
private val TYPE_THICK = floatArrayOf(0.11f, 0.08f, 0.06f, 0.045f, 0.035f)
// Mercury Flow's tile: the drops scattered round the orb, as fractions of the tile (x, y)
private val MERCURY_DROPS = floatArrayOf(0.14f, 0.24f, 0.34f, 0.78f, 0.6f, 0.14f, 0.84f, 0.36f, 0.76f, 0.8f, 0.1f, 0.66f, 0.92f, 0.62f, 0.3f, 0.12f)

/** A small drawing of each layout, in the theme's ink and accent. Everything is worked out from the size in onDraw
 *  with plain floats: nothing is allocated while drawing. */
private class Preview(c: Context, look: Look, private val id: String) : View(c) {
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.inkA(120) }
    private val hot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.glacier }
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.inkA(22) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = look.inkA(46); style = Paint.Style.STROKE; strokeWidth = c.resources.displayMetrics.density
    }
    private val lens = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE0162334.toInt() }  // the orb's dark lens
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {  // Fluid Spline's thread: a faint wide glow under a bright line
        color = look.withAlpha(look.glacier, 50); style = Paint.Style.STROKE; strokeWidth = c.resources.displayMetrics.density * 5; strokeCap = Paint.Cap.ROUND
    }
    private val thread = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = look.glacier; style = Paint.Style.STROKE; strokeWidth = c.resources.displayMetrics.density * 1.5f; strokeCap = Paint.Cap.ROUND
    }
    // Horizon Core's tunnel: neon edges (a faint wide glow under a thin bright line) on a dark wash
    private val neon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = c.resources.displayMetrics.density }
    private val neonGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = c.resources.displayMetrics.density * 3.5f }
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF00A0D18.toInt() }
    private val r = RectF()
    private val wave = Path()  // Fluid Spline's thread as one path: separate round-capped segments double the glow at every joint

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }  // the tile says what it shows

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f; val cy = h / 2f; val m = min(w, h)
        canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        when (id) {
            Layouts.GROUPS -> {  // two by two cards: three big icons and a cluster of small ones
                val gap = m * 0.08f; val cw = min((w - gap) / 2f, (h - gap) / 2f * 1.15f); val ch = (h - gap) / 2f
                val x0 = cx - cw - gap / 2f
                for (k in 0 until 4) {
                    val x = x0 + (k % 2) * (cw + gap); val y = (k / 2) * (ch + gap)
                    r.set(x, y, x + cw, y + ch); canvas.drawRoundRect(r, ch * 0.22f, ch * 0.22f, card)
                    val d = ch * 0.17f
                    canvas.drawCircle(x + cw * 0.28f, y + ch * 0.32f, d, if (k == 0) hot else dot)
                    canvas.drawCircle(x + cw * 0.72f, y + ch * 0.32f, d, dot)
                    canvas.drawCircle(x + cw * 0.28f, y + ch * 0.70f, d, dot)
                    val s = d * 0.45f
                    for (q in 0 until 4) canvas.drawCircle(x + cw * (0.62f + 0.2f * (q % 2)), y + ch * (0.60f + 0.2f * (q / 2)), s, dot)
                }
            }
            Layouts.HONEYCOMB -> {  // one field of icons, largest in the middle, shrinking toward the edges
                val pitch = m * 0.21f; val maxD = hypot(w / 2f, h / 2f)
                for (row in -3..3) for (col in -4..4) {
                    val x = cx + (col + if (row % 2 != 0) 0.5f else 0f) * pitch
                    val y = cy + row * pitch * 0.866f
                    val f = 1f - min(1f, hypot(x - cx, y - cy) / maxD)
                    val rad = pitch * 0.42f * (0.35f + 0.65f * f * f)
                    if (rad > pitch * 0.12f) canvas.drawCircle(x, y, rad, if (row == 0 && col == 0) hot else dot)
                }
            }
            Layouts.PICKS -> {  // two rows of four picks, then the A to Z list with its letter strip
                val d = m * 0.085f; val left = w * 0.14f; val step = (w * 0.72f) / 3f
                for (k in 0 until 8) canvas.drawCircle(left + (k % 4) * step, h * 0.14f + (k / 4) * d * 2.7f, d, if (k == 0) hot else dot)
                for (k in 0 until 3) {
                    val y = h * 0.62f + k * h * 0.14f
                    canvas.drawCircle(left, y, d * 0.6f, dot)
                    r.set(left + d * 1.4f, y - d * 0.2f, w * 0.78f, y + d * 0.2f); canvas.drawRoundRect(r, d, d, card)
                }
                r.set(w * 0.9f, h * 0.5f, w * 0.9f + d * 0.6f, h * 0.98f); canvas.drawRoundRect(r, d, d, card)
            }
            Layouts.SPHERE -> {  // a globe: back dots small and faint, the orb inside, front dots over it
                val rr = m * 0.44f; val n = 26
                canvas.drawCircle(cx, cy, rr, line)
                for (pass in 0 until 2) {
                    if (pass == 1) canvas.drawCircle(cx, cy, m * 0.13f, lens)
                    for (k in 0 until n) {  // golden spiral, pole tipped toward the viewer a little
                        val y = 1f - 2f * (k + 0.5f) / n; val ring = sqrt(1f - y * y); val a = k * 2.3999632f
                        val x = cos(a) * ring; val z0 = sin(a) * ring
                        val yy = y * 0.94f - z0 * 0.34f; val z = y * 0.34f + z0 * 0.94f
                        if ((z >= 0f) != (pass == 1)) continue
                        val f = 2.828f / (3f - z)  // SphereMath.spread: the rim at one radius
                        val p = if (k == 0) hot else dot
                        val was = p.alpha; p.alpha = (was * (0.3f + 0.7f * (z + 1f) / 2f)).toInt()
                        canvas.drawCircle(cx + x * rr * f, cy + yy * rr * f, m * 0.05f * (0.55f + 0.45f * (z + 1f) / 2f), p)
                        p.alpha = was
                    }
                }
            }
            Layouts.TYPE -> {  // lines of names, heavy and thin, one framed in a pill with the orb as its end dot
                val left = w * 0.08f; val span = w * 0.84f; var y = h * 0.08f
                for (row in TYPE_BARS.indices) {
                    val ws = TYPE_BARS[row]
                    val t = m * TYPE_THICK[row]; val gap = (span - ws.sum() * w) / (ws.size - 1)  // justified, as the drawer sets them
                    var x = left
                    for (k in ws.indices) {
                        val bw = ws[k]
                        r.set(x, y, x + bw * w, y + t)
                        val framed = row == 2 && k == 1
                        canvas.drawRoundRect(r, t / 2, t / 2, if (framed) hot else card.takeIf { row > 1 } ?: dot)
                        if (framed) {
                            val pad = t * 0.9f
                            r.set(x - pad, y - pad, x + bw * w + pad * 3.4f, y + t + pad); canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, line)
                            canvas.drawCircle(r.right - pad * 1.3f, y + t / 2, t * 0.55f, lens)
                        }
                        x += bw * w + gap
                    }
                    y += t + h * 0.1f
                }
            }
            Layouts.SPLINE -> {  // one winding thread from the orb, beads along it, the most used nearest
                val x0 = w * 0.16f; val span = w * 0.76f; val amp = h * 0.3f
                fun yAt(t: Float) = cy + amp * sin(t * 2.4f * PI.toFloat() + 0.6f) * (0.35f + 0.65f * t)  // the swing opens out away from the orb
                wave.rewind(); wave.moveTo(x0, yAt(0f))
                for (k in 1..32) { val t = k / 32f; wave.lineTo(x0 + span * t, yAt(t)) }
                canvas.drawPath(wave, glow); canvas.drawPath(wave, thread)
                for (k in 0 until 6) {
                    val t = 0.14f + k * 0.17f
                    canvas.drawCircle(x0 + span * t, yAt(t), m * 0.07f, if (k == 0) hot else dot)
                }
                canvas.drawCircle(x0, yAt(0f), m * 0.13f, lens)
            }
            Layouts.MERCURY -> {  // drops scattered round the orb, one melting into it through a neck
                val d = m * 0.075f
                for (k in 0 until MERCURY_DROPS.size / 2) canvas.drawCircle(MERCURY_DROPS[k * 2] * w, MERCURY_DROPS[k * 2 + 1] * h, d, dot)
                canvas.drawCircle(cx + m * 0.25f, cy - m * 0.05f, d, hot)
                canvas.drawCircle(cx + m * 0.17f, cy - m * 0.03f, d * 0.6f, hot)
                canvas.drawCircle(cx, cy, m * 0.13f, lens)
            }
            Layouts.HORIZON -> {  // a neon tunnel: tiles on a floor and a ceiling running to the orb, smaller and fainter with distance
                r.set(0f, 0f, w, h); canvas.drawRoundRect(r, m * 0.1f, m * 0.1f, wash)  // dark-backed, as the drawer is
                val lane = w * 0.22f
                neon.color = HorizonMath.NEON_CYAN; neon.alpha = 140
                for (k in -2..5) { val x = cx + (k - 1.5f) * lane * 1.6f; canvas.drawLine(x, h, cx, cy, neon); canvas.drawLine(x, 0f, cx, cy, neon) }
                neon.color = HorizonMath.NEON_YELLOW; canvas.drawLine(0f, cy, w, cy, neon)
                for (d in 2 downTo 0) for (up in 0..1) {  // back to front, the floor row then the ceiling row at each depth
                    val s = 3f / (3f + d * 1.6f); val t = m * 0.22f * s; val fade = 1f - d * 0.25f
                    val y = cy + ((if (up == 1) h * 0.03f else h * 0.97f) - cy) * s
                    for (col in 0 until 3) {
                        val x = cx + (col - 1) * lane * 1.3f * s
                        if (up == 1) r.set(x - t / 2, y, x + t / 2, y + t) else r.set(x - t / 2, y - t, x + t / 2, y)
                        canvas.drawRoundRect(r, t * 0.26f, t * 0.26f, wash)
                        val c = HorizonMath.NEON[(d * 2 + up + col) % 3]
                        neonGlow.color = c; neonGlow.alpha = (60 * fade).toInt(); canvas.drawRoundRect(r, t * 0.26f, t * 0.26f, neonGlow)
                        neon.color = c; neon.alpha = (255 * fade).toInt(); canvas.drawRoundRect(r, t * 0.26f, t * 0.26f, neon)
                    }
                }
                canvas.drawCircle(cx, cy, m * 0.13f, lens)
            }
            else -> {  // orbit: rings around the orb
                for (ring in 0 until 2) {
                    val rr = m * (0.30f + 0.17f * ring); val n = 6 + ring * 4
                    canvas.drawCircle(cx, cy, rr, line)
                    for (k in 0 until n) {
                        val a = (k.toFloat() / n * 2.0 * Math.PI - Math.PI / 2 + ring * Math.PI / n)
                        canvas.drawCircle(cx + rr * cos(a).toFloat(), cy + rr * sin(a).toFloat(), m * 0.055f, if (ring == 0 && k == 0) hot else dot)
                    }
                }
                canvas.drawCircle(cx, cy, m * 0.13f, lens)
            }
        }
        canvas.restore()
    }
}
