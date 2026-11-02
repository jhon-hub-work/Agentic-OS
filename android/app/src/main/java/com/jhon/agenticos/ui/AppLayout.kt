package com.jhon.agenticos.ui

import android.content.Context
import android.os.SystemClock
import android.view.View

/** What Home needs from any way of showing all apps (Orbit, Smart groups, Honeycomb, Jharvis picks, Sphere). Home's one orb
 *  spring drives progress; the layout never draws the orb. */
interface AppLayout {
    val view: View
    var progress: Float                 // 0 shut .. 1 open
    var menuActions: AppMenu.Actions?
    fun load()                          // cheap re-rank on resume
    fun opening()                       // once, as an open starts
    fun reset()                         // once it has settled shut
    fun ownsTouch(x: Float, y: Float): Boolean  // a drag here scrolls or pans inside, so Home leaves it alone
}

/** Jhon's own change from the app menu (hide, move to group) shows at once in an open layout; a sort that arrives on
 *  its own waits until it is shut, so nothing jumps under his finger (review 2 Nov: a hidden app stayed tappable). */
object MenuChange {
    @Volatile var at = 0L
    fun recent() = at != 0L && SystemClock.uptimeMillis() - at < 5_000
}

/** Which layout Home shows: "aos" prefs key "app_layout". */
object Layouts {
    const val KEY = "app_layout"
    const val ORBIT = "orbit"; const val GROUPS = "groups"; const val HONEYCOMB = "honeycomb"; const val PICKS = "picks"; const val SPHERE = "sphere"
    val IDS = listOf(ORBIT, GROUPS, HONEYCOMB, PICKS, SPHERE)

    /** Anything unknown (an old or torn value) is the orbit. */
    fun valid(id: String?) = if (id in IDS) id!! else ORBIT

    fun name(id: String) = when (id) { GROUPS -> "Smart groups"; HONEYCOMB -> "Honeycomb"; PICKS -> "Jharvis picks"; SPHERE -> "Sphere"; else -> "Orbit" }

    fun about(id: String) = when (id) {
        GROUPS -> "Cards sorted by what apps are for"
        HONEYCOMB -> "One field of icons, drag to look around"
        PICKS -> "Apps for right now, then A to Z"
        SPHERE -> "Apps on a globe, spin it with your finger"
        else -> "Apps on rings around Jharvis, spin a ring"
    }

    fun current(c: Context) = valid(c.getSharedPreferences("aos", Context.MODE_PRIVATE).getString(KEY, null))

    fun set(c: Context, id: String) = c.getSharedPreferences("aos", Context.MODE_PRIVATE).edit().putString(KEY, valid(id)).apply()

    /** The chosen layout's view, built with Home's callbacks. The orbit is Home's own (it also serves search). */
    fun make(id: String, c: Context, look: Look, orbit: OrbitView, onLaunch: (View, String) -> Unit, onClose: () -> Unit,
             onSearch: () -> Unit): AppLayout = when (id) {
        GROUPS -> GroupsLayout(GroupsView(c, look, onLaunch, onClose, onSearch))
        HONEYCOMB -> HoneycombLayout(HoneycombView(c, look, onLaunch, onClose, onSearch))
        PICKS -> PicksLayout(PicksView(c, look, onLaunch, onClose, onSearch))
        SPHERE -> SphereLayout(SphereView(c, look, onLaunch, onClose, onSearch))
        else -> orbit
    }.also { if (it !== orbit) it.view.accessibilityPaneTitle = "Apps, ${name(id)}" }
}

// Adapters: the four views carry the contract's members but not the interface itself.
private class GroupsLayout(private val v: GroupsView) : AppLayout {
    override val view: View get() = v
    override var progress: Float by v::progress
    override var menuActions: AppMenu.Actions? by v::menuActions
    override fun load() = v.load()
    override fun opening() = v.opening()
    override fun reset() = v.reset()
    override fun ownsTouch(x: Float, y: Float) = v.ownsTouch(x, y)
}

private class HoneycombLayout(private val v: HoneycombView) : AppLayout {
    override val view: View get() = v
    override var progress: Float by v::progress
    override var menuActions: AppMenu.Actions? by v::menuActions
    override fun load() = v.load()
    override fun opening() = v.opening()
    override fun reset() = v.reset()
    override fun ownsTouch(x: Float, y: Float) = v.ownsTouch(x, y)
}

private class PicksLayout(private val v: PicksView) : AppLayout {
    override val view: View get() = v
    override var progress: Float by v::progress
    override var menuActions: AppMenu.Actions? by v::menuActions
    override fun load() = v.load()
    override fun opening() = v.opening()
    override fun reset() = v.reset()
    override fun ownsTouch(x: Float, y: Float) = v.ownsTouch(x, y)
}

private class SphereLayout(private val v: SphereView) : AppLayout {
    override val view: View get() = v
    override var progress: Float by v::progress
    override var menuActions: AppMenu.Actions? by v::menuActions
    override fun load() = v.load()
    override fun opening() = v.opening()
    override fun reset() = v.reset()
    override fun ownsTouch(x: Float, y: Float) = v.ownsTouch(x, y)
}
