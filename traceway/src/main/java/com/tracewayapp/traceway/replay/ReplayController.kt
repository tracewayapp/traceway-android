package com.tracewayapp.traceway.replay

import com.tracewayapp.traceway.TracewayImagePolicy
import com.tracewayapp.traceway.core.Mode
import com.tracewayapp.traceway.internal.Cancellable
import com.tracewayapp.traceway.internal.TaskScheduler
import com.tracewayapp.traceway.persistence.await
import io.opentelemetry.sdk.common.CompletableResultCode

/**
 * Runs the screen recording according to the replay mode, like the browser
 * SDK runs rrweb:
 *
 * - `OFF` stops recording and forgets the frames.
 * - `BUFFER` keeps the last frames in the recorder's ring buffer and
 *   uploads a clip only when a trigger or capture flushes it.
 * - `STREAM` (a capture or trigger that asks for replay, or a rule)
 *   uploads the session as consecutive segments, one every [segmentMs].
 *
 * When masking or the image policy becomes stricter, what was recorded
 * before is dropped, as the browser SDK does: the frames held, the clips
 * being assembled and the clips not sent yet.
 *
 * The session's segments never overlap: each starts where the previous one
 * uploaded for the session ended, so the dashboard plays them in a row.
 *
 * Not thread-safe: the client calls it under its lock, and the segment
 * timer runs through [locked], which takes that lock. Clips are assembled
 * ([ReplaySource.captureRecording]) on the I/O pool.
 */
internal class ReplayController(
    val transport: RecordingsTransport,
    private val sessionId: () -> String?,
    private val nextSegmentIndex: () -> Int,
    private val scheduler: TaskScheduler,
    /**
     * How long a streamed segment is. Must stay below the frame ring
     * buffer's length, or frames are evicted before they are uploaded.
     */
    var segmentMs: Long = 8000,
    val budget: CollectionBudget = CollectionBudget(),
    /** Runs the segment timer; the client passes one that takes its lock. */
    private val locked: (() -> Unit) -> Unit = { it() },
) {
    private var source: ReplaySource? = null

    @Volatile
    var mode: Mode = Mode.OFF
        private set
    private var maxAgeMs = 600_000L

    /** What the frames held were recorded with: the strictest until [apply] says otherwise. */
    private var maskAllInputs = true
    private var images = TracewayImagePolicy.OFF
    private var segmentTimer: Cancellable? = null
    private var timerGeneration = 0

    /** The end of the last frames uploaded as part of the current session. */
    private var sessionCursor: Long? = null

    private val encodeLock = Object()
    private var encoding = 0
    private val idleWaiters = ArrayList<() -> Unit>()

    fun attach(source: ReplaySource?) {
        val previous = this.source
        if (previous != null && previous !== source) previous.stop()
        if (source == null) stopTimer()
        this.source = source
        sync()
    }

    /**
     * [sessionSampled] is whether the session falls inside
     * `replay.sessionSampleRate`; sessions outside it never stream.
     * [maskAllInputs] and [images] are the settings the source records with
     * from now on (`replay.maskAllInputs`, `replay.images`), already set on
     * it: when one is stricter than before, nothing recorded until now is
     * uploaded and the source takes the screen again.
     */
    fun apply(
        mode: Mode,
        maxAgeMs: Long,
        sessionSampled: Boolean = true,
        maskAllInputs: Boolean = true,
        images: TracewayImagePolicy = TracewayImagePolicy.OFF,
    ) {
        this.maxAgeMs = maxAgeMs
        val stricter = (maskAllInputs && !this.maskAllInputs) || images.ordinal < this.images.ordinal
        this.maskAllInputs = maskAllInputs
        this.images = images
        if (stricter) {
            // Typed text or pictures the settings no longer allow: the frames held go, and so do the clips
            // waiting to be sent, spooled ones too. A clip being assembled sees the transport's discard.
            source?.clearBuffer()
            transport.discard(unloadedSpool = true)
        }
        val effective = if (mode == Mode.STREAM && !sessionSampled) Mode.BUFFER else mode
        val previous = this.mode
        if (previous == Mode.STREAM && effective != Mode.STREAM) {
            // What was recorded since the last segment still belongs to the
            // session that was streaming.
            cutSegment()
        }
        this.mode = effective
        if (effective == Mode.STREAM && previous != Mode.STREAM) {
            val now = scheduler.now()
            val cursor = sessionCursor
            // Right after a lookback flush (a capture), streaming continues
            // where the flushed clip ended; otherwise it starts now.
            if (cursor == null || now - cursor > 1000) sessionCursor = now
        }
        sync()
    }

    private fun sync() {
        val source = source
        if (mode != Mode.STREAM) stopTimer()
        if (mode == Mode.OFF) {
            if (source?.isRunning == true) source.stop()
            source?.clearBuffer()
            transport.discard()
            sessionCursor = null
            return
        }
        if (source == null) return
        if (!source.isRunning) source.start()
        if (mode == Mode.STREAM) startTimer()
    }

    private fun startTimer() {
        if (segmentTimer != null) return
        tick(++timerGeneration)
    }

    private fun tick(gen: Int) {
        segmentTimer = scheduler.schedule(segmentMs) {
            locked {
                if (gen == timerGeneration && segmentTimer != null) {
                    cutSegment(timed = true)
                    if (gen == timerGeneration && segmentTimer != null) tick(gen)
                }
            }
        }
    }

    private fun stopTimer() {
        timerGeneration++
        segmentTimer?.cancel()
        segmentTimer = null
    }

    /**
     * Uploads the frames of the last [lookbackMs]. With [exceptionId] the
     * clip is that exception's; without, it is part of the session (a
     * capture's lookback). Returns false when no clip can be made. [sync]
     * assembles the clip on the calling thread, for a process about to die.
     */
    fun flush(lookbackMs: Long, exceptionId: String?, sync: Boolean = false): Boolean {
        val source = source
        if (mode == Mode.OFF || source == null) return false
        val until = scheduler.now()
        val windowMs = minOf(lookbackMs, maxAgeMs)
        val since = until - windowMs
        val hasException = !exceptionId.isNullOrEmpty()

        if (mode == Mode.STREAM) {
            if (!hasException) {
                // The session already streams; a capture's lookback adds nothing.
                cutSegment()
                return true
            }
            // The exception gets a clip of its own. It is not part of the
            // session, whose segments are streamed anyway.
            if (!source.hasFrames(since, until)) return false
            encode(since, until, exceptionId = exceptionId, sync = sync)
            return true
        }

        val session = sessionId()
        if (session == null && !hasException) return false
        var from = since
        val cursor = sessionCursor
        if (session != null && cursor != null && cursor > from) from = cursor
        var clipped = false
        if (hasException && from != since) {
            // Part of the window already went up with the session: the
            // exception still gets a clip of the whole window, outside it.
            if (!source.hasFrames(since, until)) return false
            encode(since, until, exceptionId = exceptionId, sync = sync)
            clipped = true
        }
        if (!source.hasFrames(from, until)) return clipped
        encode(
            from,
            until,
            exceptionId = if (hasException && !clipped) exceptionId else null,
            session = session,
            index = if (session == null) null else nextSegmentIndex(),
            sync = sync,
        )
        if (session != null) sessionCursor = until
        return true
    }

    /**
     * Streams what was recorded since the last segment of the session.
     * [session] overrides the current session, for the one that just ended.
     * A [timed] cut waits while an earlier clip is still being assembled: on
     * a device too slow to keep up the next segment covers more instead of
     * clips piling up in memory.
     */
    private fun cutSegment(session: String? = null, timed: Boolean = false) {
        val source = source
        val id = session ?: sessionId()
        if (source == null || id == null || mode != Mode.STREAM) return
        if (timed && synchronized(encodeLock) { encoding > 0 }) return
        val until = scheduler.now()
        val since = sessionCursor ?: (until - segmentMs)
        sessionCursor = until
        if (since >= until || !source.hasFrames(since, until)) return
        encode(since, until, session = id, index = nextSegmentIndex())
    }

    private fun encode(
        since: Long,
        until: Long,
        exceptionId: String? = null,
        session: String? = null,
        index: Int? = null,
        sync: Boolean = false,
    ) {
        val source = source ?: return
        val clipId = exceptionId ?: "${session}_$index"
        // An exception's clip outside the session still counts against the
        // budget of the session it happened in.
        val budgetSession = session ?: sessionId()
        val discards = transport.discards
        synchronized(encodeLock) { encoding++ }
        val work = {
            try {
                val clip = source.captureRecording(clipId, since, until)
                if (clip != null && clip.events.isNotEmpty() && mode != Mode.OFF && transport.discards == discards) {
                    val segment = RecordingSegment(
                        exceptionId = exceptionId,
                        sessionId = session,
                        segmentIndex = index,
                        events = clip.events,
                        startedAtMs = clip.startedAtMs,
                        endedAtMs = clip.endedAtMs,
                        approxBytes = clip.approxBytes,
                    )
                    if (budget.admit(budgetSession, 256 + segment.bytes, clip.events.size)) {
                        // Not queued when what was recorded was discarded while it was assembled.
                        transport.send(listOf(segment), discards)
                    }
                }
            } catch (_: Throwable) {
                // A failed clip loses the clip, never the app.
            } finally {
                encoded()
            }
        }
        if (sync) work() else scheduler.io(work)
    }

    private fun encoded() {
        val waiters: List<() -> Unit>
        synchronized(encodeLock) {
            encoding--
            if (encoding > 0) return
            encodeLock.notifyAll()
            waiters = ArrayList(idleWaiters)
            idleWaiters.clear()
        }
        for (w in waiters) {
            try {
                w()
            } catch (_: Throwable) {
            }
        }
    }

    private fun whenIdle(action: () -> Unit) {
        val now = synchronized(encodeLock) {
            if (encoding == 0) true else {
                idleWaiters.add(action)
                false
            }
        }
        if (now) action()
    }

    /** The session ended: a streaming one uploads its last segment under the session it belongs to. */
    fun onSessionEnd(endedSessionId: String) {
        cutSegment(session = endedSessionId)
        sessionCursor = null
    }

    fun onSessionStart() {
        sessionCursor = if (mode == Mode.STREAM) scheduler.now() else null
    }

    /** The app went to the background and may be killed there: a streaming session uploads what it has. */
    fun onPaused() = cutSegment()

    /** Completes once the clips being assembled are and the transport tried to send them. */
    fun drain(): CompletableResultCode {
        val result = CompletableResultCode()
        whenIdle { transport.flush().whenComplete { result.succeed() } }
        return result
    }

    /** [drain] and waits up to [timeoutMs]; true when it finished. Do not call it on an I/O thread. */
    fun drainBlocking(timeoutMs: Long): Boolean = drain().await(timeoutMs)

    /**
     * For a process about to die: waits up to [waitForClipsMs] for clips
     * still being assembled, then writes every clip waiting to be sent to
     * the transport's spool. Returns how many batches were written.
     */
    fun spool(waitForClipsMs: Long = 0): Int {
        val deadline = System.currentTimeMillis() + waitForClipsMs
        synchronized(encodeLock) {
            while (encoding > 0) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                try {
                    encodeLock.wait(left)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        return transport.spool()
    }

    fun stop() {
        stopTimer()
        source?.stop()
    }
}
