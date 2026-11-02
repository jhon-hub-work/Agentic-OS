package com.jhon.agenticos.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** "App layout": the five ways to show all apps, each with a small drawn preview, on a glass sheet that rises from
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
    private val r = RectF()

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
