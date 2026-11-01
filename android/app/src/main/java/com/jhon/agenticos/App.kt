package com.jhon.agenticos

import android.app.Activity
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.StrictMode
import com.jhon.agenticos.core.Crash
import com.jhon.agenticos.core.Link
import com.jhon.agenticos.core.LiveJobs

/** Runs in every process (UI, :core, :web, :island, :safe), so it stays tiny: no WebView, no network, nothing that can crash. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Link.init(this)
        // debug builds only (BuildConfig is off, so the manifest's debuggable flag decides); logs, never kills
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) strictMode()
        when (getProcessName()) {
            packageName -> {  // only home-screen crashes count toward safe mode
                Crash.install(this)
                registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
                    override fun onActivityPreCreated(a: Activity, s: Bundle?) = Crash.wrap()  // keep our handler first in line
                    override fun onActivityCreated(a: Activity, s: Bundle?) {}
                    override fun onActivityStarted(a: Activity) {}
                    override fun onActivityResumed(a: Activity) {}
                    override fun onActivityPaused(a: Activity) {}
                    override fun onActivityStopped(a: Activity) {}
                    override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
                    override fun onActivityDestroyed(a: Activity) {}
                })
            }
            "$packageName:core" -> try { LiveJobs.start(this) } catch (_: Throwable) {}  // live pills; its own thread does the network
        }
    }

    private fun strictMode() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        // not detectAll: untagged sockets would log every PC poll
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder()
            .detectLeakedClosableObjects().detectLeakedRegistrationObjects().detectLeakedSqlLiteObjects()
            .detectActivityLeaks().detectFileUriExposure().detectContentUriWithoutPermission().detectUnsafeIntentLaunch()
            .penaltyLog().build())
    }
}
