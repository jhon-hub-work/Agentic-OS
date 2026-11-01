package com.jhon.agenticos.ui

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.jhon.agenticos.R

/** Add widget, iOS style: every widget on the phone grouped by app (icon, name, size), on glass over the frost.
 *  Picking one binds it (Android asks once per app, "Allow Agentic OS to create widgets"), runs the widget's own
 *  setup screen if it has one, saves the id and goes back; the widgets page picks it up on its next start(). */
class WidgetPickerActivity : Activity() {
    private class Item(val info: AppWidgetProviderInfo, val label: CharSequence, val icon: Drawable?, val app: String)

    private lateinit var look: Look
    private lateinit var host: AppWidgetHost
    private lateinit var awm: AppWidgetManager
    private lateinit var column: LinearLayout
    private var pending = AppWidgetManager.INVALID_APPWIDGET_ID  // allocated, not saved yet
    private val hairline get() = look.hairline

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        look = Look(this)
        host = AppWidgetHost(applicationContext, WIDGET_HOST_ID)
        awm = AppWidgetManager.getInstance(this)
        pending = state?.getInt(PENDING, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        pushTransition()

        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val big = look.text("Add widget", 34f, look.ink, true).apply { letterSpacing = -0.025f; setPadding(look.px(20), 0, look.px(20), look.px(4)) }
        column.addView(big)
        val scroll = ScrollView(this).apply { addView(column); isVerticalScrollBarEnabled = false; clipToPadding = false }

        // the same frosted bar as Settings: the big title hands over to it as the list scrolls
        val fill = ColorDrawable(look.bar)
        val hair = View(this).apply { setBackgroundColor(hairline) }
        val small = look.text("Add widget", 17f, look.ink, true).apply { gravity = Gravity.CENTER }
        val back = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumWidth = look.px(48); setPadding(look.px(6), 0, look.px(12), 0)
            contentDescription = "Back to widgets"
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_set_back); imageTintList = ColorStateList.valueOf(look.navy) },
                LinearLayout.LayoutParams(look.px(24), look.px(24)))
            addView(look.text("Widgets", 17f, look.navy))
            isClickable = true; look.press(this, 0.92f); setOnClickListener { finish() }
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

        fun collapse(y: Int) {
            val t = (y / big.height.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            big.alpha = 1 - t
            fill.alpha = (t * 255).toInt(); hair.alpha = t
            val s = ((t - 0.6f) / 0.4f).coerceIn(0f, 1f)
            small.alpha = s; small.translationY = (1 - s) * look.px(6)
        }
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> collapse(y) }
        collapse(0)
        load(animate = state == null)
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt(PENDING, pending) }

    /** Providers, labels and icons are package-manager reads: off the main thread. */
    private fun load(animate: Boolean) = Thread {
        val pm = packageManager
        val dpi = resources.displayMetrics.densityDpi
        val providers = try { awm.getInstalledProvidersForProfile(Process.myUserHandle()) } catch (_: Exception) { emptyList() }
        val groups = providers
            .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
            .map { info ->
                val pkg = info.provider.packageName
                val app = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
                Item(info, try { info.loadLabel(pm) } catch (_: Exception) { app }, try { info.loadIcon(this, dpi) } catch (_: Exception) { null }, app)
            }
            .sortedWith(compareBy({ it.app.lowercase() }, { it.label.toString().lowercase() }))
            .groupBy { it.info.provider.packageName }
        runOnUiThread { if (!isDestroyed) fill(groups.values, animate) }
    }.start()

    private fun fill(groups: Collection<List<Item>>, animate: Boolean) {
        if (groups.isEmpty()) {
            column.addView(look.text("No apps on this phone offer widgets.", 15f, look.ink2).apply { setPadding(look.px(20), look.px(16), look.px(20), 0) })
            return
        }
        val arriving = ArrayList<View>()
        for (items in groups) {
            val header = look.text(items[0].app, 13f, look.ink3).apply { setPadding(look.px(32), look.px(22), look.px(32), look.px(7)) }
            val group = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = look.glassCard(16); clipToOutline = true
                items.forEachIndexed { i, it ->
                    if (i > 0) addView(View(context).apply { setBackgroundColor(hairline) },  // inset past the icon, as iOS
                        LinearLayout.LayoutParams(-1, 1).apply { leftMargin = look.px(66) })
                    addView(row(it))
                }
            }
            column.addView(header)
            column.addView(group, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = look.px(16); rightMargin = look.px(16) })
            arriving += header; arriving += group
        }
        if (animate && !reducedMotion(this)) arriving.forEachIndexed { i, v ->  // the first screenful arrives in turn
            v.alpha = 0f; v.translationY = look.px(20).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(60L + minOf(i, 10) * 35L).setDuration(560).setInterpolator(SpringCurve(1f)).start()
        }
    }

    private fun row(item: Item): View {
        val hint = sizeHint(item.info)
        return object : LinearLayout(this) {
            // highlight on the touch-down frame, not after the scroll view's tap delay
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) isPressed = true
                return super.dispatchTouchEvent(e)
            }
        }.apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = look.px(60)
            setPadding(look.px(16), look.px(10), look.px(14), look.px(10))
            addView(ImageView(context).apply { setImageDrawable(item.icon); scaleType = ImageView.ScaleType.FIT_CENTER },
                LinearLayout.LayoutParams(look.px(36), look.px(36)).apply { rightMargin = look.px(14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(look.text(item.label, 17f, look.ink).apply { maxLines = 2 })
                addView(look.text(hint, 13f, look.ink3))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_set_chevron); imageTintList = ColorStateList.valueOf(look.ink3); alpha = 0.6f
            }, LinearLayout.LayoutParams(look.px(16), look.px(16)).apply { leftMargin = look.px(6) })
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(look.pressed))
                setExitFadeDuration(250)  // on instantly, fades off like iOS
            }
            contentDescription = "${item.label}, ${item.app}, $hint"
            look.press(this, 0.985f); setOnClickListener { pick(item.info) }
        }
    }

    /** Cells across by cells down, as a home screen would place it: the widget's own target, else its minimum. */
    private fun sizeHint(info: AppWidgetProviderInfo): String {
        val d = resources.displayMetrics.density
        val w = info.targetCellWidth.takeIf { it > 0 } ?: widgetCells((info.minWidth / d).toInt())
        val h = info.targetCellHeight.takeIf { it > 0 } ?: widgetCells((info.minHeight / d).toInt())
        return "$w × $h"
    }

    // ── Bind, set up, save ────────────────────────────────────────────────────────────────────────

    private fun pick(info: AppWidgetProviderInfo) {
        if (pending != AppWidgetManager.INVALID_APPWIDGET_ID) return  // one at a time
        pending = try { host.allocateAppWidgetId() } catch (_: Exception) { return failed() }
        val ok = try { awm.bindAppWidgetIdIfAllowed(pending, info.profile, info.provider, null) } catch (_: Exception) { false }
        if (ok) return configure(info)
        try {
            startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pending)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile), BIND)
        } catch (_: Exception) { drop(); failed() }
    }

    private fun configure(info: AppWidgetProviderInfo?) {
        if (info == null) { drop(); return failed() }
        if (!widgetNeedsSetup(info.configure != null, info.widgetFeatures)) return added()
        try { host.startAppWidgetConfigureActivityForResult(this, pending, 0, CONFIGURE, null) }
        catch (_: Exception) { drop(); failed() }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (pending == AppWidgetManager.INVALID_APPWIDGET_ID) return
        if (res != RESULT_OK) return drop()  // he said no, or backed out of the setup screen
        when (req) {
            BIND -> configure(try { awm.getAppWidgetInfo(pending) } catch (_: Exception) { null })
            CONFIGURE -> added()
        }
    }

    private fun added() {
        WidgetStore.add(this, pending)
        pending = AppWidgetManager.INVALID_APPWIDGET_ID
        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        finish()
    }

    /** Hand an unused id back so none leak. */
    private fun drop() {
        val id = pending
        pending = AppWidgetManager.INVALID_APPWIDGET_ID
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) Thread { try { host.deleteAppWidgetId(id) } catch (_: Exception) {} }.start()
    }

    private fun failed() = Toast.makeText(this, "Couldn't add that widget.", Toast.LENGTH_SHORT).show()

    @Suppress("DEPRECATION")  // overridePendingTransition: only the API 33 path
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(R.anim.set_pop_in, R.anim.set_pop_out)
    }

    /** iOS push, the same as Settings: slides in from the right, back reverses it. */
    @Suppress("DEPRECATION")
    private fun pushTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.set_push_in, R.anim.set_push_out)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.set_pop_in, R.anim.set_pop_out)
        } else overridePendingTransition(R.anim.set_push_in, R.anim.set_push_out)
    }

    private companion object {
        const val PENDING = "pending"
        const val BIND = 1
        const val CONFIGURE = 2
    }
}
