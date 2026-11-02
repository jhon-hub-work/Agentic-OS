package com.jhon.agenticos.ui

import android.app.Activity
import android.app.role.RoleManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.jhon.agenticos.R
import com.jhon.agenticos.core.Backup
import com.jhon.agenticos.core.Crash
import com.jhon.agenticos.core.Link
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Settings, iOS style: grouped glass rows over the frost, a large title that hands over to the bar as you scroll.
 *  HomeActivity opens it until Finish setup, later from a long press on the health strip. Rows Android lets us
 *  read show their real state; the rest he ticks by opening them. */
class SetupActivity : Activity() {
    private lateinit var look: Look
    private val refresh = mutableListOf<() -> Unit>()
    private val prefs by lazy { getSharedPreferences("aos", MODE_PRIVATE) }
    private val hairline get() = look.hairline  // ink at 12%
    private var built = 0  // Appearance.version this screen was drawn with
    private val io = Executors.newSingleThreadExecutor { Thread(it, "settings-io") }  // file reads, export, import
    private val awm by lazy { AppWidgetManager.getInstance(this) }
    private val host by lazy { AppWidgetHost(applicationContext, WIDGET_HOST_ID) }  // same host id as the widgets page
    private var pendingId = AppWidgetManager.INVALID_APPWIDGET_ID  // a widget being added back
    private var pendingProvider: String? = null
    private var note: String? = null      // what the last export or import did; survives the rebuild after an import
    private var noteView: TextView? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        look = Look(this)
        built = Appearance.version(this)
        state?.let { note = it.getString(NOTE); pendingId = it.getInt(PENDING, pendingId); pendingProvider = it.getString(PROVIDER) }
        pushTransition()
        val roles = getSystemService(RoleManager::class.java)
        fun ticked(key: String) = prefs.getBoolean("setup_$key", false)
        fun tick(key: String) = prefs.edit().putBoolean("setup_$key", true).apply()
        fun word(ok: Boolean?) = when (ok) { true -> "On"; false -> NEEDED; null -> "Check" }
        fun onOff(on: Boolean) = if (on) "On" else "Off"

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val big = look.text("Settings", 34f, look.ink, true).apply { letterSpacing = -0.025f; setPadding(look.px(20), 0, look.px(20), look.px(4)) }
        column.addView(big)
        val cascade = mutableListOf<View>()
        fun section(header: String?, footer: String?, vararg rows: View): TextView? {
            fun add(v: View, lp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2)) { column.addView(v, lp); cascade += v }
            if (header == null) column.addView(View(this), LinearLayout.LayoutParams(-1, look.px(12)))
            else add(look.text(header, 13f, look.ink3).apply { setPadding(look.px(32), look.px(22), look.px(32), look.px(7)) })
            add(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = look.glassCard(16); clipToOutline = true  // row highlights keep the rounded corners
                rows.forEachIndexed { i, r ->
                    if (i > 0) addView(View(context).apply { setBackgroundColor(hairline) },  // inset past the icon, as iOS
                        LinearLayout.LayoutParams(-1, 1).apply { leftMargin = look.px(60) })
                    addView(r)
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = look.px(16); rightMargin = look.px(16) })
            return footer?.let { f -> look.text(f, 13f, look.ink3).apply { setPadding(look.px(32), look.px(7), look.px(32), 0) }.also { add(it) } }
        }

        // an import left widgets Android must OK one by one: first, as iOS puts "Finish setting up" first
        val todo = restoreList()
        if (todo.isNotEmpty()) section("Widgets to add back", "Android asks once for each widget.",
            *todo.map { (p, label) -> row(label, R.drawable.ic_set_widget, "#3FB6EC", { "Add" }) { restoreOne(p) } }.toTypedArray())

        // first, as iOS puts Display & Brightness near the top: the tile wears his current accent
        section(null, null,
            row("Appearance", R.drawable.ic_appearance_tile, "#%06X".format(look.accent and 0xFFFFFF), { look.theme.name }) {
                it.startActivity(Intent(it, AppearanceActivity::class.java)) })
        section("Home screen", "Home button opens Agentic OS. One UI Home stays installed.",
            row("Default home app", R.drawable.ic_set_home, "#2F7FD0", { word(Checks.isHome(this)) }) {
                startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), ROLE) },
            row("App layout", R.drawable.ic_set_layout, "#3D7BD9", { Layouts.name(Layouts.current(this)) }) {
                LayoutPicker.show(it, look, Layouts.current(it)) { id -> Layouts.set(this, id); refresh.forEach { r -> r() } } },
            row("Hidden and locked apps", R.drawable.ic_set_hidden, "#5B6F86", {
                (HiddenApps.all(this) + prefs.getStringSet("locked_apps", null).orEmpty()).size.let { if (it == 0) "None" else "$it" } }) {
                it.startActivity(Intent(it, HiddenAppsActivity::class.java)) })
        section("Jharvis", "Side button: pick Agentic OS as Digital assistant app (Claude to switch back), and on Samsung " +
            "set Side button, Press and hold to Digital assistant. Island: new alerts grow from the camera for 4 s, tap " +
            "opens, swipe up hides. Turn it on in Accessibility, Installed apps.",
            row("Side button", R.drawable.ic_set_side, "#3FB6EC", { onOff(Checks.isAssistant(this)) }, switch = true) {
                it.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) },
            row("Island", R.drawable.ic_set_island, "#1F2D44", { onOff(Checks.islandOn(this)) }, switch = true) {
                it.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        section("Keep it running", "Notification access greyed out: App info, three dots, Allow restricted settings. " +
            "Never sleeping: add Agentic OS and Tailscale (Samsung can't report it, so it ticks once opened). Tailscale: " +
            "its gear in VPN settings, Always-on on, Block connections off, battery Unrestricted.",
            row("Notification access", R.drawable.ic_set_bell, "#F0883E", { word(Checks.alertsGranted(this)) }) { Checks.openListenerSettings(it) },
            row("Unrestricted battery", R.drawable.ic_set_battery, "#34C759", { word(Checks.battery(this).ok) }) { Checks.battery(this).fix(it) },
            row("Never sleeping apps", R.drawable.ic_set_moon, "#5B6F86",
                { word(ticked("sleep").takeIf { it } ?: Checks.sleep(this).ok.takeIf { it == false }) }) { Checks.openNeverSleeping(it); tick("sleep") },
            row("Tailscale always on", R.drawable.ic_set_shield, "#24507A", { word(if (ticked("vpn")) true else null) }) {
                it.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)); tick("vpn") })
        noteView = section("Your setup", note ?: SETUP_NOTE,
            row("Export setup", R.drawable.ic_set_export, "#2F7FD0", {
                prefs.getLong("backup_last", 0L).takeIf { it > 0 }?.let { DateFormat.format("d MMM", it).toString() }.orEmpty() }) { export() },
            row("Import setup", R.drawable.ic_set_import, "#24507A", { "" }) { import() })
        val problems = section("About", "",
            row("Version", R.drawable.ic_set_info, "#8E9BA8", { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }, slow = true),
            row("PC", R.drawable.ic_set_pc, "#4E6072", {
                Link.lastBeat("pc").takeIf { it > 0 }?.let { "Last seen " + DateFormat.getTimeFormat(this).format(it) } ?: "Not seen yet" }, slow = true),
            row("Recent problems", R.drawable.ic_set_history, "#8E9BA8", { recentProblems().size.let { if (it == 0) "None" else "$it" } }, slow = true))
        // the last few lines of exits.log under About, small and in ink 3: there to read, not to alarm
        problems?.visibility = View.GONE
        refresh += {
            bg {
                val lines = recentProblems()
                runOnUiThread {
                    if (isDestroyed || problems == null) return@runOnUiThread
                    problems.text = lines.joinToString("\n") { if (it.length > 90) it.take(89) + "…" else it }
                    problems.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
        if (!prefs.getBoolean("setup_done", false)) column.addView(look.button("Finish setup", true) {
            prefs.edit().putBoolean("setup_done", true).apply(); finish()
        }.also { cascade += it }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(look.px(16), look.px(28), look.px(16), 0) })

        val scroll = ScrollView(this).apply { clipToPadding = false; addView(column) }
        val fill = ColorDrawable(look.bar)  // frosted bar: the ground, mostly opaque so text under it blurs out
        val hair = View(this).apply { setBackgroundColor(hairline) }
        val small = look.text("Settings", 17f, look.ink, true).apply { gravity = Gravity.CENTER }
        val back = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumWidth = look.px(48); setPadding(look.px(6), 0, look.px(12), 0)
            contentDescription = "Back to Home"
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_set_back); imageTintList = ColorStateList.valueOf(look.navy) },
                LinearLayout.LayoutParams(look.px(24), look.px(24)))
            addView(look.text("Home", 17f, look.navy))
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
        // after setContentView: before it the window has no decor, so no insets controller (crashed on open, 1 Nov)
        look.bars(window)

        // iOS large-title collapse, 1:1 with the scroll: the big title fades as it slides under the bar, then the
        // bar frosts in and the small title rises into it
        fun collapse(y: Int) {
            val span = big.height.coerceAtLeast(1).toFloat()
            val t = (y / span).coerceIn(0f, 1f)
            big.alpha = 1 - t
            fill.alpha = (t * 255).toInt(); hair.alpha = t
            val s = ((y - span * 0.6f) / (span * 0.4f)).coerceIn(0f, 1f)
            small.alpha = s; small.translationY = (1 - s) * look.px(6)
        }
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> collapse(y) }
        collapse(0)

        if (state == null && !reducedMotion(this)) cascade.forEachIndexed { i, v ->  // headers, groups, footers arrive in turn
            v.alpha = 0f; v.translationY = look.px(20).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(90L + i * 35).setDuration(620).setInterpolator(SpringCurve(0.8f)).start()
        }
    }

    override fun onResume() {
        super.onResume()
        if (built != Appearance.version(this)) { recreate(); return }  // back from Appearance with a new look
        refresh.forEach { it() }  // back from Settings: re-read what changed
    }

    @Suppress("DEPRECATION")  // overridePendingTransition: only the API 33 path
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(R.anim.set_pop_in, R.anim.set_pop_out)
    }

    /** iOS push: slides in from the right over the parent, which drifts left and dims; back reverses it. */
    @Suppress("DEPRECATION")
    private fun pushTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.set_push_in, R.anim.set_push_out)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.set_pop_in, R.anim.set_pop_out)
        } else overridePendingTransition(R.anim.set_push_in, R.anim.set_push_out)
    }

    /** One row: coloured icon tile, title, then the value word (or a switch for a choice) and a chevron. value() is
     *  re-read on every resume (slow = read off the main thread: disk or package manager). open = null makes it a plain
     *  info row with no press. */
    private fun row(title: String, icon: Int, tile: String, value: () -> String, switch: Boolean = false, slow: Boolean = false,
                    open: ((Activity) -> Unit)? = null): View {
        val word = look.text("", 17f, look.ink3)
        // a switch that mirrors the real state; the row opens the system screen that actually changes it
        val sw = if (!switch) null else Switch(this).apply {
            isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(look.glacier, look.inkA(70)))
        }
        val r = object : LinearLayout(this) {
            // highlight on the touch-down frame, not after the scroll view's tap delay
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                if (open != null && e.actionMasked == MotionEvent.ACTION_DOWN) isPressed = true
                return super.dispatchTouchEvent(e)
            }
        }.apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = look.px(52); setPadding(look.px(16), look.px(8), look.px(14), look.px(8))
            addView(ImageView(context).apply {
                setImageResource(icon); background = look.round(Color.parseColor(tile), 7)
                setPadding(look.px(5), look.px(5), look.px(5), look.px(5))
            }, LinearLayout.LayoutParams(look.px(30), look.px(30)).apply { rightMargin = look.px(14) })
            addView(look.text(title, 17f, look.ink), LinearLayout.LayoutParams(0, -2, 1f))
            addView(sw ?: word)
            if (open != null) {
                if (sw == null) addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_set_chevron); imageTintList = ColorStateList.valueOf(look.ink3); alpha = 0.6f
                }, LinearLayout.LayoutParams(look.px(16), look.px(16)).apply { leftMargin = look.px(6) })
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(look.pressed))
                    setExitFadeDuration(250)  // on instantly, fades off like iOS
                }
                look.press(this, 0.985f); setOnClickListener { open(this@SetupActivity) }
            }
        }
        r.contentDescription = title
        fun show(v: String) {
            word.text = v; word.setTextColor(if (v == NEEDED) look.bad else look.ink3)
            sw?.isChecked = v == "On"
            r.contentDescription = if (v.isEmpty()) title else "$title, $v"
        }
        fun read() = try { value() } catch (_: Exception) { "Can't check" }
        refresh += { if (!slow) show(read()) else bg { val v = read(); runOnUiThread { if (!isDestroyed) show(v) } } }
        return r
    }

    private fun bg(job: () -> Unit) { try { io.execute(job) } catch (_: RejectedExecutionException) {} }  // closing: drop it

    private fun say(s: String) { note = s; noteView?.text = s }

    /** exits.log (STABILITY writes it in Link.dir): the last few non-blank lines. */
    private fun recentProblems(): List<String> =
        File(Link.dir, "exits.log").takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { Crash.isProblem(it) }?.takeLast(3).orEmpty()

    // ── export and import: one JSON file through Android's file picker (no permission); core/Backup.kt is the format ──

    private fun export() = try {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "agentic-os-setup-" + DateFormat.format("yyyyMMdd", System.currentTimeMillis()) + ".json"), EXPORT)
    } catch (_: Exception) { say("Couldn't open Files on this phone.") }

    private fun import() = try {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream")), IMPORT)
    } catch (_: Exception) { say("Couldn't open Files on this phone.") }

    private fun saveTo(uri: Uri) = bg {
        val msg = try {
            val widgets = providers()
            val app = try { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() } catch (_: Exception) { "" }
            val out = try { contentResolver.openOutputStream(uri, "wt") } catch (_: Exception) { contentResolver.openOutputStream(uri) }
            (out ?: throw IOException("no stream")).use { Backup.write(this, it, widgets, app) }
            prefs.edit().putLong("backup_last", System.currentTimeMillis()).apply()
            "Saved." + if (prefs.getString(Appearance.WALL, null) == Appearance.PHOTO) " Your photo isn't in the file; pick it again after an import." else ""
        } catch (_: Exception) { "Couldn't save the file." }
        runOnUiThread { if (!isDestroyed) { say(msg); refresh.forEach { it() } } }
    }

    /** Reads and checks the file, writes his setup back, bumps the look so Home rebuilds, then adds back the widgets
     *  Android allows without asking. The rest wait at the top of Settings for one tap each. */
    private fun loadFrom(uri: Uri) = bg {
        val msg = try {
            val s = (contentResolver.openInputStream(uri) ?: throw Backup.Bad("Couldn't open that file.")).use { Backup.read(it) }
            Backup.apply(this, s)
            val photoGone = prefs.getString(Appearance.WALL, null) == Appearance.PHOTO && !Wallpaper.file(this).exists()
            Appearance.edit(this) { if (photoGone) putString(Appearance.WALL, Appearance.FROST) }  // bumps the version: Home rebuilds
            val (added, todo, missing) = rebind(s.widgets)
            listOfNotNull("Setup imported.",
                if (added > 0) "$added ${plural(added, "widget")} added back." else null,
                if (todo > 0) "$todo ${plural(todo, "widget")} need a tap, at the top." else null,
                if (missing > 0) "$missing ${plural(missing, "widget")} need apps that aren't installed." else null,
                if (photoGone) "Pick your photo again in Appearance." else null).joinToString(" ")
        } catch (e: Backup.Bad) { e.message ?: "Couldn't import that file." }
        catch (_: Exception) { "Couldn't import that file." }
        runOnUiThread { if (!isDestroyed) { note = msg; recreate() } }
    }

    /** The providers on the widgets page, in order. Off the main thread. */
    private fun providers() = WidgetStore.ids(this).mapNotNull { id ->
        try { awm.getAppWidgetInfo(id)?.provider?.flattenToString() } catch (_: Exception) { null }
    }

    /** Widgets the file names that this phone doesn't show yet: bound where Android allows it and the widget needs no
     *  setup screen, else queued for a tap. Off the main thread. Returns added, queued, not installed.
     *  shortcut: main profile only; work-profile widgets come back as "not installed" until someone needs them. */
    private fun rebind(wanted: List<String>): Triple<Int, Int, Int> {
        val have = providers().toMutableList()
        val installed = try { awm.getInstalledProvidersForProfile(Process.myUserHandle()) } catch (_: Exception) { emptyList() }
        val todo = JSONArray(); var added = 0; var missing = 0
        for (p in wanted) {
            if (have.remove(p)) continue  // already on the page: an import over a live setup adds only what's missing
            val info = installed.firstOrNull { it.provider.flattenToString() == p }
            if (info == null) { missing++; continue }
            val label = try { info.loadLabel(packageManager) } catch (_: Exception) { null } ?: info.provider.packageName
            if (!needsSetup(info)) {
                val id = try { host.allocateAppWidgetId() } catch (_: Exception) { AppWidgetManager.INVALID_APPWIDGET_ID }
                val ok = id != AppWidgetManager.INVALID_APPWIDGET_ID &&
                    try { awm.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null) } catch (_: Exception) { false }
                if (ok) { WidgetStore.add(this, id); added++; continue }
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) try { host.deleteAppWidgetId(id) } catch (_: Exception) {}
            }
            todo.put(JSONObject().put("p", p).put("l", label))
        }
        prefs.edit().putString(RESTORE, todo.toString()).commit()
        return Triple(added, todo.length(), missing)
    }

    private fun plural(n: Int, w: String) = if (n == 1) w else w + "s"

    private fun needsSetup(info: AppWidgetProviderInfo) =
        info.configure != null && info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL == 0

    private fun restoreList(): List<Pair<String, String>> = try {
        val a = JSONArray(prefs.getString(RESTORE, null) ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("p") to it.optString("l", "Widget") }.filter { it.first.isNotEmpty() }
    } catch (_: Exception) { emptyList() }

    private fun forget(provider: String?) {
        val left = restoreList().toMutableList()
        left.indexOfFirst { it.first == provider }.takeIf { it >= 0 }?.let { left.removeAt(it) }
        prefs.edit().putString(RESTORE, JSONArray(left.map { JSONObject().put("p", it.first).put("l", it.second) }).toString()).apply()
    }

    /** One tap, one widget, as the widget picker does it: bind, Android's ask if needed, the widget's setup screen. */
    private fun restoreOne(provider: String) {
        if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID) return  // one at a time
        val info = try { awm.getInstalledProvidersForProfile(Process.myUserHandle()).firstOrNull { it.provider.flattenToString() == provider } }
            catch (_: Exception) { null }
        if (info == null) { forget(provider); note = "That widget's app isn't installed."; recreate(); return }
        pendingId = try { host.allocateAppWidgetId() } catch (_: Exception) { return say("Couldn't add that widget.") }
        pendingProvider = provider
        val ok = try { awm.bindAppWidgetIdIfAllowed(pendingId, info.profile, info.provider, null) } catch (_: Exception) { false }
        if (ok) return configure(info)
        try {
            startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile), BIND)
        } catch (_: Exception) { drop(); say("Couldn't add that widget.") }
    }

    private fun configure(info: AppWidgetProviderInfo?) {
        if (info == null) { drop(); return say("Couldn't add that widget.") }
        if (!needsSetup(info)) return restored()
        try { host.startAppWidgetConfigureActivityForResult(this, pendingId, 0, CONFIGURE, null) }
        catch (_: Exception) { drop(); say("Couldn't add that widget.") }
    }

    private fun restored() {
        WidgetStore.add(this, pendingId)
        forget(pendingProvider)
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID; pendingProvider = null
        Haptics.confirm(window.decorView)
        recreate()
    }

    /** Hand an unused id back so none leak. */
    private fun drop() {
        val id = pendingId
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID; pendingProvider = null
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) bg { try { host.deleteAppWidgetId(id) } catch (_: Exception) {} }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")  // the result API needs AndroidX; this app has none
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        when (req) {
            EXPORT -> if (res == RESULT_OK) data?.data?.let { saveTo(it) }
            IMPORT -> if (res == RESULT_OK) data?.data?.let { loadFrom(it) }
            BIND, CONFIGURE -> {
                if (pendingId == AppWidgetManager.INVALID_APPWIDGET_ID) return
                if (res != RESULT_OK) return drop()  // he said no, or backed out of the widget's setup
                if (req == BIND) configure(try { awm.getAppWidgetInfo(pendingId) } catch (_: Exception) { null }) else restored()
            }
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString(NOTE, note); out.putInt(PENDING, pendingId); out.putString(PROVIDER, pendingProvider)
    }

    override fun onDestroy() {
        io.shutdown()  // a running export or import still finishes; nothing new starts
        super.onDestroy()
    }

    private companion object {
        const val NEEDED = "Needed"
        const val SETUP_NOTE = "One file with your dock, apps, look, modes and widgets. Not your PC pairing or photo. Import replaces this setup."
        const val RESTORE = "restore_widgets"  // Backup keeps it on this install only
        const val NOTE = "note"; const val PENDING = "pending"; const val PROVIDER = "provider"
        const val ROLE = 1; const val EXPORT = 2; const val IMPORT = 3; const val BIND = 4; const val CONFIGURE = 5
    }
}
