package com.tracewayapp.traceway.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.tracewayapp.traceway.guarded
import com.tracewayapp.traceway.wireframe.WindowList
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whether the app is in the foreground, from the activities it has started,
 * without depending on androidx.lifecycle. A configuration change (an
 * activity stopped and recreated for a rotation) is not a trip to the
 * background: going to the background waits a moment for the next start.
 * An activity started before [install] (Traceway started after a consent
 * screen, or again after `Traceway.shutdown`) cannot be seen starting: while
 * a window of the app shows at [install] the app is in the foreground, and
 * the stop of such an activity ends it unless one started since is still
 * on screen.
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
    /** The activities seen starting and not stopped yet. */
    private val started = WeakHashMap<Activity, Boolean>()
    private var pendingBackground: Runnable? = null
    private var resumed: WeakReference<Activity>? = null

    /** An activity started before [install] may still be on screen (a window showed then). */
    private var startedBefore = false

    @Volatile
    var isForeground: Boolean = false
        private set

    /** The activity on top, if one is resumed. */
    val currentActivity: Activity? get() = resumed?.get()

    fun install() {
        application.registerActivityLifecycleCallbacks(this)
        // The same assumption as the wireframe recorder's ForegroundTracker: a window showing now belongs to an
        // activity that started before these callbacks could see it.
        if (!isForeground && WindowList.anyShowing() == true) {
            startedBefore = true
            isForeground = true
            each { it.onForeground() }
        }
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
        started[activity] = true
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
        if (started.remove(activity) == null) {
            // Not seen starting: one of those started before install, or nothing to do with the foreground.
            if (!startedBefore) return
            startedBefore = false
        }
        if (started.isNotEmpty() || !isForeground) return
        val goBackground = Runnable {
            pendingBackground = null
            if (started.isEmpty() && isForeground) {
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
        // One listener must not break the others or the app.
        for (l in listeners) guarded("lifecycle listener") { block(l) }
    }
}
