package com.jhon.agenticos.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import com.jhon.agenticos.R

/** Hidden and locked apps, iOS style: a large title, then grouped glass lists with a switch per app (on = still hidden
 *  or still locked). It opens only after his screen lock, since it shows what he chose to keep out of sight. */
class HiddenAppsActivity : Activity() {
    private lateinit var look: Look
    private lateinit var column: LinearLayout

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        look = Look(this)
        if (Build.VERSION.SDK_INT >= 34) {  // iOS push, same as the other settings screens
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.set_push_in, R.anim.set_push_out)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.set_pop_in, R.anim.set_pop_out)
        }
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(column)
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top + look.px(12), i.right, i.bottom + look.px(24))
                WindowInsets.CONSUMED
            }
        }
        setContentView(scroll)
        look.bars(window)
        // nothing is drawn until the lock is passed; backing out of the prompt closes the screen
        LockedApps.ask(this, "Show hidden and locked apps", { load() }, { finish() })
    }

    private fun load() {
        val app = applicationContext
        Loader.run {
            Icons.sync(app)
            val all = Apps.get(app).associateBy { it.key }
            val hidden = HiddenApps.all(app).sorted()
            val locked = LockedApps.all(app).sorted()
            Loader.main.post { if (!isDestroyed) build(all, hidden, locked) }
        }
    }

    private fun build(all: Map<String, AppEntry>, hidden: List<String>, locked: List<String>) {
        column.removeAllViews()
        column.addView(look.text("Hidden and locked apps", 34f, look.ink, true).apply {
            letterSpacing = -0.025f; setPadding(look.px(20), look.px(8), look.px(20), look.px(4))
            isAccessibilityHeading = true
        })
        fun label(k: String) = all[k]?.label ?: AppKey.pkg(k)  // an uninstalled app still shows, so it can be cleared
        if (hidden.isEmpty() && locked.isEmpty()) {
            column.addView(look.text("Nothing hidden or locked. Hold an app in the orbit to hide or lock it.", 15f, look.ink3).apply {
                setPadding(look.px(20), look.px(16), look.px(20), 0)
            })
            return
        }
        if (hidden.isNotEmpty()) section("Hidden", "Hidden apps stay off the orbit and the dock. Search still finds them by name.",
            hidden.sortedBy { label(it).lowercase() }.map { k -> row(k, label(k), all[k]) { on -> HiddenApps.set(this, k, on) } })
        if (locked.isNotEmpty()) section("Locked", "A locked app asks for your screen lock when you open it from Agentic OS. " +
            "Its notifications and Recents are not locked.",
            locked.sortedBy { label(it).lowercase() }.map { k -> row(k, label(k), all[k]) { on -> LockedApps.set(this, k, on) } })
    }

    private fun section(header: String, footer: String, rows: List<View>) {
        column.addView(look.text(header, 13f, look.ink3).apply { setPadding(look.px(32), look.px(22), look.px(32), look.px(7)) })
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = look.glassCard(16); clipToOutline = true
            rows.forEachIndexed { i, r ->
                if (i > 0) addView(View(context).apply { setBackgroundColor(look.hairline) },  // inset past the icon, as iOS
                    LinearLayout.LayoutParams(-1, 1).apply { leftMargin = look.px(60) })
                addView(r)
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = look.px(16); rightMargin = look.px(16) })
        column.addView(look.text(footer, 13f, look.ink3).apply { setPadding(look.px(32), look.px(7), look.px(32), 0) })
    }

    /** App icon, name, and a switch; the whole row flips it (48 dp and up). */
    private fun row(key: String, label: String, e: AppEntry?, flip: (Boolean) -> Unit): View {
        val sw = Switch(this).apply {
            isChecked = true
            isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO  // the row speaks for it
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(look.accent, look.inkA(70)))
        }
        val icon = ImageView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        if (e != null) Icons.into(icon, key) else icon.background = look.round(Color.TRANSPARENT, 7, look.ink3)
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = look.px(56)
            setPadding(look.px(16), look.px(8), look.px(14), look.px(8))
            addView(icon, LinearLayout.LayoutParams(look.px(30), look.px(30)).apply { rightMargin = look.px(14) })
            addView(look.text(label, 17f, look.ink).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(sw)
            fun say() { contentDescription = label; stateDescription = if (sw.isChecked) "On" else "Off" }
            say()
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(v: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(v, info)
                    info.className = Switch::class.java.name; info.isCheckable = true; info.isChecked = sw.isChecked
                }
            }
            isClickable = true; look.press(this, 0.985f)
            setOnClickListener {
                sw.isChecked = !sw.isChecked
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                flip(sw.isChecked); say()
            }
        }
    }

}
