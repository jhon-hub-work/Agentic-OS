package com.jhon.agenticos.ui

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration

/** One curated look: every colour Home and its screens draw with. Colours are ARGB ints written as hex literals so
 *  this file's pure parts run in plain unit tests (ThemesTest checks every text colour against its ground). */
class ThemeSpec(
    val id: String, val name: String, val dark: Boolean,
    val ground: Int,
    val mesh: IntArray,          // frost light: top-left, bottom-right, top-right wash (a breath of the accent is added)
    val breath: Int,             // alpha of that accent breath
    val glass: Int, val glassEdge: Int,   // inner glass core + its lit hairline (dashboard .glass)
    val shell: Int, val shellEdge: Int,   // outer glass tray + hairline (dashboard .glass-shell)
    val bar: Int,                // frosted top bar on settings screens
    val ink: Int, val ink2: Int, val ink3: Int,
    val accent: Int,
    val navy: Int, val onNavy: Int,       // selected state fill and the text on it
    val chrome: IntArray?,       // the one primary action; null = drawn from the accent (dark themes, as the dashboard's night chrome)
    val onChrome: Int,
    val ok: Int, val warn: Int, val bad: Int,
    val sheen: Float,            // how much "light catching glass" white shows: 1 on light grounds, faint on dark
    val pair: String,            // the other-lightness twin, used when a photo or the phone wants the other ink
)

class FontSpec(val id: String, val name: String, val family: String, val body: Int, val bold: Int,
               val res: Int = 0)  // res: a bundled res/font face later (Look loads it with resources.getFont); 0 = system family

object Themes {
    private fun c(hex: Long) = hex.toInt()

    val glacier = ThemeSpec("glacier", "Glacier", false,
        ground = c(0xFFE6EEF4), mesh = intArrayOf(c(0xFFFFFFFF), c(0xFFCFE3F2), c(0xFFDCE8F1)), breath = 0x22,
        glass = c(0x8CFFFFFF), glassEdge = c(0xF2FFFFFF), shell = c(0x47FFFFFF), shellEdge = c(0xB3FFFFFF), bar = c(0xD6F4F8FB),
        ink = c(0xFF1D2B3A), ink2 = c(0xFF4E6072), ink3 = c(0xFF5E6D7B), accent = c(0xFF3FB6EC),
        navy = c(0xFF1F2D44), onNavy = c(0xFFEEF4F9), chrome = intArrayOf(c(0xFF5B6F86), c(0xFF1F2D44), c(0xFF0E1726)), onChrome = c(0xFFEEF4F9),
        // ok and warn a shade deeper than DESIGN.md's #2E7D62 / #A0661B: those were 4.2 and 3.9 to 1 on the ice
        ok = c(0xFF2A7259), warn = c(0xFF8E5A17), bad = c(0xFFB23A3A), sheen = 1f, pair = "night")

    // the dashboard's :root.dark (web/src/index.css)
    val night = ThemeSpec("night", "Night Glacier", true,
        ground = c(0xFF0A111C), mesh = intArrayOf(c(0xFF16233A), c(0xFF0E2A3D), c(0xFF121E30)), breath = 0x2A,
        glass = c(0x9922344B), glassEdge = c(0x1CFFFFFF), shell = c(0x08FFFFFF), shellEdge = c(0x17FFFFFF), bar = c(0xD60E1726),
        ink = c(0xFFE4ECF4), ink2 = c(0xFFA9B8C8), ink3 = c(0xFF8496AA), accent = c(0xFF3FB6EC),
        navy = c(0xFF9FDDF7), onNavy = c(0xFF0A111C), chrome = intArrayOf(c(0xFF3FB6EC), c(0xFF2672A2), c(0xFF1F4E78)), onChrome = c(0xFFF4FAFF),
        ok = c(0xFF4CC38A), warn = c(0xFFE0A458), bad = c(0xFFF07A7A), sheen = 0.22f, pair = "glacier")

    // iOS-like monochrome: neutral greys, the accent is ink itself
    val graphite = ThemeSpec("graphite", "Graphite", false,
        ground = c(0xFFF1F1F3), mesh = intArrayOf(c(0xFFFFFFFF), c(0xFFE1E2E6), c(0xFFEBEBEE)), breath = 0x0E,
        glass = c(0xB3FFFFFF), glassEdge = c(0xF2FFFFFF), shell = c(0x52FFFFFF), shellEdge = c(0xB3FFFFFF), bar = c(0xDBF7F7F8),
        ink = c(0xFF111214), ink2 = c(0xFF46484D), ink3 = c(0xFF5D5F65), accent = c(0xFF1C1C1E),
        navy = c(0xFF1C1C1E), onNavy = c(0xFFF5F5F7), chrome = intArrayOf(c(0xFF4A4B50), c(0xFF1F2023), c(0xFF0B0B0C)), onChrome = c(0xFFF5F5F7),
        ok = c(0xFF1E7146), warn = c(0xFF8F530B), bad = c(0xFFB3261E), sheen = 1f, pair = "midnight")

    // near-black for the OLED panel, Glacier as the one light in it
    val midnight = ThemeSpec("midnight", "Midnight", true,
        ground = c(0xFF040507), mesh = intArrayOf(c(0xFF10151C), c(0xFF07131C), c(0xFF0B0E13)), breath = 0x22,
        glass = c(0xCC15181D), glassEdge = c(0x1AFFFFFF), shell = c(0x08FFFFFF), shellEdge = c(0x14FFFFFF), bar = c(0xE6050608),
        ink = c(0xFFF2F4F7), ink2 = c(0xFFADB4BE), ink3 = c(0xFF838B97), accent = c(0xFF3FB6EC),
        navy = c(0xFF9FDDF7), onNavy = c(0xFF040507), chrome = null, onChrome = c(0xFF040507),
        ok = c(0xFF4CC38A), warn = c(0xFFE0A458), bad = c(0xFFF07A7A), sheen = 0.2f, pair = "graphite")

    // deep forest green, a mint accent
    val moss = ThemeSpec("moss", "Moss", true,
        ground = c(0xFF0A1511), mesh = intArrayOf(c(0xFF16291F), c(0xFF0D2A21), c(0xFF11201A)), breath = 0x2A,
        glass = c(0x99183026), glassEdge = c(0x1CFFFFFF), shell = c(0x08FFFFFF), shellEdge = c(0x17FFFFFF), bar = c(0xD60C1A15),
        ink = c(0xFFE3EFE8), ink2 = c(0xFFA7BEB2), ink3 = c(0xFF7F9A8C), accent = c(0xFF6FD3A6),
        navy = c(0xFFB5EBD2), onNavy = c(0xFF0A1511), chrome = null, onChrome = c(0xFF0A1511),
        ok = c(0xFF62CF95), warn = c(0xFFE3AE5F), bad = c(0xFFF08A80), sheen = 0.22f, pair = "graphite")

    // warm charcoal, an amber accent
    val ember = ThemeSpec("ember", "Ember", true,
        ground = c(0xFF141110), mesh = intArrayOf(c(0xFF2A211B), c(0xFF2B1C12), c(0xFF1E1915)), breath = 0x26,
        glass = c(0x99302823), glassEdge = c(0x1CFFFFFF), shell = c(0x08FFFFFF), shellEdge = c(0x17FFFFFF), bar = c(0xD617130F),
        ink = c(0xFFF3ECE6), ink2 = c(0xFFC4B7AC), ink3 = c(0xFF9A8B7D), accent = c(0xFFF0A64A),
        navy = c(0xFFF6C88A), onNavy = c(0xFF141110), chrome = null, onChrome = c(0xFF141110),
        ok = c(0xFF6CC79A), warn = c(0xFFF0B45E), bad = c(0xFFF2857A), sheen = 0.22f, pair = "graphite")

    val all = listOf(glacier, night, graphite, midnight, moss, ember)
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: glacier

    /** Accent override: hand-picked, each reads on both light and dark grounds. */
    val accents = listOf(
        "Glacier" to c(0xFF3FB6EC), "Lagoon" to c(0xFF22A39A), "Mint" to c(0xFF5FC495), "Amber" to c(0xFFE6A23C),
        "Coral" to c(0xFFE8735A), "Rose" to c(0xFFDE7393), "Lilac" to c(0xFF9F8AD8), "Steel" to c(0xFF7C92AB))

    val fonts = listOf(
        FontSpec("system", "System", "sans-serif", 400, 500),
        FontSpec("serif", "Serif", "serif", 400, 600),
        FontSpec("condensed", "Condensed", "sans-serif-condensed", 400, 500),
        FontSpec("medium", "Medium", "sans-serif", 500, 700),
        FontSpec("mono", "Mono", "monospace", 400, 700))
    fun font(id: String?) = fonts.firstOrNull { it.id == id } ?: fonts[0]

    /** Which theme actually shows: his pick, flipped by the phone's dark mode (Glacier pair only, when he asked),
     *  then flipped to its twin when the wallpaper wants the other ink. wallDark null = unknown, keep the theme. */
    fun resolve(themeId: String?, match: Boolean, phoneDark: Boolean, wall: String, autoInk: Boolean, wallDark: Boolean?): ThemeSpec {
        var t = byId(themeId)
        if (match && (t === glacier || t === night)) t = if (phoneDark) night else glacier
        if (wall != Appearance.FROST && autoInk && wallDark != null && wallDark != t.dark) t = byId(t.pair)
        return t
    }

    /** The chrome gradient: the theme's own, or three steps down from the accent. */
    fun chrome(t: ThemeSpec, accent: Int) = t.chrome ?: intArrayOf(accent, mix(accent, 0xFF000000.toInt(), 0.4f), mix(accent, 0xFF000000.toInt(), 0.62f))

    /** Text on the chrome: the theme's choice for its own gradient, else whichever of white or the ground reads better
     *  on the gradient's middle. */
    fun onChrome(t: ThemeSpec, accent: Int): Int {
        if (t.chrome != null) return t.onChrome
        val mid = chrome(t, accent)[1]; val light = 0xFFF4FAFF.toInt()
        return if (contrast(light, mid) >= contrast(t.ground, mid)) light else t.ground
    }

    // ── colour maths (pure) ──
    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a ushr s) and 255) + (((b ushr s) and 255) - ((a ushr s) and 255)) * t).toInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** `top` laid over `under` by its alpha (the result is opaque if under is). */
    fun over(top: Int, under: Int): Int {
        val a = (top ushr 24) / 255f
        fun ch(s: Int) = (((top ushr s) and 255) * a + ((under ushr s) and 255) * (1 - a)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun luminance(c: Int): Double {
        fun lin(v: Int): Double { val s = v / 255.0; return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin((c shr 16) and 255) + 0.7152 * lin((c shr 8) and 255) + 0.0722 * lin(c and 255)
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}

/** Appearance choices, kept in the UI process's "aos" prefs. Every change bumps ap_v, so Home and Settings know to
 *  rebuild when he comes back. */
object Appearance {
    const val FROST = "frost"; const val PHOTO = "photo"; const val SYSTEM = "system"
    const val V = "ap_v"; const val THEME = "ap_theme"; const val ACCENT = "ap_accent"; const val FONT = "ap_font"
    const val WALL = "ap_wall"; const val BLUR = "ap_blur"; const val DIM = "ap_dim"; const val INK = "ap_ink"
    const val PHOTO_DARK = "ap_photo_dark"; const val MATCH = "ap_match"
    const val THEMED_ICONS = "themed_icons"  // REDESIGN phase 2 contract: APPS draws monochrome icons when true
    const val PHOTO_FILE = "wallpaper.jpg"
    const val MIN_DIM = 10  // percent: even a calm photo gets a veil, so the ink always has something to sit on

    fun prefs(c: Context) = c.getSharedPreferences("aos", Context.MODE_PRIVATE)
    fun version(c: Context) = prefs(c).getInt(V, 0)

    fun edit(c: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        val p = prefs(c)
        p.edit().apply(block).putInt(V, p.getInt(V, 0) + 1).apply()
    }

    fun phoneDark(c: Context) = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Is the wallpaper behind the ink dark? Photo: measured when he picked it. Phone wallpaper: Android's own hint
     *  (no permission needed). null = not known. */
    fun wallDark(c: Context, wall: String): Boolean? = when (wall) {
        PHOTO -> prefs(c).takeIf { it.contains(PHOTO_DARK) }?.getBoolean(PHOTO_DARK, false)
        SYSTEM -> try {
            WallpaperManager.getInstance(c).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                ?.let { it.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT == 0 }
        } catch (_: Exception) { null }
        else -> null
    }

    fun theme(c: Context): ThemeSpec {
        val p = prefs(c); val wall = p.getString(WALL, FROST) ?: FROST
        val auto = p.getString(INK, "auto") == "auto"
        return Themes.resolve(p.getString(THEME, null), p.getBoolean(MATCH, false), phoneDark(c), wall, auto,
            if (wall != FROST && auto) wallDark(c, wall) else null)
    }
}
