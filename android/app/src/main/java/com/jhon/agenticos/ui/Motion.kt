package com.jhon.agenticos.ui

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.SparseArray
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.Interpolator
import android.widget.Toast
import android.window.SplashScreen
import java.util.WeakHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A spring's step response as an interpolator, for motion nobody's finger is on (cards arriving, the home screen
 *  settling): same feel as Spring, but runs on a ViewPropertyAnimator. damping < 1 overshoots a little. */
class SpringCurve(private val damping: Float = 0.8f) : Interpolator {  // Interpolator: view Animations take it too
    // stiff enough that the spring has settled (to under 1%) when the animator's time runs out
    private val w = if (damping < 1f) 5f / damping else 7f
    override fun getInterpolation(t: Float): Float {
        if (t >= 1f) return 1f
        if (damping >= 1f) return 1 - (1 + w * t) * exp(-w * t)
        val wd = w * sqrt(1 - damping * damping)
        return 1 - exp(-damping * w * t) * (cos(wd * t) + damping * w / wd * sin(wd * t))
    }
}

/** Pure parts of launching, unit tested. */
internal object LaunchMath {
    /** The rect a launch grows from, relative to the tapped view: as wide as the view, the screen's aspect ratio,
     *  centred on it, so the app scales up evenly instead of starting squashed into the icon's square (AOSP's
     *  scale-up scales width and height separately). x, y, w, h. */
    fun startRect(vw: Int, vh: Int, sw: Int, sh: Int): IntArray {
        if (vw <= 0 || vh <= 0 || sw <= 0 || sh <= 0) return intArrayOf(0, 0, max(vw, 0), max(vh, 0))
        val h = (vw.toLong() * sh / sw).toInt().coerceAtLeast(1)
        return intArrayOf(0, (vh - h) / 2, vw, h)
    }
}

/** Open an app the iPhone way: it grows out of the icon that was tapped, its splash showing the app's icon. A launch
 *  that fails says so in a short toast instead of doing nothing. */
fun Context.launchFrom(v: View, intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val opts = try {
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        intent.sourceBounds = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        val r = LaunchMath.startRect(v.width, v.height, v.rootView.width, v.rootView.height)
        ActivityOptions.makeScaleUpAnimation(v, r[0], r[1], r[2], r[3])
            .setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_ICON).toBundle()
    } catch (_: Exception) { null }
    try { startActivity(intent, opts); return } catch (_: Exception) {}
    try { startActivity(intent); return } catch (_: Exception) {}  // some targets refuse the options, not the launch
    try { Toast.makeText(this, "Couldn't open that", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
}

/** Light tick on touch-down, the same frame the press shows (Apple's harmony rule). SEGMENT_TICK (API 34) maps to a
 *  real tick; CLOCK_TICK maps to a texture tick that "may not make any vibration". Through performHapticFeedback, so
 *  his touch-feedback setting still applies. */
fun View.tick(): Boolean =
    performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)

/** At most one pass per gap (ring crossings while a ring spins fast). Pure, unit tested. */
internal class RateGate(private val gapMs: Long) {
    private var last = Long.MIN_VALUE / 2
    fun pass(now: Long): Boolean { if (now - last < gapMs) return false; last = now; return true }
}

/** Signature haptics (PLAN-PRODUCTION.md P1 3): composition primitives when the vibrator has all of them (checked
 *  once, on the haptics thread), played off the touch path with touch usage so the system's touch-feedback setting
 *  and intensity apply; otherwise the closest HapticFeedbackConstants. Call from the main thread. */
object Haptics {
    private val crossGate = RateGate(40)
    @Volatile private var prims: Boolean? = null    // null until the one check has run
    private var worker: Handler? = null
    @Volatile private var vib: Vibrator? = null
    private val fx = arrayOfNulls<VibrationEffect>(3) // haptics thread only

    /** A ring settles on a slot. */
    fun snap(v: View) { if (!play(v, SNAP)) v.tick() }

    /** A spinning ring passes a slot: the lightest tick, at most every 40 ms. */
    fun crossing(v: View) {
        if (!crossGate.pass(SystemClock.uptimeMillis())) return
        if (!play(v, CROSS)) v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    }

    /** A "needs you" panel arrives: a quick rise, then a click. */
    fun arrive(v: View) { if (!play(v, ARRIVE)) v.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }

    fun confirm(v: View) = v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    fun reject(v: View) = v.performHapticFeedback(HapticFeedbackConstants.REJECT)

    /** true when the effect went to the haptics thread; false = the caller plays the fallback now. */
    private fun play(v: View, which: Int): Boolean {
        if (!v.isHapticFeedbackEnabled || prims == false) return false
        val h = worker ?: start(v.context.applicationContext) ?: return false
        if (prims != true) return false  // the one check is still running: this press falls back
        h.post {
            try {
                val e = fx[which] ?: compose(which).also { fx[which] = it }
                vib?.vibrate(e, TOUCH)
            } catch (_: Throwable) {}
        }
        return true
    }

    private fun start(c: Context): Handler? = try {
        // one small thread for the process's lifetime: vibrator calls are binder calls, kept off the touch path
        val t = HandlerThread("haptics").apply { start() }
        Handler(t.looper).also { h ->
            worker = h
            h.post {
                prims = try {
                    val v = c.getSystemService(VibratorManager::class.java)?.defaultVibrator
                    vib = v
                    v != null && v.hasVibrator() && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK,
                        VibrationEffect.Composition.PRIMITIVE_LOW_TICK, VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                        VibrationEffect.Composition.PRIMITIVE_CLICK)
                } catch (_: Throwable) { false }
            }
        }
    } catch (_: Throwable) { prims = false; null }

    private fun compose(which: Int): VibrationEffect = VibrationEffect.startComposition().apply {
        when (which) {
            SNAP -> addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f)
            CROSS -> addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.3f)
            else -> {
                addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.4f)
                addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, 40)
            }
        }
    }.compose()

    private const val SNAP = 0
    private const val CROSS = 1
    private const val ARRIVE = 2
    private val TOUCH = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
}

/** Pure parts of the blur, unit tested. */
internal object BlurMath {
    /** r px in whole steps of `step` px (half a dp): 0 = no blur. One UI's method: a few cached radii, not a new
     *  effect for every fractional pixel. */
    fun steps(r: Float, step: Float): Int = if (step <= 0f || !(r > 0f)) 0 else Math.round(r / step)

    /** Fewer levels as a blur grows: none under 2 steps (a blur under a dp can't be seen, and spring tails sit
     *  there for many frames), whole steps up to 8, then each level about 20% past the last (10, 12, 14, 17, 20, 24,
     *  29, 35, 42, 50, 60...), rounded to the nearest. A moving blur re-renders its view only when the level changes,
     *  so a 28 dp push-back blurs again about 18 times instead of 56; levels 20% apart don't show in motion. */
    fun coarse(n: Int): Int {
        if (n < 2) return 0
        if (n <= 8) return n
        var lo = 8
        while (true) {
            val hi = maxOf(lo + 1, Math.round(lo * 1.2f))
            if (n < hi) return if (n - lo < hi - n) lo else hi
            lo = hi
        }
    }
}

/** Shared blur state: one RenderEffect per half-dp radius, the radius each view shows, and battery saver (asked at
 *  most once a second). Main thread. */
internal object Blur {
    private val cache = SparseArray<RenderEffect>()
    private var cacheStep = 0f
    private val shown = WeakHashMap<View, Int>()
    private var saverAt = Long.MIN_VALUE / 2
    private var saver = false

    fun step(c: Context) = c.resources.displayMetrics.density * 0.5f

    fun effect(steps: Int, step: Float): RenderEffect? {
        if (steps <= 0) return null
        if (step != cacheStep) { cache.clear(); cacheStep = step }
        cache[steps]?.let { return it }
        val r = steps * step
        return RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP).also { cache.put(steps, it) }
    }

    /** A blur of r px that ignores battery saver (a static backdrop, blurred once). */
    fun of(c: Context, r: Float): RenderEffect? { val s = step(c); return effect(BlurMath.steps(r, s), s) }

    fun saver(c: Context): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - saverAt > 1000) {
            saverAt = now
            saver = try { c.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true } catch (_: Exception) { false }
        }
        return saver
    }

    fun apply(v: View, r: Float) {
        val s = step(v.context)
        val n = if (saver(v.context)) 0 else BlurMath.coarse(BlurMath.steps(r, s))  // battery saver: the caller's scale and alpha carry it
        val was = shown[v] ?: 0
        if (n == was) return  // unchanged radius: nothing to re-apply
        if (n == 0) shown.remove(v) else shown[v] = n
        v.setRenderEffect(effect(n, s))
        if ((n == 0) != (was == 0)) v.fastFrames(n > 0)
    }
}

/** A real blur on this view and everything in it (RenderEffect). r = 0 removes it. Cached per half dp, skipped when
 *  the radius hasn't changed, off in battery saver; while blurred the view asks for the high frame rate (API 35). */
fun View.blur(r: Float) = Blur.apply(this, r)

/** Ask for the display's high frame rate while this view animates (API 35: Canvas, alpha and blur animations may
 *  otherwise run at 60 Hz), then give the choice back to the system. */
fun View.fastFrames(on: Boolean) {
    if (Build.VERSION.SDK_INT >= 35) try {
        setRequestedFrameRate(if (on) View.REQUESTED_FRAME_RATE_CATEGORY_HIGH else View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT)
    } catch (_: Exception) {}
}

/** Three box blurs each way (close to a Gaussian of sigma about r) on opaque ARGB pixels, in place. Pure, tested. */
internal object BoxBlur {
    fun blur(px: IntArray, w: Int, h: Int, r: Int) {
        if (r <= 0 || w <= 0 || h <= 0 || px.size < w * h) return
        val tmp = IntArray(w * h)
        repeat(3) { pass(px, tmp, w, h, r); pass(tmp, px, h, w, r) }
    }

    /** Blur each row of src (w wide, h tall) and write it transposed into dst (h wide, w tall): run twice, both
     *  directions are done and the picture is the right way round again. */
    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                a += p ushr 24; rr += p shr 16 and 255; g += p shr 8 and 255; b += p and 255
            }
            for (x in 0 until w) {
                dst[x * h + y] = (a / div shl 24) or (rr / div shl 16) or (g / div shl 8) or (b / div)
                val pin = src[row + min(x + r + 1, w - 1)]; val pout = src[row + max(x - r, 0)]
                a += (pin ushr 24) - (pout ushr 24)
                rr += (pin shr 16 and 255) - (pout shr 16 and 255)
                g += (pin shr 8 and 255) - (pout shr 8 and 255)
                b += (pin and 255) - (pout and 255)
            }
        }
    }
}

/** What sits behind everything, as he chose in Appearance. Frost: soft light in the theme's colours, blurred once,
 *  never moving (DESIGN: the one fixed gradient), so glass over it reads as frosted. It is drawn once into a quarter-
 *  size bitmap, blurred once off the main thread and drawn scaled up (One UI's method), instead of a full-screen
 *  RenderEffect. My photo: his picture, centre-cropped, blurred and veiled toward the ground by his sliders. Phone
 *  wallpaper: clear, with the veil only, when the window shows the wallpaper (Home); other screens fall back to the
 *  frost. */
class FrostView(c: Context, private val look: Look, home: Boolean = false) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val prefs = Appearance.prefs(c)
    private val wall = prefs.getString(Appearance.WALL, Appearance.FROST) ?: Appearance.FROST
    private val dimA = Appearance.dimA(c)
    private val quietA = max(dimA, 204)  // about 80% of the ground: ink reads on any photo
    private var veil = look.withAlpha(look.ground, dimA)

    /** 0 = his own veil, 1 = quiet: the veil deepens so text over the photo reads (apps, widgets, settings). Off Home
     *  it is always quiet; Home raises it as the orbit or the widgets page comes over. Frost is calm already. */
    var focus = 0f
        set(v) {
            val f = v.coerceIn(0f, 1f)
            if (f == field) return
            field = f
            val c = look.withAlpha(look.ground, dimA + ((quietA - dimA) * f).toInt())
            if (c == veil) return  // same alpha step: no redraw (and no glass re-record)
            veil = c
            if (wall != Appearance.FROST) invalidate()
        }
    private var photo: Bitmap? = null
    private var missing = false  // the photo file is gone or unreadable: draw the frost instead
    private val src = Rect(); private val dst = Rect()
    private var baked: Bitmap? = null
    private var bakingFor = ""
    private var sizeKey = ""  // the bake key for this size, built once per size (onDraw allocates nothing)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { sizeKey = "" }

    /** The blur (px) on this view's own drawing: the photo's. The glass (GlassView.kt) blurs its copy by the same. */
    internal var softness = 0f; private set

    init {
        setBackgroundColor(look.ground)
        if (!home) focus = 1f
        if (wall == Appearance.PHOTO) {
            softness = look.px(prefs.getInt(Appearance.BLUR, 16)).toFloat()
            setRenderEffect(Blur.of(c, softness))  // static, so battery saver leaves it alone
            Wallpaper.load(c) { b ->
                photo = b; missing = b == null
                if (missing) { softness = 0f; setRenderEffect(null) }
                invalidate()
            }
        }
    }

    private fun showsWallpaper() = wall == Appearance.SYSTEM &&
        ((context as? android.app.Activity)?.window?.attributes?.flags ?: 0) and android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER != 0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (showsWallpaper()) { setBackgroundColor(Color.TRANSPARENT); softness = 0f; setRenderEffect(null) }
    }

    override fun onDraw(canvas: Canvas) {
        if (showsWallpaper()) { canvas.drawColor(veil); return }
        val b = photo
        if (wall == Appearance.PHOTO && b != null) {
            Wallpaper.crop(b.width, b.height, width, height, src)
            dst.set(0, 0, width, height)
            paint.shader = null
            canvas.drawBitmap(b, src, dst, paint)
            canvas.drawColor(veil)
            return
        }
        if (wall == Appearance.PHOTO && !missing) return  // still decoding: the ground shows for a frame or two
        if (width == 0 || height == 0) return
        if (sizeKey.isEmpty()) sizeKey = Frost.key(look, width, height)
        val key = sizeKey
        val ready = baked?.takeIf { bakingFor == key }
        if (ready != null) {
            paint.shader = null
            dst.set(0, 0, width, height)
            canvas.drawBitmap(ready, null, dst, paint)
            return
        }
        if (bakingFor != key) {
            bakingFor = key
            Frost.bake(look, width, height, key) { bm -> if (bakingFor == key && bm != null) { baked = bm; invalidate() } }
        }
        Frost.drawMesh(canvas, width.toFloat(), height.toFloat(), look, paint)  // the first frame or two, unblurred
    }
}

/** The frost, baked: drawn at a quarter size, blurred once, cached for the process (every screen's FrostView shares
 *  it while the size and theme match). Its one worker thread stops itself when idle. */
internal object Frost {
    val exec = ThreadPoolExecutor(0, 1, 3, TimeUnit.SECONDS, LinkedBlockingQueue()) { r -> Thread(r, "frost-bake") }
    @Volatile private var cached: Pair<String, Bitmap>? = null

    fun key(look: Look, w: Int, h: Int) =
        "$w:$h:${look.ground}:${look.theme.mesh.joinToString()}:${look.accent}:${look.theme.breath}:${look.px(60)}"

    /** done runs on the main thread, with null when it couldn't be baked (the view keeps drawing the mesh). */
    fun bake(look: Look, w: Int, h: Int, key: String, done: (Bitmap?) -> Unit) {
        cached?.takeIf { it.first == key }?.let { done(it.second); return }
        val main = Handler(Looper.getMainLooper())
        try {
            exec.execute {
                val b = try { render(look, w, h) } catch (_: Throwable) { null }
                if (b != null) cached = key to b
                main.post { done(b) }
            }
        } catch (_: Exception) { done(null) }
    }

    private fun render(look: Look, w: Int, h: Int): Bitmap {
        val bw = max(1, w / 4); val bh = max(1, h / 4)
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        cv.drawColor(look.ground)
        cv.scale(bw / w.toFloat(), bh / h.toFloat())
        drawMesh(cv, w.toFloat(), h.toFloat(), look, Paint(Paint.ANTI_ALIAS_FLAG))
        val px = IntArray(bw * bh)
        b.getPixels(px, 0, bw, 0, 0, bw, bh)
        // the old 60 dp RenderEffect: its sigma is about 0.58 x radius; a triple box of radius r is close to sigma r
        BoxBlur.blur(px, bw, bh, Math.round(0.58f * look.px(60) / 4f))
        b.setPixels(px, 0, bw, 0, 0, bw, bh)
        b.prepareToDraw()
        return b
    }

    /** The dashboard's .frost-mesh: light top-left, cool bottom-right, a paler wash top-right, a breath of the accent. */
    fun drawMesh(canvas: Canvas, w: Float, h: Float, look: Look, paint: Paint) {
        fun blob(x: Float, y: Float, r: Float, color: Int) {
            paint.shader = RadialGradient(x, y, max(r, 1f), color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, r, paint)
        }
        val m = look.theme.mesh
        blob(w * 0.08f, 0f, w * 0.75f, m[0])
        blob(w, h, w * 0.85f, m[1])
        blob(w * 0.7f, h * 0.2f, w * 0.5f, m[2])
        blob(w * 0.15f, h * 0.55f, w * 0.45f, look.withAlpha(look.accent, look.theme.breath))  // so the glass has something to frost
        paint.shader = null
    }
}

/** His photo: decoded once per process (sized to the screen), shared by every screen's FrostView. */
object Wallpaper {
    @Volatile private var cached: Pair<Long, Bitmap>? = null

    fun file(c: Context) = java.io.File(c.filesDir, Appearance.PHOTO_FILE)

    /** done runs on the main thread, with null when there's no usable photo. */
    fun load(c: Context, done: (Bitmap?) -> Unit) {
        val f = file(c); val stamp = f.lastModified()
        cached?.takeIf { it.first == stamp }?.let { done(it.second); return }
        val dm = c.resources.displayMetrics
        val main = Handler(Looper.getMainLooper())
        try {
            Frost.exec.execute {
                val b = try { decode(f.path, maxOf(dm.widthPixels, dm.heightPixels)) } catch (_: Throwable) { null }
                if (b != null) cached = stamp to b
                main.post { done(b) }
            }
        } catch (_: Exception) { done(null) }
    }

    /** Decoded at the smallest power-of-two step that still covers `longest` px on its long side. */
    fun decode(path: String, longest: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= longest) s *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    /** Centre-crop: the part of a bw x bh picture that fills a vw x vh view without stretching. */
    fun crop(bw: Int, bh: Int, vw: Int, vh: Int, out: Rect) {
        if (vw <= 0 || vh <= 0) { out.set(0, 0, bw, bh); return }
        if (bw.toLong() * vh > bh.toLong() * vw) { val w = (bh.toLong() * vw / vh).toInt(); out.set((bw - w) / 2, 0, (bw - w) / 2 + w, bh) }
        else { val h = (bw.toLong() * vh / vw).toInt(); out.set(0, (bh - h) / 2, bw, (bh - h) / 2 + h) }
    }

    fun forget() { cached = null }
}
