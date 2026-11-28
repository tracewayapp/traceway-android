package com.tracewayapp.traceway.wireframe

import android.app.Activity
import com.tracewayapp.traceway.internal.ManualScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The recorder follows the app's activities: it records while one is
 * started and shows "App in the background" once none is, whether the SDK
 * started before the first activity (a cold start) or while one was
 * already on screen (Traceway started after a consent screen, or again).
 */
@RunWith(RobolectricTestRunner::class)
class ForegroundTrackerTest {
    private val scheduler = ManualScheduler()
    private val source = FakeSource().apply { screen = RowScreens().screen(1) }
    private val recorder = WireframeRecorder(50, 10, source, ManualMainThread(scheduler), scheduler::now)
    private val app = RuntimeEnvironment.getApplication()
    private var callbacks: android.app.Application.ActivityLifecycleCallbacks? = null

    @After
    fun tearDown() {
        callbacks?.let { app.unregisterActivityLifecycleCallbacks(it) }
    }

    private fun attach() {
        recorder.start()
        callbacks = recorder.attach(app)
        scheduler.runDue()
    }

    /** What the replay shows at the end of the recording. */
    private fun showing(): List<String> {
        scheduler.advance(1000)
        source.draw()
        scheduler.advance(1000)
        val clip = recorder.captureRecording("c", null, scheduler.now())!!
        return Mirror().apply { play(clip.events) }.texts()
    }

    @Test
    fun afterAColdStartTheBackgroundShowsWhenTheLastActivityStops() {
        attach()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        assertEquals(listOf("row 0", "row 1"), showing())
        activity.pause().stop()
        assertEquals(listOf("App in the background"), showing())
    }

    @Test
    fun anActivityShownBeforeTheRecorderKeepsItRecordingUntilTheLastActivityStops() {
        val before = Robolectric.buildActivity(Activity::class.java).setup()
        attach()
        assertEquals(listOf("row 0", "row 1"), showing())
        before.pause()
        val next = Robolectric.buildActivity(Activity::class.java).setup()
        before.stop()
        assertEquals(listOf("row 0", "row 1"), showing())
        next.pause().stop()
        assertEquals(listOf("App in the background"), showing())
    }
}
