package com.jhon.agenticos.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.jhon.agenticos.R

/** Press and hold an app: the icon lifts out, the window blurs back, and a frosted menu grows out of the icon with
 *  the app's latest notifications, its shortcuts, Add to dock (from the dock itself: Change app, Remove from dock), Move to group (Smart groups layout), Hide app, Lock app,
 *  App info and Delete app. Call from
 *  any long-press in any UI Activity: `AppMenu.show(iconView, key, look, actions)`, key = an app key (a plain package
 *  works). Pass the icon view (what gets lifted is a snapshot of it). What the menu lists is read on the loader
 *  thread first, so nothing decodes or asks the system on the main thread. */
object AppMenu {
    interface Actions {
        fun addToDock(pkg: String)   // home picks the slot
        fun changed()                // hidden/unhidden or uninstalled: reload lists
        fun changeDock(slot: Int) {}      // only Home's dock opens a menu with a slot
        fun removeFromDock(slot: Int) {}
    }

    private var current: Menu? = null  // one menu at a time
    val isOpen get() = current != null  // Sphere holds its idle spin while a menu is up
    private var asking = 0             // a newer long-press wins over a menu still being read

    /** dockSlot >= 0: held on that dock spot, so the menu offers Change app and Remove from dock instead of Add. */
    fun show(anchor: View, pkg: String, look: Look, actions: Actions, dockSlot: Int = -1) = open(anchor, pkg, look, actions, null, null, dockSlot)

    /** Home pressed or left: the open menu goes at once, and one still being read never opens. */
    fun dismiss() { asking++; current?.dismissNow() }

    /** The shortcuts the menu would list (Typographic Matrix shows them under a framed name). Off the main thread: IPC. */
    internal fun shortcuts(la: LauncherApps?, pkg: String, user: UserHandle) = Data.shortcuts(la, pkg, user)

    /** A pinned shortcut's menu (search): Remove shortcut. `removed` runs once it's gone from the list. */
    fun showPin(anchor: View, pin: SmartSearch.Pin, look: Look, removed: () -> Unit) = open(anchor, pin.pkg, look, null, pin, removed)

    private fun open(anchor: View, key: String, look: Look, actions: Actions?, pin: SmartSearch.Pin?, removed: (() -> Unit)?, dockSlot: Int = -1) {
        current?.dismissNow()
        val act = activityOf(anchor) ?: return
        val app = act.applicationContext
        val token = ++asking
        val dpi = act.resources.displayMetrics.densityDpi
        Loader.first {
            val data = if (pin != null) Data(emptyList(), emptyList(), false, false, false, null, true, null, null) else Data.load(app, key, dpi)
            Loader.main.post {
                if (token != asking || activityOf(anchor) == null) return@post  // scrolled away or the screen closed meanwhile
                current?.dismissNow()
                current = Menu(act, anchor, key, look, actions, data, pin, removed, dockSlot).also { it.open() }
            }
        }
    }

    private fun activityOf(anchor: View): Activity? {
        if (!anchor.isAttachedToWindow || anchor.width == 0 || anchor.height == 0) return null
        var ctx = anchor.context
        while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
        return (ctx as? Activity)?.takeIf { !it.isFinishing && !it.isDestroyed }
    }

    /** Everything the menu shows, read on the loader thread. */
    private class Data(val cuts: List<Pair<ShortcutInfo, Drawable?>>, val notes: List<AppBadges.Note>, val removable: Boolean,
                       val hidden: Boolean, val locked: Boolean, val entry: AppEntry?, val main: Boolean, val label: String?,
                       val group: String?) {  // its Smart group, only while that layout is in use
        companion object {
            fun load(c: Context, key: String, dpi: Int): Data {
                val pkg = AppKey.pkg(key)
                val e = Apps.get(c).firstOrNull { it.key == key }
                val user = e?.user ?: Process.myUserHandle()
                val main = e?.main ?: true
                val la = c.getSystemService(LauncherApps::class.java)
                val cuts = shortcuts(la, pkg, user).map { sc -> sc to try { la?.getShortcutIconDrawable(sc, dpi) } catch (_: Exception) { null } }
                val hidden = HiddenApps.isHidden(c, key)
                val locked = LockedApps.isLocked(c, key)
                // a locked or hidden app keeps what its notifications say to itself
                val notes = if (hidden || locked || !main) emptyList() else AppBadges.read()[pkg]?.notes.orEmpty()
                val removable = main && try {
                    val f = c.packageManager.getApplicationInfo(pkg, 0).flags
                    (f and ApplicationInfo.FLAG_SYSTEM) == 0 || (f and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0  // updated system apps can drop updates
                } catch (_: Exception) { false }
                // hidden apps sit in no group
                val group = if (e != null && !hidden && Layouts.current(c) == Layouts.GROUPS) AppGroups.groups(c, listOf(e))[key] else null
                return Data(cuts, notes, removable, hidden, locked, e, main, e?.label, group)
            }

            /** Dynamic, manifest and pinned shortcuts: manifest first, then dynamic, then pinned only; by rank; four at most. */
            fun shortcuts(la: LauncherApps?, pkg: String, user: UserHandle): List<ShortcutInfo> = try {
                if (la == null || !la.hasShortcutHostPermission()) emptyList()
                else la.getShortcuts(LauncherApps.ShortcutQuery().setPackage(pkg).setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED), user)
                    .orEmpty().filter { it.isEnabled }
                    .sortedWith(compareBy({ if (it.isDeclaredInManifest) 0 else if (it.isDynamic) 1 else 2 }, { it.rank }))
                    .take(4)
            } catch (_: Exception) { emptyList() }  // not Home any more (SecurityException), or that profile is locked
        }
    }

    /** Where the menu goes (window px). Below the icon if it fits, else above; horizontally it hugs the icon's side of
     *  the screen (left edges line up, or right edges). Too tall for either: beside the icon (left of it on the right
     *  half, right of it otherwise), centred on it and clamped, never over it; w narrows when that side is short. When
     *  that side has less than minSide (an icon near the middle), it goes in the roomier of above or below instead, h
     *  capped to that room so its rows scroll. h is the height to lay it out at (capped to the screen beside too). */
    internal data class Spot(val x: Int, val y: Int, val above: Boolean, val w: Int, val h: Int)

    internal fun place(iconL: Int, iconT: Int, iconR: Int, iconB: Int, w: Int, h: Int,
                       minX: Int, minY: Int, maxX: Int, maxY: Int, gap: Int, minSide: Int = 0): Spot {
        val left = (iconL + iconR) / 2 < (minX + maxX) / 2
        val x = (if (left) iconL else iconR - w).coerceIn(minX, maxOf(minX, maxX - w))
        val below = maxY - iconB - gap; val up = iconT - gap - minY  // room under and over the icon
        val above = when {
            h <= below -> false
            h <= up -> true
            else -> {
                val side = if (left) maxX - iconR - gap else iconL - gap - minX
                if (side < minSide) {  // too narrow beside: labels would be cut, so it scrolls above or below instead
                    val top = up > below
                    val ch = maxOf(1, if (top) up else below)
                    return Spot(x, if (top) iconT - gap - ch else iconB + gap, top, w, ch)
                }
                val mw = minOf(w, maxOf(1, side))
                val ch = minOf(h, maxY - minY)
                val y = ((iconT + iconB) / 2 - ch / 2).coerceIn(minY, maxOf(minY, maxY - ch))
                return Spot(if (left) iconR + gap else iconL - gap - mw, y, false, mw, ch)
            }
        }
        val y = (if (above) iconT - gap - h else iconB + gap).coerceIn(minY, maxOf(minY, maxY - h))
        return Spot(x, y, above, w, h)
    }

    private class Menu(val act: Activity, val anchor: View, val key: String, val look: Look, val actions: Actions?, val data: Data,
                       val pin: SmartSearch.Pin?, val removed: (() -> Unit)?, val dockSlot: Int) {
        val pkg = AppKey.pkg(key)
        val still = reducedMotion(act)
        val decor = act.window.decorView as ViewGroup
        val content: View = decor.findViewById(android.R.id.content)
        val overlay = FrameLayout(act)
        val scrim = View(act)
        lateinit var copy: Lifted
        lateinit var shell: FrameLayout
        var closing = false
        var gone = false
        var after: (() -> Unit)? = null  // what a pick still has to do once the menu is gone (hide, lock, dock)
        var back = 0f            // predictive back progress: the menu shrinks toward the icon as Back is dragged
        var from = 1f; var lift = 1.08f
        val spring = Spring(0f, 0.001f) { render(it) }
        val backCb: OnBackInvokedCallback =
            if (Build.VERSION.SDK_INT >= 34) object : OnBackAnimationCallback {
                override fun onBackProgressed(e: BackEvent) { back = e.progress; render(spring.value) }
                override fun onBackCancelled() { back = 0f; render(spring.value) }
                override fun onBackInvoked() = close()
            } else OnBackInvokedCallback { close() }

        fun open() {
            // where the icon is on screen right now, through every parent's move, scale and spin
            val pt = floatArrayOf(anchor.width / 2f, anchor.height / 2f)
            var s = 1f
            var v: View? = anchor
            while (v != null) {
                if (!v.matrix.isIdentity) v.matrix.mapPoints(pt)
                s *= v.scaleX
                val p = v.parent as? View ?: break
                pt[0] += v.left - p.scrollX; pt[1] += v.top - p.scrollY
                v = p
            }
            val cx = pt[0]; val cy = pt[1]
            val natural = if (anchor.scaleX != 0f) s / anchor.scaleX else s  // the press squish isn't part of the icon's size
            from = s; lift = natural * 1.08f

            copy = Lifted(anchor, look)
            overlay.addView(scrim, FrameLayout.LayoutParams(-1, -1))
            overlay.addView(copy, FrameLayout.LayoutParams(copy.w, copy.h))
            copy.translationX = cx - copy.w / 2f; copy.translationY = cy - copy.h / 2f

            shell = buildMenu()
            val ins = decor.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val g = look.px(16)
            var w = minOf(look.px(256), decor.width - g * 2 - (ins?.left ?: 0) - (ins?.right ?: 0))
            fun measure(mw: Int): Int {
                shell.measure(View.MeasureSpec.makeMeasureSpec(mw, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(decor.height, View.MeasureSpec.AT_MOST))
                return shell.measuredHeight
            }
            var h = measure(w)
            val hw = anchor.width * lift / 2; val hh = anchor.height * lift / 2
            fun spotFor() = place((cx - hw).toInt(), (cy - hh).toInt(), (cx + hw).toInt(), (cy + hh).toInt(), w, h,
                (ins?.left ?: 0) + g, (ins?.top ?: 0) + g / 2, decor.width - (ins?.right ?: 0) - g,
                decor.height - (ins?.bottom ?: 0) - g / 2, look.px(12), look.px(240))
            var spot = spotFor()
            if (spot.w != w) { w = spot.w; h = measure(w); spot = spotFor() }  // narrowed beside the icon: rows may wrap, so place again
            h = spot.h  // capped when there was no room: the rows scroll
            overlay.addView(shell, FrameLayout.LayoutParams(w, h))
            shell.translationX = spot.x.toFloat(); shell.translationY = spot.y.toFloat()
            shell.pivotX = (cx - spot.x).coerceIn(0f, w.toFloat())  // grows out of the icon's side
            shell.pivotY = if (spot.above) h.toFloat() else (cy - spot.y).coerceIn(0f, h.toFloat())  // below: its top; beside: level with the icon

            // everything behind recedes toward the icon, so the icon stays right under its lifted copy
            val loc = IntArray(2).also { content.getLocationInWindow(it) }
            content.pivotX = cx - loc[0]; content.pivotY = cy - loc[1]

            scrim.setBackgroundColor(look.shade(46))
            scrim.contentDescription = "Close menu"
            scrim.setOnClickListener { close() }
            overlay.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) = cleanup()  // the activity went away: nothing leaks
            })
            decor.addView(overlay, FrameLayout.LayoutParams(-1, -1))
            act.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, backCb)
            anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            anchor.parent?.requestDisallowInterceptTouchEvent(true)  // the rest of this hold can't spin the orbit or pull the drawer

            if (still) {  // Remove animations: no lift, no blur, a quick cross-fade
                copy.scaleX = lift / 1.08f; copy.scaleY = lift / 1.08f
                render(1f); overlay.alpha = 0f; overlay.animate().alpha(1f).setDuration(150).start()
            } else {
                render(0f); spring.to(1f, response = 0.42f, damping = 1f)
            }
        }

        fun render(raw: Float) {
            val p = raw.coerceIn(0f, 1f)
            scrim.alpha = p
            shell.alpha = p
            if (still) return
            content.blur(look.px(24) * p)
            val k = 1 - 0.05f * p
            content.scaleX = k; content.scaleY = k
            val c = from + (lift - from) * raw
            copy.scaleX = c; copy.scaleY = c
            copy.shade(p)
            val m = (0.92f + 0.08f * p) * (1 - 0.1f * back)
            shell.scaleX = m; shell.scaleY = m
            shell.blur(look.px(8) * (1 - p))
        }

        /** Same path out as in. `then` runs once it has gone (hide, dock), even when Home cuts the close short;
         *  intents start right away instead. */
        fun close(then: (() -> Unit)? = null) {
            if (closing) return
            closing = true
            after = then
            unregisterBack()
            scrim.setOnClickListener(null); scrim.isClickable = false  // taps fall through while it dissolves
            if (still) {
                overlay.animate().alpha(0f).setDuration(120).withEndAction { remove() }.start()
                return
            }
            from = lift / 1.08f  // back to the icon's resting size, not the pressed squish it started from
            spring.onRest = { remove() }
            spring.to(0f, response = 0.36f, damping = 1f)
        }

        fun dismissNow() { overlay.animate().cancel(); remove() }

        fun remove() { (overlay.parent as? ViewGroup)?.removeView(overlay); cleanup() }

        fun cleanup() {
            if (gone) return
            gone = true
            spring.stop()
            unregisterBack()
            content.blur(0f); content.scaleX = 1f; content.scaleY = 1f
            if (current === this) current = null
            after?.let { after = null; it() }  // a quick Home press mid-close still hides, shows or locks the app
        }

        fun unregisterBack() = try { act.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCb) } catch (_: Exception) {}

        fun buildMenu(): FrameLayout {
            val list = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = look.glassCard(22)
                clipToOutline = true
                setPadding(0, look.px(6), 0, look.px(6))
            }
            fun divider() = list.addView(View(act).apply { setBackgroundColor(look.inkA(30)) },
                LinearLayout.LayoutParams(-1, maxOf(1, look.px(1) / 2)).apply { setMargins(0, look.px(6), 0, look.px(6)) })
            if (pin != null) {
                list.addView(row("Remove shortcut", null, R.drawable.ic_appmenu_delete, look.bad) {
                    val app = act.applicationContext
                    close { Loader.run { SmartSearch.unpin(app, pin) }; removed?.invoke() }
                })
                return wrap(list)
            }
            for (n in data.notes) list.addView(noteRow(n))
            if (data.notes.isNotEmpty()) divider()
            for ((sc, icon) in data.cuts) {
                val label = sc.shortLabel?.takeIf { it.isNotBlank() } ?: sc.longLabel ?: continue
                list.addView(row(label, icon, null, look.ink) { startShortcut(sc) })
            }
            if (data.cuts.isNotEmpty()) divider()

            val a = actions
            if (a != null && dockSlot >= 0) {
                list.addView(row("Change app", null, R.drawable.ic_appmenu_dock, look.ink) { a.changeDock(dockSlot); close() })  // the picker opens at once, like App info
                list.addView(row("Remove from dock", null, R.drawable.ic_appmenu_undock, look.ink) { close { a.removeFromDock(dockSlot) } })
            } else if (a != null && data.main) list.addView(row("Add to dock", null, R.drawable.ic_appmenu_dock, look.ink) { close { a.addToDock(pkg) } })
            data.group?.let { g -> if (a != null) list.addView(groupRow(g, a)) }
            list.addView(row(if (data.hidden) "Show app" else "Hide app", null, if (data.hidden) R.drawable.ic_appmenu_show else R.drawable.ic_appmenu_hide, look.ink) {
                close { HiddenApps.set(act, key, !data.hidden); a?.changed() }
            })
            list.addView(row(if (data.locked) "Unlock app" else "Lock app", null, if (data.locked) R.drawable.ic_appmenu_unlock else R.drawable.ic_appmenu_lock, look.ink) {
                close { if (data.locked) unlock() else lock() }
            })
            list.addView(row("App info", null, R.drawable.ic_appmenu_info, look.ink) { info(); close() })
            if (data.removable) list.addView(row("Delete app", null, R.drawable.ic_appmenu_delete, look.bad) { delete() })
            return wrap(list)
        }

        /** "Move to group" (Smart groups only): the groups as a row of pills that scrolls sideways; the one it's in wears
         *  the accent. A pick is his own move, kept over Jharvis's sort, and the layout re-sorts once it's shut. */
        fun groupRow(now: String, a: Actions) = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, look.px(8), 0, look.px(4))
            addView(look.text("Move to group", 13f, look.ink3).apply { setPadding(look.px(16), 0, look.px(16), look.px(4)) })
            val pills = LinearLayout(act).apply { setPadding(look.px(12), 0, look.px(12), 0) }
            for (g in AppGroups.NAMES) {
                val on = g == now
                pills.addView(look.text(g, 15f, if (on) look.accentInk else look.ink, on).apply {
                    gravity = Gravity.CENTER; minHeight = look.px(48)
                    setPadding(look.px(14), 0, look.px(14), 0)
                    background = if (on) look.round(look.withAlpha(look.glacier, 36), 99, look.glacier) else look.round(look.inkA(14), 99)
                    contentDescription = if (on) "$g, in this group now" else "Move to $g"
                    isClickable = true; look.press(this, 0.92f)
                    setOnClickListener {
                        if (closing || on) return@setOnClickListener
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        close { AppGroups.move(act, key, g); a.changed() }
                    }
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = look.px(6) })
            }
            addView(android.widget.HorizontalScrollView(act).apply {
                isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
                addView(pills)
            }, LinearLayout.LayoutParams(-1, -2))
        }

        fun lock() {
            LockedApps.set(act, key, true)
            Toast.makeText(act, "Locked. Opening it from Agentic OS asks for your screen lock.", Toast.LENGTH_LONG).show()
        }

        /** Unlocking asks first, or anyone holding the phone could take the lock off. Locks match by package, so every
         *  key of it goes, or the app would stay locked under another key. */
        fun unlock() = LockedApps.ask(act, "Unlock ${data.label ?: "app"}", {
            LockedApps.all(act).filter { AppKey.pkg(it) == pkg }.forEach { LockedApps.set(act, it, false) }
        })

        /** One of the app's latest notifications: who or what, then the line; a tap opens the app. */
        fun noteRow(n: AppBadges.Note) = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = look.px(52)
            setPadding(look.px(16), look.px(8), look.px(14), look.px(8))
            background = StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_pressed), look.round(look.inkA(16), 16)) }
            if (n.title.isNotBlank()) addView(look.text(n.title, 15f, look.ink, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            if (n.text.isNotBlank()) addView(look.text(n.text, 13f, look.ink2).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            contentDescription = listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(". ")
            isClickable = true
            look.press(this, 0.97f)
            setOnClickListener { if (!closing) { Apps.start(act, anchor, key); close() } }
        }

        /** The system's App info page, for the right profile. */
        fun info() {
            val e = data.entry
            val plain = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
            try {
                if (e != null) act.getSystemService(LauncherApps::class.java).startAppDetailsActivity(e.component, e.user, Apps.bounds(anchor), Apps.options(anchor))
                else act.launchFrom(anchor, plain)
            } catch (_: Exception) { act.launchFrom(anchor, plain) }
        }

        fun wrap(list: LinearLayout): FrameLayout {
            return FrameLayout(act).apply {
                // double bezel: a clear shell with a lit hairline around the frosted core
                background = look.shell()
                setPadding(look.px(6), look.px(6), look.px(6), look.px(6))
                elevation = look.px(24).toFloat()
                outlineSpotShadowColor = look.ink; outlineAmbientShadowColor = look.ink
                accessibilityPaneTitle = if (pin != null) "Shortcut menu" else "App menu"
                // the frosted core scrolls its rows when the menu had to be capped (no room around the icon)
                addView(ScrollView(act).apply {
                    background = list.background; clipToOutline = true
                    isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
                    list.background = null; list.clipToOutline = false
                    addView(list, FrameLayout.LayoutParams(-1, -2))
                }, FrameLayout.LayoutParams(-1, -2))
            }
        }

        fun row(label: CharSequence, icon: Drawable?, line: Int?, color: Int, onPick: () -> Unit) = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = look.px(50)
            setPadding(look.px(16), 0, look.px(14), 0)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), look.round(look.inkA(16), 16))
            }
            addView(look.text(label, 17f, color).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = look.px(12) })
            addView(ImageView(act).apply {
                if (line != null) { setImageResource(line); imageTintList = ColorStateList.valueOf(color) } else setImageDrawable(icon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(look.px(if (line != null) 22 else 26), look.px(if (line != null) 22 else 26)))
            isClickable = true
            look.press(this, 0.97f)
            setOnClickListener { if (!closing) onPick() }
        }

        fun startShortcut(sc: ShortcutInfo) {
            val go = {
                try { act.getSystemService(LauncherApps::class.java).startShortcut(pkg, sc.id, Apps.bounds(anchor), Apps.options(anchor), sc.userHandle) }
                catch (_: Exception) { Toast.makeText(act, "Couldn't open that shortcut", Toast.LENGTH_SHORT).show() }  // disabled or user locked since the menu opened
            }
            if (data.locked) LockedApps.ask(act, "Open ${data.label ?: "app"}", go) else go()  // a locked app's shortcuts ask first too
            close()
        }

        fun delete() {
            val a = actions
            // Android shows its own confirm; whatever he picked, the lists reload when this screen is back
            act.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(x: Activity) { x.unregisterActivityLifecycleCallbacks(this); a?.changed() }
                override fun onActivityDestroyed(x: Activity) { x.unregisterActivityLifecycleCallbacks(this) }
                override fun onActivityCreated(x: Activity, b: Bundle?) {}
                override fun onActivityStarted(x: Activity) {}
                override fun onActivityPaused(x: Activity) {}
                override fun onActivityStopped(x: Activity) {}
                override fun onActivitySaveInstanceState(x: Activity, b: Bundle) {}
            })
            try { act.startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))) } catch (_: Exception) {}
            close()
        }
    }

    /** The lifted icon: a snapshot of the anchor with a soft shadow that follows the icon's own shape. */
    private class Lifted(anchor: View, look: Look) : View(anchor.context) {
        private val m = look.px(20)
        val w = anchor.width + m * 2; val h = anchor.height + m * 2
        // via a Picture: icons can hold hardware bitmaps, which a plain software canvas refuses
        private val icon: Bitmap = Picture().let { pic ->
            anchor.draw(pic.beginRecording(anchor.width, anchor.height)); pic.endRecording()
            Bitmap.createBitmap(pic).copy(Bitmap.Config.ARGB_8888, false)
        }
        private val off = IntArray(2)
        private val shadow: Bitmap = icon.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(look.px(9).toFloat(), BlurMaskFilter.Blur.NORMAL) }, off)
        private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = look.ink; alpha = 0 }
        private val drop = look.px(8).toFloat()

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun shade(p: Float) { val a = (p * 70).toInt(); if (a != shadowPaint.alpha) { shadowPaint.alpha = a; invalidate() } }

        override fun onDraw(canvas: Canvas) {
            if (shadowPaint.alpha > 0) canvas.drawBitmap(shadow, (m + off[0]).toFloat(), m + off[1] + drop, shadowPaint)
            canvas.drawBitmap(icon, m.toFloat(), m.toFloat(), null)
        }
    }
}

/** Apps he has hidden from the orbit, dock and search ("aos" prefs, UI process only). Holds app keys (AppKey). */
object HiddenApps {
    private fun prefs(c: Context) = c.getSharedPreferences("aos", Context.MODE_PRIVATE)
    fun isHidden(c: Context, pkg: String) = pkg in all(c)
    fun set(c: Context, pkg: String, hidden: Boolean) {
        val s = all(c).toMutableSet()  // a copy: prefs' own set must never be changed in place
        if (if (hidden) s.add(pkg) else s.remove(pkg)) prefs(c).edit().putStringSet("hidden_apps", s).apply()
    }
    fun all(c: Context): Set<String> = prefs(c).getStringSet("hidden_apps", null)?.toSet() ?: emptySet()
}
