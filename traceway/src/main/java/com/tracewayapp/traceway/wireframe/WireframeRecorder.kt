package com.tracewayapp.traceway.wireframe

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tracewayapp.traceway.interaction.PointerListener
import com.tracewayapp.traceway.internal.Cancellable
import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.replay.ReplaySource
import com.tracewayapp.traceway.replay.SessionRecordingPayload

/**
 * Where frames come from: the app's windows on a device, or frames a test
 * makes. Every method but [maskAllInputs] runs on the main thread.
 */
internal interface WireframeFrameSource {
    /** Masks what is typed into text fields; may be set from any thread. */
    var maskAllInputs: Boolean

    /** Calls [onDraw] after the app draws, until [stopObserving]. */
    fun observe(onDraw: () -> Unit)
    fun stopObserving()

    /** The screen now, or null when nothing is on it. */
    fun capture(timestampMs: Long, href: String?): WireframeFrame?

    /** Forgets the previous frame, so the next capture shares nothing with it. */
    fun reset()
}

/** The thread that owns the views. Tests run it on a manual clock. */
internal interface MainThread {
    fun isCurrent(): Boolean

    /** Runs [task] on the main thread after [delayMs]. */
    fun post(delayMs: Long, task: () -> Unit): Cancellable
}

internal object AndroidMainThread : MainThread {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun isCurrent(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    override fun post(delayMs: Long, task: () -> Unit): Cancellable {
        val runnable = Runnable {
            try {
                task()
            } catch (_: Throwable) {
                // A capture error costs one frame, never the app.
            }
        }
        handler.postDelayed(runnable, if (delayMs < 0) 0 else delayMs)
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}

internal fun logDebug(message: String) {
    try {
        Log.d("Traceway", message)
    } catch (_: Throwable) {
    }
}

/**
 * Records the screen as a wireframe in rrweb's format instead of as video:
 * the app's view tree (and Jetpack Compose semantics) becomes an HTML
 * document, and what changes from frame to frame becomes rrweb mutations.
 * The dashboard replays it with the same player as a browser session.
 *
 * The screen is captured on the main thread after frames the app draws, at
 * most [fps] times a second, and not at all while nothing draws or the app
 * is in the background. Frames share the nodes that did not change, so the
 * ring of the last `maxFrames` frames stays small. A clip is assembled when
 * it is asked for, on any thread: a full snapshot of the screen as it was
 * when the clip starts, then the mutations and touches up to its end, so
 * every clip and segment plays on its own.
 *
 * Touches come in as a [PointerListener], in dp from the screen's top-left.
 */
internal class WireframeRecorder(
    maxFrames: Int,
    fps: Int = 10,
    private val source: WireframeFrameSource,
    private val mainThread: MainThread = AndroidMainThread,
    private val clock: () -> Long = System::currentTimeMillis,
    private val debug: Boolean = false,
    private val log: (String) -> Unit = ::logDebug,
) : ReplaySource, PointerListener {

    val fps: Int = fps.coerceIn(1, 30)
    private val maxFrames = if (maxFrames < 2) 2 else maxFrames
    private val intervalMs = 1000L / this.fps

    private val lock = Any()
    private val frames = ArrayList<WireframeFrame>()
    private val pointers = ArrayList<WireframePointer>()

    @Volatile
    private var running = false

    /** In the background: nothing is on screen. */
    @Volatile
    private var paused = false

    // Main thread only.
    private var timer: Cancellable? = null
    private var lastCaptureMs = 0L
    private var observing = false
    private val lastMoveMs = HashMap<Int, Long>()

    /** Time spent capturing, for the debug log. */
    @Volatile
    private var captureNanos = 0L

    @Volatile
    private var captures = 0

    /** The screen on top (activity, fragment or route), the replay's address. */
    @Volatile
    var screenName: (() -> String?)? = null

    /**
     * Masks what is typed into text fields, as rrweb's `maskAllInputs` does.
     * On by default; remote settings can switch it off (`replay.maskAllInputs`).
     * Password fields are masked either way.
     */
    var maskAllInputs: Boolean
        get() = source.maskAllInputs
        set(value) {
            source.maskAllInputs = value
        }

    /** Number of distinct screens held; never more than the ring's capacity. */
    val bufferedFrameCount: Int
        get() = synchronized(lock) { frames.size }

    /** The pictures the wireframe records; null with a test's frame source. */
    val images: WireframeImages?
        get() = (source as? ViewFrameSource)?.images

    /** The windows recorded on a device; null with a test's frame source. */
    val roots: WireframeRoots?
        get() = (source as? ViewFrameSource)?.roots

    override val isRunning: Boolean
        get() = running

    override fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        onMain { if (running && !paused) resume() }
        if (debug) log("Traceway: wireframe recorder started (up to ${this.fps}fps)")
    }

    override fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
        }
        onMain { halt() }
        pushBlank()
    }

    override fun clearBuffer() {
        synchronized(lock) {
            frames.clear()
            pointers.clear()
        }
        onMain { source.reset() }
    }

    /**
     * Whether the app can be seen. In the background nothing is captured and
     * the replay shows a blank "App in the background" screen; back in the
     * foreground the screen is captured at once.
     */
    fun setForeground(foreground: Boolean) {
        val pause = !foreground
        synchronized(lock) {
            if (pause == paused) return
            paused = pause
        }
        if (pause) {
            pushBlank()
            onMain { halt() }
        } else {
            onMain { if (running && !paused) resume() }
        }
    }

    /** Adds [activity]'s windows when the window list cannot be read. */
    fun addActivity(activity: Activity) {
        roots?.addActivity(activity)
    }

    fun removeActivity(activity: Activity) {
        roots?.removeActivity(activity)
    }

    // ---- capturing ----------------------------------------------------------

    private fun onMain(task: () -> Unit) {
        if (mainThread.isCurrent()) {
            try {
                task()
            } catch (_: Throwable) {
            }
        } else {
            mainThread.post(0, task)
        }
    }

    private fun resume() {
        if (!observing) {
            observing = true
            source.observe(::onDraw)
        }
        schedule(immediately = true)
    }

    private fun halt() {
        timer?.cancel()
        timer = null
        if (observing) {
            observing = false
            source.stopObserving()
        }
    }

    /** Called on the main thread after the app drew, or when a window appeared. */
    fun onDraw() {
        if (running && !paused) schedule(immediately = false)
    }

    private fun schedule(immediately: Boolean) {
        if (timer != null || !running || paused) return
        val wait = if (immediately) 0L else lastCaptureMs + intervalMs - clock()
        timer = mainThread.post(if (wait < 0) 0 else wait) { captureNow() }
    }

    private fun captureNow() {
        timer = null
        if (!running || paused) return
        val now = clock()
        lastCaptureMs = now
        val started = System.nanoTime()
        try {
            val frame = source.capture(now, screenName?.invoke())
            captureNanos += System.nanoTime() - started
            captures++
            if (frame != null) push(frame)
        } catch (t: Throwable) {
            captureNanos += System.nanoTime() - started
            // A view the walk does not expect costs this frame, never the app.
            try {
                source.reset()
            } catch (_: Throwable) {
            }
            if (debug) log("Traceway: wireframe capture error: $t")
        }
    }

    /** Adds a frame the source made; tests call it with frames of their own. */
    fun push(frame: WireframeFrame) {
        synchronized(lock) {
            val last = frames.lastOrNull()
            if (last != null && last.screen === frame.screen && last.sameSize(frame)) return
            frames.add(frame)
            if (frames.size > maxFrames) frames.removeAt(0)
        }
    }

    /** Nothing is on screen from now on: the app went to the background or the recorder stopped. */
    private fun pushBlank() {
        synchronized(lock) {
            val last = frames.lastOrNull() ?: return
            if (last.screen == null) return
            frames.add(WireframeFrame(null, last.width, last.height, clock(), last.href))
            if (frames.size > maxFrames) frames.removeAt(0)
        }
    }

    // ---- pointers -----------------------------------------------------------

    override fun pointerDown(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean) =
        addPointer(WireframePointer(WireframePointer.Kind.DOWN, xDp, yDp, clock(), touch))

    override fun pointerMove(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean) {
        val now = clock()
        val last = lastMoveMs[pointerId]
        if (last != null && now - last < MOVE_INTERVAL_MS) return
        lastMoveMs[pointerId] = now
        addPointer(WireframePointer(WireframePointer.Kind.MOVE, xDp, yDp, now, touch))
    }

    override fun pointerUp(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean, tap: Boolean) {
        lastMoveMs.remove(pointerId)
        addPointer(WireframePointer(if (tap) WireframePointer.Kind.TAP else WireframePointer.Kind.UP, xDp, yDp, clock(), touch))
    }

    override fun pointerCancel(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean) {
        lastMoveMs.remove(pointerId)
        addPointer(WireframePointer(WireframePointer.Kind.CANCEL, xDp, yDp, clock(), touch))
    }

    private fun addPointer(pointer: WireframePointer) {
        if (!running || paused) return
        synchronized(lock) {
            pointers.add(pointer)
            if (pointers.size > MAX_POINTERS) pointers.subList(0, pointers.size - MAX_POINTERS).clear()
        }
    }

    // ---- clips --------------------------------------------------------------

    override fun hasFrames(sinceMs: Long?, untilMs: Long?): Boolean {
        if (sinceMs != null && untilMs != null && sinceMs >= untilMs) return false
        synchronized(lock) {
            if (sinceMs != null) {
                val at = frameAt(frames, sinceMs)
                if (at >= 0 && frames[at].screen != null) return true
            }
            return frames.any {
                it.screen != null &&
                    (sinceMs == null || it.timestampMs >= sinceMs) &&
                    (untilMs == null || it.timestampMs < untilMs)
            }
        }
    }

    override fun captureRecording(clipId: String, sinceMs: Long?, untilMs: Long?): SessionRecordingPayload? {
        val frames: List<WireframeFrame>
        val pointers: List<WireframePointer>
        synchronized(lock) {
            frames = ArrayList(this.frames)
            pointers = ArrayList(this.pointers)
        }
        val end = untilMs ?: clock()
        if (sinceMs != null && sinceMs >= end) return null
        var index = if (sinceMs == null) -1 else frameAt(frames, sinceMs)
        val start: Long
        if (index >= 0 && frames[index].screen != null) {
            start = sinceMs!!
        } else {
            index = -1
            for (i in frames.indices) {
                val f = frames[i]
                if (f.screen == null) continue
                if (sinceMs != null && f.timestampMs < sinceMs) continue
                if (f.timestampMs >= end) break
                index = i
                break
            }
            if (index < 0) return null
            start = frames[index].timestampMs
        }

        val screenEvents = ArrayList<Map<String, Any?>>()
        fun snapshot(frame: WireframeFrame, at: Long) {
            screenEvents.add(
                event(
                    4,
                    linkedMapOf(
                        "href" to (frame.href ?: "/"),
                        "width" to Math.round(frame.width),
                        "height" to Math.round(frame.height),
                    ),
                    at,
                ),
            )
            screenEvents.add(
                event(
                    2,
                    linkedMapOf(
                        "node" to serializeDocument(frame.screen ?: awayScreen(frame.width, frame.height)),
                        "initialOffset" to linkedMapOf("left" to 0, "top" to 0),
                    ),
                    at,
                ),
            )
        }

        var previous = frames[index]
        snapshot(previous, start)
        for (i in index + 1 until frames.size) {
            val frame = frames[i]
            if (frame.timestampMs >= end) break
            val before = previous.screen
            val after = frame.screen
            if (before == null || after == null || !previous.sameSize(frame)) {
                snapshot(frame, frame.timestampMs)
            } else {
                val mutation = diffScreens(before, after)
                if (mutation != null) screenEvents.add(event(3, mutation, frame.timestampMs))
            }
            previous = frame
        }

        val pointerEvents = ArrayList<Map<String, Any?>>()
        for (p in pointers) {
            if (p.timestampMs >= start && p.timestampMs < end) pointerEvents(p, pointerEvents)
        }
        val events = merge(screenEvents, pointerEvents)
        if ((events.last()["timestamp"] as Long) < end) {
            // The screen stayed as it was until the clip ends: the replay's
            // timeline runs to then.
            events.add(event(5, linkedMapOf("tag" to "traceway.end", "payload" to emptyMap<String, Any?>()), end))
        }
        val bytes = Json.stringify(events).length
        if (debug) {
            val n = captures
            val average = if (n == 0) 0.0 else captureNanos / n.toDouble() / 1e6
            log(
                "Traceway: wireframe clip of ${events.size} events, ${bytes / 1024}KB, " +
                    "${(end - start) / 1000.0}s (${String.format(java.util.Locale.US, "%.2f", average)} ms per capture over $n)",
            )
        }
        return SessionRecordingPayload(
            clipId = clipId,
            events = events,
            startedAtMs = start,
            endedAtMs = end,
            approxBytes = bytes,
        )
    }

    /** Average time a capture took, in milliseconds; for the debug log and tests. */
    val averageCaptureMs: Double
        get() = if (captures == 0) 0.0 else captureNanos / captures.toDouble() / 1e6

    companion object {
        private const val MAX_POINTERS = 4000
        private const val MOVE_INTERVAL_MS = 50L

        /**
         * The recorder of this app's windows on the device. Call [attach]
         * with the application to follow activities and the foreground, or
         * call [setForeground] and [addActivity] from your own lifecycle
         * callbacks.
         */
        fun create(
            context: Context,
            maxFrames: Int,
            fps: Int,
            debug: Boolean = false,
            clock: () -> Long = System::currentTimeMillis,
        ): WireframeRecorder {
            val source = ViewFrameSource(WireframeRoots(), context.applicationContext ?: context, debug)
            return WireframeRecorder(maxFrames, fps, source, AndroidMainThread, clock, debug)
        }

        private fun event(type: Int, data: Map<String, Any?>, timestamp: Long): Map<String, Any?> =
            linkedMapOf("type" to type, "data" to data, "timestamp" to timestamp)

        /** The index of the frame showing at [at]: the last one taken at or before it, or -1. */
        private fun frameAt(frames: List<WireframeFrame>, at: Long): Int {
            for (i in frames.indices.reversed()) {
                if (frames[i].timestampMs <= at) return i
            }
            return -1
        }

        private fun merge(a: List<Map<String, Any?>>, b: List<Map<String, Any?>>): ArrayList<Map<String, Any?>> {
            val out = ArrayList<Map<String, Any?>>(a.size + b.size)
            var i = 0
            var j = 0
            while (i < a.size || j < b.size) {
                if (j >= b.size || (i < a.size && (a[i]["timestamp"] as Long) <= (b[j]["timestamp"] as Long))) {
                    out.add(a[i++])
                } else {
                    out.add(b[j++])
                }
            }
            return out
        }

        private fun pointerEvents(p: WireframePointer, out: MutableList<Map<String, Any?>>) {
            val ms = p.timestampMs
            val x = Math.round(p.x)
            val y = Math.round(p.y)
            fun interaction(type: Int): Map<String, Any?> = event(
                3,
                linkedMapOf(
                    "source" to 2,
                    "type" to type,
                    "id" to screenNodeId,
                    "x" to x,
                    "y" to y,
                    "pointerType" to if (p.touch) 2 else 0,
                ),
                ms,
            )
            when (p.kind) {
                WireframePointer.Kind.DOWN -> out.add(interaction(if (p.touch) 7 else 1))
                WireframePointer.Kind.MOVE -> out.add(
                    event(
                        3,
                        linkedMapOf(
                            "source" to if (p.touch) 6 else 1,
                            "positions" to listOf(linkedMapOf("x" to x, "y" to y, "id" to screenNodeId, "timeOffset" to 0)),
                        ),
                        ms,
                    ),
                )
                WireframePointer.Kind.UP -> out.add(interaction(if (p.touch) 9 else 0))
                WireframePointer.Kind.TAP -> {
                    out.add(interaction(if (p.touch) 9 else 0))
                    out.add(interaction(2))
                }
                WireframePointer.Kind.CANCEL -> out.add(interaction(if (p.touch) 10 else 0))
            }
        }

        /** What the replay shows while the app is in the background. */
        internal fun awayScreen(width: Float, height: Float): VNode = VNode.element(
            screenNodeId,
            "div",
            linkedMapOf(
                "id" to "tw",
                "style" to "width:${Math.round(width)}px;height:${Math.round(height)}px;font-family:$SCREEN_FONT;background:#1e1e1e",
            ),
            listOf(
                VNode.element(
                    awayScreenTextBoxId,
                    "div",
                    mapOf("style" to "left:0;top:45%;width:100%;text-align:center;color:#9e9e9e;font-size:14px"),
                    listOf(VNode.text(awayScreenTextId, "App in the background")),
                ),
            ),
        )
    }
}

/**
 * One pointer event kept for clips: where (dp from the screen's top-left)
 * and when a finger or the mouse went down, moved, went up (a [Kind.TAP]
 * when that ended a tap) or was cancelled.
 */
internal class WireframePointer(val kind: Kind, val x: Float, val y: Float, val timestampMs: Long, val touch: Boolean) {
    enum class Kind { DOWN, MOVE, UP, TAP, CANCEL }
}

/**
 * Follows [application]'s activities for [recorder]: their windows are
 * recorded, and the recorder pauses while none is started (the app is in the
 * background). Returns the callbacks, to unregister them.
 */
internal fun WireframeRecorder.attach(application: Application): Application.ActivityLifecycleCallbacks {
    val callbacks = ForegroundTracker(this, application)
    application.registerActivityLifecycleCallbacks(callbacks)
    return callbacks
}
