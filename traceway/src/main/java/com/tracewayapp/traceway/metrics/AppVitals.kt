package com.tracewayapp.traceway.metrics

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.annotation.RequiresApi
import com.tracewayapp.traceway.internal.Cancellable
import com.tracewayapp.traceway.guarded
import com.tracewayapp.traceway.internal.TaskScheduler
import java.io.File
import java.lang.ref.WeakReference

/**
 * A start this slow counts the user as affected: Android vitals' "excessive"
 * cold (5 s), warm (2 s) and hot (1.5 s) starts.
 */
internal const val SLOW_COLD_START_MS = 5000.0
internal const val SLOW_WARM_START_MS = 2000.0
internal const val SLOW_HOT_START_MS = 1500.0

/**
 * A frame on screen this long is frozen (Android vitals, Sentry); between
 * the display's frame budget and this it is slow.
 */
internal const val FROZEN_FRAME_NS = 700_000_000L

private const val MAX_START_MS = 120_000L

/** A process the system started this long before the SDK (for a service, a broadcast) measures from the SDK. */
private const val MAX_PROCESS_TO_SDK_MS = 30_000L

internal enum class FrameKind { NORMAL, SLOW, FROZEN }

internal object FrameRules {
    /** The budget of one frame at [refreshRate] Hz; 60 Hz when the display reports something implausible. */
    fun budgetNs(refreshRate: Float): Long {
        val rate = if (refreshRate >= 24f && refreshRate <= 500f) refreshRate.toDouble() else 60.0
        return Math.round(1e9 / rate)
    }

    /**
     * A frame from `FrameMetrics` (API 24+): slow when the UI and render
     * thread work before the GPU ([cpuNs]) took longer than its budget,
     * frozen when it took 700 ms or more to reach the screen ([totalNs]).
     */
    fun classify(cpuNs: Long, totalNs: Long, budgetNs: Long): FrameKind = when {
        totalNs >= FROZEN_FRAME_NS -> FrameKind.FROZEN
        cpuNs > budgetNs -> FrameKind.SLOW
        else -> FrameKind.NORMAL
    }

    /**
     * A frame from Choreographer (below API 24): [intervalNs] from its vsync
     * to the next frame's. Frames start on vsync, so one budget is on time
     * and two or more are slow.
     */
    fun classifyInterval(intervalNs: Long, budgetNs: Long): FrameKind = when {
        intervalNs >= FROZEN_FRAME_NS -> FrameKind.FROZEN
        intervalNs > budgetNs * 3 / 2 -> FrameKind.SLOW
        else -> FrameKind.NORMAL
    }
}

/**
 * Counts frames by screen and hands them over in batches (a screen change,
 * a second, or [maxFrames]), so the recorder is not called for every frame.
 * Thread-safe.
 */
internal class FrameTally(
    private val emit: (screen: String?, total: Int, slow: Int, frozen: Int) -> Unit,
    private val batchMs: Long = 1000,
    private val maxFrames: Int = 600,
) {
    private class Batch(val screen: String?, val total: Int, val slow: Int, val frozen: Int)

    private var screen: String? = null
    private var total = 0
    private var slow = 0
    private var frozen = 0
    private var startMs = 0L

    fun add(screen: String?, kind: FrameKind, nowMs: Long) {
        var out: Batch? = null
        synchronized(this) {
            if (total > 0 && screen != this.screen) out = takeLocked()
            if (total == 0) {
                this.screen = screen
                startMs = nowMs
            }
            total++
            when (kind) {
                FrameKind.SLOW -> slow++
                FrameKind.FROZEN -> frozen++
                FrameKind.NORMAL -> {}
            }
            if (out == null && (nowMs - startMs >= batchMs || total >= maxFrames)) out = takeLocked()
        }
        out?.let { emit(it.screen, it.total, it.slow, it.frozen) }
    }

    fun flush() {
        val out = synchronized(this) { if (total > 0) takeLocked() else null } ?: return
        emit(out.screen, out.total, out.slow, out.frozen)
    }

    private fun takeLocked(): Batch {
        val batch = Batch(screen, total, slow, frozen)
        total = 0
        slow = 0
        frozen = 0
        return batch
    }
}

/**
 * Watches the main thread for hangs: every [intervalMs] a timer thread
 * posts a probe to the main thread, unless the last one has not run yet,
 * and the time the probe waited is how long the main thread could not
 * respond. Nothing spins: one message every half second, only while
 * started. Thread-safe.
 */
internal class HangWatchdog(
    /** A monotonic clock that stops in deep sleep (`SystemClock.uptimeMillis`). */
    private val clock: () -> Long,
    private val postToMain: (Runnable) -> Boolean,
    private val schedule: (Long, () -> Unit) -> Cancellable,
    private val thresholdMs: Long,
    private val intervalMs: Long,
    /** True while a hang is not the app's (a debugger stopped it). */
    private val excused: () -> Boolean = { false },
    private val onHang: (blockedMs: Long) -> Unit,
) {
    private val lock = Any()
    private var running = false
    private var timer: Cancellable? = null
    private var probe: Probe? = null

    private inner class Probe(val postedAt: Long) : Runnable {
        // Runs in the app's main looper: nothing may escape into it.
        override fun run() = guarded("hang probe") { ran(this) }
    }

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        tick()
    }

    fun stop() {
        synchronized(lock) {
            running = false
            timer?.cancel()
            timer = null
            probe = null
        }
    }

    private fun tick() {
        var post: Probe? = null
        synchronized(lock) {
            if (!running) return
            if (probe == null) {
                post = Probe(clock())
                probe = post
            }
            timer = schedule(intervalMs) { tick() }
        }
        val p = post ?: return
        val posted = try {
            postToMain(p)
        } catch (_: Throwable) {
            false
        }
        if (!posted) synchronized(lock) { if (probe === p) probe = null }
    }

    private fun ran(p: Probe) {
        val at = clock()
        synchronized(lock) {
            if (probe !== p) return
            probe = null
            if (!running) return
        }
        val blocked = at - p.postedAt
        if (blocked >= thresholdMs && !excused()) onHang(blocked)
    }
}

/** What the platform says about how the process started. */
internal class LaunchInfo(
    /** The process had foreground importance when the SDK started. */
    val importanceForeground: Boolean,
    /** An activity was being created or started right then. */
    val sawActivity: Boolean,
)

/** The platform side of [AppVitals]: activities, windows, frames, the main thread. */
internal interface VitalsHost {
    fun install()

    fun uninstall()

    /** Calls [AppVitals.drawn] once the resumed activity, or the next one to resume, draws its next frame. */
    fun requestDraw()

    fun cancelDraw()

    fun postToMain(task: Runnable): Boolean

    /** Calls back on the main thread, soon after the SDK started, with how the process was started. */
    fun checkLaunch(result: (LaunchInfo) -> Unit)

    fun debuggerAttached(): Boolean
}

/**
 * Measures what the dashboard charts as app performance, from the platform
 * alone:
 *
 * * cold start: from the process start (`Process.getStartElapsedRealtime`
 *   on API 24+, else when the SDK was initialized) to the first frame of
 *   the first activity, only when the process was started to show one;
 *   warm and hot start: from the return to the foreground (warm when an
 *   activity had to be created) to its next frame;
 * * time to initial display of a screen: from the navigation to the first
 *   frame after it; time to full display: until the app calls
 *   `Traceway.reportFullyDisplayed()`;
 * * slow and frozen frames from `FrameMetrics` (API 24+) or Choreographer:
 *   slow over the display's frame budget, frozen at 700 ms or more, only
 *   while in the foreground;
 * * hangs: the main thread did not respond for 2 s or more while the app
 *   was in the foreground.
 *
 * Thread-safe; the platform callbacks run on the main thread, frames on a
 * thread of their own. The recorder is never called under this class's lock.
 */
internal class AppVitals(
    private val recorder: MetricsRecorder,
    /** The screen being shown, normalized, for the frame and hang dimensions. */
    private val currentScreen: () -> String?,
    scheduler: TaskScheduler,
    /** The clock of the process start and of every duration measured here. */
    private val elapsedMs: () -> Long = { SystemClock.elapsedRealtime() },
    /** The watchdog's clock, which stops in deep sleep. */
    uptimeMs: () -> Long = { SystemClock.uptimeMillis() },
    /** The clock of the navigation times the client passes. */
    private val epochMs: () -> Long = System::currentTimeMillis,
    hangThresholdMs: Long = 2000,
    watchdogIntervalMs: Long = 500,
    /** Replaces the Android host in tests. */
    private var host: VitalsHost? = null,
) {
    private class PendingStart(val type: String, val originMs: Long)

    private class Visit(var screen: String, val startMs: Long) {
        var displayed = false
        var fullyDisplayed = false
    }

    private val lock = Any()
    private val frameClock = uptimeMs

    // Guarded by [lock].
    private var started = false
    private var foregroundKnown = false
    private var backgrounded = false
    private var firstFrameDone = false
    private var backgroundedBeforeFirstFrame = false
    private var pendingStart: PendingStart? = null
    private var visit: Visit? = null
    private var startedActivities = 0
    private var createdWhileBackground = false
    private var returnAtMs: Long? = null
    private var lastStopWasConfigChange = false

    @Volatile
    private var foreground = true

    /** The screen of the last navigation, for the frame and hang dimensions. */
    @Volatile
    private var screen: String? = null

    private val tally = FrameTally({ s, total, slow, frozen -> recorder.frames(s, total, slow, frozen) })

    private val watchdog = HangWatchdog(
        clock = uptimeMs,
        postToMain = { task -> host?.postToMain(task) ?: false },
        schedule = { delay, task -> scheduler.schedule(delay, task) },
        thresholdMs = hangThresholdMs,
        intervalMs = watchdogIntervalMs,
        excused = { host?.debuggerAttached() ?: false },
        onHang = { ms -> recorder.hang(ms.toDouble(), screen ?: safeScreen()) },
    )

    /**
     * Registers for the activities of [application] and starts the hang
     * watchdog. Call as early as possible, then [watchColdStart].
     */
    fun start(application: Application?) {
        var coldStart = false
        val h = synchronized(lock) {
            if (started) return
            started = true
            if (host == null && application != null) host = AndroidVitalsHost(application, this)
            coldStart = pendingStart?.type == START_COLD
            host
        }
        if (screen == null) screen = safeScreen()
        try {
            h?.install()
        } catch (_: Throwable) {
        }
        if (coldStart) awaitFirstFrame(h)
        if (foreground) watchdog.start()
    }

    fun stop() {
        val h = synchronized(lock) {
            if (!started) return
            started = false
            host
        }
        watchdog.stop()
        try {
            h?.uninstall()
        } catch (_: Throwable) {
        }
        tally.flush()
    }

    // ------------------------------------------------------------ app start

    /**
     * Measures the cold start, when the process was started for an
     * activity, to the first frame. [sdkStartMs] and [processStartMs] are on
     * the `elapsedRealtime` clock. Before or after [start], as long as it is
     * as early as possible: the first frame usually comes soon after.
     */
    fun watchColdStart(sdkStartMs: Long, processStartMs: Long? = processStartElapsedRealtime()) {
        val origin = coldStartOrigin(processStartMs, sdkStartMs)
        val h = synchronized(lock) {
            if (firstFrameDone || pendingStart != null) return
            pendingStart = PendingStart(START_COLD, origin)
            if (started) host else return
        }
        awaitFirstFrame(h)
    }

    private fun awaitFirstFrame(h: VitalsHost?) {
        h ?: return
        try {
            h.requestDraw()
            h.checkLaunch { launched(it) }
        } catch (_: Throwable) {
        }
    }

    /**
     * Started for a service, a broadcast or a job, the process has no cold
     * start somebody waited for; with no foreground importance its first
     * activity is a warm start.
     */
    internal fun launched(info: LaunchInfo) {
        var stopWatchdog = false
        synchronized(lock) {
            if (info.importanceForeground && info.sawActivity) return
            if (pendingStart?.type == START_COLD) pendingStart = null
            if (!info.importanceForeground && startedActivities == 0) {
                backgrounded = true
                if (!foregroundKnown) {
                    foreground = false
                    stopWatchdog = true
                }
            }
        }
        if (stopWatchdog) watchdog.stop()
    }

    internal fun activityCreated() {
        synchronized(lock) {
            if (backgrounded) {
                createdWhileBackground = true
                if (returnAtMs == null) returnAtMs = elapsedMs()
            }
        }
    }

    internal fun activityStarted() {
        val request = synchronized(lock) {
            val first = startedActivities == 0
            startedActivities++
            val returning = first && backgrounded && !lastStopWasConfigChange
            if (returning) {
                val at = returnAtMs ?: elapsedMs()
                pendingStart = PendingStart(if (createdWhileBackground) START_WARM else START_HOT, at)
            }
            if (first) {
                // On screen again, whether or not that was a start.
                backgrounded = false
                createdWhileBackground = false
                returnAtMs = null
                lastStopWasConfigChange = false
            }
            returning
        }
        if (request) host?.requestDraw()
    }

    internal fun activityStopped(changingConfigurations: Boolean) {
        synchronized(lock) {
            startedActivities = maxOf(0, startedActivities - 1)
            if (startedActivities == 0) lastStopWasConfigChange = changingConfigurations
        }
    }

    fun lifecycleChanged(foreground: Boolean) {
        val h = synchronized(lock) {
            foregroundKnown = true
            this.foreground = foreground
            if (!foreground) {
                if (!firstFrameDone) backgroundedBeforeFirstFrame = true
                // A start or a screen still loading when the user left is not displayed later.
                pendingStart = null
                visit = null
                backgrounded = true
            }
            host
        }
        if (foreground) {
            if (synchronized(lock) { started }) watchdog.start()
        } else {
            watchdog.stop()
            tally.flush()
            h?.cancelDraw()
        }
    }

    // -------------------------------------------------------------- screens

    /**
     * A navigation to [screen] (normalized) at [timeMs] (Unix ms). Only a
     * screen that is new (`push`, `replace`) is built and displayed;
     * returning to one is not.
     */
    fun navigated(screen: String?, action: String, timeMs: Long) {
        if (screen != null) this.screen = screen
        val h = synchronized(lock) {
            if (screen == null || (action != "push" && action != "replace")) {
                if (action != "remove") visit = null
                return
            }
            if (!foreground) {
                visit = null
                return
            }
            val ago = maxOf(0L, epochMs() - timeMs)
            visit = Visit(screen, elapsedMs() - ago)
            host
        }
        h?.requestDraw()
    }

    /**
     * The app named the current screen by its route template
     * (`Traceway.setRoute`): its display times and frames are counted under
     * it from now on.
     */
    fun routeNamed(route: String) {
        screen = route
        synchronized(lock) { visit?.screen = route }
    }

    /** The current screen shows everything it loads. */
    fun fullyDisplayed() {
        val v: Visit
        val ms: Long
        synchronized(lock) {
            v = visit ?: return
            if (v.fullyDisplayed) return
            v.fullyDisplayed = true
            ms = elapsedMs() - v.startMs
        }
        if (ms in 0..MAX_START_MS) recorder.screenFullyDisplayed(v.screen, ms.toDouble())
    }

    /** The frame [VitalsHost.requestDraw] waited for is drawn. */
    internal fun drawn() {
        val at = elapsedMs()
        var start: PendingStart? = null
        var startMs = 0L
        var displayed: Visit? = null
        var displayMs = 0L
        synchronized(lock) {
            val p = pendingStart
            if (p != null) {
                pendingStart = null
                val counts = p.type != START_COLD || coldStartCounts(backgroundedBeforeFirstFrame, foreground)
                val ms = at - p.originMs
                if (counts && ms > 0 && ms <= MAX_START_MS) {
                    start = p
                    startMs = ms
                }
            }
            firstFrameDone = true
            val v = visit
            if (v != null && !v.displayed) {
                v.displayed = true
                val ms = at - v.startMs
                if (ms in 0..MAX_START_MS) {
                    displayed = v
                    displayMs = ms
                }
            }
        }
        start?.let { recorder.appStart(startMs.toDouble(), it.type, slow = startMs >= slowStartMs(it.type)) }
        displayed?.let { recorder.screenDisplayed(it.screen, displayMs.toDouble()) }
    }

    // --------------------------------------------------------------- frames

    /** One frame of a resumed activity; any thread. */
    internal fun frame(kind: FrameKind) {
        if (!foreground) return
        tally.add(screen, kind, frameClock())
    }

    /** The activity on screen is leaving it: what it drew is counted now. */
    internal fun framesPaused() = tally.flush()

    private fun safeScreen(): String? = try {
        currentScreen()
    } catch (_: Throwable) {
        null
    }

    companion object {
        /** Where a cold start is measured from: the process start, unless it is implausible or long before the SDK's. */
        internal fun coldStartOrigin(processStartMs: Long?, sdkStartMs: Long): Long =
            if (processStartMs != null && processStartMs <= sdkStartMs &&
                sdkStartMs - processStartMs < MAX_PROCESS_TO_SDK_MS
            ) {
                processStartMs
            } else {
                sdkStartMs
            }

        /** Whether a cold start whose first frame came now is one somebody waited for. */
        internal fun coldStartCounts(backgroundedBeforeFirstFrame: Boolean, foreground: Boolean): Boolean =
            !backgroundedBeforeFirstFrame && foreground

        internal fun slowStartMs(type: String): Double = when (type) {
            START_COLD -> SLOW_COLD_START_MS
            START_WARM -> SLOW_WARM_START_MS
            else -> SLOW_HOT_START_MS
        }

        /**
         * When the process started, on the `elapsedRealtime` clock: when its
         * start was requested on API 33+, when it was forked on API 24+, null
         * below.
         */
        fun processStartElapsedRealtime(): Long? = try {
            when {
                Build.VERSION.SDK_INT >= 33 -> Process.getStartRequestedElapsedRealtime()
                Build.VERSION.SDK_INT >= 24 -> Process.getStartElapsedRealtime()
                else -> null
            }?.takeIf { it > 0 }
        } catch (_: Throwable) {
            null
        }

        /** The process start as Unix ms; null below API 24. */
        fun readProcessStartMs(): Long? {
            val start = processStartElapsedRealtime() ?: return null
            return System.currentTimeMillis() - (SystemClock.elapsedRealtime() - start)
        }

        /** The most memory the process has held (`VmHWM` of `/proc/self/status`), in bytes; 0 when unknown. */
        fun readMemoryPeakBytes(): Long = try {
            parseVmHwmBytes(File("/proc/self/status").readText())
        } catch (_: Throwable) {
            0L
        }

        internal fun parseVmHwmBytes(status: String): Long {
            for (line in status.lineSequence()) {
                if (!line.startsWith("VmHWM:")) continue
                val kb = line.substring(6).trim().substringBefore(' ').toLongOrNull() ?: return 0L
                return kb * 1024
            }
            return 0L
        }
    }
}

// ------------------------------------------------------------------ Android

/**
 * Draws, frames and lifecycle of the app's activities for [AppVitals]. All
 * state is touched on the main thread only.
 */
private class AndroidVitalsHost(
    private val app: Application,
    private val vitals: AppVitals,
) : VitalsHost, Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private var resumed: WeakReference<Activity>? = null
    private var drawWanted = false
    private var nextDraw: NextDraw? = null
    private var frames: ActivityFrames? = null
    private var framesThread: HandlerThread? = null
    private var framesHandler: Handler? = null

    @Volatile
    private var sawActivity = false

    override fun install() {
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun uninstall() {
        app.unregisterActivityLifecycleCallbacks(this)
        onMain {
            frames?.stop()
            frames = null
            nextDraw?.cancel()
            nextDraw = null
            drawWanted = false
            resumed = null
            framesThread?.quitSafely()
            framesThread = null
            framesHandler = null
        }
    }

    override fun requestDraw() = onMain {
        drawWanted = true
        arm()
    }

    override fun cancelDraw() = onMain {
        drawWanted = false
        nextDraw?.cancel()
        nextDraw = null
    }

    override fun postToMain(task: Runnable): Boolean = main.post(task)

    override fun checkLaunch(result: (LaunchInfo) -> Unit) {
        val importance = try {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } catch (_: Throwable) {
            true
        }
        // Posted twice: on some devices the first message posted while the
        // application starts runs before the launch of its activity.
        main.post {
            main.post {
                try {
                    result(LaunchInfo(importance, sawActivity))
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun debuggerAttached(): Boolean = try {
        Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    } catch (_: Throwable) {
        false
    }

    private fun onMain(task: () -> Unit) {
        val contained = { guarded("vitals main") { task() } }
        if (Looper.myLooper() == Looper.getMainLooper()) contained() else main.post(contained)
    }

    /** Waits for the next frame of the resumed activity and makes sure one comes. */
    private fun arm() {
        val activity = resumed?.get() ?: return
        val decor = activity.window?.peekDecorView() ?: return
        val current = nextDraw
        if (current != null && !current.done && current.view === decor) {
            decor.invalidate()
            return
        }
        current?.cancel()
        val draw = NextDraw(decor, main) { self ->
            if (nextDraw === self) nextDraw = null
            drawWanted = false
            vitals.drawn()
        }
        nextDraw = draw
        draw.install()
        decor.invalidate()
    }

    private fun framesHandler(): Handler {
        framesHandler?.let { return it }
        val thread = HandlerThread("TracewayFrames", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        framesThread = thread
        return Handler(thread.looper).also { framesHandler = it }
    }

    // The app's activity lifecycle dispatch runs these: nothing may escape into it.
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = guarded("vitals created") {
        sawActivity = true
        vitals.activityCreated()
    }

    override fun onActivityStarted(activity: Activity) = guarded("vitals started") {
        sawActivity = true
        vitals.activityStarted()
    }

    override fun onActivityResumed(activity: Activity) = guarded("vitals resumed") {
        sawActivity = true
        resumed = WeakReference(activity)
        try {
            frames?.stop()
            frames = ActivityFrames(activity).also { it.start() }
        } catch (_: Throwable) {
            frames = null
        }
        if (drawWanted) arm()
    }

    override fun onActivityPaused(activity: Activity) = guarded("vitals paused") {
        if (resumed?.get() === activity) resumed = null
        frames?.let {
            if (it.activity === activity) {
                it.stop()
                frames = null
            }
        }
        // Not drawn yet: the next activity to resume is waited for instead.
        nextDraw?.let {
            if (!it.done && it.view === activity.window?.peekDecorView()) {
                it.cancel()
                nextDraw = null
            }
        }
        vitals.framesPaused()
    }

    override fun onActivityStopped(activity: Activity) = guarded("vitals stopped") {
        vitals.activityStopped(activity.isChangingConfigurations)
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {}

    /** The frames of one resumed activity's window. */
    private inner class ActivityFrames(val activity: Activity) {
        private var metrics: FrameMetricsWatcher? = null
        private var chain: ChoreographerFrames? = null

        fun start() {
            val window = activity.window ?: return
            val budget = FrameRules.budgetNs(refreshRate(activity))
            if (Build.VERSION.SDK_INT >= 24) {
                metrics = FrameMetricsWatcher(window, framesHandler(), budget) { vitals.frame(it) }.also { it.start() }
            } else {
                val decor = window.peekDecorView() ?: return
                chain = ChoreographerFrames(decor, main, budget) { vitals.frame(it) }.also { it.start() }
            }
        }

        fun stop() {
            if (Build.VERSION.SDK_INT >= 24) metrics?.stop()
            metrics = null
            chain?.stop()
            chain = null
        }
    }

    private fun refreshRate(activity: Activity): Float = try {
        val display = if (Build.VERSION.SDK_INT >= 30) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }
        display?.refreshRate ?: 60f
    } catch (_: Throwable) {
        60f
    }
}

/**
 * The next draw of [view]'s window. The frame is on its way to the screen
 * once the draw pass returns, so the front of the main queue runs right
 * after it.
 */
private class NextDraw(
    val view: View,
    private val main: Handler,
    private val onDrawn: (NextDraw) -> Unit,
) : ViewTreeObserver.OnDrawListener {
    var done = false
        private set

    fun install() = addDrawListener(view, this) { done }

    override fun onDraw() {
        if (done) return
        done = true
        main.postAtFrontOfQueue {
            try {
                onDrawn(this)
            } catch (_: Throwable) {
            }
        }
        // A draw listener cannot be removed while the draw pass runs.
        main.post { removeDrawListener(view, this) }
    }

    fun cancel() {
        done = true
        main.post { removeDrawListener(view, this) }
    }
}

@RequiresApi(24)
private class FrameMetricsWatcher(
    private val window: Window,
    private val handler: Handler,
    private val budgetNs: Long,
    private val onFrame: (FrameKind) -> Unit,
) : Window.OnFrameMetricsAvailableListener {
    fun start() {
        try {
            window.addOnFrameMetricsAvailableListener(this, handler)
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        try {
            window.removeOnFrameMetricsAvailableListener(this)
        } catch (_: Throwable) {
        }
    }

    override fun onFrameMetricsAvailable(window: Window, metrics: FrameMetrics, dropCountSinceLastInvocation: Int) {
        try {
            val cpu = metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION) +
                metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) +
                metrics.getMetric(FrameMetrics.ANIMATION_DURATION) +
                metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) +
                metrics.getMetric(FrameMetrics.DRAW_DURATION) +
                metrics.getMetric(FrameMetrics.SYNC_DURATION)
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else 0L
            onFrame(FrameRules.classify(cpu, total, if (deadline > 0) deadline else budgetNs))
        } catch (_: Throwable) {
        }
    }
}

/**
 * Frames below API 24, from Choreographer: a frame counts when the window
 * drew in it, and lasts until the next vsync callback.
 */
private class ChoreographerFrames(
    private val decor: View,
    private val main: Handler,
    private val budgetNs: Long,
    private val onFrame: (FrameKind) -> Unit,
) : Choreographer.FrameCallback, ViewTreeObserver.OnDrawListener {
    private var running = false
    private var lastNs = 0L
    private var drew = false

    fun start() {
        running = true
        lastNs = 0L
        addDrawListener(decor, this) { !running }
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        main.post { removeDrawListener(decor, this) }
    }

    override fun onDraw() {
        drew = true
    }

    // A frame callback of the app's Choreographer: nothing may escape into it, and the next frame is still asked for.
    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        guarded("frame") { if (lastNs != 0L && drew) onFrame(FrameRules.classifyInterval(frameTimeNanos - lastNs, budgetNs)) }
        drew = false
        lastNs = frameTimeNanos
        guarded("frame callback") { Choreographer.getInstance().postFrameCallback(this) }
    }
}

/**
 * Below API 26 a draw listener added before the view is attached is lost
 * when it attaches, so it is added then.
 */
private fun addDrawListener(view: View, listener: ViewTreeObserver.OnDrawListener, cancelled: () -> Boolean) {
    try {
        if (Build.VERSION.SDK_INT >= 26 || view.isAttachedToWindow) {
            view.viewTreeObserver.addOnDrawListener(listener)
            return
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                if (!cancelled()) v.viewTreeObserver.addOnDrawListener(listener)
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
            }
        })
    } catch (_: Throwable) {
    }
}

private fun removeDrawListener(view: View, listener: ViewTreeObserver.OnDrawListener) {
    try {
        val observer = view.viewTreeObserver
        if (observer.isAlive) observer.removeOnDrawListener(listener)
    } catch (_: Throwable) {
    }
}
