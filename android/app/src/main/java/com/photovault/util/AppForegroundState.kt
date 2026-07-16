package com.photovault.util

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Process-wide flag tracking whether the app currently has a visible (started)
 * Activity, i.e. is in the foreground.
 *
 * Used by background workers that must behave differently depending on whether
 * the user is looking at the app right now. In particular, when a periodic scan
 * finds a user-paused backup, it resumes silently in the background but asks for
 * confirmation when the app is in the foreground (so a resume never surprises the
 * user mid-use).
 *
 * Maintained by [register], which counts started Activities via
 * [Application.ActivityLifecycleCallbacks]. Reads are cheap and thread-safe
 * (`@Volatile`), so a worker on a background thread can consult it directly.
 */
object AppForegroundState {

    @Volatile
    private var startedActivityCount: Int = 0

    /** True while at least one Activity is in the started (visible) state. */
    val isForeground: Boolean
        get() = startedActivityCount > 0

    /**
     * Registers lifecycle callbacks on [application] to keep [isForeground] in
     * sync. Call once from `Application.onCreate`.
     */
    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(object :
            Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
