package com.tracewayapp.traceway.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whether the app is in the foreground, from the activities it has started,
 * without depending on androidx.lifecycle. A configuration change (an
 * activity stopped and recreated for a rotation) is not a trip to the
 * background: going to the background waits a moment for the next start.
 */
internal class AppLifecycle(private val application: Application) : Application.ActivityLifecycleCallbacks {
    interface Listener {
        fun onForeground() {}
        fun onBackground() {}
        fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        fun onActivityResumed(activity: Activity) {}
        fun onActivityPaused(activity: Activity) {}
        fun onActivityDestroyed(activity: Activity) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var started = 0
    private var pendingBackground: Runnable? = null
    private var resumed: WeakReference<Activity>? = null

    @Volatile
    var isForeground: Boolean = false
        private set

    /** The activity on top, if one is resumed. */
    val currentActivity: Activity? get() = resumed?.get()

    fun install() {
        application.registerActivityLifecycleCallbacks(this)
    }

    fun uninstall() {
        application.unregisterActivityLifecycleCallbacks(this)
        listeners.clear()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        each { it.onActivityCreated(activity, savedInstanceState) }
    }

    override fun onActivityStarted(activity: Activity) {
        started++
        pendingBackground?.let { main.removeCallbacks(it) }
        pendingBackground = null
        if (!isForeground) {
            isForeground = true
            each { it.onForeground() }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        each { it.onActivityResumed(activity) }
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
        each { it.onActivityPaused(activity) }
    }

    override fun onActivityStopped(activity: Activity) {
        started = maxOf(0, started - 1)
        if (started > 0 || !isForeground) return
        val goBackground = Runnable {
            pendingBackground = null
            if (started == 0 && isForeground) {
                isForeground = false
                each { it.onBackground() }
            }
        }
        pendingBackground = goBackground
        main.postDelayed(goBackground, if (activity.isChangingConfigurations) 1000L else 300L)
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {
        each { it.onActivityDestroyed(activity) }
    }

    private inline fun each(block: (Listener) -> Unit) {
        for (l in listeners) {
            try {
                block(l)
            } catch (_: Throwable) {
                // One listener must not break the others or the app.
            }
        }
    }
}
