package com.tracewayapp.traceway

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.annotation.VisibleForTesting
import com.tracewayapp.traceway.core.Capture
import com.tracewayapp.traceway.core.ControlState
import com.tracewayapp.traceway.core.DEFAULT_EXCEPTION_TRIGGER_ID
import com.tracewayapp.traceway.core.FieldLookup
import com.tracewayapp.traceway.core.FiredTrigger
import com.tracewayapp.traceway.core.Ids
import com.tracewayapp.traceway.core.JsonObject
import com.tracewayapp.traceway.core.KEY_POINTER_ACTION
import com.tracewayapp.traceway.core.KEY_TARGET_MATCHES
import com.tracewayapp.traceway.core.Mode
import com.tracewayapp.traceway.core.ObservedEvent
import com.tracewayapp.traceway.core.OtlpDestination
import com.tracewayapp.traceway.core.RingBuffer
import com.tracewayapp.traceway.core.Settings
import com.tracewayapp.traceway.core.SettingsError
import com.tracewayapp.traceway.core.StreamOverride
import com.tracewayapp.traceway.core.TelemetryRoutes
import com.tracewayapp.traceway.core.TracewayEndpoints
import com.tracewayapp.traceway.core.TriggerAction
import com.tracewayapp.traceway.core.TriggerEngine
import com.tracewayapp.traceway.core.applyOverlay
import com.tracewayapp.traceway.core.ceilingsFrom
import com.tracewayapp.traceway.core.defaultSettings
import com.tracewayapp.traceway.core.destinationDigest
import com.tracewayapp.traceway.core.eventKinds
import com.tracewayapp.traceway.core.parseSettingsDocument
import com.tracewayapp.traceway.core.resolveEndpoints
import com.tracewayapp.traceway.interaction.HoverTracker
import com.tracewayapp.traceway.interaction.TapListener
import com.tracewayapp.traceway.interaction.TouchTracker
import com.tracewayapp.traceway.interaction.ViewPath
import com.tracewayapp.traceway.interaction.describeTap
import com.tracewayapp.traceway.internal.AppLifecycle
import com.tracewayapp.traceway.internal.Cancellable
import com.tracewayapp.traceway.internal.DiskScheduler
import com.tracewayapp.traceway.internal.DeviceInfoCollector
import com.tracewayapp.traceway.internal.ErrorHandler
import com.tracewayapp.traceway.internal.ExecutorScheduler
import com.tracewayapp.traceway.internal.HttpTransport
import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.LogCapture
import com.tracewayapp.traceway.internal.NavigationCapture
import com.tracewayapp.traceway.internal.NetworkState
import com.tracewayapp.traceway.internal.Rfc3339
import com.tracewayapp.traceway.internal.SdkInfo
import com.tracewayapp.traceway.internal.TaskScheduler
import com.tracewayapp.traceway.internal.UrlConnectionTransport
import com.tracewayapp.traceway.internal.asJsonList
import com.tracewayapp.traceway.internal.asJsonObject
import com.tracewayapp.traceway.internal.asLongExact
import com.tracewayapp.traceway.internal.formatException
import com.tracewayapp.traceway.internal.parseConnectionString
import com.tracewayapp.traceway.metrics.AppVitals
import com.tracewayapp.traceway.metrics.MemoryPeaks
import com.tracewayapp.traceway.metrics.MetricsRecorder
import com.tracewayapp.traceway.metrics.normalizePath
import com.tracewayapp.traceway.opamp.AgentCapabilities
import com.tracewayapp.traceway.opamp.AgentDescription
import com.tracewayapp.traceway.opamp.ApplyResult
import com.tracewayapp.traceway.opamp.OpampHttpClient
import com.tracewayapp.traceway.opamp.RemoteConfig
import com.tracewayapp.traceway.otel.ControlLogProcessor
import com.tracewayapp.traceway.otel.ControlSpanProcessor
import com.tracewayapp.traceway.otel.Events
import com.tracewayapp.traceway.otel.ObservedRecord
import com.tracewayapp.traceway.otel.PersistingExporter
import com.tracewayapp.traceway.persistence.await
import com.tracewayapp.traceway.otel.OtlpLogExporter
import com.tracewayapp.traceway.otel.OtlpSpanExporter
import com.tracewayapp.traceway.otel.RouterHooks
import com.tracewayapp.traceway.otel.RoutingLogExporter
import com.tracewayapp.traceway.otel.Routed
import com.tracewayapp.traceway.otel.TelemetryItem
import com.tracewayapp.traceway.otel.TelemetryRouter
import com.tracewayapp.traceway.persistence.BufferPersistence
import com.tracewayapp.traceway.persistence.LegacyReports
import com.tracewayapp.traceway.persistence.Outbox
import com.tracewayapp.traceway.persistence.StateStore
import com.tracewayapp.traceway.replay.RecordingsTransport
import com.tracewayapp.traceway.replay.ReplayController
import com.tracewayapp.traceway.replay.ReplaySource
import com.tracewayapp.traceway.session.SessionInfo
import com.tracewayapp.traceway.session.SessionManager
import com.tracewayapp.traceway.wireframe.ImageBudget
import com.tracewayapp.traceway.wireframe.WireframeRecorder
import com.tracewayapp.traceway.wireframe.attach
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.LogRecordProcessor
import io.opentelemetry.sdk.logs.ReadWriteLogRecord
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.trace.samplers.Sampler
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "Traceway"
private const val MAX_PENDING_BEFORE_READY = 500
private const val MAX_ERRORS_PER_MINUTE = 60
private const val DUPLICATE_ERROR_WINDOW_MS = 1000L
private const val MAX_GLOBAL_ATTRIBUTES = 64
private const val MAX_EMIT_DEPTH = 4
private const val MAX_REQUEST_SPANS = 256

/** How long the crash handler may hold the crashing thread before handing the crash to the platform. */
private const val CRASH_BUDGET_MS = 2500L

/** `traceway.collection.health` waits this long after the first drop, then twice as long each time, up to [MAX_HEALTH_DELAY_MS]. */
private const val HEALTH_DELAY_MS = 1_000L
private const val MAX_HEALTH_DELAY_MS = 300_000L

/** The longest route template [TracewayClient.setRoute] keeps. */
private const val MAX_ROUTE_LENGTH = 64

/** The most the ring buffer holds whatever the settings say: the server allows 64 MiB, a phone's heap may be 192 MB. */
private const val MAX_BUFFER_BYTES = 64L * 1024 * 1024

/**
 * The ring buffer's ceiling on this device: a sixteenth of the heap, at
 * most [MAX_BUFFER_BYTES] (§13.5, buffers stay bounded). A dashboard value
 * above it is applied up to it.
 */
internal fun deviceBufferCapBytes(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Long {
    val heap = if (maxHeapBytes <= 0 || maxHeapBytes == Long.MAX_VALUE) 256L * 1024 * 1024 else maxHeapBytes
    return minOf(MAX_BUFFER_BYTES, maxOf(1L * 1024 * 1024, heap / 16))
}

/**
 * A copy of [from] without null keys or values, which a Java map can hold
 * whatever its Kotlin type says. A map that fails while it is read (another
 * thread changing it) gives what was read before the failure.
 */
internal fun copyNonNull(from: Map<*, *>?, into: MutableMap<String, Any> = LinkedHashMap()): MutableMap<String, Any> {
    if (from == null) return into
    try {
        for ((k, v) in from) {
            if (k is String && v != null) into[k] = v
        }
    } catch (e: Throwable) {
        Guard.failed("attributes", e)
    }
    return into
}

/** [copyNonNull] for maps of strings, values as text. */
private fun copyStrings(from: Map<*, *>?): LinkedHashMap<String, String> {
    val out = LinkedHashMap<String, String>()
    for ((k, v) in copyNonNull(from)) out[k] = v as? String ?: v.toString()
    return out
}

/** Kept while the SDK starts even when breadcrumbs filled the queue. */
private val KEPT_BEFORE_READY = setOf(
    Events.EXCEPTION,
    Events.MESSAGE,
    Events.SESSION_START,
    Events.SESSION_END,
    Events.SESSION_UPDATE,
)

/**
 * Replaces the parts of the SDK that touch the platform or the network, so
 * tests can run it against in-memory exporters and a fake server.
 */
@VisibleForTesting
internal class TracewayTestOverrides(
    val logExporter: LogRecordExporter? = null,
    val spanExporter: SpanExporter? = null,
    val http: HttpTransport? = null,
    val scheduler: TaskScheduler? = null,
    /** `null` keeps state in memory only. */
    val stateDirectory: File? = null,
    val legacyDirectory: File? = null,
    val deviceAttributes: Map<String, String> = emptyMap(),
    val random: (() -> Double)? = null,
    val opampMinPollIntervalMs: Long? = null,
    /** Whether the Android instrumentation (activities, frames, crashes, taps) is installed. */
    val instrument: Boolean = false,
    /** Runs before each step of [TracewayClient.instrument] with its name; a test makes one fail. */
    val beforeInstallStep: ((String) -> Unit)? = null,
    /** The heap [deviceBufferCapBytes] sizes the ring buffer by, instead of this JVM's. */
    val maxHeapBytes: Long? = null,
    /** Starts the `TracewayStart` thread's work instead of a new thread; a test makes it fail. */
    val startThread: ((Runnable) -> Unit)? = null,
)

/** An HTTP request recorded as a client span, from [TracewayClient.startRequest] to [TracewayClient.endRequest]. */
class RequestHandle internal constructor(
    /** Null for a request made while the SDK was starting: its span is made when the SDK is ready. */
    internal val span: Span?,
    internal val method: String,
    internal val url: String,
    internal val startMs: Long,
    /** The W3C `traceparent` to send, or null when the URL is not selected (or the request has its own). */
    val traceparent: String?,
)

/**
 * The Traceway client. Records are OpenTelemetry log records and spans of
 * the SDK's own providers; a router decides per record whether it streams,
 * waits in the ring buffer on the device or is dropped, following the
 * collection settings (built-in defaults, the app's `collection`, and the
 * server's rules over OpAMP). Triggers flush the buffer and the wireframe
 * recording when something goes wrong.
 *
 * Start it with [Traceway.init]; reach it with [TracewayClient.instance].
 * Every method is safe to call from any thread.
 */
class TracewayClient private constructor(
    private val application: Application?,
    connectionString: String?,
    val options: TracewayOptions,
    private val overrides: TracewayTestOverrides?,
) {

    private val lock = Any()
    private val standalone = connectionString == null
    private var token: String? = null
    private var endpoints: TracewayEndpoints? = null
    private val routes: TelemetryRoutes
    private val control: ControlState
    private val buffer: RingBuffer<TelemetryItem>
    private val triggers: TriggerEngine
    private val scheduler: TaskScheduler = overrides?.scheduler ?: ExecutorScheduler()
    private val http: HttpTransport = overrides?.http ?: UrlConnectionTransport
    private val recordings: RecordingsTransport
    private val replay: ReplayController
    private val metrics: MetricsRecorder
    private val memoryPeaks: MemoryPeaks
    private var vitals: AppVitals? = null
    private val mainHandler: Handler? = if (overrides?.instrument == false) null else Handler(Looper.getMainLooper())

    /** When the app initialized the SDK, on the boot clock: the start of a cold start whose process start is unknown. */
    private val sdkStartElapsed = android.os.SystemClock.elapsedRealtime()
    private val stateDir: File? = stateDirectory()

    /** [deviceBufferCapBytes], the ring buffer's ceiling whatever the settings say. */
    private val bufferCapBytes = deviceBufferCapBytes(overrides?.maxHeapBytes ?: Runtime.getRuntime().maxMemory())

    /** Set while the app is crashing: telemetry goes to disk, and clips are assembled on the crashing thread. */
    @Volatile
    private var crashing = false
    private val globalAttributes = LinkedHashMap<String, String>()
    private val resourceAttributes = LinkedHashMap<String, String>()
    private val pending = ArrayList<() -> Unit>()
    private val readyLatch = CountDownLatch(1)
    private val errorTimes = ArrayList<Long>()
    private var lastErrorKey: String? = null
    private var lastErrorMs = 0L
    private var suppressedErrors = 0

    private var store = StateStore(null)
    private var outbox: Outbox? = null
    private var tracewayOutbox: Outbox? = null
    private var legacyReports: LegacyReports? = null
    private var splitsLogs = false
    private var persisted: BufferPersistence? = null
    private var sessions: SessionManager? = null
    private var router: TelemetryRouter? = null
    private var logSink: LogRecordProcessor? = null
    private var spanSink: SpanProcessor? = null
    private var logExporter: LogRecordExporter? = null
    private var spanExporter: SpanExporter? = null
    private var loggerProvider: SdkLoggerProvider? = null
    private var tracerProvider: SdkTracerProvider? = null
    private var logger: Logger? = null
    private var tracer: Tracer? = null
    private var opamp: OpampHttpClient? = null
    private var instanceUid = ByteArray(16)
    private var screenRecorder: ReplaySource? = null
    private var flushTimer: Cancellable? = null
    private var refreshTimer: Cancellable? = null
    private var overrideTimer: Cancellable? = null
    private var progressTimer: Cancellable? = null

    @Volatile
    private var ready = false

    /** Whether [instanceUid] was read from disk or made: before that it is all zeros. */
    @Volatile
    private var instanceKnown = false

    @Volatile
    private var isShutdown = false
    private var started = false

    /** Clips flushed so far; an exception compares it before and after its triggers run. */
    private var replayFlushes = 0

    /** Captures known only from the cached config start nothing new until the server answered. */
    private var capturesConfirmed = false
    private var savedProgressVersion = -1
    private var savedProgress = ""
    private var lastProgressSaveMs = 0L

    /** The path of the screen on top, as the last navigation reported it. */
    @Volatile
    private var currentScreen: String? = null

    /** The route template of the current screen ([setRoute]); a navigation to another screen clears it. */
    @Volatile
    private var currentRoute: String? = null

    /** The screen before [currentScreen], and since when (epoch ms) the current one is shown. */
    @Volatile
    private var previousScreen: String? = null

    @Volatile
    private var screenSinceMs = 0L

    /** The session the replay image budget counts for, readable without the lock. */
    @Volatile
    private var liveSessionId: String? = null

    /** The image policy when the session started, reported on its events (`traceway.replay.images`). */
    private var sessionImagePolicy: TracewayImagePolicy? = null
    private var networkState: NetworkState? = null

    // `traceway.collection.health`: one record a while after data was dropped. Its own lock, taken
    // inside the client's but never around it.
    private val healthLock = Any()
    private var healthTimer: Cancellable? = null
    private var healthDelayMs = HEALTH_DELAY_MS
    private var healthReported = false
    private var suppressedTotal = 0L

    /** Event types (`click`, `hover`, `navigation`) the triggers name. */
    @Volatile
    private var sequenceKinds: Set<String> = emptySet()
    private var screenObservedForSequences = false
    private val hover = HoverTracker()

    /** How many records are being emitted right now, one inside the other (an `emit` action re-enters). */
    private var emitDepth = 0

    /** Sessions whose start (or resumption) was reported in this run: their end is reported too, and only theirs. */
    private val reportedSessions = HashSet<String>()
    private val requestSpans = LinkedHashMap<String, Span>()

    // Android instrumentation, installed by [Traceway.init].
    private val logLineSink: (String, String) -> Unit = { line, severity ->
        consoleLine(line, TracewayLogLevel.of(severity), mapOf("log.source" to if (severity == "error") "stderr" else "stdout"))
    }
    private var recorderCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var lifecycle: AppLifecycle? = null
    private var touchTracker: TouchTracker? = null
    private var errorHandler: ErrorHandler? = null
    private var navigationCapture: NavigationCapture? = null
    private var logCaptureInstalled = false

    init {
        if (options.debug) Guard.debug = true
        if (connectionString != null) {
            val parsed = parseConnectionString(connectionString)
            token = parsed.token
            endpoints = resolveEndpoints(parsed.apiUrl)
        } else {
            require(options.telemetry?.coversAllSignals == true) {
                "Without a connection string, TracewayOptions.telemetry needs a base url or all three signal endpoints"
            }
        }
        routes = TelemetryRoutes.resolve(endpoints, token, options.telemetry)
        globalAttributes.putAll(copyStrings(options.attributes))

        var defaults = defaultSettings(
            baselineSampleRate = options.baselineSampleRate,
            recordAllSessions = options.recordAllSessions,
        )
        val errorRate = options.sampleRate.coerceIn(0.0, 1.0)
        defaults.signal("errors").sampleRate = errorRate
        defaults.signal("messages").sampleRate = errorRate
        options.collection?.let { collection ->
            try {
                defaults = applyOverlay(defaults, parseSettingsDocument(Json.stringify(collection)))
            } catch (e: Exception) {
                Log.w(TAG, "ignoring TracewayOptions.collection: ${e.message}")
            }
        }
        control = ControlState(
            defaults,
            ceilingsFrom(
                screenCapture = recordsScreen,
                captureLogs = options.captureLogs,
                captureNetwork = options.captureNetwork,
                captureNavigation = options.captureNavigation,
                captureUserActions = options.captureUserActions,
                // Standalone, nothing receives the session rows; the session id still correlates records.
                sessions = options.sessions && !standalone,
                captureMetrics = options.captureMetrics,
                replayImages = options.replayImages,
            ),
            now = scheduler::now,
        )
        val settings = control.settings
        buffer = RingBuffer(bufferBytes(settings), settings.bufferMaxAgeMs, scheduler::now)
        triggers = TriggerEngine(random = overrides?.random ?: { Math.random() }, now = scheduler::now)
        triggers.setTriggers(settings.triggers)
        recordings = RecordingsTransport(
            url = endpoints?.recordings ?: "",
            token = token ?: "",
            http = http,
            scheduler = scheduler,
            spoolDirectory = stateDir?.let { File(it, "recordings") },
        )
        // A streamed segment must leave before the frame ring wraps.
        val fps = options.fps.coerceIn(1, 30)
        val ringMs = maxOf(1, options.maxBufferFrames) * 1000L / fps
        replay = ReplayController(
            transport = recordings,
            sessionId = { sessions?.currentId },
            nextSegmentIndex = { sessions?.nextSegmentIndex() ?: 0 },
            scheduler = scheduler,
            segmentMs = (ringMs * 0.8).toLong().coerceIn(200L, 10_000L),
            locked = { block -> synchronized(lock) { block() } },
        )
        metrics = MetricsRecorder(
            control = control,
            sessionId = { sessions?.currentId },
            installationId = { Ids.bytesToUuid(instanceUid) },
            resource = { synchronized(lock) { HashMap(resourceAttributes) } },
            send = { body -> postMetrics(body) },
            store = { store },
            storageScope = routes.metrics.key,
            scopeName = SdkInfo.NAME,
            scopeVersion = SdkInfo.VERSION,
            dimensions = copyStrings(options.metricDimensions),
            deferUntilStart = true,
            scheduler = scheduler,
        )
        memoryPeaks = MemoryPeaks(metrics, { store }, storageScope = routes.metrics.key)
        recordings.onFailed = { scheduleHealth() }
        replay.budget.onRejected = { scheduleHealth() }
        control.onChange { onSettingsChanged(it) }
    }

    val debug: Boolean get() = options.debug

    /** Whether the screen is recorded: screen capture is on and there is a Traceway to upload it to. */
    private val recordsScreen: Boolean get() = options.screenCapture && !standalone

    private val remoteConfig: Boolean get() = options.remoteConfig && !standalone

    /** The ring buffer's size: what the settings say, at most this device's [bufferCapBytes]. */
    private fun bufferBytes(settings: Settings): Long = minOf(settings.bufferMaxBytes, bufferCapBytes)

    // ------------------------------------------------------------- startup

    /**
     * Installs the Android instrumentation; on the main thread, from
     * [Traceway.init]. Each hook is installed on its own: one that fails
     * leaves the SDK running without it.
     */
    internal fun instrument(app: Application) {
        val life = AppLifecycle(app)
        lifecycle = life
        step("lifecycle") {
            life.addListener(object : AppLifecycle.Listener {
                override fun onForeground() = onLifecycle(foreground = true)
                override fun onBackground() = onLifecycle(foreground = false)
            })
        }
        if (options.captureNavigation) {
            step("navigation") {
                val navigation = NavigationCapture { path, action, from -> recordNavigation(path, action, from) }
                navigationCapture = navigation
                life.addListener(navigation)
            }
        }
        if (options.captureLogs) {
            step("logs") {
                LogCapture.install(logLineSink)
                logCaptureInstalled = true
            }
        }
        step("crashes") {
            val handler = ErrorHandler(onCrash = { thread, error -> captureCrash(thread, error) })
            errorHandler = handler
            handler.install()
        }

        step("taps") {
            val tracker = TouchTracker()
            touchTracker = tracker
            tracker.addTapListener(object : TapListener {
                override fun onTap(root: View, xPx: Float, yPx: Float, xDp: Float, yDp: Float) = recordTap(root, xPx, yPx, xDp, yDp)
                override fun onHover(root: View, xPx: Float, yPx: Float, xDp: Float, yDp: Float) = recordHover(root, xPx, yPx)
            })
            tracker.install(app)
        }

        if (recordsScreen) {
            step("replay") {
                val recorder = WireframeRecorder.create(
                    app,
                    maxFrames = options.maxBufferFrames,
                    fps = options.fps.coerceIn(1, 30),
                    debug = options.debug,
                    clock = scheduler::now,
                )
                recorder.screenName = { currentScreen }
                recorder.images?.let { images ->
                    val network = NetworkState(app)
                    networkState = network
                    images.unmetered = { network.unmetered }
                    images.budget = imageBudget
                }
                // The recorder follows the activities and the foreground itself.
                recorderCallbacks = recorder.attach(app)
                touchTracker?.addPointerListener(recorder)
                setScreenRecorder(recorder)
            }
        }
        if (options.captureMetrics) {
            step("vitals") {
                val v = AppVitals(metrics, currentScreen = { metricsScreen }, scheduler = scheduler)
                vitals = v
                v.watchColdStart(sdkStartElapsed)
                v.start(app)
            }
        }
        step("foreground") { life.install() }
    }

    /** One step of [instrument]: a failure is logged in debug and leaves the others in place. */
    private inline fun step(name: String, block: () -> Unit) {
        try {
            overrides?.beforeInstallStep?.invoke(name)
            block()
        } catch (e: Throwable) {
            debugLog("could not install $name: $e")
        }
    }

    /**
     * Undoes [instrument] after [initialize] failed: the hooks are removed,
     * the crash handler gives the default back, and nothing waits for a
     * start that will not happen.
     */
    private fun abandon() {
        isShutdown = true
        readyLatch.countDown()
        uninstallHooks()
        guarded("abandon") { scheduler.shutdown() }
    }

    /** Removes what [instrument] installed; each part on its own, as [shutdown] and [abandon] need. */
    private fun uninstallHooks() {
        guarded("uninstall vitals") { vitals?.stop() }
        guarded("uninstall taps") { touchTracker?.uninstall() }
        guarded("uninstall lifecycle") { lifecycle?.uninstall() }
        guarded("uninstall navigation") { navigationCapture?.uninstall() }
        guarded("uninstall replay") {
            recorderCallbacks?.let { application?.unregisterActivityLifecycleCallbacks(it) }
            recorderCallbacks = null
            (screenRecorder as? WireframeRecorder)?.dispose()
        }
        guarded("uninstall network state") { networkState?.close() }
        guarded("uninstall logs") {
            if (logCaptureInstalled) LogCapture.uninstall(logSink = logLineSink)
            logCaptureInstalled = false
        }
        guarded("uninstall crashes") { errorHandler?.uninstall() }
    }

    private fun start() {
        try {
            startUnguarded()
        } catch (e: Throwable) {
            debugLog("failed to start: $e")
        } finally {
            // Under the lock, so records emitted live from other threads cannot overtake the queued ones.
            synchronized(lock) {
                ready = true
                val queued = ArrayList(pending)
                pending.clear()
                for (emit in queued) {
                    try {
                        emit()
                    } catch (_: Throwable) {
                    }
                }
            }
            readyLatch.countDown()
            reportCrashBeforeStart()
            // Clips a crash spooled last run go now, off the start path: reading them can take a while.
            scheduler.io { recordings.loadSpooled() }
        }
    }

    private fun startUnguarded() {
        val s = StateStore(stateDir, DiskScheduler(scheduler), destination = routes.traceway?.key ?: bufferDestination)
        s.load()
        synchronized(lock) { store = s }
        replay.budget.store = s

        // Unsent 1.x reports: delivered to the old project when the app names it, else they expire on the device.
        val legacyDirectory = overrides?.legacyDirectory
            ?: application?.let { LegacyReports.directoryIn(it.noBackupFilesDir ?: it.filesDir) }
        if (legacyDirectory != null) {
            val target = LegacyReports.destinationOf(options.legacyConnectionString)
            if (options.legacyConnectionString != null && target == null) {
                debugLog("invalid 1.x connection string; legacy reports will expire on the device")
            }
            legacyReports = LegacyReports(legacyDirectory, target, http, scheduler).also { it.drain() }
        }

        val storedUid = s.get("instanceUid") as? String
        val uid = storedUid?.let { Ids.uuidToBytes(it) }
        synchronized(lock) {
            if (uid != null) {
                instanceUid = uid
            } else {
                instanceUid = Ids.uuidToBytes(Ids.uuidv7()) ?: Ids.uuidToBytes(java.util.UUID.randomUUID().toString()) ?: ByteArray(16)
                s.set("instanceUid", Ids.bytesToUuid(instanceUid))
            }
            instanceKnown = true
        }

        var appliedHash: ByteArray? = null
        val cached = s.get("config").asJsonObject()
        if (remoteConfig && cached != null) {
            try {
                val body = cached["body"] as? String
                val hash = cached["hash"] as? String
                if (body != null && hash != null) {
                    synchronized(lock) { control.applyRemote(parseSettingsDocument(body)) }
                    appliedHash = Ids.hexToBytes(hash)
                }
            } catch (_: Throwable) {
                appliedHash = null
            }
        }

        buildResource()
        setUpOTel(stateDir)

        synchronized(lock) {
            // Sessions always run: their id correlates records, samples and keys trigger state. `sessions` only
            // decides whether the session records are sent.
            val manager = SessionManager(
                store = s,
                scheduler = scheduler,
                onStart = { session, resumed -> synchronized(lock) { onSessionStart(session, resumed) } },
                onEnd = { session, endedAt -> synchronized(lock) { onSessionEnd(session, endedAt) } },
                locked = { block -> synchronized(lock) { block() } },
            )
            sessions = manager
            manager.start()
        }

        if (stateDir != null) {
            val p = BufferPersistence(stateDir, DiskScheduler(scheduler), destination = bufferDestination)
            val settings = control.settings
            if (settings.persist) {
                p.load(sessions?.currentId, maxAgeMs = settings.bufferMaxAgeMs, maxBytes = bufferBytes(settings))
                synchronized(lock) { p.retain { control.canExport(it) } }
            } else {
                p.clear()
            }
            synchronized(lock) { persisted = p }
        }

        val e = endpoints
        if (remoteConfig && e != null) {
            opamp = OpampHttpClient(
                endpoint = e.opamp,
                headers = mapOf("Authorization" to "Bearer $token"),
                instanceUid = instanceUid,
                capabilities = AgentCapabilities.REPORTS_STATUS or
                    AgentCapabilities.ACCEPTS_REMOTE_CONFIG or
                    AgentCapabilities.REPORTS_REMOTE_CONFIG or
                    AgentCapabilities.REPORTS_HEARTBEAT,
                customCapabilities = listOf(
                    "io.traceway.sdk.buffer/v1",
                    "io.traceway.sdk.capture/v1",
                    "io.traceway.sdk.sequence-triggers/v1",
                ),
                describe = { synchronized(lock) { describe() } },
                onRemoteConfig = { config -> synchronized(lock) { applyRemoteConfig(config) } },
                appliedConfigHash = appliedHash,
                onInstanceUidChange = { newUid ->
                    synchronized(lock) {
                        instanceUid = newUid
                        store.set("instanceUid", Ids.bytesToUuid(newUid))
                    }
                },
                onStopped = { code -> debugLog("remote config stopped (HTTP $code)") },
                http = http,
                scheduler = scheduler,
                minPollIntervalMs = overrides?.opampMinPollIntervalMs ?: 30_000L,
            )
        }
        synchronized(lock) {
            started = true
            if (options.captureMetrics) metrics.start()
            restoreLocalCaptures()
            onSettingsChanged(control.settings)
        }
        val o = opamp
        if (o == null) {
            synchronized(lock) { confirmCaptures() }
        } else {
            o.start()
            o.poll { synchronized(lock) { confirmCaptures() } }
        }
        outbox?.schedule(2000)
        tracewayOutbox?.schedule(2000)
        debugLog("started, instance ${Ids.bytesToUuid(instanceUid)}")
    }

    private fun stateDirectory(): File? {
        overrides?.let { return it.stateDirectory }
        if (!options.persistToDisk) return null
        val app = application ?: return null
        return try {
            File(app.noBackupFilesDir ?: app.filesDir, "traceway_v2")
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildResource() {
        val attrs = LinkedHashMap<String, String>()
        val o = overrides
        if (o != null) {
            attrs.putAll(o.deviceAttributes)
        } else {
            application?.let { app ->
                try {
                    attrs.putAll(DeviceInfoCollector.collect(app))
                } catch (_: Throwable) {
                }
            }
        }
        attrs["service.name"] = serviceName
        attrs["service.version"] = serviceVersion
        attrs["service.instance.id"] = Ids.bytesToUuid(instanceUid)
        attrs["telemetry.sdk.name"] = "opentelemetry"
        attrs["telemetry.sdk.language"] = "java"
        attrs["telemetry.sdk.version"] = SdkInfo.OTEL_VERSION
        attrs["traceway.sdk.name"] = SdkInfo.NAME
        attrs["traceway.sdk.version"] = SdkInfo.VERSION
        options.environment?.takeIf { it.isNotEmpty() }?.let { attrs["deployment.environment.name"] = it }
        options.proguardUuid?.takeIf { it.isNotEmpty() }?.let { attrs["app.debug.proguard_uuid"] = it }
        // The numbers of the SDK's performance events arrive as metrics: the server must not count them twice.
        if (options.captureMetrics) attrs["traceway.metrics"] = "aggregated"
        synchronized(lock) {
            resourceAttributes.clear()
            resourceAttributes.putAll(attrs)
        }
    }

    private val tracewayAuth: Map<String, String>
        get() = token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

    private val bufferDestination: String
        get() = destinationDigest(listOf(routes.traceway?.key ?: "", routes.logs.key, routes.traces.key, routes.metrics.key))

    private val serviceName: String
        get() = options.serviceName?.takeIf { it.isNotEmpty() } ?: application?.packageName ?: "android-app"

    private val serviceVersion: String get() = options.version.ifEmpty { "unknown" }

    private fun setUpOTel(stateDir: File?) {
        fun dir(name: String): File? = stateDir?.let { File(it, name) }
        val e = endpoints
        // Batches queued for Traceway stay in `outbox`; batches for another receiver wait in a directory of their own.
        val twOutbox = if (e == null) null else Outbox(
            directory = dir("outbox"),
            destinations = mapOf(
                "logs" to OtlpDestination(e.logs, tracewayAuth),
                "traces" to OtlpDestination(e.traces, tracewayAuth),
            ),
            http = http,
            scheduler = scheduler,
        )
        val external = routes.logs.external || routes.traces.external
        val externalName = if (external) "outbox-${destinationDigest(listOf(routes.logs.key, routes.traces.key))}" else null
        val box = if (externalName == null && twOutbox != null) twOutbox else Outbox(
            directory = dir(externalName ?: "outbox"),
            destinations = mapOf("logs" to routes.logs, "traces" to routes.traces),
            http = http,
            scheduler = scheduler,
        )
        outbox = box
        // Drained too when it is not the live one, so what an earlier run left there for Traceway still goes.
        tracewayOutbox = if (twOutbox === box) null else twOutbox
        splitsLogs = routes.splitsLogs
        box.claim()
        tracewayOutbox?.claim()
        Outbox.pruneStale(stateDir, keep = externalName)
        val delay = maxOf(50L, options.exportDelayMs)

        var lx: LogRecordExporter = overrides?.logExporter ?: OtlpLogExporter(routes.logs, box, http, scheduler)
        val tracewayLogs = routes.traceway
        if (routes.splitsLogs && tracewayLogs != null && twOutbox != null) {
            lx = RoutingLogExporter(lx, OtlpLogExporter(tracewayLogs, twOutbox, http, scheduler))
        }
        val sx = overrides?.spanExporter ?: OtlpSpanExporter(routes.traces, box, http, scheduler)
        logExporter = lx
        spanExporter = sx
        val ls = BatchLogRecordProcessor.builder(lx).setScheduleDelay(delay, TimeUnit.MILLISECONDS).build()
        val ss = BatchSpanProcessor.builder(sx).setScheduleDelay(delay, TimeUnit.MILLISECONDS).build()
        logSink = ls
        spanSink = ss
        val issueBox = tracewayOutbox ?: box
        val r = TelemetryRouter(
            control = control,
            buffer = buffer,
            logSink = ls,
            spanSink = ss,
            logExporter = lx,
            spanExporter = sx,
            hooks = hooks,
            persistIssue = if (stateDir == null || overrides?.logExporter != null) {
                null
            } else {
                { data ->
                    if (crashing) {
                        issueBox.persistLog(data)
                    } else {
                        // Off the caller's thread and the lock: the caller may be the main thread.
                        scheduler.disk { if (issueBox.persistLog(data)) issueBox.drain() else lx.export(listOf(data)) }
                        true
                    }
                }
            },
            persistFirst = { crashing },
            now = scheduler::now,
            random = overrides?.random ?: { Math.random() },
        )
        synchronized(lock) { router = r }

        val resource = Resource.getDefault().merge(
            Resource.create(
                Attributes.builder().apply {
                    synchronized(lock) { for ((k, v) in resourceAttributes) put(k, v) }
                }.build(),
            ),
        )
        // Private providers: the app's own OpenTelemetry setup (GlobalOpenTelemetry, its exporters) is untouched.
        val lp = SdkLoggerProvider.builder()
            .setResource(resource)
            .addLogRecordProcessor(ControlLogProcessor(lock, { router }, ls))
            .build()
        val tp = SdkTracerProvider.builder()
            .setResource(resource)
            .setSampler(Sampler.parentBased(Sampler.alwaysOn()))
            .addSpanProcessor(ControlSpanProcessor(lock, { router }, ss))
            .build()
        loggerProvider = lp
        tracerProvider = tp
        logger = lp.loggerBuilder(SdkInfo.NAME).setInstrumentationVersion(SdkInfo.VERSION).build()
        tracer = tp.tracerBuilder(SdkInfo.NAME).setInstrumentationVersion(SdkInfo.VERSION).build()
    }

    // ------------------------------------------------------------ public API

    /**
     * The id this installation reports to the server (`service.instance.id`),
     * usable to target it from the dashboard; null until the SDK has read
     * it from disk (or made one), just after [Traceway.init].
     */
    fun getInstanceId(): String? = guarded("getInstanceId", null) {
        synchronized(lock) { if (instanceKnown) Ids.bytesToUuid(instanceUid) else null }
    }

    /** The current session, or null before the SDK started. */
    val currentSessionId: String? get() = guarded("currentSessionId", null) { synchronized(lock) { sessions?.currentId } }

    /**
     * Reports a caught exception, with the screen it happened on. Dropped
     * when [TracewayOptions.ignoreErrors] matches it or
     * [TracewayOptions.beforeCapture] returns false.
     */
    @JvmOverloads
    fun captureException(error: Throwable?, attributes: Map<String, String>? = null) {
        if (error == null) return
        guarded("captureException") {
            val attrs = exceptionAttributes(error, attributes)
            val captured = TracewayCapturedError(
                error = error,
                type = attrs["exception.type"] as String,
                message = attrs["exception.message"] as String,
                isMessage = false,
                attributes = readOnly(attrs),
                stackTrace = attrs["exception.stacktrace"] as String,
            )
            if (!reports(captured)) return
            attrs[Events.ATTR_EXCEPTION_ID] = Ids.uuidv4()
            emit(
                eventName = Events.EXCEPTION,
                severity = Severity.ERROR,
                severityText = "ERROR",
                attributes = attrs,
            )
        }
    }

    private fun exceptionAttributes(error: Throwable, attributes: Map<String, String>?): LinkedHashMap<String, Any> {
        val attrs = LinkedHashMap<String, Any>(screenContext())
        copyNonNull(attributes, attrs)
        attrs["exception.type"] = error.javaClass.name
        attrs["exception.message"] = safeMessage(error) ?: ""
        attrs["exception.stacktrace"] = safeStackTrace(error)
        return attrs
    }

    private fun readOnly(attrs: Map<String, Any>): Map<String, Any> =
        java.util.Collections.unmodifiableMap(LinkedHashMap(attrs))

    /**
     * Whether [captured] is reported: [TracewayOptions.ignoreErrors] does
     * not match its first line, and [TracewayOptions.beforeCapture] does not
     * drop it. A callback that throws keeps it.
     */
    private fun reports(captured: TracewayCapturedError): Boolean {
        // An app pattern that fails (a regex that overflows the stack) filters nothing.
        if (guarded("ignoreErrors", false) { ignored(captured.line) }) return false
        val before = options.beforeCapture ?: return true
        return try {
            before(captured)
        } catch (e: Throwable) {
            debugLog("beforeCapture threw, the record is kept: $e")
            true
        }
    }

    /**
     * Patterns match the `Type: message` line only: matching stack frames
     * would let a method named like a pattern (any `onTimeout` for the
     * default `/timeout/i`) silence every error that passes through it.
     */
    private fun ignored(line: String): Boolean =
        ignoreErrorsMatch(options.ignoreErrors ?: TracewayOptions.DEFAULT_IGNORE_ERRORS, line)

    /**
     * Where the app was when an issue happened: the screen on top, its route
     * template, the screen before it, how long it had been shown, and the
     * size of the screen in dp. Read when the issue is captured.
     */
    private fun screenContext(): Map<String, Any> {
        val out = LinkedHashMap<String, Any>(8)
        val screen = currentScreen
        if (!screen.isNullOrEmpty()) out["app.screen.name"] = screen
        currentRoute?.let { out["app.screen.route"] = it }
        previousScreen?.takeIf { it.isNotEmpty() }?.let { out["app.screen.previous"] = it }
        val since = screenSinceMs
        if (!screen.isNullOrEmpty() && since > 0) out["app.screen.duration_ms"] = maxOf(0L, scheduler.now() - since)
        viewport()?.let { out["viewport"] = it }
        return out
    }

    /** The screen's size in dp, `<width>x<height>`. */
    private fun viewport(): String? = try {
        val metrics = application?.resources?.displayMetrics
        val density = metrics?.density ?: 0f
        if (metrics == null || density <= 0f || metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            null
        } else {
            "${Math.round(metrics.widthPixels / density)}x${Math.round(metrics.heightPixels / density)}"
        }
    } catch (_: Throwable) {
        null
    }

    /** The app's exception classes run code here (getMessage, toString): it must not throw into the app. */
    private fun safeMessage(error: Throwable): String? = try {
        error.message
    } catch (_: Throwable) {
        null
    }

    private fun safeStackTrace(error: Throwable): String = try {
        formatException(error)
    } catch (_: Throwable) {
        val frames = runCatching { error.stackTrace.joinToString("\n") { "\tat $it" } }.getOrDefault("")
        "${error.javaClass.name}\n$frames"
    }

    /** Reports a free-form message as an issue, with the screen it was sent from; filtered like [captureException]. */
    @JvmOverloads
    fun captureMessage(message: String?, attributes: Map<String, String>? = null) {
        if (message == null) return
        guarded("captureMessage") {
            val attrs = LinkedHashMap<String, Any>(screenContext())
            copyNonNull(attributes, attrs)
            val captured = TracewayCapturedError(null, null, message, true, readOnly(attrs), null)
            if (!reports(captured)) return
            attrs[Events.ATTR_EXCEPTION_ID] = Ids.uuidv4()
            emit(eventName = Events.MESSAGE, severity = Severity.INFO, severityText = "INFO", body = message, attributes = attrs)
        }
    }

    /** Records a custom breadcrumb, shown on the session timeline. */
    @JvmOverloads
    fun recordAction(category: String?, name: String?, data: Map<String, Any?>? = null) {
        guarded("recordAction") {
            markActivity()
            val attrs = LinkedHashMap<String, Any>()
            attrs["traceway.action.category"] = category ?: ""
            attrs["traceway.action.name"] = name ?: ""
            if (!data.isNullOrEmpty()) {
                attrs["traceway.action.data"] = try {
                    Json.stringify(data)
                } catch (_: Throwable) {
                    runCatching { data.toString() }.getOrDefault("")
                }
            }
            emit(eventName = Events.ACTION, severity = Severity.INFO, attributes = attrs)
        }
    }

    /** Emits a named product event, e.g. `checkout_failed`. Triggers can match it by `event.name`. */
    @JvmOverloads
    fun track(eventName: String?, attributes: Map<String, Any>? = null) {
        if (eventName.isNullOrEmpty()) return
        guarded("track") {
            // A copy: the record may wait for the SDK to start, and the app may change its map meanwhile.
            val attrs = copyNonNull(attributes)
            emit(eventName = eventName, severity = Severity.INFO, attributes = attrs)
        }
    }

    /** Sets an attribute attached to every later record and reported to the server for remote-config targeting. */
    fun setAttribute(key: String?, value: String?) {
        if (key.isNullOrEmpty() || value == null) return
        guarded("setAttribute") {
            synchronized(lock) {
                if (!globalAttributes.containsKey(key) && globalAttributes.size >= MAX_GLOBAL_ATTRIBUTES) return
                globalAttributes[key] = value
                scheduleRefresh()
            }
        }
    }

    fun setAttributes(attributes: Map<String, String>?) {
        guarded("setAttributes") { for ((k, v) in copyStrings(attributes)) setAttribute(k, v) }
    }

    fun removeAttribute(key: String?) {
        if (key == null) return
        guarded("removeAttribute") {
            synchronized(lock) {
                if (globalAttributes.remove(key) != null) scheduleRefresh()
            }
        }
    }

    fun clearAttributes() {
        guarded("clearAttributes") {
            synchronized(lock) {
                if (globalAttributes.isEmpty()) return
                globalAttributes.clear()
                scheduleRefresh()
            }
        }
    }

    /** Asks the server for collection settings now instead of at the next poll. */
    fun refreshConfig() {
        guarded("refreshConfig") { opamp?.refresh() }
    }

    /**
     * Records [message] as a log line at [level] (`log.source = "api"`),
     * shown on the session timeline and filtered by the console's minimum
     * severity in the collection settings. To report an issue, use
     * [captureException] or [captureMessage].
     */
    @JvmOverloads
    fun log(message: String?, level: TracewayLogLevel? = TracewayLogLevel.INFO, attributes: Map<String, String>? = null) {
        if (!options.captureLogs || message == null) return
        guarded("log") {
            val attrs = copyNonNull(attributes)
            attrs["log.source"] = "api"
            consoleLine(message, level ?: TracewayLogLevel.INFO, attrs)
        }
    }

    /** Records a log line; [severity] is trace, debug, info, warn, error or fatal. */
    @Deprecated(
        "Use log(message, level), with a TracewayLogLevel.",
        ReplaceWith("log(message, TracewayLogLevel.INFO)", "com.tracewayapp.traceway.TracewayLogLevel"),
    )
    @JvmOverloads
    fun recordLog(message: String?, severity: String? = "info") = log(message, TracewayLogLevel.of(severity ?: "info"))

    private fun consoleLine(message: String, level: TracewayLogLevel, attributes: Map<String, Any>) {
        // The SDK's own output is not app telemetry, nor is a line printed while a record is emitted.
        if (message.startsWith("Traceway: ") || (emitDepth > 0 && Thread.holdsLock(lock))) return
        emit(severity = level.severity, severityText = level.severityText, body = message, attributes = attributes)
    }

    /**
     * Reports the screen the user arrived at: `/checkout` or `/orders/{id}`
     * as the route reads, with its route template [route] when the router
     * knows it (the same as calling [setRoute] right after). Activities and
     * AndroidX fragments are reported automatically; call this from Compose
     * Navigation or another router.
     */
    @JvmOverloads
    fun recordNavigation(path: String?, action: String? = "push", from: String? = null, route: String? = null) {
        if (path == null) return
        guarded("recordNavigation") { navigated(path, action ?: "push", from, route) }
    }

    private fun navigated(path: String, action: String, from: String?, route: String?) {
        markActivity()
        val nowMs = scheduler.now()
        // A removal takes a screen away without the user arriving anywhere: on the timeline, not a navigation.
        val arrived = action != "remove"
        val to = if (arrived) path else null
        if (!to.isNullOrEmpty() && to != currentScreen) {
            previousScreen = currentScreen
            currentScreen = to
            screenSinceMs = nowMs
            currentRoute = null
        }
        val template = routeTemplate(route)
        if (arrived && template != null) currentRoute = template
        if (options.captureMetrics && arrived) {
            vitals?.navigated(if (to.isNullOrEmpty()) null else template ?: normalizePath(to), action, nowMs)
        }
        val attrs = LinkedHashMap<String, Any>()
        attrs["url.full"] = to ?: ""
        attrs["navigation.action"] = action
        attrs["browser.navigation.type"] = action
        if (from != null) attrs["navigation.from"] = from
        if (to != null) attrs["navigation.to"] = to
        currentScreen?.takeIf { it.isNotEmpty() }?.let { attrs["app.screen.name"] = it }
        currentRoute?.let { attrs["app.screen.route"] = it }
        emit(eventName = Events.NAVIGATION, severity = Severity.INFO, timeMs = nowMs, attributes = attrs)
    }

    /**
     * Names the current screen by its route template (`/orders/:id`): issues
     * carry it (`app.screen.route`), and metrics group by it instead of by
     * the path with its ids replaced. Lasts until the user navigates to
     * another screen; null removes it. Trimmed, with a leading `/`, at most
     * 64 characters.
     */
    fun setRoute(template: String?) {
        guarded("setRoute") {
            val route = routeTemplate(template)
            currentRoute = route
            if (route != null && options.captureMetrics) vitals?.routeNamed(route)
        }
    }

    private fun routeTemplate(template: String?): String? {
        val trimmed = template?.trim()
        if (trimmed.isNullOrEmpty()) return null
        val withSlash = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return if (withSlash.length > MAX_ROUTE_LENGTH) withSlash.substring(0, MAX_ROUTE_LENGTH) else withSlash
    }

    /**
     * Starts an interaction named [name] (`checkout`, `search`): a span the
     * requests made in its [TracewayInteraction.run] are children of, and
     * that the exceptions and logs recorded there point to. Call
     * [TracewayInteraction.end] when the action is over. One started inside
     * another's `run` is its child. Before the SDK has started it records
     * nothing.
     */
    @JvmOverloads
    fun startInteraction(name: String?, attributes: Map<String, Any>? = null): TracewayInteraction {
        val t = tracer
        var span: Span? = null
        if (t != null && ready && !isShutdown && !name.isNullOrEmpty()) {
            try {
                val parent = TracewayInteraction.currentContext()
                val builder = t.spanBuilder(name).setSpanKind(SpanKind.INTERNAL)
                if (parent != null) builder.setParent(parent) else builder.setNoParent()
                copyNonNull(attributes).forEach { (key, value) ->
                    when (value) {
                        is String -> builder.setAttribute(key, value)
                        is Boolean -> builder.setAttribute(key, value)
                        is Int -> builder.setAttribute(key, value.toLong())
                        is Long -> builder.setAttribute(key, value)
                        is Float -> builder.setAttribute(key, value.toDouble())
                        is Double -> builder.setAttribute(key, value)
                        else -> builder.setAttribute(key, value.toString())
                    }
                }
                builder.setAttribute("traceway.interaction", true)
                span = builder.startSpan()
            } catch (e: Throwable) {
                debugLog("interaction failed: $e")
            }
        }
        return guarded("startInteraction", null) { TracewayInteraction(span) { error, attrs -> captureException(error, attrs) } }
            ?: TracewayInteraction(null) { _, _ -> }
    }

    /** The screen on top shows everything it loads: ends its time to full display (`app.screen.ttfd`). */
    fun reportFullyDisplayed() {
        guarded("reportFullyDisplayed") { if (options.captureMetrics) vitals?.fullyDisplayed() }
    }

    /** Observes [value] of an app-defined distribution, e.g. `checkout.duration`. */
    @JvmOverloads
    fun recordMetric(name: String?, value: Double, dimensions: Map<String, Any>? = null) {
        if (name == null) return
        guarded("metric") { if (options.captureMetrics) metrics.metric(name, value, copyNonNull(dimensions)) }
    }

    /** Adds [n] to an app-defined counter. */
    @JvmOverloads
    fun recordCount(name: String?, n: Double = 1.0, dimensions: Map<String, Any>? = null) {
        if (name == null) return
        guarded("count") { if (options.captureMetrics) metrics.count(name, n, copyNonNull(dimensions)) }
    }

    /** Sets an app-defined dimension on every metric from now on (`custom.<key>`); null removes it. */
    fun setMetricDimension(key: String?, value: String?) {
        if (key == null) return
        guarded("setMetricDimension") { metrics.setDimension(key, value) }
    }

    /** Notes user activity so the session's inactivity timeout restarts. */
    fun markActivity() {
        guarded("markActivity") { synchronized(lock) { sessions?.markActivity() } }
    }

    // ------------------------------------------------------------- network

    private fun isOwnRequest(url: String): Boolean =
        endpoints?.isOwn(url) == true || routes.isOwn(url) || legacyReports?.destination?.url == url

    private fun propagates(url: String): Boolean =
        options.propagateTraceHeaderUrls.any { url.startsWith(it) } ||
            options.propagateTraceHeaderUrlPatterns.any { it.containsMatchIn(url) }

    /**
     * Starts the client span of an HTTP request, or returns null when it is
     * not recorded (network capture off, an SDK request). Used by
     * [com.tracewayapp.traceway.network.TracewayOkHttpInterceptor]; other
     * HTTP stacks can call it too, then [endRequest].
     */
    fun startRequest(method: String?, url: String?, hasTraceparent: Boolean = false): RequestHandle? {
        if (method == null || url == null) return null
        return guarded("startRequest", null) { requestStarted(method, url, hasTraceparent) }
    }

    private fun requestStarted(method: String, url: String, hasTraceparent: Boolean): RequestHandle? {
        if (!options.captureNetwork || isShutdown || isOwnRequest(url)) return null
        // Before the SDK is ready there is no tracer yet: the request is still timed, and recorded once it is.
        val t = tracer ?: return RequestHandle(null, method.uppercase(), url, scheduler.now(), null)
        return try {
            val startMs = scheduler.now()
            val builder = t.spanBuilder(method.uppercase())
                .setSpanKind(SpanKind.CLIENT)
                .setStartTimestamp(startMs, TimeUnit.MILLISECONDS)
            // A request made inside an interaction's `run` is part of it; any other starts a trace of its own.
            val parent = TracewayInteraction.currentContext()
            if (parent != null) builder.setParent(parent) else builder.setNoParent()
            val span = builder.startSpan()
            val header = if (!hasTraceparent && propagates(url)) {
                val c = span.spanContext
                "00-${c.traceId}-${c.spanId}-${if (c.isSampled) "01" else "00"}"
            } else {
                null
            }
            RequestHandle(span, method.uppercase(), url, startMs, header)
        } catch (e: Throwable) {
            debugLog("request span failed: $e")
            null
        }
    }

    /** Ends a request started with [startRequest]. [error] names the failure when no response came. */
    fun endRequest(handle: RequestHandle?, statusCode: Int?, requestBytes: Long?, responseBytes: Long?, error: String?) {
        if (handle == null) return
        guarded("endRequest") { requestEnded(handle, statusCode, requestBytes, responseBytes, error) }
    }

    private fun requestEnded(handle: RequestHandle, statusCode: Int?, requestBytes: Long?, responseBytes: Long?, error: String?) {
        val endMs = scheduler.now()
        if (handle.span == null) {
            synchronized(lock) {
                if (ready) {
                    endSpan(lateSpan(handle) ?: return, handle, statusCode, requestBytes, responseBytes, error, endMs)
                } else {
                    queue(keep = false) {
                        lateSpan(handle)?.let { endSpan(it, handle, statusCode, requestBytes, responseBytes, error, endMs) }
                    }
                }
            }
        } else {
            endSpan(handle.span, handle, statusCode, requestBytes, responseBytes, error, endMs)
        }
        if (options.captureMetrics) {
            runCatching {
                metrics.request(
                    handle.url,
                    (endMs - handle.startMs).toDouble(),
                    failed = error != null || statusCode == null || statusCode >= 400,
                    responseBytes = responseBytes,
                )
            }
        }
    }

    /** The span of a request made before the SDK was ready, started at the time the request was. */
    private fun lateSpan(handle: RequestHandle): Span? = try {
        tracer?.spanBuilder(handle.method)
            ?.setSpanKind(SpanKind.CLIENT)
            ?.setNoParent()
            ?.setStartTimestamp(handle.startMs, TimeUnit.MILLISECONDS)
            ?.startSpan()
    } catch (_: Throwable) {
        null
    }

    private fun endSpan(
        span: Span,
        handle: RequestHandle,
        statusCode: Int?,
        requestBytes: Long?,
        responseBytes: Long?,
        error: String?,
        endMs: Long,
    ) {
        try {
            span.setAttribute("http.request.method", handle.method)
            span.setAttribute("url.full", handle.url)
            hostOf(handle.url)?.let { span.setAttribute("server.address", it) }
            if (statusCode != null) span.setAttribute("http.response.status_code", statusCode.toLong())
            if (requestBytes != null) span.setAttribute("http.request.body.size", requestBytes)
            if (responseBytes != null) span.setAttribute("http.response.body.size", responseBytes)
            if (error != null) {
                span.setAttribute("error.type", error)
            } else if (statusCode != null && statusCode >= 400) {
                span.setAttribute("error.type", statusCode.toString())
            }
            val failed = error != null || (statusCode != null && statusCode >= 400)
            if (failed) span.setStatus(StatusCode.ERROR)
            span.end(endMs, TimeUnit.MILLISECONDS)
        } catch (e: Throwable) {
            debugLog("network span failed: $e")
        }
    }

    private fun hostOf(url: String): String? = try {
        java.net.URI(url).host
    } catch (_: Throwable) {
        null
    }

    // --------------------------------------------------------------- taps

    /** A tap on [root] at ([xPx], [yPx]) in the root's coordinates: a click record and a click for recording triggers. */
    private fun recordTap(root: View, xPx: Float, yPx: Float, xDp: Float, yDp: Float) {
        if (isShutdown || !options.captureUserActions) return
        markActivity()
        val wantsTriggers = ready && "click" in sequenceKinds
        if (!wantsTriggers && synchronized(lock) { control.modeFor("userActions") } == Mode.OFF) return
        val path = try {
            ViewPath.pathAt(root, xPx, yPx)
        } catch (e: Throwable) {
            debugLog("tap lookup failed: $e")
            return
        } ?: return
        val target = describeTap(path)
        if (target != null) {
            val attrs = LinkedHashMap<String, Any>()
            attrs["browser.css_selector"] = target.selector
            attrs["browser.tag_name"] = target.tag
            attrs["app.widget.name"] = target.tag
            target.id?.let { attrs["app.widget.id"] = it }
            target.text?.let { attrs["app.widget.text"] = it }
            target.label?.let { attrs["app.widget.label"] = it }
            attrs["app.screen.coordinate.x"] = Math.round(xDp).toLong()
            attrs["app.screen.coordinate.y"] = Math.round(yDp).toLong()
            currentScreen?.let { attrs["app.screen.name"] = it }
            emit(eventName = Events.CLICK, severity = Severity.INFO, attributes = attrs)
        }
        if (wantsTriggers) observePointer("click") { selector -> path.matches(selector) }
    }

    /** Feeds a hover to recording triggers: a hover step matches when the pointer enters a matching view. */
    private fun recordHover(root: View, xPx: Float, yPx: Float) {
        if (isShutdown || !ready || !options.captureUserActions || "hover" !in sequenceKinds) return
        // Hit testing is cheap; the path is built only when the pointer is over something else than before.
        val move = try {
            hover.move(root, xPx, yPx)
        } catch (_: Throwable) {
            null
        } ?: return
        observePointer("hover", move::entering)
    }

    private fun observePointer(type: String, test: (String) -> Boolean) = synchronized(lock) {
        if (!control.enabled || sessions?.currentId == null) return
        runFired(
            triggers.observe(
                ObservedEvent(
                    timeMs = scheduler.now(),
                    lookup = pageLookup(mapOf(KEY_POINTER_ACTION to type, KEY_TARGET_MATCHES to test)),
                    sequence = type,
                ),
            ),
        )
    }

    /** The fields of an event the app reports itself, next to what every record can be matched on. */
    private fun pageLookup(fields: Map<String, Any?>): FieldLookup = { key ->
        when {
            fields.containsKey(key) -> fields[key]
            key.startsWith("global.") -> globalAttributes[key.substring("global.".length)]
            key.startsWith("resource.") -> resourceAttributes[key.substring("resource.".length)]
            key == Events.ATTR_SESSION_ID -> sessions?.currentId
            else -> null
        }
    }

    // --------------------------------------------------------------- flush

    /**
     * Sends everything that is ready now: queued records, replay clips and
     * telemetry that failed earlier, waiting up to [timeoutMs]. Telemetry
     * still held in the ring buffer stays there. Blocks: call it off the
     * main thread.
     */
    @JvmOverloads
    fun flush(timeoutMs: Long = 10_000) {
        // After shutdown nothing is sent any more: there is nothing to wait for.
        if (isShutdown) return
        guarded("flush") { flushUnguarded(timeoutMs) }
    }

    private fun flushUnguarded(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + maxOf(0L, timeoutMs)
        fun left() = maxOf(1L, deadline - System.currentTimeMillis())
        if (!awaitQuietly(readyLatch, left())) return
        synchronized(lock) {
            flushTimer?.cancel()
            flushTimer = null
        }
        try {
            logSink?.forceFlush()?.join(left(), TimeUnit.MILLISECONDS)
            spanSink?.forceFlush()?.join(left(), TimeUnit.MILLISECONDS)
            router?.settle(left())
            logExporter?.flush()?.join(left(), TimeUnit.MILLISECONDS)
            spanExporter?.flush()?.join(left(), TimeUnit.MILLISECONDS)
            replay.drainBlocking(left())
            logSink?.forceFlush()?.join(left(), TimeUnit.MILLISECONDS)
            logExporter?.flush()?.join(left(), TimeUnit.MILLISECONDS)
            metrics.flush()
            metrics.awaitUploads(left())
            legacyReports?.drainBlocking(left())
            outbox?.drainBlocking(left())
            tracewayOutbox?.drainBlocking(left())
            persistBuffer()
            synchronized(lock) { saveTriggerProgress(now = true) }
            store.flush()
        } catch (e: Throwable) {
            debugLog("flush failed: $e")
        }
    }

    /** Flushes (up to [timeoutMs]) and stops the SDK. [Traceway.init] starts it again. Blocks. */
    @JvmOverloads
    fun shutdown(timeoutMs: Long = 10_000) {
        synchronized(shutdownLock) {
            if (isShutdown || shuttingDown) return
            shuttingDown = true
        }
        // Every part on its own: one that fails must not leave the others (hooks, threads) behind.
        guarded("shutdown wait") { awaitQuietly(readyLatch, timeoutMs) }
        guarded("shutdown session") {
            synchronized(lock) {
                sessions?.current?.let { emitSessionEnd(it, scheduler.now()) }
                // The session may go on after a restart: its memory is observed when it ends.
                if (options.captureMetrics) memoryPeaks.remember(sessions?.currentId)
            }
        }
        guarded("shutdown vitals") { vitals?.stop() }
        flush(timeoutMs)
        guarded("shutdown metrics") { metrics.stop() }
        isShutdown = true
        guarded("shutdown timers") {
            synchronized(lock) {
                sessions?.stop()
                refreshTimer?.cancel()
                overrideTimer?.cancel()
                progressTimer?.cancel()
            }
        }
        guarded("shutdown opamp") { opamp?.stop() }
        guarded("shutdown replay") { synchronized(lock) { replay.stop() } }
        uninstallHooks()
        guarded("shutdown health") {
            synchronized(healthLock) {
                healthTimer?.cancel()
                healthTimer = null
            }
        }
        guarded("shutdown store") { store.flush() }
        // What the processors still export from here is written to disk, for the next start, not sent; without a
        // place on disk it is sent.
        if (keepsOnDisk()) setPersistOnly(true)
        guarded("shutdown providers") {
            loggerProvider?.shutdown()?.join(2, TimeUnit.SECONDS)
            tracerProvider?.shutdown()?.join(2, TimeUnit.SECONDS)
        }
        // After the processors' last export: a batch that fails then is still queued on disk for the next start.
        guarded("shutdown outbox") {
            outbox?.dispose()
            tracewayOutbox?.dispose()
        }
        guarded("shutdown scheduler") { scheduler.shutdown() }
        synchronized(Companion) { if (current === this) current = null }
    }

    private val shutdownLock = Any()
    private var shuttingDown = false

    /** Whether the exporters can write what they hold to the outbox, to be sent at the next start. */
    private fun keepsOnDisk(): Boolean = guarded("outbox", false) {
        outbox?.usable == true && (tracewayOutbox == null || tracewayOutbox?.usable == true)
    }

    private fun setPersistOnly(on: Boolean) {
        guarded("persistOnly") {
            (logExporter as? PersistingExporter)?.persistOnly = on
            (spanExporter as? PersistingExporter)?.persistOnly = on
        }
    }

    // ------------------------------------------------------------- crashes

    /**
     * An uncaught exception is about to end the process: report it, write it
     * with its breadcrumbs and replay clip to disk (nothing in flight would
     * survive), then try to send it all for a moment. What does not make it
     * is sent at the next start. Without a place on disk
     * ([TracewayOptions.persistToDisk] off, or no usable directory) it is
     * sent right away instead, on any thread, within the same budget.
     */
    private fun captureCrash(thread: Thread, error: Throwable) {
        if (isShutdown) return
        // The whole path shares one deadline: a crash on the main thread freezes the screen while it runs.
        val deadline = System.currentTimeMillis() + CRASH_BUDGET_MS
        fun left() = maxOf(1L, deadline - System.currentTimeMillis())
        // An interrupted thread would skip every wait below; the flag is put back for the next handler.
        val interrupted = Thread.interrupted()
        try {
            if (!awaitQuietly(readyLatch, 300) || logger == null) {
                // Not started yet: nothing can be emitted, so the crash waits on disk for the next start.
                saveCrashBeforeStart(thread, error)
                return
            }
            crashing = true
            // Nothing in flight would survive the process: from here on every export is written to the outbox, when
            // there is one that can keep it. Otherwise what is exported goes out live, and is waited for below.
            val toDisk = keepsOnDisk()
            if (toDisk) setPersistOnly(true)
            synchronized(lock) {
                if (options.captureMetrics) metrics.crash()
                val attrs = exceptionAttributes(error, mapOf("thread.name" to thread.name))
                attrs[Events.ATTR_EXCEPTION_ID] = Ids.uuidv4()
                attrs["exception.unhandled"] = true
                // The record is written to disk before its triggers run (it is the one thing that must
                // survive), so it carries the replay status the default trigger is about to give it.
                attrs[Events.ATTR_REPLAY_STATUS] = if (control.replayMode == Mode.OFF) "disabled" else "requested"
                // Its triggers then flush the buffer (to disk, now) and assemble the replay clip on this thread.
                emit(eventName = Events.EXCEPTION, severity = Severity.FATAL, severityText = "FATAL", attributes = attrs)
            }
            // The batch processors may still hold records from just before the crash.
            logSink?.forceFlush()?.join(minOf(300L, left()), TimeUnit.MILLISECONDS)
            spanSink?.forceFlush()?.join(minOf(150L, left()), TimeUnit.MILLISECONDS)
            replay.spool(waitForClipsMs = minOf(500L, left()))
            val (session, entries) = synchronized(lock) {
                saveTriggerProgress(now = true)
                sessions?.currentId to buffer.snapshot()
            }
            if (control.settings.persist) persisted?.saveNow(session, entries)
            if (options.captureMetrics) metrics.flush(final = true)
            store.flush()
            if (!toDisk) {
                // Nothing can wait for the next start: what the processors handed over is on its way now.
                logSink?.forceFlush()?.join(left(), TimeUnit.MILLISECONDS)
                spanSink?.forceFlush()?.join(left(), TimeUnit.MILLISECONDS)
                val pending = listOfNotNull(logExporter?.flush(), spanExporter?.flush(), recordings.flush())
                CompletableResultCode.ofAll(pending).join(left(), TimeUnit.MILLISECONDS)
            } else if (!isMainThread()) {
                // Everything is on disk now. Off the main thread, try to send it before the process ends; on it, the
                // next start sends it rather than the screen staying frozen.
                val pending = listOfNotNull(outbox?.drain(), tracewayOutbox?.drain(), recordings.flush())
                CompletableResultCode.ofAll(pending).join(left(), TimeUnit.MILLISECONDS)
            }
        } catch (_: Throwable) {
            // Never let our own failure stop the crash from reaching the previous handler.
        } finally {
            // The app's own handler may swallow the crash and keep running.
            crashing = false
            setPersistOnly(false)
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun isMainThread(): Boolean = try {
        Looper.getMainLooper()?.thread === Thread.currentThread()
    } catch (_: Throwable) {
        false
    }

    private val crashFile: File? get() = stateDir?.let { File(it, "crash.json") }

    /** A crash before the SDK started: kept as JSON and reported as an exception at the next start. */
    private fun saveCrashBeforeStart(thread: Thread, error: Throwable) {
        val file = crashFile ?: return
        try {
            val record = linkedMapOf(
                "timeMs" to System.currentTimeMillis(),
                "type" to error.javaClass.name,
                "message" to (safeMessage(error) ?: ""),
                "stacktrace" to safeStackTrace(error),
                "thread" to thread.name,
            )
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(Json.stringify(record), Charsets.UTF_8)
            tmp.renameTo(file)
        } catch (_: Throwable) {
        }
    }

    private fun reportCrashBeforeStart() {
        val file = crashFile ?: return
        try {
            if (!file.exists()) return
            val record = Json.parse(file.readText(Charsets.UTF_8)).asJsonObject()
            file.delete()
            if (record == null) return
            val attrs = LinkedHashMap<String, Any>()
            attrs["exception.type"] = record["type"] as? String ?: "java.lang.Throwable"
            attrs["exception.message"] = record["message"] as? String ?: ""
            attrs["exception.stacktrace"] = record["stacktrace"] as? String ?: ""
            attrs["exception.unhandled"] = true
            attrs["thread.name"] = record["thread"] as? String ?: ""
            attrs["traceway.crash.before_start"] = true
            attrs[Events.ATTR_EXCEPTION_ID] = Ids.uuidv4()
            emit(
                eventName = Events.EXCEPTION,
                severity = Severity.FATAL,
                severityText = "FATAL",
                timeMs = record["timeMs"].asLongExact(),
                attributes = attrs,
            )
            if (options.captureMetrics) metrics.crash()
        } catch (_: Throwable) {
        }
    }

    private fun awaitQuietly(latch: CountDownLatch, timeoutMs: Long): Boolean = try {
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        latch.count == 0L
    }

    // ------------------------------------------------------------ emission

    /** Breadcrumbs take at most [MAX_PENDING_BEFORE_READY] places while starting, so they cannot crowd out errors. */
    private fun queue(keep: Boolean, emit: () -> Unit) {
        val limit = if (keep) 2 * MAX_PENDING_BEFORE_READY else MAX_PENDING_BEFORE_READY
        if (pending.size < limit) pending.add(emit)
    }

    /**
     * Emits a log record. [context] is the caller's (an interaction's trace,
     * inside its `run`), kept for a record that waits for the SDK to start.
     */
    private fun emit(
        eventName: String? = null,
        severity: Severity? = null,
        severityText: String? = null,
        body: String? = null,
        attributes: Map<String, Any> = emptyMap(),
        timeMs: Long? = null,
        context: Context? = null,
    ) {
        if (isShutdown) return
        val timestamp = timeMs ?: scheduler.now()
        synchronized(lock) {
            if (!ready) {
                // The caller's context (an interaction's trace) is kept for the record that waits.
                val caller = context ?: currentContext()
                queue(eventName in KEPT_BEFORE_READY) {
                    emit(eventName, severity, severityText, body, attributes, timestamp, caller)
                }
                return
            }
            val l = logger ?: return
            emitDepth++
            try {
                val b = l.logRecordBuilder()
                (context ?: currentContext())?.let { b.setContext(it) }
                b.setTimestamp(timestamp, TimeUnit.MILLISECONDS)
                if (eventName != null) b.setEventName(eventName)
                if (severity != null) b.setSeverity(severity)
                if (severityText != null) b.setSeverityText(severityText)
                if (body != null) b.setBody(body)
                for ((k, v) in attributes) {
                    when (v) {
                        is String -> b.setAttribute(k, v)
                        is Boolean -> b.setAttribute(k, v)
                        is Int -> b.setAttribute(k, v.toLong())
                        is Long -> b.setAttribute(k, v)
                        is Double -> b.setAttribute(k, v)
                        is Float -> b.setAttribute(k, v.toDouble())
                        is Number -> b.setAttribute(k, v.toDouble())
                        else -> b.setAttribute(k, v.toString())
                    }
                }
                b.emit()
            } catch (e: Throwable) {
                debugLog("emit failed: $e")
            } finally {
                emitDepth--
            }
        }
    }

    /**
     * The OpenTelemetry context of the calling thread, or null when it cannot
     * be read: on API 21-23 without core library desugaring OpenTelemetry's
     * context storage fails to load (NoClassDefFoundError).
     */
    private fun currentContext(): Context? = guarded("context", null) { Context.current() }

    private fun emitSessionEnd(session: SessionInfo, endedAtMs: Long) {
        if (!reportedSessions.remove(session.id)) return
        emit(eventName = Events.SESSION_END, severity = Severity.INFO, timeMs = endedAtMs, attributes = sessionAttributes(session))
    }

    /** What every session event carries: the session, how much of its diagnostic budget replay used and what was dropped. */
    private fun sessionAttributes(session: SessionInfo): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        out[Events.ATTR_SESSION_ID] = session.id
        out[Events.ATTR_SESSION_STARTED_AT] = session.startedAtMs
        for ((k, v) in replay.budget.snapshot(session.id)) out[k] = v
        out["traceway.replay.images"] = (sessionImagePolicy ?: control.settings.replayImages).wire
        out["traceway.replay.failed_segments"] = recordings.failedSegments.toLong()
        out["traceway.context.evicted"] = buffer.evictedCount.toLong()
        out["traceway.error.suppressed_total"] = synchronized(healthLock) { suppressedTotal }
        return out
    }

    private fun onSessionStart(session: SessionInfo, resumed: Boolean) {
        liveSessionId = session.id
        sessionImagePolicy = control.settings.replayImages
        synchronized(healthLock) {
            healthDelayMs = HEALTH_DELAY_MS
            healthReported = false
        }
        // What an earlier session left behind is not this one's context.
        if (!resumed) {
            buffer.clear()
            persisted?.clearRestored()
            // Its pictures are recorded again, against its own image budget.
            (screenRecorder as? WireframeRecorder)?.images?.newSession()
        }
        triggers.resetSession()
        val limits = store.get("triggerLimits").asJsonObject()
        if (limits != null && limits["sessionId"] == session.id) triggers.restoreSessionLimits(limits["limits"])
        restoreTriggerProgress(session.id)
        replay.onSessionStart()
        if (options.captureMetrics) {
            val id = session.id
            // On the disk thread, in order with remember and ended: they read and write the same stored peak.
            scheduler.disk { memoryPeaks.settle(id) }
            metrics.sessionStarted(session.id, resumed)
        }
        if (control.settings.sessionsEnabled) {
            reportedSessions.add(session.id)
            emit(
                eventName = if (resumed) Events.SESSION_UPDATE else Events.SESSION_START,
                severity = Severity.INFO,
                timeMs = if (resumed) null else session.startedAtMs,
                attributes = sessionAttributes(session),
            )
        }
        applyReplay()
        scheduleRefresh()
    }

    private fun onSessionEnd(session: SessionInfo, endedAtMs: Long) {
        replay.onSessionEnd(session.id)
        if (options.captureMetrics) {
            val id = session.id
            // Reads /proc: off the caller's thread, which can be the main one (a tap that ends an expired session).
            scheduler.disk { memoryPeaks.ended(id) }
        }
        emitSessionEnd(session, endedAtMs)
    }

    // ------------------------------------------------------- RouterHooks

    /** What the router asks of the client; called with the lock held. */
    private val hooks = object : RouterHooks {
        override val sessionId: String? get() = sessions?.currentId
        override fun globalAttribute(key: String): String? = globalAttributes[key]
        override fun resourceAttribute(key: String): String? = resourceAttributes[key]
        override fun enrichLog(record: ReadWriteLogRecord, signal: String): Boolean = this@TracewayClient.enrichLog(record, signal)
        override fun observe(record: ObservedRecord) = this@TracewayClient.observe(record)
    }

    /** The session's replay image budget, asked by the wireframe capture on the main thread and its encoder. */
    private val imageBudget = object : ImageBudget {
        override fun hasRoom(): Boolean = replay.budget.hasImageRoom(liveSessionId)
        override fun admit(bytes: Int): Boolean = replay.budget.admitImage(liveSessionId, bytes.toLong())
    }

    // Only session events carry traceway.session.started_at, as in the browser SDK: the server reads the
    // session's start from them and nothing else.
    private fun enrichLog(record: ReadWriteLogRecord, signal: String): Boolean {
        for ((key, value) in globalAttributes) {
            val k = AttributeKey.stringKey(key)
            if (record.getAttribute(k) == null) record.setAttribute(k, value)
        }
        // A crash is never collapsed into an earlier report of the same error: it is the one that matters.
        if (signal == "errors" && record.getAttribute(ATTR_UNHANDLED) != true) {
            val key = "${record.getAttribute(ATTR_EXCEPTION_TYPE)}:${record.getAttribute(ATTR_EXCEPTION_MESSAGE)}"
            if (!admitError(key)) return false
            if (suppressedErrors > 0) {
                record.setAttribute(ATTR_SUPPRESSED, suppressedErrors.toLong())
                suppressedErrors = 0
            }
        }
        return true
    }

    /** Stops a crash loop from flooding the server: identical errors within a second collapse, at most 60 a minute. */
    private fun admitError(key: String): Boolean {
        val nowMs = scheduler.now()
        if (key == lastErrorKey && nowMs - lastErrorMs < DUPLICATE_ERROR_WINDOW_MS) {
            suppressed()
            return false
        }
        errorTimes.removeAll { nowMs - it > 60_000 }
        if (errorTimes.size >= MAX_ERRORS_PER_MINUTE) {
            suppressed()
            return false
        }
        errorTimes.add(nowMs)
        lastErrorKey = key
        lastErrorMs = nowMs
        return true
    }

    private fun suppressed() {
        suppressedErrors++
        synchronized(healthLock) { suppressedTotal++ }
        scheduleHealth()
    }

    /**
     * Reports, a while after the first drop, that data was dropped: replay
     * segments over the session's budget, errors the flood guard suppressed,
     * replay uploads given up. One `traceway.collection.health` record per
     * delay, which starts at 1 s and doubles to 5 minutes; the session's row
     * learns it once from a `traceway.session.update`. Any thread.
     */
    private fun scheduleHealth() {
        if (isShutdown) return
        synchronized(healthLock) {
            if (healthTimer != null) return
            healthTimer = scheduler.schedule(healthDelayMs) { emitHealth() }
        }
    }

    private fun emitHealth() {
        val updateSession: Boolean
        val suppressedNow: Long
        synchronized(healthLock) {
            healthTimer = null
            updateSession = !healthReported
            healthReported = true
            healthDelayMs = minOf(healthDelayMs * 2, MAX_HEALTH_DELAY_MS)
            suppressedNow = suppressedTotal
        }
        if (isShutdown) return
        synchronized(lock) {
            val session = sessions?.current
            val attrs = LinkedHashMap<String, Any>()
            for ((k, v) in replay.budget.snapshot(session?.id)) attrs[k] = v
            attrs["traceway.error.suppressed_total"] = suppressedNow
            attrs["traceway.context.evicted"] = buffer.evictedCount.toLong()
            attrs["traceway.replay.failed_segments"] = recordings.failedSegments.toLong()
            emit(eventName = Events.COLLECTION_HEALTH, severity = Severity.INFO, attributes = attrs)
            if (session != null && updateSession && session.id in reportedSessions) {
                emit(eventName = Events.SESSION_UPDATE, severity = Severity.INFO, attributes = sessionAttributes(session))
            }
        }
    }

    private fun observe(record: ObservedRecord) {
        if (record.signal == Events.SIGNAL_CONTROL || !control.enabled) return
        // An error or message left out by its sample rate is as if it never happened.
        if (record.routed == Routed.FILTERED && (record.signal == "errors" || record.signal == "messages")) return
        if (record.signal == "navigation" && "navigation" in sequenceKinds) screenObservedForSequences = true
        val fired = triggers.observe(ObservedEvent(timeMs = record.timeMs, lookup = record.lookup))
        val item = record.item
        val live = (item as? TelemetryItem.Log)?.mutable
        val isError = record.signal == "errors" && live != null

        var flushRequested = false
        var replayAvailable = false
        if (isError && live != null) {
            replayAvailable = control.replayMode != Mode.OFF
            val defaultTrigger = control.settings.triggers.firstOrNull { it.id == DEFAULT_EXCEPTION_TRIGGER_ID }
            val replayRequested = fired.any { f -> f.trigger.actions.any { it.replay || it.type == "startReplay" } }
            flushRequested = fired.any { f -> f.trigger.actions.any { it.replay && (it.type == "flush" || it.type == "capture") } }
            val skipped = triggers.skipped[DEFAULT_EXCEPTION_TRIGGER_ID]
            val status = when {
                control.replayMode == Mode.OFF -> "disabled"
                replayRequested -> "requested"
                skipped != null -> skipped
                defaultTrigger?.enabled == false -> "trigger_disabled"
                else -> "not_triggered"
            }
            live.setAttribute(ATTR_REPLAY_STATUS, status)
            // A clip skipped for cooldown or the per-session limit still gets its breadcrumbs: only replay is rationed.
            val contextActions = defaultTrigger?.actions?.filter { it.type == "flush" && !it.replay } ?: emptyList()
            if (skipped == "cooldown" || skipped == "session_clip_limit") {
                for (action in contextActions) runAction(action)
            }
            val lookback = contextActions.maxOfOrNull { it.lookbackSeconds ?: 0.0 } ?: 0.0
            live.setAttribute(ATTR_LOOKBACK, lookback)
            live.setAttribute(ATTR_EVICTED, buffer.evictedCount.toLong())
        }

        val flushesBefore = replayFlushes
        runFired(fired)
        if (isError && live != null && replayAvailable && flushRequested && replayFlushes == flushesBefore) {
            live.setAttribute(ATTR_REPLAY_STATUS, "buffer_unavailable")
        }
        if (fired.isNotEmpty()) router?.sendIfStreaming(record)
        if (record.signal == "errors" || record.signal == "messages") flushSoon()
    }

    // ------------------------------------------------------------ triggers

    private fun runFired(fired: List<FiredTrigger>) {
        for (trigger in fired) runTrigger(trigger)
        if (fired.isNotEmpty()) {
            store.set(
                "triggerLimits",
                mapOf("sessionId" to sessions?.currentId, "limits" to triggers.snapshotSessionLimits()),
            )
        }
        saveTriggerProgress()
    }

    /** Keeps sequences under way across a restart within the session; at most every two seconds. */
    private fun saveTriggerProgress(now: Boolean = false) {
        if (triggers.progressVersion == savedProgressVersion) return
        val nowMs = scheduler.now()
        if (!now && nowMs - lastProgressSaveMs < 2000) {
            if (progressTimer == null) {
                progressTimer = scheduler.schedule(2000) {
                    synchronized(lock) {
                        progressTimer = null
                        saveTriggerProgress(now = true)
                    }
                }
            }
            return
        }
        progressTimer?.cancel()
        progressTimer = null
        lastProgressSaveMs = nowMs
        savedProgressVersion = triggers.progressVersion
        val progress = triggers.snapshotProgress()
        val encoded = Json.stringify(listOf(sessions?.currentId, progress))
        if (encoded == savedProgress) return
        savedProgress = encoded
        store.set("triggerProgress", mapOf("sessionId" to sessions?.currentId, "progress" to progress))
    }

    private fun restoreTriggerProgress(session: String?, only: Set<String>? = null) {
        val saved = store.get("triggerProgress").asJsonObject() ?: return
        if (session != null && saved["sessionId"] == session) triggers.restoreProgress(saved["progress"], null, only)
    }

    private fun runTrigger(fired: FiredTrigger) {
        val exceptionId = fired.cause.lookup("attributes.${Events.ATTR_EXCEPTION_ID}") as? String
        for (action in fired.trigger.actions) runAction(action, exceptionId, fired.trigger.id)
        val wantsReplay = fired.trigger.actions.any { it.replay || it.type == "startReplay" }
        val attrs = LinkedHashMap<String, Any>()
        attrs["traceway.trigger.id"] = fired.trigger.id
        fired.trigger.name?.let { attrs["traceway.trigger.name"] = it }
        // The server lists the sessions a recording trigger asked replay for, with the sequence that did.
        if (wantsReplay && control.replayMode != Mode.OFF) {
            attrs["traceway.trigger.replay"] = "true"
            attrs["traceway.trigger.steps"] = Json.stringify(fired.trigger.stepsJson())
        }
        emit(eventName = Events.TRIGGER_FIRED, severity = Severity.INFO, attributes = attrs)
    }

    private fun selectorSet(signals: List<String>?): Set<String>? =
        if (signals == null || "*" in signals) null else signals.toSet()

    private fun runAction(action: TriggerAction, exceptionId: String? = null, triggerId: String? = null) {
        val nowMs = scheduler.now()
        when (action.type) {
            "flush" -> {
                val lookbackMs = Math.round((action.lookbackSeconds ?: 0.0) * 1000)
                val signals = selectorSet(action.signals)
                router?.flushBuffered(nowMs - lookbackMs, signals, nowMs)
                flushRestored(nowMs - lookbackMs, signals, nowMs)
                if (action.replay && replay.flush(lookbackMs, exceptionId, sync = crashing)) replayFlushes++
                // The file must not hold records that just left, or a crash before the next save would resend them.
                if (persisted != null && !crashing) persistBuffer()
            }
            "stream" -> streamFor(
                action.signals ?: listOf("*"),
                replay = action.replay,
                untilMs = nowMs + Math.round((action.durationSeconds ?: 0.0) * 1000),
            )
            "startReplay" -> streamFor(
                emptyList(),
                replay = true,
                untilMs = nowMs + Math.round((action.durationSeconds ?: 0.0) * 1000),
            )
            "emit" -> {
                if (emitDepth >= MAX_EMIT_DEPTH) return
                emit(eventName = action.eventName, severity = Severity.INFO, attributes = action.attributes ?: emptyMap())
            }
            "capture" -> startLocalCapture(action, exceptionId, triggerId ?: "local")
        }
    }

    /** Uploads what the last run left in the buffer for this session, within the window, through the outbox. */
    private fun flushRestored(sinceMs: Long, signals: Set<String>?, untilMs: Long) {
        val p = persisted ?: return
        val box = outbox ?: return
        val traceway = if (splitsLogs) tracewayOutbox else null
        val bodies = p.take(sinceMs, signals, untilMs, { control.canExport(it) }, splitTracewayLogs = traceway != null)
        if (bodies.isEmpty) return
        fun queue() {
            for (body in bodies.logs) box.add("logs", body)
            for (body in bodies.traces) box.add("traces", body)
            for (body in bodies.tracewayLogs) traceway?.add("logs", body)
        }
        // A crashing process writes them now; otherwise up to a few MiB of disk writes stay off the caller's
        // thread (a tap can fire the trigger) and out of the lock.
        if (crashing) {
            queue()
            return
        }
        scheduler.io {
            queue()
            box.drain()
            traceway?.drain()
        }
    }

    private fun persistBuffer() {
        val p = persisted ?: return
        val (session, entries) = synchronized(lock) {
            if (!control.settings.persist || isShutdown) return
            sessions?.currentId to buffer.snapshot()
        }
        p.save(session, entries)
    }

    private fun streamFor(
        signals: List<String>,
        replay: Boolean,
        untilMs: Long,
        capture: Boolean = false,
        owner: String? = null,
    ) {
        val override = StreamOverride(signals = signals, untilMs = untilMs, replay = replay, includeOff = capture)
        if (owner != null) control.setOverrides(owner, listOf(override)) else control.addOverride(override)
        applyReplay()
        scheduleOverrideExpiry()
    }

    /** Re-applies replay when the next override ends, so a streaming capture returns to buffering on time. */
    private fun scheduleOverrideExpiry() {
        overrideTimer?.cancel()
        overrideTimer = null
        val expiry = control.nextOverrideExpiryMs ?: return
        if (isShutdown) return
        val delay = maxOf(0L, expiry - scheduler.now()) + 1
        overrideTimer = scheduler.schedule(delay) {
            synchronized(lock) {
                overrideTimer = null
                applyReplay()
                scheduleOverrideExpiry()
            }
        }
    }

    private fun applyReplay() {
        val session = sessions?.currentId
        val settings = control.settings
        (screenRecorder as? WireframeRecorder)?.let { recorder ->
            recorder.maskAllInputs = settings.replayMaskAllInputs
            recorder.images?.policy = settings.replayImages
        }
        replay.apply(
            control.replayMode,
            maxAgeMs = settings.bufferMaxAgeMs,
            sessionSampled = session != null && Ids.unitHash(session) < settings.replaySessionSampleRate,
        )
    }

    private fun readLocalCaptures(): MutableList<MutableMap<String, Any?>> {
        val nowMs = scheduler.now()
        val out = ArrayList<MutableMap<String, Any?>>()
        store.get("localCaptures").asJsonList()?.forEach { raw ->
            val c = raw.asJsonObject() ?: return@forEach
            val until = c["untilMs"].asLongExact()
            if (c["id"] is String && c["triggerId"] is String && until != null && until > nowMs) out.add(LinkedHashMap(c))
        }
        return out
    }

    /** Local captures outlive the app run that started them: a restart keeps streaming until they end. */
    private fun restoreLocalCaptures() {
        for (capture in readLocalCaptures()) {
            val signals = capture["signals"].asJsonList()?.filterIsInstance<String>() ?: listOf("*")
            control.setOverrides(
                "local:${capture["triggerId"]}",
                listOf(
                    StreamOverride(
                        signals = signals,
                        untilMs = capture["untilMs"].asLongExact()!!,
                        replay = capture["replay"] == true,
                        includeOff = true,
                    ),
                ),
            )
        }
    }

    /** A capture a trigger started on this device: the lookback goes up once, then the signals stream until it ends. */
    private fun startLocalCapture(action: TriggerAction, exceptionId: String?, triggerId: String) {
        val nowMs = scheduler.now()
        val untilMs = nowMs + Math.round((action.durationSeconds ?: 0.0) * 1000)
        val signals = action.signals ?: listOf("*")
        val captures = readLocalCaptures()
        val running = captures.firstOrNull { it["triggerId"] == triggerId }
        if (running != null) {
            running["untilMs"] = maxOf(running["untilMs"].asLongExact() ?: 0L, untilMs)
        } else {
            val id = "$triggerId:$nowMs"
            captures.add(
                linkedMapOf(
                    "id" to id,
                    "triggerId" to triggerId,
                    "untilMs" to untilMs,
                    "signals" to signals,
                    "replay" to action.replay,
                ),
            )
            runAction(
                TriggerAction(
                    type = "flush",
                    lookbackSeconds = action.lookbackSeconds ?: 60.0,
                    signals = signals,
                    replay = action.replay,
                ),
                exceptionId = exceptionId,
            )
            emit(
                eventName = Events.CAPTURE_STARTED,
                severity = Severity.INFO,
                attributes = mapOf(
                    "traceway.capture.id" to id,
                    "traceway.capture.source" to "trigger",
                    "traceway.trigger.id" to triggerId,
                    "traceway.capture.until" to Rfc3339.format(untilMs),
                ),
            )
        }
        store.set("localCaptures", captures)
        streamFor(
            signals,
            replay = action.replay,
            untilMs = running?.get("untilMs").asLongExact() ?: untilMs,
            capture = true,
            owner = "local:$triggerId",
        )
    }

    private fun confirmCaptures() {
        if (capturesConfirmed || isShutdown) return
        capturesConfirmed = true
        runCaptures(control.settings)
        applyReplay()
    }

    /** Starts the dashboard's captures aimed at this app: the lookback once per capture, then streaming until it ends. */
    private fun runCaptures(settings: Settings) {
        if (!started) return
        val nowMs = scheduler.now()
        control.setOverrides(
            "remote-captures",
            settings.captures.filter { it.untilMs > nowMs }.map { c: Capture ->
                StreamOverride(signals = c.signals ?: listOf("*"), replay = c.replay, untilMs = c.untilMs, includeOff = true)
            },
        )
        scheduleOverrideExpiry()
        if (!capturesConfirmed) return
        val stored = store.get("captures").asJsonList()
        val done = ArrayList<Map<String, Any>>()
        stored?.forEach { raw ->
            val c = raw.asJsonObject() ?: return@forEach
            val id = c["id"] as? String
            val until = c["untilMs"].asLongExact()
            if (id != null && until != null && until > nowMs) done.add(mapOf("id" to id, "untilMs" to until))
        }
        var changed = done.size != (stored?.size ?: 0)
        for (capture in settings.captures) {
            if (capture.untilMs <= nowMs || done.any { it["id"] == capture.id }) continue
            val lookbackSeconds = capture.lookbackSeconds ?: 600.0
            if (lookbackSeconds > 0) {
                runAction(
                    TriggerAction(
                        type = "flush",
                        lookbackSeconds = lookbackSeconds,
                        signals = capture.signals ?: listOf("*"),
                        replay = capture.replay,
                    ),
                )
            }
            emit(
                eventName = Events.CAPTURE_STARTED,
                severity = Severity.INFO,
                attributes = mapOf("traceway.capture.id" to capture.id, "traceway.capture.source" to "remote"),
            )
            done.add(mapOf("id" to capture.id, "untilMs" to capture.untilMs))
            changed = true
        }
        if (changed) store.set("captures", done)
    }

    private fun onSettingsChanged(settings: Settings) {
        runCaptures(settings)
        buffer.retain { control.canExport(it.signal) }
        persisted?.retain { control.canExport(it) }
        if (!settings.persist) persisted?.clear(keepRestored = false)
        buffer.configure(maxBytes = bufferBytes(settings), maxAgeMs = settings.bufferMaxAgeMs)
        // Triggers already running keep their progress: what was saved can be older, and would undo a fire.
        restoreTriggerProgress(sessions?.currentId, only = triggers.setTriggers(settings.triggers))
        val kinds = HashSet<String>()
        for (trigger in triggers.triggers) for (step in trigger.steps) kinds.addAll(eventKinds(step.match))
        sequenceKinds = kinds
        if ("hover" !in kinds) mainHandler?.post { hover.reset() } ?: hover.reset()
        observeScreenForSequences()
        applyReplay()
        metrics.configure()
    }

    /** Triggers that arrive after the app reached its current screen still see that screen once. */
    private fun observeScreenForSequences() {
        val screen = currentScreen
        if (!ready || screenObservedForSequences || screen == null || "navigation" !in sequenceKinds ||
            !control.enabled || sessions?.currentId == null || !options.captureNavigation
        ) {
            return
        }
        screenObservedForSequences = true
        runFired(
            triggers.observe(
                ObservedEvent(
                    timeMs = scheduler.now(),
                    lookup = pageLookup(
                        mapOf("event.name" to Events.NAVIGATION, "signal" to "navigation", "attributes.url.full" to screen),
                    ),
                ),
            ),
        )
    }

    // --------------------------------------------------------------- OpAMP

    private fun describe(): AgentDescription {
        val nonIdentifying = LinkedHashMap<String, Any>()
        for ((key, value) in resourceAttributes) {
            if (key != "service.name" && key != "service.instance.id") nonIdentifying[key] = clip(value)
        }
        for ((key, value) in globalAttributes) if (key.length <= 128) nonIdentifying[key] = clip(value)
        sessions?.currentId?.let { nonIdentifying[Events.ATTR_SESSION_ID] = it }
        return AgentDescription(
            identifying = mapOf("service.name" to serviceName, "service.instance.id" to Ids.bytesToUuid(instanceUid)),
            nonIdentifying = nonIdentifying,
        )
    }

    /** At most the 1024 UTF-8 bytes the server reads of a value; it ignores longer ones. */
    private fun clip(value: String): String {
        if (value.length <= 256) return value
        var bytes = 0
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            val n = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + n > 1024) break
            bytes += n
            out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private fun applyRemoteConfig(config: RemoteConfig): ApplyResult {
        val body = config.configMap["traceway"]?.body?.toString(Charsets.UTF_8) ?: "{}"
        val overlay: JsonObject = try {
            parseSettingsDocument(body)
        } catch (e: SettingsError) {
            return ApplyResult.failed(e.message ?: "invalid settings")
        }
        control.applyRemote(overlay)
        val hash = Ids.bytesToHex(config.configHash)
        store.set("config", mapOf("hash" to hash, "body" to body))
        emit(eventName = Events.CONFIG_APPLIED, severity = Severity.INFO, attributes = mapOf("traceway.config.hash" to hash))
        debugLog("remote config applied ($hash)")
        return ApplyResult.applied()
    }

    /** A targeting attribute or the session changed: let the server re-evaluate its rules soon, without a poll per change. */
    private fun scheduleRefresh() {
        if (opamp == null || refreshTimer != null) return
        refreshTimer = scheduler.schedule(2000) {
            synchronized(lock) { refreshTimer = null }
            opamp?.refresh()
        }
    }

    // ------------------------------------------------------------ metrics

    /** The current screen as a metrics dimension: its route template, or its path with ids replaced. */
    private val metricsScreen: String?
        get() = currentRoute ?: currentScreen?.takeIf { it.isNotEmpty() }?.let { normalizePath(it) }

    private fun postMetrics(body: ByteArray): Int =
        com.tracewayapp.traceway.otel.postOtlp(http, routes.metrics, body)

    // ------------------------------------------------------------ lifecycle

    /** An error should not wait for the batch delay: the app may be about to die. */
    private fun flushSoon() {
        if (flushTimer != null) return
        flushTimer = scheduler.schedule(100) {
            synchronized(lock) { flushTimer = null }
            (tracewayOutbox ?: outbox)?.drain()
            logSink?.forceFlush()
            spanSink?.forceFlush()
        }
    }

    /** The app came to the foreground or left it; on the main thread, from [AppLifecycle]. */
    private fun onLifecycle(foreground: Boolean) {
        if (isShutdown) return
        guarded("lifecycle vitals") { if (options.captureMetrics) vitals?.lifecycleChanged(foreground) }
        if (foreground) {
            guarded("foreground") {
                markActivity()
                opamp?.poll()
                legacyReports?.drain()
                outbox?.drain()
                tracewayOutbox?.drain()
            }
        } else {
            guarded("background") {
                synchronized(lock) {
                    replay.onPaused()
                    if (options.captureMetrics && started) {
                        // Read from /proc, so off the main thread, in order with the other memory peak updates.
                        val session = sessions?.currentId
                        scheduler.disk { memoryPeaks.remember(session) }
                        // Stored before it is sent: the process may not live to see the answer.
                        metrics.flush(final = true)
                    }
                    saveTriggerProgress(now = true)
                }
                scheduler.io {
                    persistBuffer()
                    store.flush()
                }
                (tracewayOutbox ?: outbox)?.drain()
                logSink?.forceFlush()
                spanSink?.forceFlush()
            }
        }
    }

    private fun setScreenRecorder(recorder: ReplaySource?) = synchronized(lock) {
        screenRecorder = recorder
        (recorder as? WireframeRecorder)?.let {
            it.maskAllInputs = control.settings.replayMaskAllInputs
            it.images?.policy = control.settings.replayImages
        }
        replay.attach(recorder)
    }

    private fun debugLog(message: String) {
        if (options.debug) Log.d(TAG, message)
    }

    // ------------------------------------------------------------ testing

    @VisibleForTesting
    internal val settings: Settings get() = synchronized(lock) { control.settings }

    @VisibleForTesting
    internal val bufferedCount: Int get() = synchronized(lock) { buffer.length }

    /** The ring buffer's size in bytes as applied: the settings', at most the device's cap. */
    @VisibleForTesting
    internal val bufferLimitBytes: Long get() = synchronized(lock) { buffer.limitBytes }

    @VisibleForTesting
    internal fun awaitReady(timeoutMs: Long = 5000): Boolean = readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** Uses [source] instead of the wireframe recorder as the origin of clips. */
    @VisibleForTesting
    internal fun setReplaySource(source: ReplaySource?) = setScreenRecorder(source)

    @VisibleForTesting
    internal fun simulateLifecycle(foreground: Boolean) = onLifecycle(foreground)

    /** Runs the uncaught-exception path as the installed handler would, without ending the process. */
    @VisibleForTesting
    internal fun simulateCrash(thread: Thread, error: Throwable) = captureCrash(thread, error)

    companion object {
        private val ATTR_EXCEPTION_TYPE = AttributeKey.stringKey("exception.type")
        private val ATTR_EXCEPTION_MESSAGE = AttributeKey.stringKey("exception.message")
        private val ATTR_SUPPRESSED = AttributeKey.longKey("traceway.error.suppressed_count")
        private val ATTR_UNHANDLED = AttributeKey.booleanKey("exception.unhandled")
        private val ATTR_REPLAY_STATUS = AttributeKey.stringKey(Events.ATTR_REPLAY_STATUS)
        private val ATTR_LOOKBACK = AttributeKey.doubleKey("traceway.context.lookback_seconds")
        private val ATTR_EVICTED = AttributeKey.longKey("traceway.context.evicted")

        @Volatile
        private var current: TracewayClient? = null

        /** The running client, or null before [Traceway.init] (or after [shutdown]). */
        @JvmStatic
        val instance: TracewayClient?
            get() = current

        /**
         * What the SDK needs of the platform and cannot do without, checked
         * before anything is installed: OpenTelemetry's context, which on API
         * 21-23 needs core library desugaring (`java.util.function`). Throws a
         * [LinkageError] when it is missing.
         */
        @VisibleForTesting
        @Volatile
        internal var platformCheck: () -> Unit = { Context.current() }

        /**
         * Starts the SDK, or returns the running client. Without a
         * [connectionString] it runs standalone and sends everything to
         * [TracewayOptions.telemetry]. Nothing is left installed when it
         * fails: [instance] stays null.
         * @throws IllegalArgumentException for an invalid connection string or telemetry setting.
         * @throws IllegalStateException when the platform lacks what the SDK needs.
         */
        internal fun initialize(
            application: Application,
            connectionString: String?,
            options: TracewayOptions,
            overrides: TracewayTestOverrides? = null,
        ): TracewayClient {
            synchronized(this) {
                current?.let { if (!it.isShutdown) return it }
                try {
                    platformCheck()
                } catch (e: LinkageError) {
                    throw IllegalStateException("this device lacks java.util.function; enable core library desugaring ($e)")
                }
                val client = TracewayClient(application, connectionString, options, overrides)
                try {
                    client.instrument(application)
                    val work = Runnable { client.start() }
                    val launch = overrides?.startThread
                    if (launch != null) launch(work) else Thread(work, "TracewayStart").apply { isDaemon = true }.start()
                } catch (e: Throwable) {
                    // Out of memory for a thread at cold start, say: the app runs on without the SDK, and without its hooks.
                    client.abandon()
                    throw e
                }
                current = client
                return client
            }
        }

        /** Builds and starts a client without Android instrumentation, against [overrides]; waits until it is ready. */
        @VisibleForTesting
        internal fun initializeForTest(
            connectionString: String?,
            options: TracewayOptions,
            overrides: TracewayTestOverrides = TracewayTestOverrides(),
        ): TracewayClient {
            resetForTest()
            val client = TracewayClient(null, connectionString, options, overrides)
            synchronized(this) { current = client }
            client.start()
            return client
        }

        @VisibleForTesting
        internal fun resetForTest() {
            val existing = synchronized(this) { current.also { current = null } }
            existing?.shutdown(2000)
        }
    }
}
