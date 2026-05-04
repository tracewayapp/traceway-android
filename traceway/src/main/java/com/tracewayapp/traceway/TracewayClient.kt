package com.tracewayapp.traceway

import android.util.Log
import com.tracewayapp.traceway.events.CustomEvent
import com.tracewayapp.traceway.events.EventBuffer
import com.tracewayapp.traceway.events.LogEvent
import com.tracewayapp.traceway.events.NavigationEvent
import com.tracewayapp.traceway.events.NetworkEvent
import com.tracewayapp.traceway.events.TracewayEvent
import androidx.annotation.VisibleForTesting
import com.tracewayapp.traceway.internal.DefaultReportSender
import com.tracewayapp.traceway.internal.ExceptionStore
import com.tracewayapp.traceway.internal.formatException
import com.tracewayapp.traceway.internal.parseConnectionString
import com.tracewayapp.traceway.transport.ReportSender
import com.tracewayapp.traceway.models.CollectionFrame
import com.tracewayapp.traceway.models.ExceptionStackTrace
import com.tracewayapp.traceway.models.ReportRequest
import com.tracewayapp.traceway.models.SessionRecordingPayload
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The Traceway client — collects exceptions and a rolling timeline of logs,
 * network calls, and navigation transitions, batches them, and sends them to
 * the Traceway backend in the same wire format as the Flutter SDK.
 *
 * Construct via [Traceway.init]. Access the singleton via [TracewayClient.instance].
 */
class TracewayClient internal constructor(
    private val apiUrl: String,
    private val token: String,
    val options: TracewayOptions,
    /**
     * Directory used for pending-exception persistence. Pass an
     * `Application.getNoBackupFilesDir()` (or `filesDir`) subdirectory.
     */
    persistDir: File?,
    private val sender: ReportSender = DefaultReportSender,
) {
    private val store: ExceptionStore? = if (options.persistToDisk && persistDir != null) {
        ExceptionStore(
            dir = persistDir,
            maxLocalFiles = options.maxLocalFiles,
            maxAgeHours = options.localFileMaxAgeHours,
            debug = options.debug,
        )
    } else null

    private val logs = EventBuffer<LogEvent>(
        windowMs = options.eventsWindowMs,
        maxSize = options.eventsMaxCount,
    )
    private val actions = EventBuffer<TracewayEvent>(
        windowMs = options.eventsWindowMs,
        maxSize = options.eventsMaxCount,
    )

    private val pendingExceptions: MutableList<ExceptionStackTrace> = mutableListOf()
    private val pendingRecordings: MutableList<SessionRecordingPayload> = mutableListOf()
    private val lock = Any()

    private var isSyncing: Boolean = false
    private var debounceFuture: ScheduledFuture<*>? = null
    private var retryFuture: ScheduledFuture<*>? = null

    @Volatile
    private var deviceAttributes: Map<String, String> = emptyMap()

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "TracewayScheduler").apply { isDaemon = true }
        }

    init {
        // Open the store eagerly so the first captureException — which may
        // arrive before [loadPendingFromDisk] is called — actually persists.
        store?.init()
    }

    val debug: Boolean get() = options.debug

    /** Sets the device attribute map merged into every captured exception. */
    fun setDeviceAttributes(attributes: Map<String, String>) {
        deviceAttributes = HashMap(attributes)
        if (debug) Log.d(TAG, "device attributes: $deviceAttributes")
    }

    /** Initializes disk storage and re-queues anything that was persisted previously. */
    fun loadPendingFromDisk() {
        val store = store ?: return
        store.init()
        if (!store.isAvailable) return

        val entries = store.loadAll()
        if (entries.isEmpty()) return

        synchronized(lock) {
            for (entry in entries) {
                entry.exception.fileId = entry.id
                pendingExceptions.add(entry.exception)
                entry.recording?.let { pendingRecordings.add(it) }
            }
            trimPending()
        }

        if (debug) Log.d(TAG, "loaded ${entries.size} pending entries from disk")
        scheduleSync()
    }

    fun recordLog(line: String, level: String = "info") {
        if (!options.captureLogs) return
        logs.add(LogEvent(message = line, level = level))
    }

    fun recordNetworkEvent(event: NetworkEvent) {
        if (!options.captureNetwork) return
        actions.add(event)
    }

    fun recordNavigationEvent(event: NavigationEvent) {
        if (!options.captureNavigation) return
        actions.add(event)
    }

    /**
     * Records a custom user-defined breadcrumb. Use for any app-level action
     * that should ride along with the next exception.
     */
    fun recordAction(category: String, name: String, data: Map<String, Any?>? = null) {
        actions.add(CustomEvent(category = category, name = name, data = data))
    }

    /** Capture a caught exception. */
    fun captureException(error: Throwable) {
        val formatted = formatException(error)
        addException(
            ExceptionStackTrace(
                stackTrace = formatted,
                recordedAtMs = System.currentTimeMillis(),
                isMessage = false,
            )
        )
    }

    /** Capture a free-form message (sent as a non-exception report). */
    fun captureMessage(message: String) {
        addException(
            ExceptionStackTrace(
                stackTrace = message,
                recordedAtMs = System.currentTimeMillis(),
                isMessage = true,
            )
        )
    }

    fun addException(exception: ExceptionStackTrace) {
        if (!shouldSample()) {
            if (debug) Log.d(TAG, "exception dropped by sampling")
            return
        }

        if (deviceAttributes.isNotEmpty()) {
            val merged = LinkedHashMap<String, String>(deviceAttributes)
            exception.attributes?.let { merged.putAll(it) }
            exception.attributes = merged
        }

        val logSnapshot = logs.snapshot()
        val actionSnapshot = actions.snapshot()
        val hasTimelineData = logSnapshot.isNotEmpty() || actionSnapshot.isNotEmpty()

        val recordingId = if (hasTimelineData) UUID.randomUUID().toString() else null
        if (recordingId != null) {
            exception.sessionRecordingId = recordingId
        }

        synchronized(lock) {
            pendingExceptions.add(exception)
            store?.write(exception)?.also { exception.fileId = it }

            if (recordingId != null) {
                val payload = buildSessionRecording(
                    recordingId = recordingId,
                    logs = logSnapshot,
                    actions = actionSnapshot,
                )
                pendingRecordings.add(payload)
                exception.fileId?.let { fid -> store?.writeRecording(fid, payload) }
            }

            trimPending()
        }

        scheduleSync()
    }

    /** Force a sync now and wait up to [timeoutMs] (null = no timeout). */
    fun flush(timeoutMs: Long? = null) {
        cancelDebounce()
        cancelRetry()
        val task = scheduler.submit { doSync() }
        try {
            if (timeoutMs != null) {
                task.get(timeoutMs, TimeUnit.MILLISECONDS)
            } else {
                task.get()
            }
        } catch (_: Throwable) {
        }
    }

    private fun shouldSample(): Boolean {
        if (options.sampleRate >= 1.0) return true
        if (options.sampleRate <= 0.0) return false
        return Random.nextDouble() < options.sampleRate
    }

    private fun buildSessionRecording(
        recordingId: String,
        logs: List<LogEvent>,
        actions: List<TracewayEvent>,
    ): SessionRecordingPayload {
        var startedAt: Long? = null
        var endedAt: Long? = null

        // Fall back to the timestamp range of the buffered logs/actions so the
        // backend still has a known interval to align against.
        val timestamps = (logs.map { it.timestampMs } + actions.map { it.timestampMs }).sorted()
        if (timestamps.isNotEmpty()) {
            startedAt = timestamps.first()
            endedAt = timestamps.last()
        }

        return SessionRecordingPayload(
            exceptionId = recordingId,
            events = emptyList(),
            logs = logs,
            actions = actions,
            startedAtMs = startedAt,
            endedAtMs = endedAt,
        )
    }

    private fun trimPending() {
        while (pendingExceptions.size > options.maxPendingExceptions) {
            val dropped = pendingExceptions.removeAt(0)
            if (dropped.sessionRecordingId != null) {
                pendingRecordings.removeAll { it.exceptionId == dropped.sessionRecordingId }
            }
            dropped.fileId?.let { store?.remove(listOf(it)) }
            if (debug) Log.d(TAG, "dropped oldest exception (buffer full)")
        }
    }

    private fun scheduleSync() {
        synchronized(lock) {
            cancelDebounce()
            debounceFuture = scheduler.schedule(
                { doSync() },
                options.debounceMs,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun cancelDebounce() {
        synchronized(lock) {
            debounceFuture?.cancel(false)
            debounceFuture = null
        }
    }

    private fun cancelRetry() {
        synchronized(lock) {
            retryFuture?.cancel(false)
            retryFuture = null
        }
    }

    private fun scheduleRetry() {
        synchronized(lock) {
            if (retryFuture != null) return
            retryFuture = scheduler.schedule(
                {
                    synchronized(lock) { retryFuture = null }
                    doSync()
                },
                options.retryDelayMs,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun doSync() {
        val batch: List<ExceptionStackTrace>
        val recordings: List<SessionRecordingPayload>

        synchronized(lock) {
            if (isSyncing) return
            if (pendingExceptions.isEmpty()) return

            isSyncing = true

            // Split exceptions into ready (recording done or no recording) vs waiting.
            val readyRecordingIds = pendingRecordings.map { it.exceptionId }.toHashSet()
            val ready = mutableListOf<ExceptionStackTrace>()
            val waiting = mutableListOf<ExceptionStackTrace>()
            for (e in pendingExceptions) {
                if (e.sessionRecordingId == null || readyRecordingIds.contains(e.sessionRecordingId)) {
                    ready.add(e)
                } else {
                    waiting.add(e)
                }
            }

            if (ready.isEmpty()) {
                isSyncing = false
                return
            }

            batch = ready
            val batchRecordingIds = batch.mapNotNull { it.sessionRecordingId }.toHashSet()
            recordings = pendingRecordings.filter { batchRecordingIds.contains(it.exceptionId) }

            pendingExceptions.clear()
            pendingExceptions.addAll(waiting)
            pendingRecordings.removeAll { batchRecordingIds.contains(it.exceptionId) }
        }

        val frame = CollectionFrame(
            stackTraces = batch,
            sessionRecordings = if (recordings.isNotEmpty()) recordings else null,
        )
        val payload = ReportRequest(
            collectionFrames = listOf(frame),
            appVersion = options.version,
            serverName = "",
        )

        var failed = false
        try {
            val jsonBody = payload.toJson().toString()
            if (debug) Log.d(TAG, "payload_size_bytes=${jsonBody.toByteArray(Charsets.UTF_8).size}")
            val success = sender.send(apiUrl, token, jsonBody)
            if (!success) {
                failed = true
                synchronized(lock) {
                    pendingExceptions.addAll(0, batch)
                    pendingRecordings.addAll(0, recordings)
                    trimPending()
                }
                if (debug) Log.w(TAG, "sync failed, re-queued exceptions")
            } else {
                val fileIds = batch.mapNotNull { it.fileId }
                if (fileIds.isNotEmpty()) {
                    store?.remove(fileIds)
                }
            }
        } catch (e: Throwable) {
            failed = true
            synchronized(lock) {
                pendingExceptions.addAll(0, batch)
                pendingRecordings.addAll(0, recordings)
                trimPending()
            }
            if (debug) Log.w(TAG, "sync error: $e")
        } finally {
            val hasMore: Boolean
            synchronized(lock) {
                isSyncing = false
                hasMore = pendingExceptions.isNotEmpty()
            }
            if (hasMore) {
                if (failed) scheduleRetry() else scheduler.execute { doSync() }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Test-only accessors. These are part of the published API but marked
    // @VisibleForTesting so consumers know they may change without notice.
    // ──────────────────────────────────────────────────────────────────

    @VisibleForTesting
    fun bufferedLogs(): List<LogEvent> = logs.snapshot()

    @VisibleForTesting
    fun bufferedActions(): List<TracewayEvent> = actions.snapshot()

    @VisibleForTesting
    fun pendingExceptionCount(): Int = synchronized(lock) { pendingExceptions.size }

    @VisibleForTesting
    fun pendingRecordingCount(): Int = synchronized(lock) { pendingRecordings.size }

    @VisibleForTesting
    fun pendingExceptionsSnapshot(): List<ExceptionStackTrace> =
        synchronized(lock) { ArrayList(pendingExceptions) }

    @VisibleForTesting
    fun clearBuffers() {
        logs.clear()
        actions.clear()
    }

    companion object {
        private const val TAG = "Traceway"

        @Volatile
        private var current: TracewayClient? = null

        @JvmStatic
        val instance: TracewayClient?
            get() = current

        internal fun parseAndCreate(
            connectionString: String,
            options: TracewayOptions,
            persistDir: File?,
            sender: ReportSender = DefaultReportSender,
        ): TracewayClient {
            val parsed = parseConnectionString(connectionString)
            val client = TracewayClient(
                apiUrl = parsed.apiUrl,
                token = parsed.token,
                options = options,
                persistDir = persistDir,
                sender = sender,
            )
            current = client
            return client
        }

        /**
         * Test-only: build a client with an injected [sender] and an explicit
         * [persistDir], without going through [Traceway.init] (no logcat
         * redirection, no UncaughtExceptionHandler install, no Activity
         * lifecycle hooks).
         *
         * Pair with [resetForTest] in `@After`.
         */
        @JvmStatic
        @VisibleForTesting
        fun initializeForTesting(
            connectionString: String,
            options: TracewayOptions,
            persistDir: File? = null,
            sender: ReportSender? = null,
        ): TracewayClient = parseAndCreate(
            connectionString = connectionString,
            options = options,
            persistDir = persistDir,
            sender = sender ?: DefaultReportSender,
        )

        @JvmStatic
        @VisibleForTesting
        fun resetForTest() {
            current?.let { c ->
                c.cancelDebounce()
                c.cancelRetry()
                c.scheduler.shutdownNow()
            }
            current = null
        }
    }
}
