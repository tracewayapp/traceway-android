package com.tracewayapp.traceway.internal

import android.app.Activity
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The foreground as the SDK sees it, from the activities the app starts and
 * stops, including those started before the SDK was (an app that starts
 * Traceway after a consent screen, or again after `Traceway.shutdown`).
 */
@RunWith(RobolectricTestRunner::class)
class AppLifecycleTest {
    private class Seen : AppLifecycle.Listener {
        val events = ArrayList<String>()
        override fun onForeground() {
            events.add("foreground")
        }

        override fun onBackground() {
            events.add("background")
        }
    }

    private fun installed(): Pair<AppLifecycle, Seen> {
        val lifecycle = AppLifecycle(RuntimeEnvironment.getApplication())
        val seen = Seen()
        lifecycle.addListener(seen)
        lifecycle.install()
        return lifecycle to seen
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    @Test
    fun theAppGoesToTheBackgroundWhenItsLastActivityStops() {
        val (lifecycle, seen) = installed()
        val first = Robolectric.buildActivity(Activity::class.java).setup()
        first.pause()
        val second = Robolectric.buildActivity(Activity::class.java).setup()
        first.stop()
        settle()
        assertTrue(lifecycle.isForeground)
        second.pause().stop()
        settle()
        assertFalse(lifecycle.isForeground)
        assertEquals(listOf("foreground", "background"), seen.events)
        lifecycle.uninstall()
    }

    @Test
    fun anActivityStartedBeforeTheSdkDoesNotEndTheForegroundWhenItStops() {
        val before = Robolectric.buildActivity(Activity::class.java).setup()
        val (lifecycle, seen) = installed()
        before.pause()
        val next = Robolectric.buildActivity(Activity::class.java).setup()
        before.stop()
        settle()
        assertTrue("the activity started after it is on screen", lifecycle.isForeground)
        assertEquals(listOf("foreground"), seen.events)
        next.pause().stop()
        settle()
        assertFalse(lifecycle.isForeground)
        assertEquals(listOf("foreground", "background"), seen.events)
        lifecycle.uninstall()
    }

    @Test
    fun anActivityOnScreenAtInstallIsTheForegroundAndItsStopTheBackground() {
        val before = Robolectric.buildActivity(Activity::class.java).setup()
        val (lifecycle, seen) = installed()
        assertTrue("its window shows: the app is in the foreground", lifecycle.isForeground)
        before.pause().stop()
        settle()
        assertFalse(lifecycle.isForeground)
        assertEquals(listOf("foreground", "background"), seen.events)
        lifecycle.uninstall()
    }

    @Test
    fun withNothingOnScreenAtInstallTheForegroundWaitsForAnActivity() {
        val (lifecycle, seen) = installed()
        assertFalse(lifecycle.isForeground)
        assertTrue(seen.events.isEmpty())
        lifecycle.uninstall()
    }
}
