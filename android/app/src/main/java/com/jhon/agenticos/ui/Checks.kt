package com.jhon.agenticos.ui

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.jhon.agenticos.ChatListener
import com.jhon.agenticos.ListenService
import com.jhon.agenticos.MainActivity
import com.jhon.agenticos.core.Link

/** One thing that must be true for the phone to keep working. ok = null: off by his choice (mic switched off). */
class Check(val label: String, val ok: Boolean?, val word: String, val fix: (Activity) -> Unit)

/** What the health strip and the setup checklist both read. Re-read at every resume, because Samsung reverts
 *  settings after updates and never says so. */
object Checks {
    const val LISTENER_STALE_MS = 120_000L  // ChatListener beats every 3 s; a slow PC can stretch one turn to ~70 s

    enum class Pc { OK, UNPAIRED, OFF, UNKNOWN }

    /** A check that throws shows as "can't tell" instead of taking the home screen down with it. */
    fun strip(a: Activity, pc: Pc) = listOf<(Activity) -> Check>({ pcCheck(it, pc) }, ::vpn, ::alerts, ::mic, ::battery, ::sleep)
        .map { check -> try { check(a) } catch (e: Exception) { Check("?", null, "Can't check") {} } }

    fun pcCheck(a: Context, pc: Pc): Check {
        val since = Link.lastBeat("pc").takeIf { it > 0 }?.let { " since " + android.text.format.DateFormat.getTimeFormat(a).format(it) } ?: ""
        return when (pc) {
            Pc.OK -> Check("PC", true, "PC on") {}
            Pc.UNKNOWN -> Check("PC", null, "PC checking") {}
            Pc.UNPAIRED -> Check("PC", false, "Not paired") { it.startActivity(Intent(it, MainActivity::class.java)) }
            Pc.OFF -> Check("PC", false, "PC off$since") { it.startActivity(Intent(it, MainActivity::class.java)) }
        }
    }

    fun vpn(a: Context): Check {
        val cm = a.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")  // allNetworks is the only way to see a VPN that isn't the default network
        val up = cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        return Check("Tailscale", up, if (up) "Tailscale on" else "Tailscale off") {
            it.startActivity(it.packageManager.getLaunchIntentForPackage("com.tailscale.ipn") ?: Intent(Settings.ACTION_VPN_SETTINGS))
        }
    }

    fun alertsGranted(a: Context) =
        a.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(a, ChatListener::class.java))

    fun alerts(a: Context): Check {
        if (!alertsGranted(a)) return Check("Alerts", false, "Alerts off") { openListenerSettings(it) }
        val alive = System.currentTimeMillis() - Link.lastBeat("listener") < LISTENER_STALE_MS
        return Check("Alerts", alive, if (alive) "Alerts on" else "Alerts stuck") { Watchdog.kick(it, force = true) }
    }

    fun mic(a: Context): Check {
        if (!ListenService.wanted(a)) return Check("Mic", null, "Mic off") {}
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return Check("Mic", false, "Mic not allowed") { it.startActivity(Intent(it, MainActivity::class.java)) }
        @Suppress("DEPRECATION")  // still returns this app's own services, across its processes
        val running = a.getSystemService(ActivityManager::class.java).getRunningServices(50)
            .any { it.service.packageName == a.packageName && it.service.className == ListenService::class.java.name && it.foreground }
        return Check("Mic", running, if (running) "Mic on" else "Mic stopped") { ListenService.start(it) }
    }

    fun battery(a: Context): Check {
        val ok = a.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(a.packageName)
        return Check("Battery", ok, if (ok) "Unrestricted" else "Battery limited") { act ->
            try {
                act.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${act.packageName}")))
            } catch (_: Exception) { appInfo(act) }
        }
    }

    /** Samsung's sleeping lists can't be read, but their effect can: a sleeping app drops to a low standby bucket. */
    fun sleep(a: Context): Check {
        val bucket = a.getSystemService(UsageStatsManager::class.java).appStandbyBucket
        val ok = bucket < UsageStatsManager.STANDBY_BUCKET_RARE
        return Check("Sleep", ok, if (ok) "Awake" else "Put to sleep") { openNeverSleeping(it) }
    }

    fun isHome(a: Context) = a.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)

    /** The side button's "Digital assistant" goes to whoever holds this role (Claude today). */
    fun isAssistant(a: Context) = a.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)

    fun islandOn(a: Context) = Settings.Secure.getString(a.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        .split(':').any { ComponentName.unflattenFromString(it) == ComponentName(a, IslandService::class.java) }

    fun openNeverSleeping(a: Activity) = try {
        a.startActivity(Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")
            .setPackage("com.samsung.android.lool").putExtra("activity_type", 2))
    } catch (_: Exception) { appInfo(a) }

    fun openListenerSettings(a: Activity) = a.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(a, ChatListener::class.java).flattenToString()))

    fun appInfo(a: Activity) = a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
}

/** Android can drop the chat listener silently. First ask for a rebind; if it's still quiet half a minute later,
 *  switch the component off and on, which makes Android bind it fresh. */
object Watchdog {
    private var rebindAt = 0L

    fun check(c: Context) {
        if (!Checks.alertsGranted(c)) return
        if (System.currentTimeMillis() - Link.lastBeat("listener") < Checks.LISTENER_STALE_MS) { rebindAt = 0; return }
        kick(c, force = false)
    }

    fun kick(c: Context, force: Boolean) {
        val cn = ComponentName(c, ChatListener::class.java)
        val now = System.currentTimeMillis()
        if (!force && rebindAt == 0L) { rebindAt = now; NotificationListenerService.requestRebind(cn); return }
        if (!force && now - rebindAt < 30_000) return
        rebindAt = now
        val pm = c.packageManager
        pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
    }
}
