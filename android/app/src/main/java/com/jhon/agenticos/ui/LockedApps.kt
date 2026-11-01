package com.jhon.agenticos.ui

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.widget.Toast

/** Apps that ask for his fingerprint, face or screen lock before they open from this launcher (Phase 2 contract:
 *  "aos" prefs "locked_apps", a set of app keys). It guards launches from Agentic OS only, not notifications or
 *  Recents; the UI says so.
 *
 *  For HOME's dock: `LockedApps.launch(activity, key, label) { ...open it... }`. The orbit and search already gate
 *  their own launches before calling onLaunch, so don't gate those twice. */
object LockedApps {
    private const val PREF = "locked_apps"
    private const val AUTH = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun all(c: Context): Set<String> = Appearance.prefs(c).getStringSet(PREF, null)?.toSet() ?: emptySet()
    /** By package, not exact key: the dock passes a plain package while a two-activity app is locked as "component#serial". */
    fun isLocked(c: Context, key: String) = lockedIn(all(c), key)
    /** Any profile's copy of this package locked: shortcuts and notes are gated by package, erring on the safe side. */
    fun anyLocked(c: Context, pkg: String) = lockedIn(all(c), pkg)
    internal fun lockedIn(keys: Set<String>, key: String) = AppKey.pkg(key).let { p -> keys.any { AppKey.pkg(it) == p } }

    fun set(c: Context, key: String, locked: Boolean) {
        val s = all(c).toMutableSet()  // a copy: prefs' own set must never be changed in place
        if (if (locked) s.add(key) else s.remove(key)) Appearance.prefs(c).edit().putStringSet(PREF, s).apply()
    }

    /** Opens right away, or after the lock prompt when this app is locked. */
    fun launch(act: Activity, key: String, label: String?, open: () -> Unit) =
        if (!isLocked(act, key)) open() else ask(act, "Open ${label ?: "app"}", open)

    /** The system lock prompt; `ok` runs on success, `cancelled` when he backs out or it fails for good. With no screen
     *  lock set there's nothing to check against, so it says so once and goes ahead. */
    fun ask(act: Activity, title: String, ok: () -> Unit, cancelled: () -> Unit = {}) {
        val can = try { act.getSystemService(BiometricManager::class.java)?.canAuthenticate(AUTH) } catch (_: Exception) { null }
        if (can == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
            Toast.makeText(act, "Set a screen lock for app locks to ask", Toast.LENGTH_SHORT).show(); ok(); return
        }
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(act, "Couldn't check your lock. Try again.", Toast.LENGTH_SHORT).show(); cancelled(); return
        }
        try {
            BiometricPrompt.Builder(act).setTitle(title).setAllowedAuthenticators(AUTH).setConfirmationRequired(false).build()
                .authenticate(CancellationSignal(), act.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult?) { if (!act.isDestroyed) ok() }
                    override fun onAuthenticationError(code: Int, msg: CharSequence?) { if (!act.isDestroyed) cancelled() }
                })
        } catch (_: Exception) {
            Toast.makeText(act, "Couldn't check your lock. Try again.", Toast.LENGTH_SHORT).show(); cancelled()
        }
    }
}
