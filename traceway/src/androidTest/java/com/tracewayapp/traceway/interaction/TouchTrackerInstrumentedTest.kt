package com.tracewayapp.traceway.interaction

import android.app.Application
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class TouchTrackerInstrumentedTest {
    @get:Rule
    val rule = ActivityScenarioRule(InteractionTestActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val tracker = TouchTracker()
    private val recorder = Recorder()

    @After
    fun tearDown() {
        tracker.uninstall()
        instrumentation.waitForIdleSync()
    }

    private class Recorder : PointerListener, TapListener {
        val events = ArrayList<String>()
        val taps = ArrayList<FloatArray>()
        val hovers = ArrayList<FloatArray>()
        var tapRoot: View? = null
        var describedAtTap: TapTarget? = null
        var clicksAtTap = -1
        var activity: InteractionTestActivity? = null

        override fun pointerDown(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean) {
            events.add("down $pointerId touch=$touch")
        }

        override fun pointerMove(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean) {
            events.add("move $pointerId touch=$touch")
        }

        override fun pointerUp(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean, tap: Boolean) {
            events.add("up $pointerId touch=$touch tap=$tap")
        }

        override fun pointerCancel(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean) {
            events.add("cancel $pointerId")
        }

        override fun onTap(root: View, xPx: Float, yPx: Float, xDp: Float, yDp: Float) {
            tapRoot = root
            taps.add(floatArrayOf(xPx, yPx, xDp, yDp))
            clicksAtTap = activity?.clicks ?: -1
            describedAtTap = ViewPath.pathAt(root, xPx, yPx)?.let { describeTap(it) }
        }

        override fun onHover(root: View, xPx: Float, yPx: Float, xDp: Float, yDp: Float) {
            hovers.add(floatArrayOf(xPx, yPx, xDp, yDp))
        }
    }

    private fun <T> onActivity(block: (InteractionTestActivity) -> T): T {
        var result: Result<T>? = null
        rule.scenario.onActivity { result = runCatching { block(it) } }
        return result!!.getOrThrow()
    }

    private fun tracked(): InteractionTestActivity = onActivity {
        recorder.activity = it
        tracker.addPointerListener(recorder)
        tracker.addTapListener(recorder)
        tracker.track(it.window)
        it
    }

    /** The center of the button in the window's coordinates. */
    private fun buttonCenter(activity: InteractionTestActivity): Pair<Float, Float> = onActivity {
        val location = IntArray(2)
        activity.button.getLocationInWindow(location)
        (location[0] + activity.button.width / 2f) to (location[1] + activity.button.height / 2f)
    }

    private fun touch(
        activity: InteractionTestActivity,
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long = downTime,
        toolType: Int = MotionEvent.TOOL_TYPE_FINGER,
        source: Int = InputDevice.SOURCE_TOUCHSCREEN,
        buttons: Int = 0,
        generic: Boolean = false,
    ) {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            this.toolType = toolType
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        })
        val event = MotionEvent.obtain(downTime, eventTime, action, 1, properties, coords, 0, buttons, 1f, 1f, 0, 0, source, 0)
        onActivity {
            val decor = it.window.decorView
            if (generic) decor.dispatchGenericMotionEvent(event) else decor.dispatchTouchEvent(event)
        }
        event.recycle()
    }

    private fun tap(activity: InteractionTestActivity, x: Float, y: Float, holdMs: Long = 50) {
        val down = SystemClock.uptimeMillis()
        touch(activity, MotionEvent.ACTION_DOWN, x, y, down)
        touch(activity, MotionEvent.ACTION_UP, x, y, down, down + holdMs)
        instrumentation.waitForIdleSync()
    }

    @Test
    fun aTapIsReportedBeforeTheAppHandlesIt() {
        val activity = tracked()
        val (x, y) = buttonCenter(activity)
        tap(activity, x, y)
        assertEquals(listOf("down 0 touch=true", "up 0 touch=true tap=true"), recorder.events)
        assertEquals(1, recorder.taps.size)
        val density = onActivity { it.resources.displayMetrics.density }
        val (xPx, yPx, xDp, yDp) = recorder.taps[0].toList()
        assertEquals(x, xPx, 0.01f)
        assertEquals(y, yPx, 0.01f)
        // Synthetic events carry no window offset: their raw coordinates are the window's.
        assertEquals(x / density, xDp, 0.01f)
        assertEquals(y / density, yDp, 0.01f)
        assertSame(onActivity { it.window.decorView }, recorder.tapRoot)
        assertEquals("the click comes after the tap", 0, recorder.clicksAtTap)
        assertEquals(1, onActivity { it.clicks })
        assertEquals("Button#button1", recorder.describedAtTap?.selector)
        assertEquals("Pay", recorder.describedAtTap?.text)
    }

    @Test
    fun movingPastTheSlopIsNotATap() {
        val activity = tracked()
        val (x, y) = buttonCenter(activity)
        val slop = onActivity { ViewConfiguration.get(it).scaledTouchSlop }
        val down = SystemClock.uptimeMillis()
        touch(activity, MotionEvent.ACTION_DOWN, x, y, down)
        touch(activity, MotionEvent.ACTION_MOVE, x + slop * 3, y, down, down + 20)
        touch(activity, MotionEvent.ACTION_MOVE, x, y, down, down + 40)
        touch(activity, MotionEvent.ACTION_UP, x, y, down, down + 60)
        assertEquals(
            listOf("down 0 touch=true", "move 0 touch=true", "move 0 touch=true", "up 0 touch=true tap=false"),
            recorder.events,
        )
        assertTrue(recorder.taps.isEmpty())
    }

    @Test
    fun aLongPressIsNotATap() {
        val activity = tracked()
        val (x, y) = buttonCenter(activity)
        tap(activity, x, y, holdMs = ViewConfiguration.getLongPressTimeout() + 100L)
        assertTrue(recorder.taps.isEmpty())
        assertEquals("up 0 touch=true tap=false", recorder.events.last())
    }

    @Test
    fun aCancelledGestureIsNotATap() {
        val activity = tracked()
        val (x, y) = buttonCenter(activity)
        val down = SystemClock.uptimeMillis()
        touch(activity, MotionEvent.ACTION_DOWN, x, y, down)
        touch(activity, MotionEvent.ACTION_CANCEL, x, y, down, down + 10)
        touch(activity, MotionEvent.ACTION_UP, x, y, down, down + 20)
        assertTrue(recorder.taps.isEmpty())
        assertTrue("cancel 0" in recorder.events)
    }

    @Test
    fun onlyThePrimaryMouseButtonTaps() {
        val activity = tracked()
        val (x, y) = buttonCenter(activity)
        val down = SystemClock.uptimeMillis()
        val mouse = MotionEvent.TOOL_TYPE_MOUSE
        touch(activity, MotionEvent.ACTION_DOWN, x, y, down, toolType = mouse, source = InputDevice.SOURCE_MOUSE, buttons = MotionEvent.BUTTON_SECONDARY)
        touch(activity, MotionEvent.ACTION_UP, x, y, down, down + 30, toolType = mouse, source = InputDevice.SOURCE_MOUSE)
        assertTrue(recorder.taps.isEmpty())
        assertEquals("down 0 touch=false", recorder.events.first())

        val again = SystemClock.uptimeMillis()
        touch(activity, MotionEvent.ACTION_DOWN, x, y, again, toolType = mouse, source = InputDevice.SOURCE_MOUSE, buttons = MotionEvent.BUTTON_PRIMARY)
        touch(activity, MotionEvent.ACTION_UP, x, y, again, again + 30, toolType = mouse, source = InputDevice.SOURCE_MOUSE)
        assertEquals(1, recorder.taps.size)
    }

    @Test
    fun theMouseHoversButAStylusDoesNot() {
        val activity = tracked()
        val (x, y) = buttonCenter(activity)
        val now = SystemClock.uptimeMillis()
        touch(activity, MotionEvent.ACTION_HOVER_MOVE, x, y, now, toolType = MotionEvent.TOOL_TYPE_MOUSE, source = InputDevice.SOURCE_MOUSE, generic = true)
        assertEquals(1, recorder.hovers.size)
        assertEquals(x, recorder.hovers[0][0], 0.01f)
        assertEquals("move 0 touch=false", recorder.events.single())
        touch(activity, MotionEvent.ACTION_HOVER_MOVE, x, y, now, toolType = MotionEvent.TOOL_TYPE_STYLUS, source = InputDevice.SOURCE_STYLUS, generic = true)
        touch(activity, MotionEvent.ACTION_HOVER_EXIT, x, y, now, toolType = MotionEvent.TOOL_TYPE_MOUSE, source = InputDevice.SOURCE_MOUSE, generic = true)
        assertEquals(1, recorder.hovers.size)
    }

    @Test
    fun aWindowIsNeverWrappedTwice() {
        val activity = tracked()
        onActivity { tracker.track(it.window) }
        val callback = onActivity { it.window.callback }
        assertTrue(callback is TracewayWindowCallback)
        assertSame(activity, (callback as TracewayWindowCallback).delegate)
        val (x, y) = buttonCenter(activity)
        tap(activity, x, y)
        assertEquals(1, recorder.taps.size)
    }

    @Test
    fun aCallbackSetLaterIsWrappedAgainAndReportsOnce() {
        val activity = tracked()
        // Another library wraps the wrapper.
        onActivity { it.window.callback = forwarding(it.window.callback) }
        onActivity { tracker.track(it.window) }
        val top = onActivity { it.window.callback }
        assertTrue(top is TracewayWindowCallback)
        val (x, y) = buttonCenter(activity)
        tap(activity, x, y)
        assertEquals(1, recorder.taps.size)
        assertEquals(1, onActivity { it.clicks })

        // The app sets its own callback, dropping the wrappers.
        onActivity { it.window.callback = it }
        onActivity { tracker.track(it.window) }
        tap(activity, x, y)
        assertEquals(2, recorder.taps.size)
    }

    @Test
    fun untrackRestoresTheCallback() {
        val activity = tracked()
        onActivity { tracker.untrack(it.window) }
        assertSame(activity, onActivity { it.window.callback })
        val (x, y) = buttonCenter(activity)
        tap(activity, x, y)
        assertTrue(recorder.taps.isEmpty())
        assertEquals("the app still gets its taps", 1, onActivity { it.clicks })
    }

    @Test
    fun aWrapperLeftInsideAnotherOneStopsReporting() {
        val activity = tracked()
        onActivity { it.window.callback = forwarding(it.window.callback) }
        onActivity { tracker.untrack(it.window) }
        val (x, y) = buttonCenter(activity)
        tap(activity, x, y)
        assertTrue(recorder.taps.isEmpty())
        assertEquals(1, onActivity { it.clicks })
    }

    @Test
    fun installTracksActivitiesAsTheyAreCreated() {
        val application = instrumentation.targetContext.applicationContext as Application
        tracker.addTapListener(recorder)
        onActivity { tracker.install(application) }
        rule.scenario.recreate()
        assertTrue(onActivity { it.window.callback } is TracewayWindowCallback)
        val activity = onActivity { it }
        recorder.activity = activity
        val (x, y) = buttonCenter(activity)
        tap(activity, x, y)
        assertEquals(1, recorder.taps.size)
        onActivity { tracker.uninstall() }
        assertSame(activity, onActivity { it.window.callback })
    }

    @Test
    fun aListenerThatThrowsBreaksNothing() {
        val activity = tracked()
        tracker.addTapListener(object : TapListener {
            override fun onTap(root: View, xPx: Float, yPx: Float, xDp: Float, yDp: Float) = throw IllegalStateException("boom")
        })
        tracker.addPointerListener(object : PointerListener {
            override fun pointerDown(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean) = throw IllegalStateException("boom")
        })
        val (x, y) = buttonCenter(activity)
        tap(activity, x, y)
        assertEquals(1, recorder.taps.size)
        assertEquals(1, onActivity { it.clicks })
    }

    @Test
    fun withoutListenersNothingIsComputed() {
        val activity = onActivity {
            tracker.track(it.window)
            it
        }
        val (x, y) = buttonCenter(activity)
        tap(activity, x, y)
        assertEquals(1, onActivity { it.clicks })
        assertFalse(recorder.events.isNotEmpty())
    }

    /** A callback of another library that forwards everything to [inner]. */
    private fun forwarding(inner: Window.Callback): Window.Callback =
        Proxy.newProxyInstance(Window.Callback::class.java.classLoader, arrayOf(Window.Callback::class.java)) { _, method, args ->
            method.invoke(inner, *(args ?: emptyArray()))
        } as Window.Callback
}
