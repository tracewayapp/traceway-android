package com.tracewayapp.traceway.metrics

import com.tracewayapp.traceway.internal.ManualScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The platform side, driven by the test: draws, the main queue, the launch check. */
internal class FakeHost : VitalsHost {
    var draws = 0
    var cancels = 0
    val main = ArrayDeque<Runnable>()
    var launch: ((LaunchInfo) -> Unit)? = null
    var debugger = false

    override fun install() {}

    override fun uninstall() {}

    override fun requestDraw() {
        draws++
    }

    override fun cancelDraw() {
        cancels++
    }

    override fun postToMain(task: Runnable): Boolean = main.add(task)

    override fun checkLaunch(result: (LaunchInfo) -> Unit) {
        launch = result
    }

    override fun debuggerAttached() = debugger

    fun runMain() {
        while (main.isNotEmpty()) main.removeFirst().run()
    }
}

class AppVitalsTest {
    private lateinit var r: Recorded
    private lateinit var host: FakeHost
    private lateinit var vitals: AppVitals
    private var screen: String? = "/cart"

    private val clock get() = r.scheduler.nowMs

    @Before
    fun setUp() {
        r = recorder()
        host = FakeHost()
        vitals = vitals()
    }

    private fun vitals(currentScreen: () -> String? = { screen }, reportHangsInDebug: Boolean = false) = AppVitals(
        r.recorder,
        currentScreen = currentScreen,
        scheduler = r.scheduler,
        elapsedMs = { r.scheduler.nowMs },
        uptimeMs = { r.scheduler.nowMs },
        epochMs = { r.scheduler.nowMs },
        hangThresholdMs = 150,
        watchdogIntervalMs = 20,
        host = host,
        reportHangsInDebug = reportHangsInDebug,
    )

    @After
    fun tearDown() = vitals.stop()

    @Test
    fun timeToInitialAndFullDisplayOfAPushedScreen() {
        vitals.start(null)
        vitals.navigated("/users/:id", "push", clock)
        assertEquals(1, host.draws)
        r.scheduler.advance(250)
        vitals.drawn()
        r.scheduler.advance(750)
        vitals.fullyDisplayed()
        vitals.fullyDisplayed()
        // A return to a screen already built is not a display.
        vitals.navigated("/cart", "pop", clock)
        vitals.drawn()
        r.recorder.flush()
        val h = r.decoded.histograms
        assertEquals(setOf("app.screen.ttid|screen=/users/:id", "app.screen.ttfd|screen=/users/:id"), h.keys)
        assertEquals(250.0, h["app.screen.ttid|screen=/users/:id"]!!.sum, 0.0)
        assertEquals(1000.0, h["app.screen.ttfd|screen=/users/:id"]!!.sum, 0.0)
    }

    @Test
    fun aNavigationReportedLateIsMeasuredFromWhenItHappened() {
        vitals.start(null)
        vitals.navigated("/a", "replace", clock - 40)
        r.scheduler.advance(60)
        vitals.drawn()
        r.recorder.flush()
        assertEquals(100.0, r.decoded.histograms["app.screen.ttid|screen=/a"]!!.sum, 0.0)
    }

    @Test
    fun aScreenLeftForTheBackgroundIsNotDisplayedLater() {
        vitals.start(null)
        vitals.navigated("/a", "push", clock)
        vitals.lifecycleChanged(false)
        assertEquals(1, host.cancels)
        vitals.drawn()
        vitals.fullyDisplayed()
        vitals.navigated("/b", "push", clock)
        assertEquals("no draw is waited for in the background", 1, host.draws)
        r.recorder.flush()
        assertTrue(r.bodies.isEmpty())
    }

    @Test
    fun framesAreClassifiedAgainstTheDisplaysBudget() {
        val budget = FrameRules.budgetNs(60f)
        assertEquals(16_666_667L, budget)
        assertEquals(8_333_333L, FrameRules.budgetNs(120f))
        assertEquals("implausible rates are 60 Hz", budget, FrameRules.budgetNs(0f))
        assertEquals(FrameKind.NORMAL, FrameRules.classify(2_000_000, 30_000_000, budget))
        assertEquals(FrameKind.SLOW, FrameRules.classify(30_000_000, 40_000_000, budget))
        assertEquals(FrameKind.FROZEN, FrameRules.classify(10_000_000, 800_000_000, budget))
        assertEquals(FrameKind.FROZEN, FrameRules.classify(700_000_000, 700_000_000, budget))
        // Choreographer: frames start on vsync, one interval is on time.
        assertEquals(FrameKind.NORMAL, FrameRules.classifyInterval(16_700_000, budget))
        assertEquals(FrameKind.SLOW, FrameRules.classifyInterval(33_300_000, budget))
        assertEquals(FrameKind.FROZEN, FrameRules.classifyInterval(750_000_000, budget))
    }

    @Test
    fun slowAndFrozenFramesByScreen() {
        vitals.start(null)
        for (kind in listOf(FrameKind.NORMAL, FrameKind.SLOW, FrameKind.SLOW, FrameKind.FROZEN)) vitals.frame(kind)
        vitals.navigated("/b", "pop", clock)
        vitals.frame(FrameKind.NORMAL)
        vitals.framesPaused()
        r.recorder.flush()
        assertEquals(
            mapOf(
                "app.frames.slow.total|screen=/cart" to 4.0,
                "app.frames.slow|screen=/cart" to 2.0,
                "app.frames.frozen.total|screen=/cart" to 4.0,
                "app.frames.frozen|screen=/cart" to 1.0,
                "app.frames.slow.total|screen=/b" to 1.0,
                "app.frames.frozen.total|screen=/b" to 1.0,
            ),
            r.decoded.sums,
        )
    }

    @Test
    fun framesWithoutAScreenAndOnlyInTheForeground() {
        vitals = vitals(currentScreen = { null })
        vitals.start(null)
        vitals.frame(FrameKind.NORMAL)
        vitals.lifecycleChanged(false)
        vitals.frame(FrameKind.FROZEN)
        vitals.framesPaused()
        r.recorder.flush()
        assertEquals(mapOf("app.frames.slow.total" to 1.0, "app.frames.frozen.total" to 1.0), r.decoded.sums)
    }

    @Test
    fun framesAreHandedOverInBatches() {
        val batches = ArrayList<List<Any?>>()
        var now = 0L
        val tally = FrameTally({ s, total, slow, frozen -> batches.add(listOf(s, total, slow, frozen)) })
        tally.add("/a", FrameKind.SLOW, now)
        now += 500
        tally.add("/a", FrameKind.NORMAL, now)
        assertTrue(batches.isEmpty())
        now += 500
        tally.add("/a", FrameKind.FROZEN, now)
        assertEquals(listOf<Any?>("/a", 3, 1, 1), batches.single())
        tally.add("/a", FrameKind.NORMAL, now)
        tally.add("/b", FrameKind.NORMAL, now)
        assertEquals(listOf<Any?>("/a", 1, 0, 0), batches[1])
        tally.flush()
        assertEquals(listOf<Any?>("/b", 1, 0, 0), batches[2])
        tally.flush()
        assertEquals(3, batches.size)
    }

    @Test
    fun aBlockedMainThreadIsAHangOnlyInTheForeground() {
        vitals.start(null)
        host.runMain()
        r.scheduler.advance(20)
        // The main thread is busy for 300 ms: the probe posted now waits.
        r.scheduler.advance(300)
        host.runMain()
        r.scheduler.advance(20)
        host.runMain()
        vitals.lifecycleChanged(false)
        r.recorder.flush()
        assertEquals(1.0, r.decoded.sums["app.hang|screen=/cart"])
        val duration = r.decoded.histograms["app.hang.duration|screen=/cart"]!!
        assertEquals(300.0, duration.sum, 0.0)

        // In the background the main thread may be suspended: no watchdog runs.
        r.bodies.clear()
        r.scheduler.advance(300)
        host.runMain()
        r.scheduler.advance(300)
        host.runMain()
        r.recorder.flush()
        assertTrue(r.bodies.isEmpty())
        assertEquals(0, r.scheduler.pendingTimers)
    }

    @Test
    fun aShortDelayOrADebuggerIsNotAHang() {
        vitals.start(null)
        r.scheduler.advance(100)
        host.runMain()
        host.debugger = true
        r.scheduler.advance(20)
        r.scheduler.advance(500)
        host.runMain()
        r.recorder.flush()
        assertTrue(r.bodies.isEmpty())
    }

    /** AND2-18: `reportHangsInDebug` counts a hang from Android Studio's Debug run. */
    @Test
    fun reportHangsInDebugCountsAHangUnderADebugger() {
        vitals.stop()
        vitals = vitals(reportHangsInDebug = true)
        vitals.start(null)
        host.runMain()
        host.debugger = true
        r.scheduler.advance(20)
        r.scheduler.advance(300)
        host.runMain()
        r.scheduler.advance(20)
        host.runMain()
        vitals.lifecycleChanged(false)
        r.recorder.flush()
        assertEquals(1.0, r.decoded.sums["app.hang|screen=/cart"])
    }

    @Test
    fun theWatchdogPostsOneProbeAtATime() {
        val main = ArrayDeque<Runnable>()
        val scheduler = ManualScheduler()
        val hangs = ArrayList<Long>()
        val watchdog = HangWatchdog(
            clock = { scheduler.nowMs },
            postToMain = { main.add(it) },
            schedule = { delay, task -> scheduler.schedule(delay, task) },
            thresholdMs = 2000,
            intervalMs = 500,
            onHang = { hangs.add(it) },
        )
        watchdog.start()
        scheduler.advance(5000)
        assertEquals(1, main.size)
        main.removeFirst().run()
        assertEquals(listOf(5000L), hangs)
        watchdog.stop()
        watchdog.start()
        scheduler.advance(100)
        val stale = main.removeFirst()
        watchdog.stop()
        scheduler.advance(3000)
        stale.run()
        assertEquals("a probe of a stopped watchdog is ignored", 1, hangs.size)
    }

    @Test
    fun aColdStartRunsFromTheProcessStartToTheFirstFrame() {
        vitals.watchColdStart(sdkStartMs = clock - 200, processStartMs = clock - 1000)
        assertEquals("nothing to wait for before start", 0, host.draws)
        vitals.start(null)
        assertEquals(1, host.draws)
        host.launch!!(LaunchInfo(importanceForeground = true, sawActivity = true))
        r.scheduler.advance(500)
        vitals.drawn()
        vitals.drawn()
        r.recorder.flush()
        val start = r.decoded.histograms["app.start.duration|start_type=cold"]!!
        assertEquals(1L, start.count)
        assertEquals(1500.0, start.sum, 0.0)
    }

    @Test
    fun aColdStartMeasuresFromTheSdkWhenTheProcessStartIsImplausible() {
        assertEquals(1000L, AppVitals.coldStartOrigin(1000, 5000))
        assertEquals("long before the SDK: a service started it", 50_000L, AppVitals.coldStartOrigin(1000, 50_000))
        assertEquals("after the SDK", 5000L, AppVitals.coldStartOrigin(6000, 5000))
        assertEquals("unknown below API 24", 5000L, AppVitals.coldStartOrigin(null, 5000))
        assertTrue(AppVitals.coldStartCounts(false, true))
        assertFalse(AppVitals.coldStartCounts(true, true))
        assertFalse(AppVitals.coldStartCounts(false, false))
    }

    @Test
    fun aProcessStartedForAServiceHasNoColdStartAndItsFirstActivityIsWarm() {
        vitals.start(null)
        vitals.watchColdStart(sdkStartMs = clock, processStartMs = clock - 100)
        host.launch!!(LaunchInfo(importanceForeground = false, sawActivity = false))
        r.scheduler.advance(5000)
        vitals.activityCreated()
        r.scheduler.advance(100)
        vitals.activityStarted()
        r.scheduler.advance(300)
        vitals.drawn()
        r.recorder.flush()
        assertEquals(setOf("app.start.duration|start_type=warm"), r.decoded.histograms.keys)
        assertEquals(400.0, r.decoded.histograms.values.single().sum, 0.0)
    }

    @Test
    fun aBroadcastWithForegroundImportanceButNoActivityHasNoStart() {
        vitals.start(null)
        vitals.watchColdStart(sdkStartMs = clock, processStartMs = clock - 100)
        host.launch!!(LaunchInfo(importanceForeground = true, sawActivity = false))
        vitals.drawn()
        vitals.activityStarted()
        vitals.drawn()
        r.recorder.flush()
        assertTrue(r.bodies.isEmpty())
    }

    @Test
    fun leavingBeforeTheFirstFrameIsNoColdStartButTheReturnIsHot() {
        vitals.start(null)
        vitals.watchColdStart(sdkStartMs = clock, processStartMs = clock - 100)
        host.launch!!(LaunchInfo(importanceForeground = true, sawActivity = true))
        vitals.activityStarted()
        vitals.activityStopped(changingConfigurations = false)
        vitals.lifecycleChanged(false)
        vitals.drawn()
        r.scheduler.advance(10_000)
        vitals.lifecycleChanged(true)
        vitals.activityStarted()
        r.scheduler.advance(120)
        vitals.drawn()
        r.recorder.flush()
        assertEquals(setOf("app.start.duration|start_type=hot"), r.decoded.histograms.keys)
        assertEquals(120.0, r.decoded.histograms.values.single().sum, 0.0)
    }

    @Test
    fun aReturnThatRecreatesTheActivityIsWarmAndASlowOneIsReported() {
        vitals.start(null)
        vitals.activityStarted()
        vitals.activityStopped(changingConfigurations = false)
        vitals.lifecycleChanged(false)
        r.scheduler.advance(60_000)
        vitals.activityCreated()
        r.scheduler.advance(1500)
        vitals.lifecycleChanged(true)
        vitals.activityStarted()
        r.scheduler.advance(1000)
        vitals.drawn()
        r.recorder.flush()
        val warm = r.decoded.histograms["app.start.duration|start_type=warm"]!!
        assertEquals(2500.0, warm.sum, 0.0)
        assertEquals("slow warm starts are worth opening", 1, warm.exemplars.size)
    }

    @Test
    fun aConfigurationChangeIsNotAStart() {
        vitals.start(null)
        vitals.activityStarted()
        vitals.activityStopped(changingConfigurations = true)
        // An integration that reports the rotation as leaving and coming back.
        vitals.lifecycleChanged(false)
        vitals.lifecycleChanged(true)
        vitals.activityCreated()
        vitals.activityStarted()
        vitals.drawn()
        r.recorder.flush()
        assertTrue(r.bodies.isEmpty())
        assertEquals(0, host.draws)
    }

    @Test
    fun anotherActivityStartingIsNotAReturn() {
        vitals.start(null)
        vitals.activityStarted()
        vitals.lifecycleChanged(false) // reported while an activity is still started
        vitals.activityStarted()
        vitals.drawn()
        r.recorder.flush()
        assertTrue(r.bodies.isEmpty())
    }

    @Test
    fun memoryPeakIsReadFromVmHwm() {
        val status = "Name:\tapp\nVmPeak:\t 999 kB\nVmHWM:\t  204800 kB\nVmRSS:\t 1000 kB\n"
        assertEquals(204800L * 1024, AppVitals.parseVmHwmBytes(status))
        assertEquals(0L, AppVitals.parseVmHwmBytes("Name:\tapp\n"))
        assertEquals(0L, AppVitals.parseVmHwmBytes("VmHWM:\tlots kB\n"))
        // JVM tests have no API level: the process start is unknown.
        assertNull(AppVitals.processStartElapsedRealtime())
    }
}
