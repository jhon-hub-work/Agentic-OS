package com.jhon.agenticos.ui

import android.app.ActivityOptions
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import android.window.SplashScreen
import com.jhon.agenticos.core.Link
import org.json.JSONObject
import java.io.File

/** The launcher's one background thread (P0 7): the app list, icons, badges, launch history and search lookups all
 *  run here in order. It lives as long as the UI process, like Launcher3's loader. A job that throws is logged and
 *  dropped, so a bad app or a torn file can never take Home down. */
object Loader {
    private val thread = HandlerThread("launcher-loader", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    val handler = Handler(thread.looper)
    val main = Handler(Looper.getMainLooper())
    /** Network waits (Ask Jharvis) get their own thread, so a slow PC never holds up icons. */
    private val net by lazy { Handler(HandlerThread("launcher-net").apply { start() }.looper) }

    fun run(job: () -> Unit) { handler.post { safe(job) } }
    /** Something he's waiting on (a menu, a lookup, an icon on screen): ahead of background warm-up. */
    fun first(job: () -> Unit) { handler.postAtFrontOfQueue { safe(job) } }
    fun net(job: () -> Unit) { net.post { safe(job) } }
    fun safe(job: () -> Unit) { try { job() } catch (t: Throwable) { Log.w("Loader", "job failed", t) } }
}

/** An app's id in launch counts, hidden and locked apps (P0 7). Main-profile apps with one launcher activity keep the
 *  plain package, so every list saved before stays valid; the rest are "component#userSerial". */
internal object AppKey {
    fun of(pkg: String, flatComponent: String, mainUser: Boolean, single: Boolean, serial: Long) =
        if (mainUser && single) pkg else "$flatComponent#$serial"
    fun pkg(key: String) = key.substringBefore('/')
    fun serial(key: String): Long? = if ('/' in key) key.substringAfterLast('#', "").toLongOrNull() else null
    fun component(key: String): ComponentName? = if ('/' in key) ComponentName.unflattenFromString(key.substringBefore('#')) else null
}

class AppEntry(val key: String, val pkg: String, val label: String, val info: LauncherActivityInfo, val user: UserHandle,
               val main: Boolean, val installed: Long, val suspended: Boolean) {
    val component: ComponentName get() = info.componentName
}

/** A shortcut search can offer (published by the app, or one he pinned). */
class Cut(val pkg: String, val id: String, val label: String, val user: UserHandle)

/** Every launchable app in every profile, kept live by one LauncherApps.Callback (UI process only, never :core). */
object Apps {
    @Volatile var list: List<AppEntry> = emptyList(); private set   // A to Z
    @Volatile var version = 0; private set                         // grows on every change
    private var loaded = false                                      // loader thread only
    @Volatile private var cuts: List<Cut>? = null                   // null = read again
    private val listeners = ArrayList<() -> Unit>()                 // main thread only
    private var watching = false                                    // main thread only

    /** Loader thread: the list, reading every profile the first time. */
    fun get(c: Context): List<AppEntry> {
        if (!loaded) { list = query(c.applicationContext, null); loaded = true; version++ }
        return list
    }

    fun find(key: String) = list.firstOrNull { it.key == key }

    /** Main thread. `changed` runs on the main thread after an install, removal, update or suspend. */
    fun listen(c: Context, changed: () -> Unit) { watch(c); listeners += changed }
    fun unlisten(changed: () -> Unit) { listeners -= changed }

    private fun query(c: Context, pkg: String?): List<AppEntry> {
        val la = c.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val um = c.getSystemService(UserManager::class.java)
        val me = Process.myUserHandle()
        val out = ArrayList<AppEntry>()
        val users = try { la.profiles } catch (_: Exception) { listOf(me) }
        for (user in users) {
            // Lawnchair notes Android 17 QPR2 may throw here for other profiles (unverified): skip that profile only
            val acts = try { la.getActivityList(pkg, user) } catch (_: Exception) { continue } ?: continue
            val perPkg = acts.groupingBy { it.componentName.packageName }.eachCount()
            val serial = try { um?.getSerialNumberForUser(user) ?: -1L } catch (_: Exception) { -1L }
            val main = user == me
            for (a in acts) try {
                val p = a.componentName.packageName
                if (p == c.packageName) continue
                val plain = a.label?.toString()?.takeIf { it.isNotBlank() } ?: p
                val label = if (main) plain else c.packageManager.getUserBadgedLabel(plain, user).toString()  // "Work Gmail"
                val suspended = (a.applicationInfo.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
                out += AppEntry(AppKey.of(p, a.componentName.flattenToString(), main, perPkg[p] == 1, serial), p, label, a, user, main,
                    a.firstInstallTime, suspended)
            } catch (_: Exception) {}  // one broken entry never hides the rest
        }
        return out.sortedBy { it.label.lowercase() }
    }

    /** Loader thread: one package changed in one profile; re-read just that package. */
    private fun refresh(c: Context, pkg: String, user: UserHandle) {
        Icons.evict(pkg)
        cuts = null
        if (!loaded) return
        val fresh = query(c, pkg).filter { it.user == user }
        list = (list.filterNot { it.pkg == pkg && it.user == user } + fresh).sortedBy { it.label.lowercase() }
        version++
        Loader.main.post { for (l in listeners.toList()) Loader.safe(l) }
    }

    private fun watch(c: Context) {
        if (watching) return
        val app = c.applicationContext
        fun each(ps: Array<out String>?, u: UserHandle) = Loader.safe { ps?.forEach { refresh(app, it, u) } }
        val cb = object : LauncherApps.Callback() {
            override fun onPackageRemoved(p: String, u: UserHandle) = each(arrayOf(p), u)
            override fun onPackageAdded(p: String, u: UserHandle) = each(arrayOf(p), u)
            override fun onPackageChanged(p: String, u: UserHandle) = each(arrayOf(p), u)
            override fun onPackagesAvailable(ps: Array<out String>?, u: UserHandle, replacing: Boolean) = each(ps, u)
            override fun onPackagesUnavailable(ps: Array<out String>?, u: UserHandle, replacing: Boolean) = each(ps, u)
            override fun onPackagesSuspended(ps: Array<out String>?, u: UserHandle) = each(ps, u)
            override fun onPackagesUnsuspended(ps: Array<out String>?, u: UserHandle) = each(ps, u)
            override fun onShortcutsChanged(p: String, s: MutableList<ShortcutInfo>, u: UserHandle) { cuts = null }
        }
        try { app.getSystemService(LauncherApps::class.java)?.registerCallback(cb, Loader.handler); watching = true } catch (_: Exception) {}
    }

    /** Loader thread: the shortcuts apps publish (manifest and dynamic), for search. Dropped when any app changes them. */
    fun shortcuts(c: Context): List<Cut> {
        cuts?.let { return it }
        val la = c.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val got = try {
            if (!la.hasShortcutHostPermission()) emptyList()
            else la.profiles.flatMap { u ->
                try {
                    la.getShortcuts(LauncherApps.ShortcutQuery().setQueryFlags(
                        LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST), u).orEmpty()
                } catch (_: Exception) { emptyList() }
            }.filter { it.isEnabled }.mapNotNull { s ->
                val label = s.shortLabel?.toString()?.takeIf { it.isNotBlank() } ?: s.longLabel?.toString() ?: return@mapNotNull null
                Cut(s.`package`, s.id, label, s.userHandle)
            }
        } catch (_: Exception) { emptyList() }
        cuts = got
        return got
    }

    /** Main thread. Opens an app by key, growing out of `from` (P0 7: startMainActivity, any profile). */
    fun start(c: Context, from: View?, key: String): Boolean {
        val la = c.getSystemService(LauncherApps::class.java) ?: return false
        try {
            val e = find(key)
            val comp: ComponentName; val user: UserHandle
            if (e != null) { comp = e.component; user = e.user }
            else {  // the list isn't read yet (a cold start): work it out from the key
                val serial = AppKey.serial(key)
                user = if (serial == null) Process.myUserHandle()
                    else c.getSystemService(UserManager::class.java)?.getUserForSerialNumber(serial) ?: return fail(c)
                comp = AppKey.component(key) ?: la.getActivityList(key, user)?.firstOrNull()?.componentName ?: return fail(c)
            }
            la.startMainActivity(comp, user, bounds(from), options(from))
            return true
        } catch (_: Exception) { return fail(c) }
    }

    private fun fail(c: Context): Boolean { Toast.makeText(c, "Couldn't open that app", Toast.LENGTH_SHORT).show(); return false }

    fun bounds(v: View?): Rect? = v?.let { val l = IntArray(2); it.getLocationOnScreen(l); Rect(l[0], l[1], l[0] + it.width, l[1] + it.height) }

    /** Scale up from a start rect with the screen's shape, centred on the icon, so the app never starts squashed into a
     *  square (P1 2), with the app's own icon on its splash. */
    fun options(v: View?): Bundle? {
        if (v == null || v.width == 0) return null
        val dm = v.resources.displayMetrics
        val h = v.width * dm.heightPixels / dm.widthPixels.coerceAtLeast(1)
        return ActivityOptions.makeScaleUpAnimation(v, 0, (v.height - h) / 2, v.width, h)
            .apply { splashScreenStyle = SplashScreen.SPLASH_SCREEN_STYLE_ICON }.toBundle()
    }
}

/** App icons drawn once on the loader thread and kept in memory, sized by bytes (P0 8). Nothing decodes an icon on the
 *  main thread: views take a cached bitmap or get filled in a moment later.
 *  shortcut: memory only; add a disk cache if a cold start shows blank icons. */
object Icons {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
        override fun sizeOf(k: String, b: Bitmap) = b.allocationByteCount
    }
    @Volatile private var themed: Boolean? = null
    @Volatile private var ink: Look? = null  // the theme's colours for themed icons, read once per batch
    @Volatile private var trimHooked = false

    fun cached(key: String): Bitmap? = cache.get(key)

    /** Package changed: its icons are drawn again next time. */
    fun evict(pkg: String) { for (k in cache.snapshot().keys) if (AppKey.pkg(k) == pkg) cache.remove(k) }

    /** Loader thread, before a batch: the themed-icons choice changed, so every icon is drawn again. */
    fun sync(c: Context) {
        hookTrim(c)
        val now = Appearance.prefs(c).getBoolean(Appearance.THEMED_ICONS, false)
        if (themed != null && themed != now) cache.evictAll()
        themed = now
        ink = if (now) Look(c) else null
    }

    /** Loader thread. The app's icon at 56 dp: themed (monochrome on a glass disc) when that's on and the app has one,
     *  badged for work and other profiles, greyed while the app is paused. */
    fun load(c: Context, e: AppEntry): Bitmap? {
        cache.get(e.key)?.let { return it }
        val dm = c.resources.displayMetrics
        val size = (56 * dm.density).toInt()
        val b = try {
            val d = e.info.getIcon(dm.densityDpi) ?: return null
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val cv = Canvas(out)
            if (e.suspended) cv.saveLayer(null, Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) })
            val mono = (d as? AdaptiveIconDrawable)?.monochrome
            val look = ink
            if (themed == true && mono != null && look != null) drawThemed(look, dm.density, cv, mono.mutate(), size)
            else { d.setBounds(0, 0, size, size); d.draw(cv) }
            if (e.suspended) cv.restore()
            if (e.main) out
            else (c.packageManager.getUserBadgedIcon(android.graphics.drawable.BitmapDrawable(c.resources, out), e.user)).let { bd ->
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { r -> bd.setBounds(0, 0, size, size); bd.draw(Canvas(r)) }
            }
        } catch (_: Exception) { null }  // a broken icon is a blank tile, never a crash
        if (b != null) cache.put(e.key, b)
        return b
    }

    /** Ink glyph on the frosted glass disc (P2 themed icons): the monochrome layer drawn as an adaptive icon would be,
     *  its 108 dp canvas spread over 1.5x the disc. */
    private fun drawThemed(look: Look, hair: Float, cv: Canvas, mono: android.graphics.drawable.Drawable, size: Int) {
        val r = size / 2f
        cv.drawCircle(r, r, r - hair, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.white(235) })
        cv.drawCircle(r, r, r - hair, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = hair; color = look.glassEdge })
        cv.save()
        cv.clipPath(Path().apply { addCircle(r, r, r - hair, Path.Direction.CW) })
        mono.colorFilter = PorterDuffColorFilter(look.ink, PorterDuff.Mode.SRC_IN)
        mono.setBounds(-size / 4, -size / 4, size + size / 4, size + size / 4)
        mono.draw(cv)
        cv.restore()
    }

    /** Main thread. Shows the cached icon now, or fills it in from the loader thread (a recycled view is checked by tag). */
    fun into(img: ImageView, key: String) {
        cache.get(key)?.let { img.tag = key; img.setImageBitmap(it); return }
        img.setImageDrawable(null); img.tag = key
        val app = img.context.applicationContext
        Loader.first {
            val e = Apps.get(app).firstOrNull { it.key == key } ?: Apps.list.firstOrNull { it.pkg == AppKey.pkg(key) && it.main }
            val b = e?.let { load(app, it) } ?: return@first
            Loader.main.post { if (img.tag == key) img.setImageBitmap(b) }
        }
    }

    /** Memory pressure: let every icon go (they're drawn again on demand). Hooked once, from the first batch. */
    private fun hookTrim(c: Context) {
        if (trimHooked) return
        trimHooked = true
        c.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            @Suppress("DEPRECATION")
            override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) cache.evictAll() }
            override fun onConfigurationChanged(cfg: Configuration) {}
            @Deprecated("Deprecated in Java") override fun onLowMemory() = cache.evictAll()
        })
    }
}

/** Notification counts and the latest notes per app, from :core's Link.dir/badges.json (Phase 2 contract). */
object AppBadges {
    class Note(val title: String, val text: String, val time: Long)
    class Badge(val count: Int, val notes: List<Note>)
    const val ACTION = "com.jhon.agenticos.BADGES"

    @Volatile var all: Map<String, Badge> = emptyMap(); private set

    /** Loader thread. */
    fun read(): Map<String, Badge> {
        all = try { parse(File(Link.dir, "badges.json").takeIf { it.exists() }?.readText() ?: "") } catch (_: Exception) { emptyMap() }
        return all
    }

    internal fun parse(s: String): Map<String, Badge> {
        if (s.isBlank()) return emptyMap()
        val o = try { JSONObject(s) } catch (_: Exception) { return emptyMap() }  // a torn file is "no badges"
        val out = HashMap<String, Badge>()
        for (pkg in o.keys()) {
            val b = o.optJSONObject(pkg) ?: continue
            val arr = b.optJSONArray("notes")
            val notes = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                arr!!.optJSONObject(i)?.let { n -> Note(n.optString("title"), n.optString("text"), n.optLong("time")) }
            }.filter { it.title.isNotBlank() || it.text.isNotBlank() }.take(3)
            val count = b.optInt("count", 0)
            if (count > 0 || notes.isNotEmpty()) out[pkg] = Badge(count.coerceAtLeast(0), notes)
        }
        return out
    }

    /** What a pill says: never more than "99+". */
    fun word(n: Int) = if (n > 99) "99+" else n.toString()

    /** The count pill (text, accent hairline), never a bare dot. */
    fun pill(look: Look) = look.text("", 11f, look.ink, true).apply {
        gravity = android.view.Gravity.CENTER
        minWidth = look.px(20); minHeight = look.px(20)
        setPadding(look.px(6), 0, look.px(6), 0)
        background = look.round(look.white(245), 99, look.accent)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO  // the icon says it ("Gmail, 3 notifications")
    }
}
