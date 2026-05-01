package com.tracewayapp.traceway.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.tracewayapp.traceway.TracewayClient
import com.tracewayapp.traceway.events.NavigationEvent

/**
 * Records activity transitions as [NavigationEvent]s on the timeline.
 *
 *  * `onCreate` ⟶ `push` (with `from` = the previously-foregrounded activity)
 *  * `onDestroy` (non-config-change) ⟶ `pop` (with `to` = the activity that
 *     becomes foregrounded next, if any)
 */
internal class NavigationCapture : Application.ActivityLifecycleCallbacks {
    private var lastForeground: String? = null

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        val name = activity.javaClass.simpleName
        emit("push", from = lastForeground, to = name)
    }

    override fun onActivityResumed(activity: Activity) {
        lastForeground = activity.javaClass.simpleName
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity.isChangingConfigurations) return
        val name = activity.javaClass.simpleName
        emit("pop", from = name, to = null)
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    private fun emit(action: String, from: String?, to: String?) {
        try {
            TracewayClient.instance?.recordNavigationEvent(
                NavigationEvent(action = action, from = from, to = to)
            )
        } catch (_: Throwable) {
            // Never let event recording break navigation.
        }
    }
}
