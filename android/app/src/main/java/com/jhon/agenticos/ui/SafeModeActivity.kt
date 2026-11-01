package com.jhon.agenticos.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.jhon.agenticos.core.Crash
import com.jhon.agenticos.launcherApps

/** Opened after 2 home-screen crashes in 2 minutes. Runs in its own process (:safe) with no PC calls, no services, no
 *  WebView, and only framework widgets with fixed colours (nothing from Look, Spring or Motion, so a bug there can't
 *  take safe mode down too): a plain app grid, a way back to One UI Home, and a retry. */
class SafeModeActivity : Activity() {
    private val ground = Color.rgb(0xE6, 0xEE, 0xF4)
    private val ink = Color.rgb(0x1D, 0x2B, 0x3A)
    private val ink2 = Color.rgb(0x4E, 0x60, 0x72)

    private val main = Handler(Looper.getMainLooper())
    private val loader = HandlerThread("safe-loader")
    private lateinit var bg: Handler
    private var apps: List<Pair<String, String>> = emptyList()  // (label, package)
    private val icons = HashMap<String, Drawable?>()            // main thread only
    private lateinit var grid: GridView

    private val cells = object : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(p: Int) = apps[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, old: View?, parent: ViewGroup): View {
            val cell = old as? LinearLayout ?: cell()
            val (label, pkg) = apps[p]
            cell.tag = pkg
            (cell.getChildAt(1) as TextView).text = label
            val icon = cell.getChildAt(0) as ImageView
            if (icons.containsKey(pkg)) icon.setImageDrawable(icons[pkg]) else { icon.setImageDrawable(null); loadIcon(pkg) }
            return cell
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        loader.start(); bg = Handler(loader.looper)
        grid = GridView(this).apply {
            numColumns = 4
            clipToPadding = false
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)  // each cell draws its own press
            adapter = cells
            setOnItemClickListener { v, _, p, _ -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); launch(apps[p].second) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ground)
            addView(text("Safe mode", 28f, ink, true))
            addView(text("The Agentic home stopped twice in two minutes, so it's paused. Your apps are below. " +
                "Chat alerts and the PC link keep running. Installing a new build also clears this.", 15f, ink2, false).apply {
                setLineSpacing(0f, 1.25f); setPadding(0, dp(8), 0, 0)
            })
            addView(LinearLayout(context).apply {
                setPadding(0, dp(20), 0, dp(16))
                addView(button("Use One UI Home", true) {
                    try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) } catch (_: Exception) {}
                }, LinearLayout.LayoutParams(0, dp(52), 1f))
                addView(View(context), LinearLayout.LayoutParams(dp(10), 1))
                addView(button("Try again", false) {
                    try { Crash.ui.clear() } catch (_: Exception) {}
                    try { startActivity(Intent(this@SafeModeActivity, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                    finish()
                }, LinearLayout.LayoutParams(0, dp(52), 1f))
            })
            addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left + dp(20), i.top + dp(24), i.right + dp(20), 0)
                grid.setPadding(0, 0, 0, i.bottom + dp(8))  // the grid scrolls under the navigation bar
                WindowInsets.CONSUMED
            }
        }
        setContentView(root)
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(light, light)
        bg.post {
            val list = try {
                launcherApps().filter { it.second != packageName }.distinctBy { it.second }.sortedBy { it.first.lowercase() }
            } catch (_: Exception) { emptyList() }
            main.post { if (!isDestroyed) { apps = list; cells.notifyDataSetChanged() } }
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        loader.quitSafely()
        super.onDestroy()
    }

    private fun loadIcon(pkg: String) {
        icons[pkg] = null  // marks it requested; the decode runs off the main thread
        bg.post {
            val d = try { packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
            main.post {
                if (isDestroyed) return@post
                icons[pkg] = d
                grid.findViewWithTag<LinearLayout>(pkg)?.let { (it.getChildAt(0) as ImageView).setImageDrawable(d) }
            }
        }
    }

    private fun launch(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    private fun cell() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumHeight = dp(92)
        setPadding(dp(4), dp(8), dp(4), dp(8))
        background = RippleDrawable(ColorStateList.valueOf(0x1F1D2B3A),
            null, GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.WHITE) })
        addView(ImageView(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(text("", 12f, ink2, false).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        if (sp >= 20f) letterSpacing = -0.01f
    }

    private fun button(label: String, primary: Boolean, onTap: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        stateListAnimator = null
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) Color.WHITE else ink)
        background = RippleDrawable(ColorStateList.valueOf(if (primary) 0x33FFFFFF else 0x1F1D2B3A),
            GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(if (primary) ink else Color.WHITE)
                if (!primary) setStroke(dp(1), 0x291D2B3A)
            }, null)
        setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onTap() }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
