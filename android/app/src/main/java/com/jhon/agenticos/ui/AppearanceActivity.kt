package com.jhon.agenticos.ui

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import com.jhon.agenticos.R
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** Appearance, in the same iOS Settings look as SetupActivity: theme cards (each a little Home in that theme), the
 *  accent, the wallpaper with a live preview and its blur and dim, and the font. Every change saves at once (bumping
 *  Appearance.version), and the whole screen cross-fades into the new look; Home and Settings rebuild on return. */
class AppearanceActivity : Activity() {
    private lateinit var look: Look
    private val prefs by lazy { Appearance.prefs(this) }
    private lateinit var scroll: ScrollView
    private var themesRow: HorizontalScrollView? = null
    private var first = true

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        pushTransition()
        first = state == null
        render(0, 0)
    }

    /** The whole screen, built from the saved choices. y / x: scroll positions to land on (a rebuild keeps his place). */
    private fun render(y: Int, x: Int) {
        look = Look(this)
        val wall = prefs.getString(Appearance.WALL, Appearance.FROST) ?: Appearance.FROST
        val chosen = Themes.byId(prefs.getString(Appearance.THEME, null))
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val big = look.text("Appearance", 34f, look.ink, true).apply { letterSpacing = -0.025f; setPadding(look.px(20), 0, look.px(20), look.px(4)) }
        column.addView(big)
        val cascade = mutableListOf<View>()
        fun add(v: View, lp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2)) { column.addView(v, lp); cascade += v }
        fun header(s: String) = add(look.text(s, 13f, look.ink3).apply { setPadding(look.px(32), look.px(22), look.px(32), look.px(7)) })
        fun footer(s: String) = add(look.text(s, 13f, look.ink3).apply { setPadding(look.px(32), look.px(7), look.px(32), 0) })
        fun card(vararg rows: View) = add(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = look.glassCard(16); clipToOutline = true
            rows.forEachIndexed { i, r ->
                if (i > 0) addView(View(context).apply { setBackgroundColor(look.hairline) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = look.px(16) })
                addView(r)
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = look.px(16); rightMargin = look.px(16) })

        // ── theme: a little Home in each, a radio under it like iOS's Light / Dark ──
        header("Theme")
        val strip = LinearLayout(this).apply { setPadding(look.px(10), look.px(16), look.px(10), look.px(12)) }
        val override = prefs.getInt(Appearance.ACCENT, 0)
        for (t in Themes.all) strip.addView(themeCard(t, if (override != 0) override else t.accent, t === chosen) {
            change { putString(Appearance.THEME, t.id) }
        })
        val themes = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) }
        themesRow = themes
        if (chosen === Themes.glacier || chosen === Themes.night) {
            card(themes, switchRow("Match phone dark mode", prefs.getBoolean(Appearance.MATCH, false)) { on -> change { putBoolean(Appearance.MATCH, on) } })
            footer("Glacier by day, Night Glacier when the phone is in dark mode.")
        } else card(themes)

        // ── accent ──
        header("Accent")
        card(swatches(override))
        val accentName = Themes.accents.firstOrNull { it.second == override }?.first ?: "${look.theme.name}'s own"
        footer("$accentName. The core, switches and selected things wear it.")

        // ── wallpaper: live preview, the three kinds, then what tunes the chosen one ──
        header("Wallpaper")
        val preview = HomePreview(this, look, look.theme, look.accent, detail = true).apply {
            this.wall = wall; blur = prefs.getInt(Appearance.BLUR, 16); dim = prefs.getInt(Appearance.DIM, 30)
            system = systemColours()
            if (wall == Appearance.PHOTO) Wallpaper.load(context) { photo = it }
            contentDescription = "Preview of Home in this look"
        }
        val stage = FrameLayout(this).apply {
            setPadding(0, look.px(18), 0, look.px(14))
            addView(preview, FrameLayout.LayoutParams(look.px(176), look.px(362), Gravity.CENTER_HORIZONTAL))
        }
        val kinds = listOf(Appearance.FROST, Appearance.PHOTO, Appearance.SYSTEM)
        val pick = Segmented(this, look, listOf("Frost", "My photo", "Phone"), kinds.indexOf(wall)) { i ->
            when {
                kinds[i] == wall -> {}
                kinds[i] == Appearance.PHOTO && !Wallpaper.file(this).exists() -> pickPhoto()
                else -> change { putString(Appearance.WALL, kinds[i]) }
            }
        }
        val rows = mutableListOf<View>(stage, pad(pick))
        if (wall == Appearance.PHOTO) rows += sliderRow("Blur", prefs.getInt(Appearance.BLUR, 16) / 40f,
            { preview.blur = (it * 40).toInt(); preview.invalidate() }) { change { putInt(Appearance.BLUR, (it * 40).toInt()) } }
        if (wall != Appearance.FROST) {
            rows += sliderRow("Dim", (prefs.getInt(Appearance.DIM, 30) - Appearance.MIN_DIM) / 70f,
                { preview.dim = Appearance.MIN_DIM + (it * 70).toInt(); preview.invalidate() }) {
                change { putInt(Appearance.DIM, Appearance.MIN_DIM + (it * 70).toInt()) } }
            val auto = prefs.getString(Appearance.INK, "auto") == "auto"
            rows += labelled("Text", Segmented(this, look, listOf("From wallpaper", "Theme"), if (auto) 0 else 1) { i ->
                if ((i == 0) != auto) change { putString(Appearance.INK, if (i == 0) "auto" else "theme") }
            })
        }
        if (wall == Appearance.PHOTO) rows += actionRow("Choose another photo") { pickPhoto() }
        card(*rows.toTypedArray())
        footer(when (wall) {
            Appearance.PHOTO -> "Your photo stays on this phone. With text from wallpaper, the ink turns light or dark to suit it."
            Appearance.SYSTEM -> "Shows the wallpaper set in One UI behind Home. Other screens keep the frost."
            else -> "Soft light in the theme's colours, the dashboard's frost."
        })

        // ── font: each name set in its own face ──
        header("Font")
        card(*Themes.fonts.map { f -> fontRow(f, f === look.font) { change { putString(Appearance.FONT, f.id) } } }.toTypedArray())
        footer("Text across Home and these screens.")

        // ── icons: the apps' own one-colour icons in the theme's ink (APPS draws them) ──
        header("Icons")
        card(switchRow("Themed icons", prefs.getBoolean(Appearance.THEMED_ICONS, false)) { on -> change { putBoolean(Appearance.THEMED_ICONS, on) } })
        footer("Apps in one ink colour. Apps without a themed icon keep theirs.")
        column.addView(View(this), LinearLayout.LayoutParams(-1, look.px(8)))

        scroll = ScrollView(this).apply { clipToPadding = false; addView(column) }
        val fill = ColorDrawable(look.bar)
        val hair = View(this).apply { setBackgroundColor(look.hairline) }
        val small = look.text("Appearance", 17f, look.ink, true).apply { gravity = Gravity.CENTER }
        val back = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumWidth = look.px(48); setPadding(look.px(6), 0, look.px(12), 0)
            contentDescription = "Back to Settings"
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_set_back); imageTintList = ColorStateList.valueOf(look.navy) },
                LinearLayout.LayoutParams(look.px(24), look.px(24)))
            addView(look.text("Settings", 17f, look.navy))
            look.press(this, 0.92f); setOnClickListener { finish() }
        }
        val bar = FrameLayout(this).apply {
            background = fill
            addView(hair, FrameLayout.LayoutParams(-1, 1, Gravity.BOTTOM))
            addView(small, FrameLayout.LayoutParams(-2, look.px(52), Gravity.CENTER_HORIZONTAL))
            addView(back, FrameLayout.LayoutParams(-2, look.px(48), Gravity.CENTER_VERTICAL).apply { leftMargin = look.px(4) })
        }
        setContentView(FrameLayout(this).apply {
            addView(FrostView(context, look))
            addView(scroll)
            addView(bar, FrameLayout.LayoutParams(-1, -2))
            setOnApplyWindowInsetsListener { _, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                bar.setPadding(i.left, i.top, i.right, 0)
                scroll.setPadding(i.left, i.top + look.px(52), i.right, i.bottom + look.px(32))
                WindowInsets.CONSUMED
            }
        })
        look.bars(window)

        // iOS large-title collapse, 1:1 with the scroll (as SetupActivity)
        fun collapse(y: Int) {
            val span = big.height.coerceAtLeast(1).toFloat()
            val t = (y / span).coerceIn(0f, 1f)
            big.alpha = 1 - t
            fill.alpha = (t * 255).toInt(); hair.alpha = t
            val s = ((y - span * 0.6f) / (span * 0.4f)).coerceIn(0f, 1f)
            small.alpha = s; small.translationY = (1 - s) * look.px(6)
        }
        scroll.setOnScrollChangeListener { _, _, sy, _, _ -> collapse(sy) }
        // land where he was (a rebuild), once the new tree has its size
        scroll.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                scroll.viewTreeObserver.removeOnPreDrawListener(this)
                scroll.scrollTo(0, y); themes.scrollTo(x, 0); collapse(scroll.scrollY)
                return true
            }
        })

        if (first && !reducedMotion(this)) cascade.forEachIndexed { i, v ->
            v.alpha = 0f; v.translationY = look.px(20).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(90L + i * 35).setDuration(620).setInterpolator(SpringCurve(0.8f)).start()
        }
        first = false
    }

    /** Save, then cross-fade the whole screen into the new look: a picture of the old one fades off the rebuilt one
     *  (iOS's dark mode switch). Taps during the fade start a fresh one from whatever is on screen. */
    private fun change(edit: SharedPreferences.Editor.() -> Unit) {
        Appearance.edit(this, edit)
        val decor = window.decorView
        val y = scroll.scrollY; val x = themesRow?.scrollX ?: 0
        if (decor.width == 0 || decor.height == 0) { render(y, x); return }
        val shot = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, shot, { result ->
            if (isDestroyed) return@request
            render(y, x)
            if (result != PixelCopy.SUCCESS) return@request
            val cover = BitmapDrawable(resources, shot).apply { setBounds(0, 0, shot.width, shot.height) }
            decor.overlay.clear(); decor.overlay.add(cover)
            Spring(1f, 0.004f) { cover.alpha = (it * 255).toInt().coerceIn(0, 255); decor.invalidate() }.apply {
                onRest = { decor.overlay.remove(cover) }
                to(0f, response = if (reducedMotion(this@AppearanceActivity)) 0.22f else 0.42f, damping = 1f)
            }
        }, Handler(Looper.getMainLooper()))
    }

    // ── photo: Android's photo picker (no permission), copied in, measured once ──

    private fun pickPhoto() {
        try { startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), PICK) } catch (_: Exception) {}
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")  // the result API needs AndroidX; this app has none
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (req != PICK || res != RESULT_OK || uri == null) return
        val dm = resources.displayMetrics
        val main = Handler(Looper.getMainLooper())
        Thread {
            val lum = try { copyIn(uri, max(dm.widthPixels, dm.heightPixels)) } catch (_: Throwable) { null }
            main.post {
                if (isDestroyed) return@post
                if (lum == null) return@post  // unreadable picture: nothing changes
                Wallpaper.forget()
                // dark when white text would out-contrast black on its average (luminance under ~0.18)
                change { putString(Appearance.WALL, Appearance.PHOTO); putBoolean(Appearance.PHOTO_DARK, lum < 0.179); putFloat(Appearance.PHOTO_LUM, lum.toFloat()) }
            }
        }.start()
    }

    /** Decodes his picture upright (ImageDecoder applies the camera's rotation), about screen-sized, saves it as our
     *  own JPEG (temp file, then rename, so a half-written file never shows) and returns its average luminance. */
    private fun copyIn(uri: Uri, longest: Int): Double {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            var s = 1
            while (max(info.size.width, info.size.height) / (s * 2) >= longest) s *= 2
            d.setTargetSampleSize(s)
        }
        val tmp = File(filesDir, Appearance.PHOTO_FILE + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (!tmp.renameTo(Wallpaper.file(this))) throw IllegalStateException("rename")
        return Appearance.lum(bmp)
    }

    private fun systemColours(): IntArray? = try {
        WallpaperManager.getInstance(this).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.let { w ->
            intArrayOf(w.primaryColor.toArgb(), (w.secondaryColor ?: w.primaryColor).toArgb(), (w.tertiaryColor ?: w.secondaryColor ?: w.primaryColor).toArgb())
        }
    } catch (_: Exception) { null }

    // ── rows ──

    private fun themeCard(t: ThemeSpec, accent: Int, on: Boolean, pick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(look.px(6), 0, look.px(6), 0)
        addView(HomePreview(context, look, t, accent, detail = false), LinearLayout.LayoutParams(look.px(92), look.px(190)))
        addView(look.text(t.name, 13f, if (on) look.ink else look.ink2, on).apply { gravity = Gravity.CENTER; setPadding(0, look.px(8), 0, look.px(4)) })
        addView(Radio(context, look, on), LinearLayout.LayoutParams(look.px(24), look.px(24)))
        contentDescription = "${t.name}, ${if (t.dark) "dark" else "light"}${if (on) ", selected" else ""}"
        isClickable = true; look.press(this, 0.95f)
        setOnClickListener { if (!on) pick() }
    }

    /** Theme's own accent first, then the eight; two rows of 48 dp targets. */
    private fun swatches(current: Int): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(look.px(8), look.px(10), look.px(8), look.px(10)) }
        val all = listOf(0 to "${look.theme.name}'s own") + Themes.accents.map { it.second to it.first }
        all.chunked(5).forEach { line ->
            grid.addView(LinearLayout(this).apply {
                for ((col, name) in line) addView(Swatch(context, look, if (col == 0) look.theme.accent else col, col == 0, col == current).apply {
                    contentDescription = name + if (col == current) ", selected" else ""
                    isClickable = true; look.press(this, 0.88f)
                    setOnClickListener { if (col != current) change { putInt(Appearance.ACCENT, col) } }
                }, LinearLayout.LayoutParams(0, look.px(56), 1f))
                repeat(5 - line.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            })
        }
        return grid
    }

    private fun pressable(v: LinearLayout, act: () -> Unit) = v.apply {
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(look.pressed))
            setExitFadeDuration(250)
        }
        isClickable = true; look.press(this, 0.985f); setOnClickListener { act() }
    }

    private fun rowBase() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = look.px(52); setPadding(look.px(16), look.px(8), look.px(14), look.px(8))
    }

    private fun switchRow(title: String, on: Boolean, flip: (Boolean) -> Unit): View {
        val sw = Switch(this).apply {
            isChecked = on; contentDescription = title
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(look.accent, look.inkA(70)))
            setOnCheckedChangeListener { v, now -> v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); flip(now) }
        }
        return rowBase().apply {
            addView(look.text(title, 17f, look.ink), LinearLayout.LayoutParams(0, -2, 1f))
            addView(sw)
        }
    }

    private fun actionRow(title: String, act: () -> Unit) = pressable(rowBase().apply {
        addView(look.text(title, 17f, look.navy), LinearLayout.LayoutParams(0, -2, 1f))
    }, act)

    private fun fontRow(f: FontSpec, on: Boolean, pick: () -> Unit) = pressable(rowBase().apply {
        addView(look.text(f.name, 17f, look.ink).apply { typeface = Look.face(context, f, false) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_appearance_check); imageTintList = ColorStateList.valueOf(look.accent)
            visibility = if (on) View.VISIBLE else View.INVISIBLE
        }, LinearLayout.LayoutParams(look.px(22), look.px(22)))
        contentDescription = f.name + if (on) ", selected" else ""
    }) { if (!on) pick() }

    private fun sliderRow(title: String, value: Float, move: (Float) -> Unit, done: (Float) -> Unit) = rowBase().apply {
        addView(look.text(title, 17f, look.ink), LinearLayout.LayoutParams(look.px(64), -2))
        addView(GlassSlider(context, look, value, title, move, done), LinearLayout.LayoutParams(0, look.px(48), 1f))
    }

    private fun labelled(title: String, v: View) = rowBase().apply {
        addView(look.text(title, 17f, look.ink), LinearLayout.LayoutParams(look.px(64), -2))
        addView(v, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun pad(v: View) = FrameLayout(this).apply { setPadding(look.px(16), 0, look.px(16), look.px(14)); addView(v) }

    @Suppress("DEPRECATION")  // overridePendingTransition: only the API 33 path
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(R.anim.set_pop_in, R.anim.set_pop_out)
    }

    /** iOS push, as SetupActivity: slides in from the right over Settings; back reverses it. */
    @Suppress("DEPRECATION")
    private fun pushTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.set_push_in, R.anim.set_push_out)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.set_pop_in, R.anim.set_pop_out)
        } else overridePendingTransition(R.anim.set_push_in, R.anim.set_push_out)
    }

    private companion object { const val PICK = 7 }
}

/** A little Home in a theme: frost or wallpaper, the health chip, the glance, the accent's dot wave, a "needs you"
 *  glass panel with the chrome button, the double-bezel dock. detail = real words (the live preview); otherwise text
 *  is drawn as soft bars (the theme cards). Laid out in units of 1/100 of its width. */
private class HomePreview(c: Context, private val look: Look, private val t: ThemeSpec, private val accent: Int,
                          private val detail: Boolean) : View(c) {
    var wall = Appearance.FROST
    var photo: Bitmap? = null; set(v) { field = v; soft = null; invalidate() }
    var system: IntArray? = null
    var blur = 16; set(v) { if (field != v) soft = null; field = v }
    var dim = 30
    private var soft: Bitmap? = null           // the photo, cropped and shrunk: drawn back up, it reads as blurred
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = Path(); private val r = RectF(); private val src = Rect(); private val dst = Rect()
    private val chrome = Themes.chrome(t, accent); private val onChrome = Themes.onChrome(t, accent)
    private val date = DateFormat.format("EEEE d MMMM", System.currentTimeMillis()).toString()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { soft = null }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val u = w / 100f
        clip.reset(); r.set(0f, 0f, w, h); clip.addRoundRect(r, 13 * u, 13 * u, Path.Direction.CW)
        cv.save(); cv.clipPath(clip)
        background(cv, w, h, u)

        // top row: the health chip, the gear
        pill(cv, 6 * u, 7 * u, 30 * u, 8 * u, t.glass, t.glassEdge)
        words(cv, "All good", 10 * u, 12.4f * u, 3.6f * u, t.ok, true, 22 * u)
        pill(cv, 86 * u, 7 * u, 8 * u, 8 * u, t.glass, t.glassEdge)
        p.style = Paint.Style.FILL; p.shader = null; p.color = t.ink2; cv.drawCircle(90 * u, 11 * u, 1.6f * u, p)

        // the glance
        words(cv, date, 7 * u, 25 * u, 5.4f * u, t.ink, true, 64 * u)
        words(cv, "Core hours 1 AM to 4 AM", 7 * u, 31 * u, 3.6f * u, t.ink2, false, 50 * u)

        // the core: the dashboard's dot wave in the accent, nearer rows bigger and brighter
        val cy = h * 0.44f
        for (row in 0 until 6) {
            val near = row / 5f
            val y = cy - 7 * u + row * (2.6f + row * 0.5f) * u
            for (col in -7..7) {
                val edge = 1f - abs(col) / 7.5f
                val wave = 0.6f + 0.4f * kotlin.math.sin(col * 0.7f + row * 0.9f)
                p.color = look.withAlpha(Themes.mix(accent, t.ink3, 0.25f * (1 - near)), (255 * (0.25f + 0.75f * near) * edge * wave).toInt())
                cv.drawCircle(50 * u + col * (4.2f + near * 1.4f) * u, y, (0.5f + near * 0.55f) * u, p)
            }
        }

        // a "needs you" panel in liquid glass, with the one chrome action
        val top = h * 0.58f
        r.set(6 * u, top, 94 * u, top + 30 * u)
        p.color = t.glass; cv.drawRoundRect(r, 7 * u, 7 * u, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = max(1f, 0.35f * u); p.color = t.glassEdge
        cv.drawRoundRect(r, 7 * u, 7 * u, p); p.style = Paint.Style.FILL
        words(cv, "Waiting on you", 11 * u, top + 9 * u, 4.4f * u, t.ink, true, 50 * u)
        words(cv, "Nothing right now", 11 * u, top + 15 * u, 3.6f * u, t.ink2, false, 40 * u)
        r.set(62 * u, top + 19.5f * u, 89 * u, top + 26.5f * u)
        p.shader = LinearGradient(r.left, r.top, r.right, r.bottom, chrome, null, Shader.TileMode.CLAMP)
        cv.drawRoundRect(r, 3.5f * u, 3.5f * u, p); p.shader = null
        words(cv, "Open", r.centerX() - 4.2f * u, r.centerY() + 1.2f * u, 3.4f * u, onChrome, true, 9 * u)

        // the dock: a glass plate in a glass tray, four apps and the accent's Apps dots
        r.set(4 * u, h - 27 * u, 96 * u, h - 5 * u)
        pill(cv, r.left, r.top, r.width(), r.height(), t.shell, t.shellEdge, 8 * u)
        pill(cv, r.left + 1.6f * u, r.top + 1.6f * u, r.width() - 3.2f * u, r.height() - 3.2f * u, t.glass, t.glassEdge, 6.4f * u)
        val tile = 12 * u; val gap = (r.width() - 5 * tile) / 6
        for (k in 0 until 5) {
            val x = r.left + gap + k * (tile + gap); val y = r.centerY() - tile / 2
            if (k < 4) { p.color = Themes.mix(t.ground, t.ink, if (t.dark) 0.22f + k * 0.05f else 0.12f + k * 0.04f); cv.drawRoundRect(x, y, x + tile, y + tile, 3.2f * u, 3.2f * u, p) }
            else {
                pill(cv, x, y, tile, tile, t.glass, t.glassEdge, 3.2f * u)
                p.color = accent
                for (i in 1..3) for (j in 1..3) cv.drawCircle(x + tile * i / 4f, y + tile * j / 4f, 0.75f * u, p)
            }
        }
        cv.restore()
        p.style = Paint.Style.STROKE; p.strokeWidth = max(1f, 0.3f * u); p.color = look.hairline  // the card's edge on the settings glass
        r.set(0f, 0f, w, h); r.inset(p.strokeWidth / 2, p.strokeWidth / 2); cv.drawRoundRect(r, 13 * u, 13 * u, p)
        p.style = Paint.Style.FILL
    }

    private fun background(cv: Canvas, w: Float, h: Float, u: Float) {
        val veil = look.withAlpha(t.ground, dim.coerceIn(Appearance.MIN_DIM, 90) * 255 / 100)
        val b = photo
        if (wall == Appearance.PHOTO && b != null) {
            val s = soft ?: shrink(b, w, h, u).also { soft = it }
            dst.set(0, 0, width, height); cv.drawBitmap(s, null, dst, p)
            cv.drawColor(veil); return
        }
        val sys = system
        if (wall == Appearance.SYSTEM && sys != null) {
            cv.drawColor(sys[0] or (0xFF shl 24))
            blob(cv, 10 * u, 0f, 90 * u, sys[1]); blob(cv, w, h, 100 * u, sys[2])
            cv.drawColor(veil); return
        }
        cv.drawColor(t.ground)
        blob(cv, 8 * u, 0f, 75 * u, t.mesh[0]); blob(cv, w, h, 85 * u, t.mesh[1]); blob(cv, 70 * u, h * 0.2f, 50 * u, t.mesh[2])
        blob(cv, 15 * u, h * 0.55f, 45 * u, look.withAlpha(accent, t.breath))
    }

    /** Crop to this card's shape, then shrink by the blur: drawn back up with filtering it softens like the real blur
     *  (which on Home is blur dp at full screen width, so scaled to this card's width). */
    private fun shrink(b: Bitmap, w: Float, h: Float, u: Float): Bitmap {
        Wallpaper.crop(b.width, b.height, width, height, src)
        val screen = resources.displayMetrics.widthPixels.toFloat()
        val radius = look.px(blur) * w / screen
        val k = 1f + radius / 1.5f
        val sw = max(2, (w / k).toInt()); val sh = max(2, (h / k).toInt())
        val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, src, Rect(0, 0, sw, sh), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun blob(cv: Canvas, x: Float, y: Float, rad: Float, color: Int) {
        p.shader = RadialGradient(x, y, max(rad, 1f), color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        cv.drawCircle(x, y, rad, p); p.shader = null
    }

    private fun pill(cv: Canvas, x: Float, y: Float, w: Float, h: Float, fill: Int, edge: Int, rad: Float = h / 2) {
        r.set(x, y, x + w, y + h)
        p.style = Paint.Style.FILL; p.color = fill; cv.drawRoundRect(r, rad, rad, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = max(1f, width / 300f); p.color = edge; cv.drawRoundRect(r, rad, rad, p)
        p.style = Paint.Style.FILL
    }

    /** Real words in the chosen font, or (theme cards) a soft bar the width the words would take. */
    private fun words(cv: Canvas, s: String, x: Float, base: Float, size: Float, color: Int, bold: Boolean, barW: Float) {
        p.shader = null; p.style = Paint.Style.FILL
        if (detail) {
            p.textSize = size; p.typeface = if (bold) look.medium else look.sans; p.color = color
            cv.drawText(s, x, base, p); p.typeface = Typeface.DEFAULT
        } else {
            p.color = look.withAlpha(color, if (bold) 230 else 150)
            val hgt = size * 0.62f
            cv.drawRoundRect(x, base - hgt, x + barW, base, hgt / 2, hgt / 2, p)
        }
    }
}

/** iOS's selection circle: a ring when off, filled with the accent and a check when on. */
private class Radio(c: Context, private val look: Look, private val on: Boolean) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val check = Path()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(cv: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val rad = minOf(width, height) / 2f - look.px(1)
        if (!on) { p.style = Paint.Style.STROKE; p.strokeWidth = look.px(1) * 1.5f; p.color = look.inkA(90); cv.drawCircle(cx, cy, rad, p); return }
        p.style = Paint.Style.FILL; p.color = look.accent; cv.drawCircle(cx, cy, rad, p)
        check.reset(); check.moveTo(cx - rad * 0.42f, cy + rad * 0.02f); check.lineTo(cx - rad * 0.1f, cy + rad * 0.34f); check.lineTo(cx + rad * 0.45f, cy - rad * 0.3f)
        p.style = Paint.Style.STROKE; p.strokeWidth = rad * 0.2f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        p.color = if (Themes.contrast(Color.WHITE, look.accent) >= Themes.contrast(look.ground, look.accent)) Color.WHITE else look.ground
        cv.drawPath(check, p)
    }
}

/** An accent swatch: a filled circle; the theme's own is a smaller dot inside a ring; the chosen one gets an outer
 *  ring in its colour with a gap, as iOS does. */
private class Swatch(c: Context, private val look: Look, private val color: Int, private val own: Boolean, private val on: Boolean) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val rad = look.px(15).toFloat()
        p.style = Paint.Style.FILL; p.color = color
        if (own) {
            cv.drawCircle(cx, cy, rad * 0.5f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = look.px(2).toFloat(); p.color = look.inkA(80); cv.drawCircle(cx, cy, rad - look.px(1), p)
        } else cv.drawCircle(cx, cy, rad, p)
        if (on) { p.style = Paint.Style.STROKE; p.strokeWidth = look.px(2).toFloat(); p.color = if (own) look.ink2 else color; cv.drawCircle(cx, cy, rad + look.px(5), p) }
    }
}

/** A segmented control: a glass track, the chosen segment lifted on a lighter plate. 48 dp tall. */
private class Segmented(c: Context, look: Look, labels: List<String>, chosen: Int, pick: (Int) -> Unit) : LinearLayout(c) {
    init {
        background = look.round(look.inkA(18), 12)
        setPadding(look.px(3), look.px(3), look.px(3), look.px(3))
        labels.forEachIndexed { i, s ->
            val on = i == chosen
            addView(look.text(s, 14f, if (on) look.ink else look.ink2, on).apply {
                gravity = Gravity.CENTER; maxLines = 1
                if (on) { background = look.round(if (look.night) look.white(255) else Color.WHITE, 10); elevation = look.px(1).toFloat() }
                contentDescription = s + if (on) ", selected" else ""
                isClickable = true; look.press(this, 0.96f); setOnClickListener { pick(i) }
            }, LayoutParams(0, look.px(42), 1f))
        }
    }
}

/** An iOS slider: thin track, the accent up to a white knob that grows a touch under the finger. Tracks the finger
 *  1:1, ticks at every tenth, reports while moving (live preview) and once on release (save). */
private class GlassSlider(c: Context, private val look: Look, private var value: Float, private val name: String,
                          private val move: (Float) -> Unit, private val done: (Float) -> Unit) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(look.px(3).toFloat(), 0f, look.px(1).toFloat(), look.shade(70)) }
    private var grow = 1f
    private val growS = Spring(1f, 0.002f) { grow = it; invalidate() }
    private val kr = look.px(13).toFloat()

    init { contentDescription = name; isFocusable = true }

    private fun at(x: Float) = ((x - kr) / (width - 2 * kr).coerceAtLeast(1f)).coerceIn(0f, 1f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); tick(); growS.to(1.12f, response = 0.18f); set(at(e.x)) }
            MotionEvent.ACTION_MOVE -> set(at(e.x))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { growS.to(1f, response = 0.35f); done(value) }
        }
        return true
    }

    private fun set(v: Float) {
        if ((v * 10).toInt() != (value * 10).toInt() || (v != value && (v == 0f || v == 1f))) tick()
        value = v; move(v); invalidate()
    }

    override fun onDraw(cv: Canvas) {
        val cy = height / 2f; val x0 = kr; val x1 = width - kr; val x = x0 + (x1 - x0) * value
        val th = look.px(2).toFloat()
        p.color = look.inkA(36); cv.drawRoundRect(x0, cy - th, x1, cy + th, th, th, p)
        p.color = look.accent; cv.drawRoundRect(x0, cy - th, x, cy + th, th, th, p)
        cv.drawCircle(x, cy, kr * grow, knob)
    }

    // TalkBack: a percentage it can step through
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.SeekBar::class.java.name
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT, 0f, 100f, value * 100)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        val step = when (action) { AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 0.1f; AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -0.1f; else -> return super.performAccessibilityAction(action, args) }
        value = (value + step).coerceIn(0f, 1f); move(value); done(value); invalidate()
        return true
    }
}
