package com.jhon.agenticos.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import com.jhon.agenticos.core.osHttp
import org.json.JSONArray
import org.json.JSONObject

/** Which smart group each app sits in ("aos" prefs "app_groups", JSON `{"<app key>": {"g": "Work", "by": "ai"}}`).
 *  Jharvis sorts apps on the PC (POST /api/apps/groups); until it answers, or while the PC is off, a local guess from
 *  the app's category and name stands in. His own moves always win, then Jharvis, then the guess. */
object AppGroups {
    val NAMES = listOf("Work", "Chats", "Money", "Create", "Play", "Tools", "Other")
    internal const val MANUAL = "manual"; internal const val AI = "ai"; internal const val GUESS = "guess"
    private const val PREF = "app_groups"
    private const val RETRY_MS = 10 * 60_000L  // PC unreachable: try again after this, not on every resume

    internal class Pick(val group: String, val by: String)

    private val lock = Any()
    private val asked = HashSet<String>()      // keys sent to Jharvis this process (lock)
    @Volatile private var failedAt = 0L

    // ── Pure parts, unit tested ─────────────────────────────────────────────────────────────────

    internal fun rank(by: String) = when (by) { MANUAL -> 3; AI -> 2; GUESS -> 1; else -> 0 }

    /** The pick that stands: a new one replaces the old only when it comes from the same or a stronger source. */
    internal fun merge(old: Pick?, new: Pick): Pick = if (old == null || rank(new.by) >= rank(old.by)) new else old

    internal fun parse(s: String?): MutableMap<String, Pick> {
        val out = HashMap<String, Pick>()
        val o = try { JSONObject(s ?: return out) } catch (_: Exception) { return out }  // a torn value is "nothing sorted yet"
        for (k in o.keys()) {
            val p = o.optJSONObject(k) ?: continue
            val g = p.optString("g")
            if (g in NAMES) out[k] = Pick(g, p.optString("by", GUESS))
        }
        return out
    }

    internal fun format(m: Map<String, Pick>) = JSONObject().apply {
        for ((k, p) in m) put(k, JSONObject().put("g", p.group).put("by", p.by))
    }.toString()

    /** Jharvis's answer `{"groups": {"<key>": "Work"}}`, unknown group names dropped. */
    internal fun parseAnswer(s: String): Map<String, String> {
        val g = try { JSONObject(s).optJSONObject("groups") } catch (_: Exception) { null } ?: return emptyMap()
        return g.keys().asSequence().associateWith { g.optString(it) }.filterValues { it in NAMES }
    }

    /** The category's name as Android spells it, for Jharvis. */
    internal fun categoryName(cat: Int) = when (cat) {
        ApplicationInfo.CATEGORY_GAME -> "game"; ApplicationInfo.CATEGORY_AUDIO -> "audio"
        ApplicationInfo.CATEGORY_VIDEO -> "video"; ApplicationInfo.CATEGORY_IMAGE -> "image"
        ApplicationInfo.CATEGORY_SOCIAL -> "social"; ApplicationInfo.CATEGORY_NEWS -> "news"
        ApplicationInfo.CATEGORY_MAPS -> "maps"; ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
        ApplicationInfo.CATEGORY_ACCESSIBILITY -> "accessibility"
        else -> "undefined"
    }

    // whole words in the name or package; checked in this order, so a bank tagged "productivity" still lands in Money
    private val words = listOf(
        "Money" to setOf("gcash", "maya", "paymaya", "bdo", "bpi", "metrobank", "unionbank", "landbank", "seabank", "gotyme",
            "tonik", "paypal", "wise", "revolut", "amex", "binance", "coins", "gpay", "wallet", "pay", "cash", "money", "finance"),
        "Chats" to setOf("whatsapp", "messenger", "telegram", "viber", "signal", "discord", "line", "wechat", "messages", "sms",
            "phone", "dialer", "contacts", "facebook", "instagram", "threads", "snapchat", "twitter", "reddit"),
        "Work" to setOf("gmail", "mail", "outlook", "teams", "slack", "zoom", "meet", "docs", "sheets", "slides", "drive",
            "calendar", "office", "word", "excel", "powerpoint", "onedrive", "notion", "github", "linkedin", "keep", "notes", "jira"),
        "Play" to setOf("netflix", "youtube", "spotify", "tiktok", "twitch", "roblox", "steam", "games", "game", "disney", "prime"),
        "Create" to setOf("camera", "gallery", "photos", "capcut", "canva", "lightroom", "snapseed", "editor", "studio", "recorder"),
        "Tools" to setOf("settings", "clock", "calculator", "files", "weather", "maps", "chrome", "brave", "browser", "store",
            "authenticator", "vpn", "scanner", "flashlight", "translate", "lens", "health", "wear", "home"))
    private val splitter = Regex("[^a-z0-9]+")
    // pieces of words, for names like "Mybank" or "ChatGo"
    private val stems = listOf("Money" to listOf("bank", "wallet", "crypto", "invest"), "Chats" to listOf("messag", "chat"),
        "Work" to listOf("mail"), "Play" to listOf("game"), "Create" to listOf("camera", "photo", "video", "music"))

    /** Where an app goes before Jharvis has sorted it: its name first, then the category it declares. */
    internal fun guess(category: Int, label: String, pkg: String): String {
        val tokens = (label.lowercase().split(splitter) + pkg.lowercase().split('.', '_')).filter { it.isNotEmpty() }
        words.firstOrNull { (_, w) -> tokens.any { it in w } }?.let { return it.first }
        stems.firstOrNull { (_, s) -> tokens.any { t -> s.any { it in t } } }?.let { return it.first }
        return when (category) {
            ApplicationInfo.CATEGORY_SOCIAL -> "Chats"
            ApplicationInfo.CATEGORY_GAME -> "Play"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Work"
            ApplicationInfo.CATEGORY_IMAGE, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_AUDIO -> "Create"
            ApplicationInfo.CATEGORY_MAPS, ApplicationInfo.CATEGORY_ACCESSIBILITY -> "Tools"
            else -> "Other"
        }
    }

    /** The cards in their fixed order, each with its apps in the order given (most used first); empty groups left out. */
    internal fun arrange(ranked: List<String>, pick: Map<String, String>): List<Pair<String, List<String>>> =
        NAMES.map { n -> n to ranked.filter { (pick[it]?.takeIf { g -> g in NAMES } ?: "Other") == n } }.filter { it.second.isNotEmpty() }

    /** Apps that still only have a guess (or nothing): the ones to send to Jharvis. */
    internal fun unsorted(m: Map<String, Pick>, keys: Collection<String>) = keys.filter { (m[it]?.let { p -> rank(p.by) } ?: 0) < rank(AI) }

    // ── Prefs and the PC ────────────────────────────────────────────────────────────────────────

    private fun prefs(c: Context) = c.getSharedPreferences("aos", Context.MODE_PRIVATE)

    private fun edit(c: Context, change: (MutableMap<String, Pick>) -> Boolean): Map<String, Pick> = synchronized(lock) {
        val m = parse(prefs(c).getString(PREF, null))
        if (change(m)) prefs(c).edit().putString(PREF, format(m)).apply()
        m
    }

    /** Loader thread. Every app's group (key -> name), guessing for apps never sorted. */
    fun groups(c: Context, apps: List<AppEntry>): Map<String, String> = edit(c) { m ->
        var added = false
        for (e in apps) if (e.key !in m) { m[e.key] = Pick(guess(e.info.applicationInfo.category, e.label, e.pkg), GUESS); added = true }
        added
    }.mapValues { it.value.group }

    /** His own move: stands over Jharvis and the guess for good. Any thread (the write runs on the loader thread). */
    fun move(c: Context, key: String, group: String) {
        if (group !in NAMES) return
        val app = c.applicationContext
        Loader.run { edit(app) { it[key] = merge(it[key], Pick(group, MANUAL)); true } }
    }

    /** Loader thread. Asks Jharvis about apps that only have a guess, once each per process, on the network thread;
     *  `changed` runs on the main thread when his answer moved anything. PC off: the guesses stay, retried later. */
    fun ask(c: Context, apps: List<AppEntry>, changed: () -> Unit) {
        if (SystemClock.elapsedRealtime() - failedAt < RETRY_MS && failedAt != 0L) return
        val app = c.applicationContext
        val todo = synchronized(lock) {
            unsorted(parse(prefs(app).getString(PREF, null)), apps.map { it.key }).filter { asked.add(it) }.toSet()
        }
        if (todo.isEmpty()) return
        val body = JSONObject().put("apps", JSONArray().apply {
            for (e in apps) if (e.key in todo) put(JSONObject().put("key", e.key).put("label", e.label)
                .put("category", categoryName(e.info.applicationInfo.category)))
        }).toString().toByteArray()
        Loader.net {
            val answer = try { parseAnswer(String(osHttp("/api/apps/groups", "POST", "application/json", body, 30000))) }
            catch (_: Exception) {
                failedAt = SystemClock.elapsedRealtime()
                synchronized(lock) { asked.removeAll(todo) }
                return@net
            }
            var moved = false
            edit(app) { m ->
                for ((k, g) in answer) if (k in todo) {
                    val old = m[k]; val now = merge(old, Pick(g, AI)); m[k] = now
                    if (old?.group != now.group) moved = true
                }
                answer.isNotEmpty()
            }
            if (moved) Loader.main.post(changed)
        }
    }
}
