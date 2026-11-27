package com.tracewayapp.traceway.metrics

import com.tracewayapp.traceway.core.ControlState
import com.tracewayapp.traceway.core.Ids
import com.tracewayapp.traceway.internal.Cancellable
import com.tracewayapp.traceway.internal.SdkInfo
import com.tracewayapp.traceway.internal.TaskScheduler
import com.tracewayapp.traceway.internal.asJsonList
import com.tracewayapp.traceway.internal.asJsonObject
import com.tracewayapp.traceway.internal.asLongExact
import com.tracewayapp.traceway.persistence.StateStore
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal const val METRIC_APP_START = "app.start.duration"
internal const val METRIC_SCREEN_TTID = "app.screen.ttid"
internal const val METRIC_SCREEN_TTFD = "app.screen.ttfd"
internal const val METRIC_FRAMES_SLOW = "app.frames.slow"
internal const val METRIC_FRAMES_FROZEN = "app.frames.frozen"
internal const val METRIC_HANG = "app.hang"
internal const val METRIC_HANG_DURATION = "app.hang.duration"
internal const val METRIC_CRASH = "app.crash"
internal const val METRIC_SESSION = "app.session"
internal const val METRIC_USER = "app.user"
internal const val METRIC_USER_AFFECTED = "app.user.affected"
internal const val METRIC_MEMORY_PEAK = "app.memory.peak"
internal const val METRIC_REQUEST_DURATION = "app.request.duration"
internal const val METRIC_REQUEST_BYTES = "app.request.bytes"
internal const val METRIC_REQUEST_FAILED = "app.request.failed"

/** `start_type` of [METRIC_APP_START], as the server's catalog has them. */
internal const val START_COLD = "cold"
internal const val START_WARM = "warm"
internal const val START_HOT = "hot"

/**
 * Things that went wrong for a user, counted once per user and day. The
 * server accepts exactly these (`app.user.affected` in its catalog).
 */
internal enum class UserIssue(val wire: String) {
    CRASH("crash"),
    HANG("hang"),
    SLOW_START("slow_start"),
    FROZEN_FRAMES("frozen_frames"),
    FAILED_REQUEST("failed_request"),
}

/**
 * The device facts the server keeps as dimensions. Nothing that identifies
 * a user or a device.
 */
internal val METRIC_RESOURCE_KEYS = listOf(
    "service.name",
    "service.version",
    "deployment.environment.name",
    "os.name",
    "os.major",
    "device.form_factor",
    "device.class",
    "traceway.sdk.name",
    "traceway.sdk.version",
)

/** The server refuses a larger body (`clientmetrics.MaxBodyBytes` is 64 KiB). */
internal const val MAX_METRICS_BODY_BYTES = 60 * 1024
private const val MAX_HOSTS = 20
private const val MAX_CUSTOM_DIMENSIONS = 2
private const val MAX_DIMENSION_LENGTH = 64
private const val DEFAULT_USER_KEY = "metricsUser"
private const val DAY_MS = 86_400_000L
private const val MAX_EARLY = 64

/** Rejections a second attempt would get too. */
private val PERMANENT_STATUSES = setOf(400, 401, 403, 413, 422)
private val CUSTOM_NAME = Regex("^[a-z][a-z0-9_.]{0,62}$")
private val RESERVED_PREFIX = Regex("^(browser|app|traceway|custom)\\.")
private val GLOBAL_DIMENSION_KEY = Regex("^[a-z][a-z0-9_]{0,31}$")

/**
 * Turns measurements into device histograms and counters and uploads them
 * as one small OTLP payload per flush, like the browser and Flutter SDKs'
 * `MetricsRecorder`. The detailed events of the same measurements still go
 * through the router, where their signal's mode decides what leaves the
 * device: aggregates always travel, events only when asked for.
 *
 * Every payload is stored before it is sent and forgotten once answered, so
 * one that failed, or whose process died first, is resent once: after the
 * next upload that works or on the next start. The server drops a resend it
 * already has by its batch id.
 *
 * Thread-safe: every method may be called from any thread. The recorder's
 * own lock is never held while it calls [sessionId], [installationId],
 * [resource], [store] or [send]; [send] runs on [TaskScheduler.io].
 */
internal class MetricsRecorder(
    private val control: ControlState,
    private val sessionId: () -> String?,
    private val installationId: () -> String,
    private val resource: () -> Map<String, String>,
    /** One blocking upload of a protobuf body: the HTTP status, 0 when no answer came. */
    private val send: (ByteArray) -> Int,
    private val store: () -> StateStore,
    private val scheduler: TaskScheduler,
    private val scopeName: String = SdkInfo.NAME,
    private val scopeVersion: String = SdkInfo.VERSION,
    dimensions: Map<String, String>? = null,
    deferUntilStart: Boolean = false,
    /**
     * Where the state kept between runs belongs: payloads waiting for a
     * resend and the users counted today are kept per destination, so a
     * changed receiver is not sent another's numbers.
     */
    storageScope: String = "",
    private val random: Random = defaultRandom(),
) {
    private val lock = ReentrantLock()
    private val idle = lock.newCondition()

    private val userKey = if (storageScope.isEmpty()) DEFAULT_USER_KEY else "$DEFAULT_USER_KEY:$storageScope"
    private val now: () -> Long = scheduler::now

    /** Exposed for tests. */
    internal val aggregator = MetricAggregator(now = now)
    private val pending = PendingMetrics(now, storageScope)

    // Guarded by [lock].
    /**
     * Observations made before [start], while there is no session, no
     * settings from disk and no installation id yet: the cold start, the
     * first screen, an early error. They are recorded once it starts.
     */
    private var ready = !deferUntilStart
    private val early = ArrayList<() -> Unit>()
    private val hosts = HashSet<String>()
    private val inFlight = HashSet<String>()
    private val customDimensions = LinkedHashMap<String, String>()
    private var timer: Cancellable? = null
    private var running = false
    private var uploads = 0
    private var countedSession: String? = null
    private var lastSession: String? = null

    /** The sample rate the cells collected so far were recorded under: a payload states one rate. */
    private var batchRate: Double = control.metrics?.sampleRate ?: 0.0
    private var userDay: UserDay? = null

    private class UserDay(val day: Long, val seen: List<String>)

    private class Drained(val batch: MetricBatch?, val rate: Double, val dimensions: Map<String, String>)

    init {
        dimensions?.forEach { (k, v) -> setDimension(k, v) }
    }

    fun start() {
        val observations = lock.withLock {
            ready = true
            running = true
            ArrayList(early).also { early.clear() }
        }
        for (observe in observations) guarded(observe)
        schedule()
        guarded { resend() }
        guarded { user(null) }
    }

    fun stop() {
        lock.withLock {
            running = false
            timer?.cancel()
            timer = null
        }
    }

    /** True when [observe] was kept for [start]. */
    private fun defer(observe: () -> Unit): Boolean = lock.withLock {
        if (ready) return false
        if (early.size < MAX_EARLY) early.add(observe)
        true
    }

    /**
     * Remote settings changed: the flush interval may have, and the sample
     * rate. What was recorded under the old rate is uploaded with it first,
     * since the server weights a whole payload by the rate it states.
     */
    fun configure() {
        val rate = control.metrics?.sampleRate ?: 0.0
        var drained: Drained? = null
        val reschedule = lock.withLock {
            if (rate != batchRate) {
                // Switched off means nothing more is sent, including what was measured.
                if (rate > 0) drained = drainLocked() else aggregator.drain()
                batchRate = rate
            }
            running
        }
        drained?.let { guarded { upload(it, final = false) } }
        if (reschedule) schedule()
    }

    /** The session's sample rate when it records metrics, else 0. */
    private fun rate(session: String? = null): Double {
        val settings = control.metrics ?: return 0.0
        val id = session ?: run {
            val current = try {
                sessionId()
            } catch (_: Throwable) {
                null
            }
            lock.withLock {
                if (current != null) lastSession = current
                lastSession
            }
        } ?: return 0.0
        return if (Ids.unitHash(id) < settings.sampleRate) settings.sampleRate else 0.0
    }

    val active: Boolean get() = rate() > 0

    private fun exemplar(worthOpening: Boolean): String? {
        if (!worthOpening) return null
        val current = try {
            sessionId()
        } catch (_: Throwable) {
            null
        }
        return current ?: lock.withLock { lastSession }
    }

    /** An app-defined dimension on every metric from now on; null removes it. A third key is ignored. */
    fun setDimension(key: String, value: String?) {
        lock.withLock {
            if (value == null) {
                customDimensions.remove(key)
                return
            }
            if (!GLOBAL_DIMENSION_KEY.matches(key) ||
                (!customDimensions.containsKey(key) && customDimensions.size >= MAX_CUSTOM_DIMENSIONS)
            ) {
                return
            }
            customDimensions[key] = clip(value)
        }
    }

    /**
     * Counts this installation once per UTC day, and once per day for each
     * thing that went wrong for it, so "users affected" is a ratio of two
     * counters and no id leaves the device. Without storage that outlives
     * the run nothing is counted: every start would count the user again.
     * Sampled by installation, not by session, so a user with many sessions
     * is not more likely to be counted.
     */
    private fun user(issue: UserIssue?) {
        val settings = control.metrics ?: return
        val storage = store()
        if (!storage.persistent) return
        if (Ids.unitHash(installationId()) >= settings.sampleRate) return
        val day = now() / DAY_MS
        val wanted = if (issue == null) listOf(METRIC_USER) else listOf(METRIC_USER, issue.wire)
        lock.withLock {
            val known = userDay
            if (known != null && known.day == day && known.seen.containsAll(wanted)) return
            var seen: List<String> = emptyList()
            val record = storage.get(userKey).asJsonObject()
            val recorded = record?.get("i").asJsonList()
            if (record != null && record["d"].asLongExact() == day && recorded != null) {
                seen = recorded.map { "$it" }
            }
            val added = wanted.filter { it !in seen }
            seen = seen + added
            if (added.isNotEmpty()) storage.set(userKey, linkedMapOf("d" to day, "i" to seen))
            userDay = UserDay(day, seen)
            for (name in added) {
                if (name == METRIC_USER) {
                    aggregator.count(METRIC_USER)
                } else {
                    aggregator.count(METRIC_USER_AFFECTED, 1.0, mapOf("issue" to name))
                }
            }
        }
    }

    fun sessionStarted(id: String, resumed: Boolean = false) {
        if (defer { sessionStarted(id, resumed) }) return
        guarded { user(null) }
        lock.withLock {
            if (countedSession == id) return
            if (resumed) {
                // An earlier run of the app counted it.
                countedSession = id
                return
            }
        }
        if (rate(id) <= 0) return
        lock.withLock {
            if (countedSession == id) return
            countedSession = id
            aggregator.count(METRIC_SESSION)
        }
    }

    /** [type] is [START_COLD], [START_WARM] or [START_HOT]. */
    fun appStart(ms: Double, type: String, slow: Boolean) {
        if (defer { appStart(ms, type, slow) }) return
        if (slow) guarded { user(UserIssue.SLOW_START) }
        if (!active) return
        val session = exemplar(slow || ms > exemplarStartMs(type))
        lock.withLock {
            aggregator.observe(METRIC_APP_START, ms, mapOf("start_type" to type), "ms", session)
        }
    }

    fun screenDisplayed(screen: String, ms: Double) {
        if (defer { screenDisplayed(screen, ms) }) return
        if (!active) return
        val session = exemplar(ms > 1000)
        lock.withLock { aggregator.observe(METRIC_SCREEN_TTID, ms, mapOf("screen" to screen), "ms", session) }
    }

    fun screenFullyDisplayed(screen: String, ms: Double) {
        if (defer { screenFullyDisplayed(screen, ms) }) return
        if (!active) return
        val session = exemplar(ms > 2000)
        lock.withLock { aggregator.observe(METRIC_SCREEN_TTFD, ms, mapOf("screen" to screen), "ms", session) }
    }

    fun frames(screen: String?, total: Int, slow: Int, frozen: Int) {
        if (frozen > 0) guarded { user(UserIssue.FROZEN_FRAMES) }
        if (total <= 0 || !active) return
        val dims = if (screen != null) mapOf("screen" to screen) else emptyMap()
        lock.withLock {
            aggregator.ratio(METRIC_FRAMES_SLOW, slow.toDouble(), total.toDouble(), dims)
            aggregator.ratio(METRIC_FRAMES_FROZEN, frozen.toDouble(), total.toDouble(), dims)
        }
    }

    fun hang(ms: Double, screen: String?) {
        guarded { user(UserIssue.HANG) }
        if (!active) return
        val dims = if (screen != null) mapOf("screen" to screen) else emptyMap()
        val session = exemplar(true)
        lock.withLock {
            aggregator.count(METRIC_HANG, 1.0, dims)
            aggregator.observe(METRIC_HANG_DURATION, ms, dims, "ms", session)
        }
    }

    /**
     * An exception nothing caught. On Android it ends the process: call
     * [flush] with `final = true` right after, then flush the state store,
     * so the count outlives it.
     */
    fun crash() {
        if (defer { crash() }) return
        guarded { user(UserIssue.CRASH) }
        if (!active) return
        lock.withLock { aggregator.count(METRIC_CRASH) }
    }

    /** A finished HTTP request the SDK saw. */
    fun request(url: String, ms: Double, failed: Boolean, responseBytes: Long? = null) {
        if (defer { request(url, ms, failed, responseBytes) }) return
        if (failed) guarded { user(UserIssue.FAILED_REQUEST) }
        if (!active) return
        val parsed = hostOf(url) ?: return
        lock.withLock {
            var host = parsed
            if (host !in hosts) {
                if (hosts.size >= MAX_HOSTS) host = OVERFLOW_VALUE else hosts.add(host)
            }
            val dims = mapOf("host" to clip(host))
            aggregator.observe(METRIC_REQUEST_DURATION, ms, dims, "ms")
            aggregator.ratio(METRIC_REQUEST_FAILED, if (failed) 1.0 else 0.0, 1.0, dims)
            if (responseBytes != null && responseBytes >= 0) {
                aggregator.observe(METRIC_REQUEST_BYTES, responseBytes.toDouble(), dims, "By")
            }
        }
    }

    /** The highest memory the process used, once per session. */
    fun memoryPeak(session: String, bytes: Long) {
        if (defer { memoryPeak(session, bytes) }) return
        if (bytes <= 0 || rate(session) <= 0) return
        lock.withLock { aggregator.observe(METRIC_MEMORY_PEAK, bytes.toDouble(), unit = "By") }
    }

    /** An app-defined distribution, e.g. `checkout.duration`. */
    fun metric(name: String, value: Double, dims: Map<String, Any?>? = null) {
        if (!isCustomName(name) || !active) return
        lock.withLock { aggregator.observe(name, value, customDims(dims)) }
    }

    /** An app-defined counter. */
    fun count(name: String, n: Double = 1.0, dims: Map<String, Any?>? = null) {
        if (!isCustomName(name) || !active) return
        lock.withLock { aggregator.count(name, n, customDims(dims)) }
    }

    /**
     * Uploads what was collected; the request itself runs on the I/O pool.
     * [final] when the process may end soon (going to the background, a
     * crash): the payload is already in the state store when this returns,
     * so flush the store after it.
     */
    fun flush(final: Boolean = false) {
        val drained = lock.withLock { drainLocked() }
        guarded { upload(drained, final) }
    }

    /** Waits up to [timeoutMs] for the uploads started so far. False when some are still running. */
    fun awaitUploads(timeoutMs: Long): Boolean {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        lock.withLock {
            while (uploads > 0) {
                if (remaining <= 0) return false
                remaining = idle.awaitNanos(remaining)
            }
        }
        return true
    }

    private fun drainLocked(): Drained =
        Drained(aggregator.drain(), batchRate, LinkedHashMap(customDimensions))

    private fun upload(drained: Drained, final: Boolean) {
        val batch = drained.batch ?: return
        if (control.metrics == null || !(drained.rate > 0)) return
        val all = try {
            resource()
        } catch (_: Throwable) {
            emptyMap()
        }
        sendBatch(batch, drained, all, final)
    }

    private fun batchId(): String {
        val bytes = ByteArray(16)
        synchronized(random) { random.nextBytes(bytes) }
        return Ids.bytesToHex(bytes)
    }

    /** A payload over the server's body limit is sent as two; only a single cell that is too large is dropped. */
    private fun sendBatch(batch: MetricBatch, drained: Drained, all: Map<String, String>, final: Boolean) {
        val id = batchId()
        val attrs = LinkedHashMap<String, Any>()
        attrs["traceway.metrics"] = "aggregated"
        attrs["traceway.sample_rate"] = drained.rate
        attrs[ATTR_SENT_AT] = now()
        attrs["traceway.batch_id"] = id
        for (key in METRIC_RESOURCE_KEYS) {
            val value = all[key]
            if (!value.isNullOrEmpty()) attrs[key] = value
        }
        for ((key, value) in drained.dimensions) attrs["custom.$key"] = value
        val body = encodeMetrics(batch, attrs, scopeName, scopeVersion)
        if (body.size > MAX_METRICS_BODY_BYTES) {
            if (batch.cells < 2) return
            val half = (batch.cells + 1) / 2
            val cut = minOf(half, batch.histograms.size)
            sendBatch(
                MetricBatch(
                    batch.startMs, batch.endMs,
                    batch.histograms.subList(0, cut), batch.sums.subList(0, half - cut),
                ),
                drained, all, final,
            )
            sendBatch(
                MetricBatch(
                    batch.startMs, batch.endMs,
                    batch.histograms.subList(cut, batch.histograms.size),
                    batch.sums.subList(half - cut, batch.sums.size),
                ),
                drained, all, final,
            )
            return
        }
        // Stored before it is sent: the process may not live to see the answer.
        val storage = store()
        lock.withLock {
            inFlight.add(id)
            pending.add(storage, id, body)
        }
        track {
            val status = attempt(body)
            val failed = status == 0 || status == 429 || status >= 500
            lock.withLock {
                inFlight.remove(id)
                if (!failed) pending.remove(storage, id)
            }
            if (!final && !failed && status !in PERMANENT_STATUSES) resend()
        }
    }

    private fun attempt(body: ByteArray): Int = try {
        send(body)
    } catch (_: Throwable) {
        0
    }

    /** The one retry of each waiting payload. It is forgotten before it is sent, whatever the answer will be. */
    private fun resend() {
        if (control.metrics == null) return
        val storage = store()
        val waiting = lock.withLock { pending.take(storage, inFlight) }
        for (entry in waiting) {
            val body = restampForRetry(entry.body, now()) ?: continue // A payload this run cannot read is dropped.
            track { attempt(body) }
        }
    }

    /** Runs [upload] on the I/O pool, counted for [awaitUploads]. */
    private fun track(upload: () -> Unit) {
        lock.withLock { uploads++ }
        scheduler.io {
            try {
                upload()
            } catch (_: Throwable) {
                // An upload never takes the I/O thread down.
            } finally {
                lock.withLock {
                    uploads--
                    idle.signalAll()
                }
            }
        }
    }

    private fun schedule() {
        val seconds = control.settings.metrics.flushSeconds
        val delay = Math.round(seconds * 1000)
        lock.withLock {
            timer?.cancel()
            timer = if (running) scheduler.schedule(delay) { onTimer() } else null
        }
    }

    private fun onTimer() {
        if (!lock.withLock { running }) return
        flush()
        schedule()
    }

    private fun guarded(task: () -> Unit) {
        try {
            task()
        } catch (_: Throwable) {
            // Metrics must never break the app.
        }
    }

    private fun customDims(dims: Map<String, Any?>?): MetricDims {
        if (dims.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in dims.keys.sorted().take(MAX_CUSTOM_DIMENSIONS)) out[clip(key)] = clip("${dims[key]}")
        return out
    }

    companion object {
        private fun defaultRandom(): Random = try {
            SecureRandom()
        } catch (_: Throwable) {
            Random()
        }

        private fun exemplarStartMs(type: String): Double = if (type == START_COLD) 2000.0 else 1000.0

        private fun isCustomName(name: String): Boolean =
            CUSTOM_NAME.matches(name) && !RESERVED_PREFIX.containsMatchIn(name)

        private fun clip(value: String): String =
            if (value.length > MAX_DIMENSION_LENGTH) value.substring(0, MAX_DIMENSION_LENGTH) else value

        /**
         * The `host` dimension of [url]: its host, lowercase, with the port
         * when it is not the scheme's default. Null without a host.
         */
        internal fun hostOf(url: String): String? {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd <= 0) return null
            val scheme = url.substring(0, schemeEnd).lowercase(java.util.Locale.US)
            var authority = url.substring(schemeEnd + 3)
            val cut = authority.indexOfFirst { it == '/' || it == '?' || it == '#' }
            if (cut >= 0) authority = authority.substring(0, cut)
            authority = authority.substring(authority.lastIndexOf('@') + 1)
            val host: String
            var port: String? = null
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) return null
                host = authority.substring(0, close + 1)
                if (close + 1 < authority.length && authority[close + 1] == ':') port = authority.substring(close + 2)
            } else {
                val colon = authority.lastIndexOf(':')
                if (colon >= 0) {
                    host = authority.substring(0, colon)
                    port = authority.substring(colon + 1)
                } else {
                    host = authority
                }
            }
            if (host.isEmpty()) return null
            val lower = host.lowercase(java.util.Locale.US)
            val number = port?.toIntOrNull()
            val default = if (scheme == "https") 443 else 80
            return if (number != null && number != default) "$lower:$number" else lower
        }
    }
}
