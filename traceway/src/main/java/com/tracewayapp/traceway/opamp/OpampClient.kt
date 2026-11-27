package com.tracewayapp.traceway.opamp

import com.tracewayapp.traceway.core.Ids
import com.tracewayapp.traceway.guarded
import com.tracewayapp.traceway.internal.Cancellable
import com.tracewayapp.traceway.internal.HttpResponse
import com.tracewayapp.traceway.internal.HttpTransport
import com.tracewayapp.traceway.internal.TaskScheduler
import com.tracewayapp.traceway.internal.UrlConnectionTransport

/** Result of applying a remote config; a null [errorMessage] means applied. */
internal class ApplyResult private constructor(val errorMessage: String?) {
    val applied: Boolean get() = errorMessage == null

    companion object {
        private val APPLIED = ApplyResult(null)

        fun applied(): ApplyResult = APPLIED

        fun failed(errorMessage: String): ApplyResult = ApplyResult(errorMessage)
    }
}

private const val MIN_POLL_INTERVAL_MS = 30_000L
private const val MAX_BACKOFF_MS = 30 * 60_000L
private const val FIRST_BACKOFF_MS = 30_000L
private const val INSTANCE_UID_HEADER = "OpAMP-Instance-UID"

private fun sameBytes(a: ByteArray?, b: ByteArray?): Boolean =
    a != null && b != null && a.contentEquals(b)

/**
 * An OpAMP client for the plain-HTTP transport: it polls, applies the remote
 * config the server offers and reports the result on the next poll. Polling
 * follows the server's heartbeat interval, backs off on errors and honours
 * Retry-After; a 413 is not retried and a 401/403/404 stops the client.
 *
 * Every method may be called from any thread. The exchange runs on
 * [scheduler]'s I/O pool, and so do all callbacks ([describe],
 * [onRemoteConfig], [onInstanceUidChange], [onStopped] and the `onDone` of
 * [poll] and [refresh]); none is called while the client holds its own
 * lock, so a callback may take the caller's lock or call back in.
 */
internal class OpampHttpClient(
    /** The server's OpAMP endpoint, e.g. https://cloud.tracewayapp.com/api/otel/v1/opamp. */
    val endpoint: String,
    val headers: Map<String, String> = emptyMap(),
    instanceUid: ByteArray,
    val capabilities: Long,
    val customCapabilities: List<String> = emptyList(),
    /**
     * Read on every poll: the client sends its full state each time, so a
     * stateless server can answer.
     */
    private val describe: () -> AgentDescription,
    private val onRemoteConfig: (RemoteConfig) -> ApplyResult,
    appliedConfigHash: ByteArray? = null,
    private val onInstanceUidChange: ((ByteArray) -> Unit)? = null,
    /**
     * Called once with the HTTP status when the server rejects the client for
     * good (bad credentials or no OpAMP support).
     */
    private val onStopped: ((Int) -> Unit)? = null,
    private val http: HttpTransport = UrlConnectionTransport,
    private val scheduler: TaskScheduler,
    defaultPollIntervalMs: Long = 300_000L,
    minPollIntervalMs: Long = MIN_POLL_INTERVAL_MS,
    val requestTimeoutMs: Int = 10_000,
) {
    /** What to do once an exchange is over. */
    private sealed class Next {
        /** The server turned the client away for good. */
        object Stopped : Next()

        /** The client was stopped while the exchange ran; its answer is dropped. */
        object Dropped : Next()
        object Now : Next()
        object Interval : Next()
        class BackOff(val retryAfterMs: Long?) : Next()
    }

    private val lock = Any()
    private val minIntervalMs = minPollIntervalMs

    // Guarded by [lock].
    private var uid: ByteArray = instanceUid.copyOf()
    private var sequenceNum = 0L
    private var status: RemoteConfigStatus? = null
    private var intervalMs: Long = maxOf(defaultPollIntervalMs, minPollIntervalMs)
    private var backoffMs = 0L
    private var timer: Cancellable? = null
    private var timerGeneration = 0L
    private var inFlight = false
    private var waiters = ArrayList<() -> Unit>()
    private var stopped = true

    /** Answers in a row that asked for another poll at once: past the first, the next waits [minIntervalMs]. */
    private var pollsAtOnce = 0

    init {
        if (appliedConfigHash != null && appliedConfigHash.isNotEmpty()) {
            status = RemoteConfigStatus(
                lastRemoteConfigHash = appliedConfigHash.copyOf(),
                status = RemoteConfigStatuses.APPLIED,
            )
        }
    }

    val pollIntervalMs: Long get() = synchronized(lock) { intervalMs }

    val isRunning: Boolean get() = synchronized(lock) { !stopped }

    val instanceUid: ByteArray get() = synchronized(lock) { uid.copyOf() }

    fun start() {
        synchronized(lock) {
            if (!stopped) return
            stopped = false
        }
        poll()
    }

    fun stop() {
        synchronized(lock) {
            stopped = true
            cancelTimerLocked()
        }
    }

    /**
     * Polls now unless a poll is running (then [onDone] waits for that one)
     * or the client is stopped or backing off after an error. [onDone] runs
     * on the I/O pool once the poll is over.
     */
    fun poll(onDone: (() -> Unit)? = null) {
        var exchange = false
        synchronized(lock) {
            when {
                stopped -> {}
                inFlight -> {
                    if (onDone != null) waiters.add(onDone)
                    return
                }
                backoffMs > 0 && timer != null -> {}
                else -> {
                    cancelTimerLocked()
                    inFlight = true
                    if (onDone != null) waiters.add(onDone)
                    exchange = true
                }
            }
        }
        if (exchange) {
            scheduler.io { exchange() }
        } else if (onDone != null) {
            scheduler.io { guarded("opamp callback", onDone) }
        }
    }

    /**
     * Polls with the current description even if a poll is under way, e.g.
     * right after a targeting attribute changed.
     */
    fun refresh(onDone: (() -> Unit)? = null) {
        synchronized(lock) {
            if (inFlight) {
                waiters.add { poll(onDone) }
                return
            }
        }
        poll(onDone)
    }

    private fun scheduleLocked(delayMs: Long) {
        if (stopped) return
        cancelTimerLocked()
        val generation = ++timerGeneration
        timer = scheduler.schedule(delayMs) { onTimer(generation) }
    }

    private fun cancelTimerLocked() {
        timer?.cancel()
        timer = null
        timerGeneration++
    }

    private fun onTimer(generation: Long) {
        synchronized(lock) {
            // A timer cancelled after it started firing.
            if (generation != timerGeneration) return
            timer = null
        }
        poll()
    }

    private fun exchange() {
        var next: Next = Next.BackOff(null)
        try {
            next = send()
        } catch (_: Throwable) {
            // A bug here must not end polling: back off and try again.
        } finally {
            finish(next)
        }
    }

    private fun send(): Next {
        val body: ByteArray
        val requestHeaders: Map<String, String>
        try {
            val (currentUid, seq, currentStatus) = synchronized(lock) {
                Triple(uid, sequenceNum++, status)
            }
            body = encodeAgentToServer(
                AgentToServer(
                    instanceUid = currentUid,
                    sequenceNum = seq,
                    agentDescription = describe(),
                    capabilities = capabilities,
                    remoteConfigStatus = currentStatus,
                    customCapabilities = customCapabilities,
                ),
            )
            requestHeaders = requestHeaders(currentUid)
        } catch (_: Throwable) {
            return Next.BackOff(null)
        }
        val response = try {
            http.post(endpoint, requestHeaders, body, requestTimeoutMs)
        } catch (_: Throwable) {
            HttpResponse(0)
        }

        val code = response.status
        if (code == 0) return Next.BackOff(null)
        if (code == 401 || code == 403 || code == 404 || code == 405 || code == 501) {
            stop()
            onStopped?.let { callback -> guarded("opamp callback") { callback(code) } }
            return Next.Stopped
        }
        if (code == 413) {
            synchronized(lock) { backoffMs = 0 }
            return Next.Interval
        }
        val message = try {
            decodeServerToAgent(response.body)
        } catch (_: Throwable) {
            null
        }
        if (code == 429 || code >= 500 ||
            message?.errorResponse?.type == ServerErrorTypes.UNAVAILABLE
        ) {
            return Next.BackOff(response.retryAfterMs ?: message?.errorResponse?.retryAfterMs)
        }
        if (code < 200 || code >= 300 || message == null) return Next.BackOff(null)
        synchronized(lock) {
            if (stopped) return Next.Dropped
            backoffMs = 0
        }
        return handle(message)
    }

    private fun requestHeaders(uid: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((key, value) in headers) {
            if (!key.equals("Content-Type", ignoreCase = true) &&
                !key.equals(INSTANCE_UID_HEADER, ignoreCase = true)
            ) {
                out[key] = value
            }
        }
        out["Content-Type"] = "application/x-protobuf"
        out[INSTANCE_UID_HEADER] = Ids.bytesToUuid(uid)
        return out
    }

    private fun handle(message: ServerToAgent): Next {
        val newUid = message.newInstanceUid
        if (newUid != null && newUid.size == 16) {
            synchronized(lock) { uid = newUid.copyOf() }
            onInstanceUidChange?.let { callback -> guarded("opamp callback") { callback(newUid.copyOf()) } }
        }
        val heartbeat = message.heartbeatIntervalSeconds
        if (heartbeat != null && heartbeat > 0) {
            val next = heartbeat.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
            synchronized(lock) { intervalMs = maxOf(next, minIntervalMs) }
        }

        var reportNow = false
        val config = message.remoteConfig
        if (config != null &&
            !sameBytes(config.configHash, synchronized(lock) { status?.lastRemoteConfigHash })
        ) {
            val result = try {
                onRemoteConfig(config)
            } catch (e: Throwable) {
                ApplyResult.failed(e.toString())
            }
            synchronized(lock) {
                status = RemoteConfigStatus(
                    lastRemoteConfigHash = config.configHash.copyOf(),
                    status = if (result.applied) RemoteConfigStatuses.APPLIED else RemoteConfigStatuses.FAILED,
                    errorMessage = result.errorMessage,
                )
            }
            reportNow = true
        }
        // The server answers a poll it cannot evaluate with ReportFullState; this
        // client always sends its full state, so it only needs to poll again. A
        // freshly applied config is reported straight away for the same reason.
        if (reportNow || (message.flags and SERVER_FLAG_REPORT_FULL_STATE) != 0L) return Next.Now
        return Next.Interval
    }

    /**
     * Ends an exchange: the next poll is scheduled under the same lock that
     * clears [inFlight], so a timer can never fire into a poll that is
     * already over and be folded into it.
     */
    private fun finish(next: Next) {
        val done: List<() -> Unit>
        synchronized(lock) {
            inFlight = false
            when (next) {
                // Started again (from onStopped): no faster than the interval.
                Next.Stopped -> scheduleLocked(intervalMs)
                // Started again while this exchange ran: that start's poll was
                // folded into this one, whose answer was dropped.
                Next.Dropped -> scheduleLocked(0)
                // Once at once (a config just applied, a server that lost track of this client); a server or proxy that
                // asks every time gets no faster than the minimum interval, not a tight loop.
                Next.Now -> scheduleLocked(if (++pollsAtOnce <= 1) 0 else minIntervalMs)
                Next.Interval -> {
                    pollsAtOnce = 0
                    scheduleLocked(intervalMs)
                }
                is Next.BackOff -> {
                    backoffMs = next.retryAfterMs
                        ?: if (backoffMs > 0) minOf(backoffMs * 2, MAX_BACKOFF_MS) else FIRST_BACKOFF_MS
                    scheduleLocked(maxOf(backoffMs, 1000L))
                }
            }
            done = waiters
            waiters = ArrayList()
        }
        // A callback must never take the poll loop down.
        for (callback in done) guarded("opamp callback", callback)
    }
}
