package com.tracewayapp.traceway.internal

import android.app.Activity
import android.os.Bundle
import java.util.WeakHashMap

/**
 * Reports the screen on top as it changes: activities by their class
 * (`/CheckoutActivity`) and, when the app uses AndroidX fragments, the
 * fragments that show a screen (`/CartFragment`). Compose Navigation and
 * other routers report their route with `Traceway.recordNavigation`.
 *
 * The action is `push` when the screen was just created and `pop` when the
 * user came back to one that already existed, as the Flutter SDK reports
 * navigator pushes and pops.
 */
internal class NavigationCapture(
    private val onNavigation: (path: String, action: String, from: String?) -> Unit,
) : AppLifecycle.Listener {
    private val createdActivities = WeakHashMap<Activity, Boolean>()
    private var current: String? = null
    private val fragments: Any? = if (FragmentSupport.available) FragmentSupport(this) else null

    @Volatile
    private var stopped = false

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        createdActivities[activity] = true
        (fragments as? FragmentSupport)?.attach(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        // An activity created before this capture (Traceway started again after a shutdown) is followed from now.
        (fragments as? FragmentSupport)?.attach(activity)
        val fresh = createdActivities.remove(activity) == true
        report("/" + screenName(activity.javaClass), if (fresh) "push" else "pop")
    }

    /**
     * Stops reporting and takes the fragment callbacks off the fragment
     * managers they were put on, which would otherwise keep this client
     * alive (and report to it) after `Traceway.shutdown`. Main thread.
     */
    fun uninstall() {
        stopped = true
        (fragments as? FragmentSupport)?.detachAll()
    }

    internal fun report(path: String, action: String) {
        if (stopped || path == current) return
        val from = current
        current = path
        try {
            onNavigation(path, action, from)
        } catch (_: Throwable) {
        }
    }

    companion object {
        /** A readable name for a screen class; anonymous and obfuscated classes fall back to the full name. */
        fun screenName(type: Class<*>): String {
            val simple = type.simpleName
            return if (simple.isNullOrEmpty()) type.name.substringAfterLast('.') else simple
        }
    }
}

/** AndroidX fragment screens, only touched when the app has androidx.fragment. */
private class FragmentSupport(private val capture: NavigationCapture) {
    private val createdViews = WeakHashMap<Any, Boolean>()

    /** The fragment managers the callbacks are registered on. */
    private val managers = WeakHashMap<androidx.fragment.app.FragmentManager, Boolean>()

    private val callbacks = object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: androidx.fragment.app.FragmentManager,
            f: androidx.fragment.app.Fragment,
            v: android.view.View,
            savedInstanceState: Bundle?,
        ) {
            createdViews[f] = true
        }

        override fun onFragmentResumed(fm: androidx.fragment.app.FragmentManager, f: androidx.fragment.app.Fragment) {
            if (!isScreen(f)) return
            val fresh = createdViews.remove(f) == true
            capture.report("/" + NavigationCapture.screenName(f.javaClass), if (fresh) "push" else "pop")
        }
    }

    fun attach(activity: Activity) {
        try {
            if (activity is androidx.fragment.app.FragmentActivity) {
                val manager = activity.supportFragmentManager
                if (managers.containsKey(manager)) return
                manager.registerFragmentLifecycleCallbacks(callbacks, true)
                managers[manager] = true
            }
        } catch (_: Throwable) {
        }
    }

    fun detachAll() {
        val all = ArrayList(managers.keys)
        managers.clear()
        for (manager in all) {
            try {
                manager.unregisterFragmentLifecycleCallbacks(callbacks)
            } catch (_: Throwable) {
            }
        }
    }

    /** Containers (a NavHostFragment) and fragments without a view or hidden are not screens. */
    private fun isScreen(f: androidx.fragment.app.Fragment): Boolean {
        if (f.view == null || f.isHidden) return false
        val name = f.javaClass.name
        return !name.endsWith("NavHostFragment") && !name.startsWith("com.bumptech.glide")
    }

    companion object {
        val available: Boolean = try {
            Class.forName("androidx.fragment.app.FragmentManager\$FragmentLifecycleCallbacks")
            true
        } catch (_: Throwable) {
            false
        }
    }
}
