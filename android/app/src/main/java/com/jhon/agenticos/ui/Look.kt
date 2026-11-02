package com.jhon.agenticos.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import android.widget.TextView

/** The tokens every screen draws with. Their values come from the theme he picked in Appearance (Themes.kt), the
 *  accent override and the font; the default is Glacier, the dashboard's ice light (DESIGN.md). */
class Look(val c: Context) {
    val theme: ThemeSpec = Appearance.theme(c)
    val night = theme.dark
    val ground = theme.ground
    val glass = theme.glass
    val glassEdge = theme.glassEdge
    val shellFill = theme.shell
    val shellEdge = theme.shellEdge
    val bar = theme.bar                 // frosted top bar on settings screens
    val ink = theme.ink
    val ink2 = theme.ink2
    val ink3 = theme.ink3
    val navy = theme.navy
    val onNavy = theme.onNavy
    val accent: Int = Appearance.prefs(c).getInt(Appearance.ACCENT, 0).takeIf { it != 0 } ?: theme.accent
    val glacier = accent                // the one accent (Glacier by default); kept under its old name
    val ok = theme.ok
    val warn = theme.warn
    val bad = theme.bad
    val accentInk = Themes.readable(theme, accent)  // the accent as a word: deepened where the accent itself is too faint
    private val onWall = Appearance.prefs(c).getString(Appearance.WALL, Appearance.FROST).let { it == Appearance.PHOTO || it == Appearance.SYSTEM }
    /** Inks for words straight on his photo or the phone wallpaper (Home's glance, the empty line): they follow the
     *  wallpaper's lightness, not the theme's. On the frost, the theme itself. */
    val bare: ThemeSpec by lazy { Appearance.bare(c, theme) }  // screens that never write on the wallpaper skip the lookup
    /** Under the needs cards over a wallpaper: the theme's glass, strong enough for its ink over any picture. 0 on the frost. */
    val cardTint = if (onWall) Themes.cardTint(theme, accent) else 0
    val hairline = inkA(31)             // separators: ink at 12%
    val pressed = inkA(22)              // row highlight under the finger
    val font: FontSpec = Themes.font(Appearance.prefs(c).getString(Appearance.FONT, null))
    val sans: Typeface = face(font, false)
    val medium: Typeface = face(font, true)
    private val chromeStops = Themes.chrome(theme, accent)
    private val onChrome = Themes.onChrome(theme, accent)
    private val density = c.resources.displayMetrics.density
    fun px(dp: Int) = (dp * density).toInt()

    /** This colour at alpha a (0..255). */
    fun withAlpha(color: Int, a: Int) = (color and 0xFFFFFF) or (a.coerceIn(0, 255) shl 24)
    fun inkA(a: Int) = withAlpha(ink, a)
    /** Light catching glass: white at a on light themes, a faint version on dark ones (the dashboard's night glass). */
    fun white(a: Int) = Color.argb((a * theme.sheen).toInt().coerceIn(0, 255), 255, 255, 255)
    /** Shadows and scrims: ink-tinted on light themes, deeper black on dark ones so they still separate. */
    fun shade(a: Int) = if (night) Color.argb((a * 2).coerceAtMost(255), 0, 0, 0) else inkA(a)

    fun text(s: CharSequence, size: Float = 16f, color: Int = ink, bold: Boolean = false) = TextView(c).apply {
        text = s; textSize = size; setTextColor(color); typeface = if (bold) medium else sans
        // tracking by size (Apple): big text tighter, small text a touch looser
        letterSpacing = when { size >= 20f -> -0.02f; size <= 13f -> 0.01f; else -> 0f }
    }

    /** Frosted glass: translucent fill over the blurred frost, with a bright hairline where light catches the edge. */
    fun glassCard(radiusDp: Int = 22) = round(glass, radiusDp, glassEdge)

    /** Glass for things that sit on the wallpaper (the needs pill, home's chips and gear, the dock): over his photo
     *  or the phone wallpaper the cards' strong theme tint, so grey words read on any picture; plain glass on the frost. */
    fun wallGlass(radiusDp: Int) = if (cardTint != 0) round(cardTint, radiusDp, glassEdge) else glassCard(radiusDp)

    /** The outer tray of a double bezel (dashboard .glass-shell): pad it 6 dp and put a glassCard(radius - 6) inside. */
    fun shell(radiusDp: Int = 28) = round(shellFill, radiusDp, shellEdge)

    /** Chrome (dashboard .chrome): the theme's gradient for the one main action on a screen. */
    fun chrome(radiusDp: Int) = GradientDrawable(GradientDrawable.Orientation.TL_BR, chromeStops).apply { cornerRadius = px(radiusDp).toFloat() }

    fun round(fill: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = px(radiusDp).toFloat(); stroke?.let { setStroke(px(1), it) }
    }

    /** A pill or rounded button: ripple, 48 dp tall at least, press feedback on touch-down (scale 0.97). */
    fun button(label: String, filled: Boolean, onClick: () -> Unit) = text(label, 15f, if (filled) onChrome else ink, true).apply {
        gravity = android.view.Gravity.CENTER
        minHeight = px(48); setPadding(px(18), 0, px(18), 0)
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(40, 128, 128, 128)),
            if (filled) chrome(14) else round(glass, 14, glassEdge), null)
        press(this); setOnClickListener { onClick() }
    }

    /** Text drawn straight on his photo or the phone wallpaper (Home's glance, the empty line, orbit labels): a 2 dp
     *  glow of the opposite lightness, so the ink lifts off a busy patch. Small, so it never merges into a box; the
     *  view needs that much room around its letters (padding, or clipChildren off on its parent). Text on glass, chips
     *  or the dock gets none; on the frost nothing needs it. Call after the text colour is set. */
    fun legible(t: TextView) {
        if (!onWall) return
        val dark = Themes.luminance(t.currentTextColor) < 0.179
        t.setShadowLayer(px(2).toFloat(), 0f, 0f, if (dark) Color.argb(150, 255, 255, 255) else Color.argb(120, 0, 0, 0))
    }

    /** The halo itself, whatever the wallpaper: Jharvis's overlay floats its words over any app. */
    fun halo(t: TextView) = t.setShadowLayer(px(6).toFloat(), 0f, 0f, withAlpha(ground, 230))

    /** Status and navigation bar icons that read on this ground, and a window background that matches it (no ice
     *  flash under a dark theme). Call after setContentView: the window has no insets controller before it. */
    fun bars(w: Window) {
        w.setBackgroundDrawable(ColorDrawable(ground))
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        w.insetsController?.setSystemBarsAppearance(if (night) 0 else light, light)
    }

    /** Squish on touch-down with a light tick, spring back on release with the faintest settle. Interruptible: a second
     *  tap mid-bounce starts from wherever the scale is. */
    fun press(v: View, depth: Float = 0.94f) {
        val still = reducedMotion(c)
        val s = Spring(1f, 0.0005f) { v.scaleX = it; v.scaleY = it }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { view.tick(); if (!still) s.to(depth, response = 0.16f, damping = 1f) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!still) s.to(1f, response = 0.42f, damping = 0.82f)
            }
            false
        }
    }

    companion object {
        /** A font's two faces. System keeps the exact families Home always used; the others pick a weight from a
         *  family every Android ships. A bundled face (FontSpec.res) loads from res/font. */
        fun face(c: Context, f: FontSpec, bold: Boolean): Typeface {
            if (f.id == "system") return Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            val base = if (f.res != 0) (try { c.resources.getFont(f.res) } catch (_: Exception) { null } ?: Typeface.DEFAULT)
                else Typeface.create(f.family, Typeface.NORMAL)
            return Typeface.create(base, if (bold) f.bold else f.body, false)
        }
    }

    private fun face(f: FontSpec, bold: Boolean) = face(c, f, bold)
}
