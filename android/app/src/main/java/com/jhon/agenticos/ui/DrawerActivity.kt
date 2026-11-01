package com.jhon.agenticos.ui

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** All apps A to Z (every profile; hidden apps stay hidden), with search. Opened with SLOT (or KEY, a prefs key such
 *  as a mode's dock slot) it picks an app for the dock instead of launching it. */
class DrawerActivity : Activity() {
    companion object { const val SLOT = "slot"; const val KEY = "key" }

    private lateinit var look: Look
    private lateinit var search: EditText
    private var picking = false
    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private val changed: () -> Unit = { load() }
    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, old: View?, parent: ViewGroup): View {
            val row = (old as? LinearLayout) ?: LinearLayout(this@DrawerActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = look.px(56)
                addView(ImageView(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                    LinearLayout.LayoutParams(look.px(40), look.px(40)).apply { rightMargin = look.px(14) })
                addView(look.text("", 16f).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            }
            val e = shown[p]
            Icons.into(row.getChildAt(0) as ImageView, e.key)  // cached, or filled in from the loader thread
            (row.getChildAt(1) as TextView).text = e.label
            row.contentDescription = e.label
            return row
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        look = Look(this)
        val slot = intent.getIntExtra(SLOT, -1)
        val key = intent.getStringExtra(KEY) ?: if (slot >= 0) "dock$slot" else null
        picking = key != null
        // browsing all apps: the list first, the keyboard only when he taps search
        if (key == null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        search = EditText(this).apply {
            hint = if (key != null) "Pick an app for the dock" else "Search apps"
            setTextColor(look.ink); setHintTextColor(look.ink3); textSize = 16f
            background = look.round(look.glass, 10); setPadding(look.px(14), 0, look.px(14), 0)
            isSingleLine = true
        }
        val list = ListView(this).apply {
            adapter = this@DrawerActivity.adapter
            divider = null
            setOnItemClickListener { _, row, p, _ ->
                val e = shown.getOrNull(p) ?: return@setOnItemClickListener
                // the dock opens apps by package, so a pick stores the package (a work app's dock spot opens the personal one)
                if (key != null) { getSharedPreferences("aos", MODE_PRIVATE).edit().putString(key, e.pkg).apply(); finish() }
                else LockedApps.launch(this@DrawerActivity, e.key, e.label) {
                    LaunchCounts.bump(this@DrawerActivity, e.key)
                    if (Apps.start(this@DrawerActivity, (row as? ViewGroup)?.getChildAt(0) ?: row, e.key)) finish()
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(look.ground)
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(i.left + look.px(16), i.top + look.px(12), i.right + look.px(16), i.bottom)
                WindowInsets.CONSUMED
            }
            addView(search, LinearLayout.LayoutParams(-1, look.px(48)))
            addView(list, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = look.px(8) })
        })
        look.bars(window)
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filter(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        Apps.listen(this, changed)
        load()
    }

    override fun onDestroy() { Apps.unlisten(changed); super.onDestroy() }

    private fun load() {
        val app = applicationContext
        val mainOnly = picking
        Loader.run {
            Icons.sync(app)
            val hidden = HiddenApps.all(app)
            val apps = Apps.get(app).filter { it.key !in hidden && (!mainOnly || it.main) }
            Loader.main.post { if (!isDestroyed) { all = apps; filter(search.text.toString()) } }
            for (e in apps) Loader.run { Icons.load(app, e) }  // warm the rest, one job each, so scrolling never waits
        }
    }

    private fun filter(q: String) {
        shown = if (q.isBlank()) all else SmartSearch.rank(q, all) { it.label }
        adapter.notifyDataSetChanged()
    }
}
