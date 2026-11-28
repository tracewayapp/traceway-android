package com.tracewayapp.traceway.session

import com.tracewayapp.traceway.core.Ids
import com.tracewayapp.traceway.internal.Cancellable
import com.tracewayapp.traceway.internal.TaskScheduler
import com.tracewayapp.traceway.internal.asJsonObject
import com.tracewayapp.traceway.internal.asLongExact
import com.tracewayapp.traceway.persistence.StateStore

internal class SessionInfo(
    val id: String,
    val startedAtMs: Long,
    /** A session an earlier run of the app left open, found over at this start: it is only ended. */
    val earlierRun: Boolean = false,
    /** What [SessionManager.save] kept with it, for an earlier run's session. */
    val saved: Map<String, Any?>? = null,
)

/**
 * One session per app run, ended after [inactivityMs] without user activity
 * or [maxDurationMs] in total. A restart or a return from the background
 * inside the inactivity window continues the same session. A session an
 * earlier run left open (the app was killed in the background, as most
 * mobile sessions end) and that is over by the next start is ended first,
 * at its last activity, with what was [save]d with it; then the next one
 * starts.
 *
 * Not thread-safe: the client calls it under its lock, and the 30 s check
 * runs through [locked], which takes that lock. The callbacks run on the
 * caller's thread, inside that lock.
 */
internal class SessionManager(
    private val store: StateStore,
    private val scheduler: TaskScheduler,
    private val onStart: (session: SessionInfo, resumed: Boolean) -> Unit,
    private val onEnd: (session: SessionInfo, endedAtMs: Long) -> Unit,
    val inactivityMs: Long = DEFAULT_INACTIVITY_MS,
    val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
    /** Runs the periodic check; the client passes one that takes its lock. */
    private val locked: (() -> Unit) -> Unit = { it() },
    private val now: () -> Long = scheduler::now,
) {
    companion object {
        const val DEFAULT_INACTIVITY_MS = 15L * 60 * 1000
        const val DEFAULT_MAX_DURATION_MS = 60L * 60 * 1000
        const val CHECK_INTERVAL_MS = 30_000L
        private const val STORE_KEY = "session"
    }

    private var session: SessionInfo? = null
    private var saved: Map<String, Any?>? = null
    private var lastActivityMs = 0L
    private var segmentIndex = 0
    private var lastPersistMs = 0L
    private var timer: Cancellable? = null
    private var generation = 0
    private var started = false

    val current: SessionInfo? get() = session
    val currentId: String? get() = session?.id

    fun start() {
        if (started) return
        started = true
        open()
        scheduleCheck(++generation)
    }

    fun stop() {
        started = false
        generation++
        timer?.cancel()
        timer = null
        persist()
        session = null
    }

    private fun scheduleCheck(gen: Int) {
        timer = scheduler.schedule(CHECK_INTERVAL_MS) {
            locked {
                if (started && gen == generation) {
                    check()
                    scheduleCheck(gen)
                }
            }
        }
    }

    /**
     * Keeps the session as it is now (its last activity, and [attributes]
     * when given) in the store, where a later run finds it if this process
     * is killed.
     */
    fun save(attributes: Map<String, Any?>? = null) {
        if (attributes != null) saved = attributes
        persist()
    }

    /**
     * The app went to the background: the user was in it until now, which
     * is the session's last activity (and, if it is not opened again in
     * time, its end). An expired session is ended; none is started.
     */
    fun leave() {
        if (!started) return
        check()
        if (session == null) return
        lastActivityMs = now()
        persist()
    }

    /** Next index for a replay segment of this session. */
    fun nextSegmentIndex(): Int {
        val index = segmentIndex++
        persist()
        return index
    }

    /** Notes user activity; an expired session is replaced first. */
    fun markActivity() {
        if (!started) return
        check()
        if (session == null) open()
        lastActivityMs = now()
        if (lastActivityMs - lastPersistMs > 5000) persist()
    }

    /** Ends an expired session. The next activity starts its successor. */
    fun check() {
        val current = session ?: return
        val now = now()
        val idle = now - lastActivityMs >= inactivityMs
        val tooLong = now - current.startedAtMs >= maxDurationMs
        if (!idle && !tooLong) return
        val endedAt = if (idle) lastActivityMs else now
        session = null
        saved = null
        store.set(STORE_KEY, null)
        onEnd(current, endedAt)
    }

    private fun open() {
        val now = now()
        val stored = store.get(STORE_KEY).asJsonObject()
        var resumed: SessionInfo? = null
        saved = null
        if (stored != null) {
            val id = stored["id"] as? String
            val startedAt = stored["startedAtMs"].asLongExact()
            val lastActivity = stored["lastActivityMs"].asLongExact()
            if (id != null && startedAt != null && lastActivity != null) {
                if (now - lastActivity < inactivityMs && now - startedAt < maxDurationMs) {
                    resumed = SessionInfo(id, startedAt)
                    segmentIndex = stored["segmentIndex"].asLongExact()?.toInt()?.coerceAtLeast(0) ?: 0
                    saved = stored["attributes"].asJsonObject()
                } else {
                    // Left open by a run that was killed: it ends where it stopped, before its successor starts.
                    store.set(STORE_KEY, null)
                    val ended = SessionInfo(id, startedAt, earlierRun = true, saved = stored["attributes"].asJsonObject())
                    onEnd(ended, minOf(lastActivity, startedAt + maxDurationMs).coerceAtLeast(startedAt))
                }
            }
        }
        if (resumed == null) segmentIndex = 0
        val next = resumed ?: SessionInfo(Ids.uuidv4(), now)
        session = next
        lastActivityMs = now
        persist()
        onStart(next, resumed != null)
    }

    private fun persist() {
        val current = session ?: return
        lastPersistMs = now()
        store.set(
            STORE_KEY,
            linkedMapOf(
                "id" to current.id,
                "startedAtMs" to current.startedAtMs,
                "lastActivityMs" to lastActivityMs,
                "segmentIndex" to segmentIndex,
                "attributes" to saved,
            ),
        )
    }
}
