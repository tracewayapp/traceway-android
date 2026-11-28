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
import com.tracewayapp.traceway.diagnostics.AppHangs
import com.tracewayapp.traceway.diagnostics.ExitReasons
import com.tracewayapp.traceway.diagnostics.ExitRecord
import com.tracewayapp.traceway.diagnostics.ExitSource
import com.tracewayapp.traceway.diagnostics.Hang
import com.tracewayapp.traceway.interaction.HoverTracker
import com.tracewayapp.traceway.interaction.PointerListener
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
import com.tracewayapp.traceway.internal.ProcessName
import com.tracewayapp.traceway.internal.parseConnectionString
import com.tracewayapp.traceway.metrics.AppVitals
import com.tracewayapp.traceway.metrics.HangWatchdog
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

/** The shortest block [TracewayOptions.appHangThresholdMs] can make an `AppHang`. */
private const val MIN_HANG_THRESHOLD_MS = 250L

/** How long [TracewayClient.flush] waits at most on the main thread, whatever it is asked: below the 5 s of an ANR. */
internal const val MAIN_THREAD_FLUSH_MS = 2_000L

/** The processes whose session ids are kept for their exit reasons, newest last. */
private const val MAX_PROCESS_SESSIONS = 8

/** How long the recovery files of another token or server are kept after their last write: as long as a spooled clip. */
private const val RECOVERY_MAX_AGE_MS = RecordingsTransport.SPOOL_MAX_AGE_MS

/** An issue from an earlier run of the process (an exit reason, a hang it did not survive): no trigger of this run is its. */
private const val ATTR_PREVIOUS_RUN = "traceway.previous_run"

/** The longest log line taken from logcat, as from `System.out`. */
private const val MAX_LOGCAT_CHARS = 16 * 1024

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
    /** Keep state where an app keeps it (`no_backup/traceway_v2`), as without overrides, instead of [stateDirectory]. */
    val appStateDirectory: Boolean = false,
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
    /** The exits of earlier runs, instead of Android's `ApplicationExitInfo`. */
    val exitSource: ExitSource? = null,
    /** The API level the exit records are read for (tombstones from 31). */
    val sdkInt: Int? = null,
    /** This process's id, instead of `Process.myPid()`. */
    val pid: Int? = null,
    /** The main thread's stack a hang reports, instead of the main looper thread's. */
    val mainThreadStack: (() -> Array<StackTraceElement>?)? = null,
    /** Opens one of the app's assets, instead of its `AssetManager`. */
    val readAsset: ((String) -> java.io.InputStream?)? = null,
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

/** A concurrent OTel forceFlush can return the previous drain even after it completed. Wait for a fresh queue snapshot. */
internal fun flushBatchQueue(timeoutMs: Long, request: () -> CompletableResultCode) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    fun remaining() = (deadline - System.nanoTime()).coerceAtLeast(0L)
    val first = request()
    first.join(remaining(), TimeUnit.NANOSECONDS)
    while (remaining() > 0 && !Thread.currentThread().isInterrupted) {
        val next = request()
        if (next !== first) {
            next.join(remaining(), TimeUnit.NANOSECONDS)
            return
        }
        java.util.concurrent.locks.LockSupport.parkNanos(minOf(remaining(), 100_000L))
    }
}

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
    val options: TracewayOptions,
    private val overrides: TracewayTestOverrides?,
    private val legacyReportUrl: String?,
) {

    private val lock = Any()
    private var token: String? = options.token?.trim()?.takeIf { it.isNotEmpty() }
    private val standalone = token == null
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

    /** This process, whose session ids are kept for the exit reasons the next start reads. */
    private val pid: Int = overrides?.pid ?: guarded("pid", 0) { android.os.Process.myPid() }

    /** Main-thread hangs as `AppHang` issues, fed by the hang watchdog; null with `captureAppHangs` off. */
    private val appHangs: AppHangs? by lazy {
        if (!options.captureAppHangs) return@lazy null
        AppHangs(
            thresholdMs = maxOf(MIN_HANG_THRESHOLD_MS, options.appHangThresholdMs),
            directory = recoveryDirectory?.let { File(it, "hangs") },
            pid = pid,
            mainStack = overrides?.mainThreadStack ?: { Looper.getMainLooper()?.thread?.stackTrace },
            screen = { screenContext() },
            sessionId = { liveSessionId },
            now = { scheduler.now() },
            background = { task -> scheduler.disk(task) },
            report = { hang -> reportHang(hang) },
        )
    }

    /** The hang watchdog when metrics are off (with metrics, [AppVitals] runs it). */
    private var hangWatchdog: HangWatchdog? = null

    /** The session ids of earlier processes by pid, as this start found them: their exit reasons are reported in them. */
    private var previousProcessSessions: Map<String, String> = emptyMap()

    /** The console's minimum severity number from the settings: logcat lines below it are dropped before they cost a record. */
    @Volatile
    private var consoleMinSeverity = 0

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

    @Volatile
    private var otelTracer: Tracer? = null
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
        if (!standalone) {
            endpoints = resolveEndpoints(options.baseUrl?.takeIf { it.isNotBlank() } ?: TracewayOptions.DEFAULT_BASE_URL)
        } else {
            require(options.telemetry?.coversAllSignals == true) {
                "TracewayOptions has no token: pass the project token, TracewayOptions(token = \"YOUR_TOKEN\"), " +
                    "or a telemetry destination (a url or all three signal endpoints) to run without Traceway"
            }
        }
        routes = TelemetryRoutes.resolve(endpoints, token, options.telemetry)
        globalAttributes.putAll(copyStrings(options.attributes.orEmpty()))

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
                replayImages = options.replayImages ?: TracewayImagePolicy.ALWAYS,
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
            spoolDirectory = recoveryDirectory?.let { File(it, "recordings") },
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
            dimensions = copyStrings(options.metricDimensions.orEmpty()),
            deferUntilStart = true,
            scheduler = scheduler,
        )
        memoryPeaks = MemoryPeaks(metrics, { store }, storageScope = routes.metrics.key)
        recordings.onFailed = { scheduleHealth() }
        replay.budget.onRejected = { scheduleHealth() }
        control.onChange { onSettingsChanged(it) }
        consoleMinSeverity = settings.signal("console").minSeverity?.let { Events.severityNumberOf(it) } ?: 0
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
            // Any touch is the user being there, whatever it becomes (a scroll, a drag, a long press) and
            // whether taps are recorded: the session stays alive, as in the browser, iOS and Flutter SDKs.
            tracker.addPointerListener(object : PointerListener {
                override fun pointerDown(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean) {
                    if (!isShutdown) markActivity()
                }
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
                val v = AppVitals(
                    metrics,
                    currentScreen = { metricsScreen },
                    scheduler = scheduler,
                    hangListener = appHangs,
                    reportHangsInDebug = options.reportHangsInDebug,
                )
                vitals = v
                v.watchColdStart(sdkStartElapsed)
                v.start(app)
            }
        } else if (appHangs != null) {
            // Without metrics the watchdog runs for the hang issues alone, in the foreground.
            step("hangs") {
                val main = Handler(Looper.getMainLooper())
                val watchdog = HangWatchdog(
                    clock = { android.os.SystemClock.uptimeMillis() },
                    postToMain = { task -> main.post(task) },
                    schedule = { delay, task -> scheduler.schedule(delay, task) },
                    thresholdMs = Long.MAX_VALUE,
                    intervalMs = 500,
                    excused = { !options.reportHangsInDebug && (android.os.Debug.isDebuggerConnected() || android.os.Debug.waitingForDebugger()) },
                    onHang = { },
                    listener = appHangs,
                )
                hangWatchdog = watchdog
                life.addListener(object : AppLifecycle.Listener {
                    override fun onForeground() = watchdog.start()
                    override fun onBackground() = watchdog.stop()
                })
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
        guarded("uninstall hangs") { hangWatchdog?.stop() }
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
            // Recover beta migration files that identify this project.
            scheduler.io { guarded("1.x reports") { reportLegacyReports() } }
            // Clips a crash spooled last run go now, off the start path: reading them can take a while.
            scheduler.io {
                // Files from older SDKs carry no destination or consent information.
                stateDir?.let {
                    File(it, "recordings").deleteRecursively()
                    File(it, "crash.json").delete()
                    File(it, "hangs").deleteRecursively()
                }
                guarded("recovery expiry") { pruneOtherRecoveries() }
                if (!isShutdown) recordings.loadSpooled { synchronized(lock) { recordsScreen && control.replayMode != Mode.OFF } }
            }
            // How the earlier runs ended: a binder call and trace files, so off the start path too.
            scheduler.io { guarded("previous runs") { reportPreviousRuns() } }
        }
    }

    private fun startUnguarded() {
        val s = StateStore(stateDir, DiskScheduler(scheduler), destination = routes.traceway?.key ?: bufferDestination)
        s.load()
        // Read before this process's session is added: a pid can be used again.
        val processSessions = LinkedHashMap<String, String>()
        s.get(KEY_PROCESS_SESSIONS).asJsonObject()?.forEach { (k, v) -> if (v is String) processSessions[k] = v }
        synchronized(lock) {
            store = s
            previousProcessSessions = processSessions
        }
        replay.budget.store = s

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
        if (options.debug && synchronized(lock) { control.settings.signal("console").mode } == Mode.BUFFER) {
            debugLog(
                "console, network, navigation, userActions and custom records are kept on the device and sent with an " +
                    "error or a capture; recordAllSessions = true streams them",
            )
        }
    }

    /**
     * Where state is kept: `no_backup/traceway_v2` (`traceway_v2-sync` in a
     * `:sync` process run with `allProcesses`). Only the path: it is made
     * by the first write, on the SDK's threads, so `Traceway.init` touches
     * no disk on the main thread (`noBackupFilesDir` would create it).
     */
    private fun stateDirectory(): File? {
        overrides?.let { if (!it.appStateDirectory) return it.stateDirectory }
        if (!options.persistToDisk) return null
        val app = application ?: return null
        return try {
            val name = ProcessName.stateDirectoryName(app, "traceway_v2")
            val dataDir = app.applicationInfo?.dataDir
            if (dataDir != null) File(File(dataDir, "no_backup"), name) else File(app.noBackupFilesDir ?: app.filesDir, name)
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
        attrs["service.version"] = options.version?.takeIf { it.isNotEmpty() } ?: appVersionName() ?: "unknown"
        attrs["service.instance.id"] = Ids.bytesToUuid(instanceUid)
        attrs["telemetry.sdk.name"] = "opentelemetry"
        attrs["telemetry.sdk.language"] = "java"
        attrs["telemetry.sdk.version"] = SdkInfo.OTEL_VERSION
        attrs["traceway.sdk.name"] = SdkInfo.NAME
        attrs["traceway.sdk.version"] = SdkInfo.VERSION
        options.environment?.takeIf { it.isNotEmpty() }?.let { attrs["deployment.environment.name"] = it }
        val proguardUuid = options.proguardUuid?.takeIf { it.isNotEmpty() } ?: pluginProguardUuid()
        if (proguardUuid != null) {
            attrs["app.debug.proguard_uuid"] = proguardUuid
        } else if (minified()) {
            Log.w(
                TAG,
                "Traceway: this build is minified but has no ProGuard UUID, so its crashes arrive obfuscated. " +
                    "Apply the com.tracewayapp.symbols Gradle plugin, which uploads mapping.txt and sets it",
            )
        }
        // The numbers of the SDK's performance events arrive as metrics: the server must not count them twice.
        if (options.captureMetrics) attrs["traceway.metrics"] = "aggregated"
        synchronized(lock) {
            resourceAttributes.clear()
            resourceAttributes.putAll(attrs)
        }
    }

    private val tracewayAuth: Map<String, String>
        get() = token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

    private val recoveryDirectory: File?
        get() = stateDir?.let { File(it, "recovery/${routes.traceway?.key ?: routes.logs.key}") }

    /**
     * Removes what was kept for another token or server (a crash before the
     * start, hangs, spooled replay clips) once nothing in it was written for
     * [RECOVERY_MAX_AGE_MS]: it waits that long for its own destination to
     * come back, and is never sent anywhere else. On the I/O pool.
     */
    private fun pruneOtherRecoveries() {
        val own = recoveryDirectory ?: return
        val cutoff = scheduler.now() - RECOVERY_MAX_AGE_MS
        for (dir in own.parentFile?.listFiles() ?: return) {
            if (!dir.isDirectory || dir.name == own.name) continue
            val newest = dir.walkTopDown().maxOfOrNull { it.lastModified() } ?: 0L
            if (newest < cutoff) dir.deleteRecursively()
        }
    }

    // Accessed under the client lock, only while emitting a disk-backed previous-run issue.
    private val recoveringIssues = HashMap<String, File>()

    private val bufferDestination: String
        get() = destinationDigest(listOf(routes.traceway?.key ?: "", routes.logs.key, routes.traces.key, routes.metrics.key))

    private val serviceName: String
        get() = options.serviceName?.takeIf { it.isNotEmpty() } ?: application?.packageName ?: "android-app"

    /** The app's `versionName`, the default `service.version`; on the start thread. */
    private fun appVersionName(): String? = guarded("versionName", null) {
        val app = application ?: return@guarded null
        @Suppress("DEPRECATION")
        app.packageManager?.getPackageInfo(app.packageName, 0)?.versionName?.takeIf { it.isNotEmpty() }
    }

    /**
     * The ProGuard UUID the `com.tracewayapp.symbols` Gradle plugin wrote
     * into the app's assets ([PROGUARD_UUID_ASSET]), when the app did not
     * pass one; on the start thread.
     */
    private fun pluginProguardUuid(): String? = guarded("proguard uuid", null) {
        try {
            val open = overrides?.readAsset
            val stream = if (open != null) open(PROGUARD_UUID_ASSET) else application?.assets?.open(PROGUARD_UUID_ASSET)
            (stream ?: return@guarded null).use { input ->
                input.readBytes().toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() && it.length <= 64 && '\n' !in it }
            }
        } catch (_: java.io.IOException) {
            null
        }
    }

    /** Whether R8 renamed this SDK's classes, as it does in a minified build that keeps no rule for them. */
    private fun minified(): Boolean = application != null && TracewayClient::class.java.name != "com.tracewayapp.traceway.TracewayClient"

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
            persistIssue = { data, onItsWay ->
                val id = data.attributes.get(AttributeKey.stringKey(Events.ATTR_EXCEPTION_ID))
                val source = recoveringIssues[id]
                when {
                    source != null -> {
                        if (overrides?.logExporter != null) {
                            val sent = lx.export(listOf(data))
                            sent.whenComplete { if (sent.isSuccess) source.delete() }
                        } else if (issueBox.persistLog(data)) {
                            source.delete()
                            issueBox.drain()
                        }
                        onItsWay()
                        true // On failure the source file remains available for the next start.
                    }
                    stateDir == null || overrides?.logExporter != null -> false
                    crashing -> issueBox.persistLog(data).also { if (it) onItsWay() }
                    else -> {
                        // Off the caller's thread and the lock: the caller may be the main thread.
                        scheduler.disk {
                            try {
                                if (issueBox.persistLog(data)) issueBox.drain() else lx.export(listOf(data))
                            } finally {
                                onItsWay()
                            }
                        }
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
        otelTracer = tp.tracerBuilder(SdkInfo.NAME).setInstrumentationVersion(SdkInfo.VERSION).build()
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
     * The SDK's OpenTelemetry tracer, for spans of the app's own: they go
     * through Traceway's exporter and collection settings (the `network`
     * signal), carry the session id (`session.id`), and are children of the
     * interaction whose [TracewayInteraction.run] they start in (the current
     * OpenTelemetry context's span). Null until the SDK has started (just
     * after [Traceway.init]) and after [shutdown]. The SDK never registers
     * it as the global OpenTelemetry (`GlobalOpenTelemetry`): the app's own
     * setup, if it has one, stays as it is.
     */
    val tracer: Tracer? get() = if (isShutdown) null else otelTracer

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
        if (guarded("ignoreErrors", false) { ignored(captured.line) }) {
            if (options.debug) debugLog("dropped ${captured.line}, which matches ${ignoredBy(captured.line)}")
            return false
        }
        val before = options.beforeCapture ?: return true
        val kept = try {
            before(captured)
        } catch (e: Throwable) {
            debugLog("beforeCapture threw, the record is kept: $e")
            true
        }
        if (!kept) debugLog("dropped ${captured.line} by beforeCapture")
        return kept
    }

    /** Which of the ignoreErrors patterns drops [line], for the debug log. */
    private fun ignoredBy(line: String): String = guarded("ignoredBy", "ignoreErrors") {
        val own = options.ignoreErrors
        val patterns = own ?: TracewayOptions.DEFAULT_IGNORE_ERRORS
        val hit = patterns.firstOrNull { ignoreErrorsMatch(listOf(it), line) }
        val shown = when (hit) {
            is Regex -> "/${hit.pattern}/"
            is java.util.regex.Pattern -> "/${hit.pattern()}/"
            else -> "\"$hit\""
        }
        if (own == null) {
            "ignoreErrors $shown (DEFAULT_IGNORE_ERRORS); set ignoreErrors = emptyList() to report it"
        } else {
            "ignoreErrors $shown"
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

    /**
     * A call to `android.util.Log` the Gradle plugin rewrote to
     * [TracewayLogcat]: a console record with `log.source = "logcat"` and
     * the tag as `log.tag`, the throwable's stack after the message as
     * logcat prints it. Lines below the console's minimum severity are
     * dropped here, before they cost a record.
     */
    internal fun logcatLine(priority: Int, tag: String?, message: String?, error: Throwable?) {
        if (!options.captureLogs || isShutdown) return
        val level = logcatLevel(priority)
        if (level.severity.severityNumber < consoleMinSeverity) return
        guarded("logcat line") {
            var body = message ?: ""
            if (error != null) {
                val stack = guarded("logcat stack", "") { safeStackTrace(error) }
                body = if (body.isEmpty()) stack else "$body\n$stack"
            }
            if (body.isEmpty()) return
            if (body.length > MAX_LOGCAT_CHARS) body = body.substring(0, MAX_LOGCAT_CHARS)
            val attrs = LinkedHashMap<String, Any>(2)
            attrs["log.source"] = "logcat"
            if (!tag.isNullOrEmpty()) attrs["log.tag"] = tag
            consoleLine(body, level, attrs)
        }
    }

    private fun consoleLine(message: String, level: TracewayLogLevel, attributes: Map<String, Any>) {
        // The SDK's own output is not app telemetry, nor is a line printed while a record is emitted.
        if (message.startsWith("Traceway: ") || (emitDepth > 0 && Thread.holdsLock(lock))) return
        emit(severity = level.severity, severityText = level.severityText, body = message, attributes = attributes)
    }

    /**
     * Reports the screen the user arrived at, as a path (`/checkout`,
     * `/orders/8841`), with its route template [route] (`/orders/:id`) when
     * the router knows it (the same as calling [setRoute] right after).
     * Activities and AndroidX fragments are reported automatically, Compose
     * Navigation with `Traceway.trackNavigation`; call this from another
     * router.
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
        val t = otelTracer
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
        endpoints?.isOwn(url) == true || routes.isOwn(url)

    private fun propagates(url: String): Boolean =
        options.propagateTraceHeaderUrls.orEmpty().any { it != null && url.startsWith(it) } ||
            options.propagateTraceHeaderUrlPatterns.orEmpty().any { it != null && it.containsMatchIn(url) }

    /**
     * Starts the client span of an HTTP request, or returns null when it is
     * not recorded (network capture off, an SDK request). Used by
     * [com.tracewayapp.traceway.network.TracewayOkHttpInterceptor]; other
     * HTTP stacks can call it too, then [endRequest].
     */
    @JvmOverloads
    fun startRequest(method: String?, url: String?, hasTraceparent: Boolean = false): RequestHandle? {
        if (method == null || url == null) return null
        return guarded("startRequest", null) { requestStarted(method, url, hasTraceparent) }
    }

    private fun requestStarted(method: String, url: String, hasTraceparent: Boolean): RequestHandle? {
        if (!options.captureNetwork || isShutdown || isOwnRequest(url)) return null
        // Before the SDK is ready there is no tracer yet: the request is still timed, and recorded once it is.
        val t = otelTracer ?: return RequestHandle(null, method.uppercase(), url, scheduler.now(), null)
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
        otelTracer?.spanBuilder(handle.method)
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
     * still held in the ring buffer stays there. Blocks the calling thread:
     * on the main thread for [MAIN_THREAD_FLUSH_MS] (2 s) at most, whatever
     * [timeoutMs] says, so that it cannot cause an ANR.
     */
    @JvmOverloads
    fun flush(timeoutMs: Long = 10_000) {
        // After shutdown nothing is sent any more: there is nothing to wait for.
        if (isShutdown) return
        // On the main thread a long wait would be an ANR (5 s): what is not sent by then goes later.
        guarded("flush") { flushUnguarded(if (isMainThread()) minOf(timeoutMs, MAIN_THREAD_FLUSH_MS) else timeoutMs) }
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
            logSink?.let { flushBatchQueue(left(), it::forceFlush) }
            spanSink?.let { flushBatchQueue(left(), it::forceFlush) }
            router?.settle(left())
            guarded("flush logs") { logExporter?.flush()?.join(left(), TimeUnit.MILLISECONDS) }
            guarded("flush spans") { spanExporter?.flush()?.join(left(), TimeUnit.MILLISECONDS) }
            replay.drainBlocking(left())
            logSink?.let { flushBatchQueue(left(), it::forceFlush) }
            guarded("flush logs") { logExporter?.flush()?.join(left(), TimeUnit.MILLISECONDS) }
            metrics.flush()
            metrics.awaitUploads(left())
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

    @Volatile
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
            // The crash is how this run ends: a block that led to it, or the time its handlers take, is not a
            // fatal hang as well.
            appHangs?.crashed()
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

    private val crashFile: File? get() = recoveryDirectory?.let { File(it, "crash.json") }

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
            if (record == null) {
                file.delete()
                return
            }
            val attrs = LinkedHashMap<String, Any>()
            attrs["exception.type"] = record["type"] as? String ?: "java.lang.Throwable"
            attrs["exception.message"] = record["message"] as? String ?: ""
            attrs["exception.stacktrace"] = record["stacktrace"] as? String ?: ""
            attrs["exception.unhandled"] = true
            attrs["thread.name"] = record["thread"] as? String ?: ""
            attrs["traceway.crash.before_start"] = true
            emitRecovered(
                file, attrs,
                eventName = Events.EXCEPTION,
                severity = Severity.FATAL,
                severityText = "FATAL",
                timeMs = record["timeMs"].asLongExact(),
            )
            if (options.captureMetrics) metrics.crash()
        } catch (_: Throwable) {
        }
    }

    /**
     * Migrate the old reports claimed by this project after a durable handoff.
     * Runs on the I/O pool once per start.
     */
    private fun reportLegacyReports() {
        val directory = overrides?.legacyDirectory
            ?: application?.let { LegacyReports.directoryIn(it.noBackupFilesDir ?: it.filesDir) }
        if (directory == null || isShutdown) return
        val reportUrl = endpoints?.base
        val keys = if (reportUrl == null || token == null) emptySet() else {
            val urls = listOf("", "/", "/api", "/api/", "/api/report", "/api/report/").map { reportUrl + it }
            (urls + listOfNotNull(legacyReportUrl)).map { OtlpDestination(it, tracewayAuth).key }.toSet()
        }
        for (report in LegacyReports.pending(directory, keys, scheduler.now())) {
            guarded("1.x report") {
                val attrs = LinkedHashMap<String, Any>()
                for ((k, v) in report.attributes) attrs[k] = v
                if (report.isMessage) {
                    val captured = TracewayCapturedError(null, null, report.message, true, readOnly(attrs), null)
                    if (!reports(captured)) {
                        report.file.delete()
                        return@guarded
                    }
                    emitRecovered(report.file, attrs, eventName = Events.MESSAGE, severity = Severity.INFO, severityText = "INFO", body = report.message, timeMs = report.timeMs)
                } else {
                    attrs["exception.type"] = report.type
                    attrs["exception.message"] = report.message
                    attrs["exception.stacktrace"] = report.stackTrace
                    val captured = TracewayCapturedError(null, report.type, report.message, false, readOnly(attrs), report.stackTrace)
                    if (!reports(captured)) {
                        report.file.delete()
                        return@guarded
                    }
                    emitRecovered(report.file, attrs, eventName = Events.EXCEPTION, severity = Severity.ERROR, severityText = "ERROR", timeMs = report.timeMs)
                }
            }
        }
    }

    private fun emitRecovered(
        file: File, attrs: MutableMap<String, Any>, eventName: String, severity: Severity,
        severityText: String, timeMs: Long?, body: String? = null,
    ) {
        synchronized(lock) {
            if (isShutdown || logger == null) return
            val signal = Events.classifyLog(eventName)
            if (control.modeFor(signal) == Mode.BUFFER) return // The source file already holds this issue.
            val id = java.util.UUID.nameUUIDFromBytes((file.absolutePath + ":" + timeMs).toByteArray()).toString()
            attrs[ATTR_PREVIOUS_RUN] = true
            attrs[Events.ATTR_EXCEPTION_ID] = id
            recoveringIssues[id] = file
            try {
                emit(eventName, severity, severityText, body, attrs, timeMs, Context.root())
            } finally {
                recoveringIssues.remove(id)
            }
        }
    }

    // ------------------------------------------------------- hangs and exits

    /** A hang that ended: an `AppHang` issue of this run, filtered like an exception. On the disk thread. */
    private fun reportHang(hang: Hang) {
        if (isShutdown) return
        val attrs = hangAttributes(hang, fatal = false)
        if (!reports(capturedHang(hang, attrs))) return
        attrs[Events.ATTR_EXCEPTION_ID] = Ids.uuidv4()
        emit(eventName = Events.EXCEPTION, severity = Severity.ERROR, severityText = "ERROR", attributes = attrs)
    }

    /** [hang] as `ignoreErrors` and `beforeCapture` see it. */
    private fun capturedHang(hang: Hang, attrs: Map<String, Any>) = TracewayCapturedError(
        error = null,
        type = Hang.TYPE,
        message = hang.message,
        isMessage = false,
        attributes = readOnly(attrs),
        stackTrace = attrs["exception.stacktrace"] as String,
    )

    private fun hangAttributes(hang: Hang, fatal: Boolean): LinkedHashMap<String, Any> {
        val attrs = LinkedHashMap<String, Any>(hang.screen)
        attrs["exception.type"] = Hang.TYPE
        attrs["exception.message"] = hang.message
        attrs["exception.stacktrace"] = hang.stackTrace()
        attrs["thread.name"] = "main"
        attrs["app.hang.duration_ms"] = hang.blockedMs
        attrs["app.hang.fatal"] = fatal
        return attrs
    }

    /** Keeps this process's session for the exit reason the next start may find. Under the lock. */
    private fun rememberProcessSession(sessionId: String) {
        val key = pid.toString()
        val known = LinkedHashMap<String, Any?>()
        store.get(KEY_PROCESS_SESSIONS).asJsonObject()?.let { known.putAll(it) }
        if (known[key] == sessionId) return
        known.remove(key)
        known[key] = sessionId
        while (known.size > MAX_PROCESS_SESSIONS) known.remove(known.keys.first())
        store.set(KEY_PROCESS_SESSIONS, known)
    }

    /**
     * How the earlier runs of the process ended (CONTRACT §14.1, §14.2),
     * once per start on the I/O pool: the exits Android recorded since the
     * last one seen (a native crash and an ANR as issues, a low-memory kill
     * as `app.oom`), and the hangs a run did not survive, unless Android
     * recorded that run's end as an ANR or as a crash: the ANR or the crash
     * is the issue then. A fatal hang passes `ignoreErrors` and
     * `beforeCapture` like a hang that ended.
     */
    private fun reportPreviousRuns() {
        if (isShutdown) return
        val hangs = appHangs?.takePrevious() ?: emptyList()
        val unseen = if (options.captureExitReasons) newExits() else emptyList()
        val exits = unseen.filter { ExitReasons.isReportable(it.reason) }
            .sortedByDescending { it.timestampMs }
            .take(ExitReasons.MAX_REPORTS)
            .sortedBy { it.timestampMs }
        val sdkInt = overrides?.sdkInt ?: android.os.Build.VERSION.SDK_INT
        val sessionsByPid = synchronized(lock) { previousProcessSessions }
        val answered = HashSet<Hang>()
        // A run that died of a crash has its issue: from the crash handler, or the native crash below.
        for (exit in unseen) {
            if (exit.reason != ExitReasons.REASON_CRASH && exit.reason != ExitReasons.REASON_CRASH_NATIVE) continue
            hangs.filterTo(answered) { it.pid == exit.pid && it.startedAtMs <= exit.timestampMs }
        }
        for (exit in exits) {
            guarded("exit reason") {
                when (exit.reason) {
                    ExitReasons.REASON_LOW_MEMORY -> if (options.captureMetrics) metrics.oom()
                    ExitReasons.REASON_CRASH_NATIVE, ExitReasons.REASON_ANR -> {
                        val hang = if (exit.reason == ExitReasons.REASON_ANR) {
                            hangs.lastOrNull { it.pid == exit.pid && it.startedAtMs <= exit.timestampMs }
                        } else {
                            null
                        }
                        val issue = ExitReasons.issueFor(exit, sdkInt, fallbackFrames = hang?.frames) ?: return@guarded
                        if (hang != null) answered.add(hang)
                        val attrs = LinkedHashMap<String, Any>()
                        if (hang != null) attrs.putAll(hang.screen)
                        attrs["exception.type"] = issue.type
                        attrs["exception.message"] = issue.message
                        attrs["exception.stacktrace"] = issue.stackTrace
                        issue.threadName?.let { attrs["thread.name"] = it }
                        attrs["app.exit.reason"] = issue.reason
                        exit.description?.takeIf { it.isNotBlank() }?.let { attrs["app.exit.description"] = it.take(1024) }
                        if (exit.pid > 0) attrs["process.pid"] = exit.pid.toLong()
                        emitPreviousRun(attrs, exit.timestampMs, (sessionsByPid[exit.pid.toString()] ?: hang?.sessionId))
                        if (options.captureMetrics) {
                            if (exit.reason == ExitReasons.REASON_ANR) metrics.anr() else metrics.crash()
                        }
                    }
                }
            }
        }
        for (hang in hangs) {
            if (hang in answered) continue
            guarded("fatal hang") {
                val attrs = hangAttributes(hang, fatal = true)
                if (!reports(capturedHang(hang, attrs))) return@guarded
                emitPreviousRun(attrs, hang.startedAtMs + hang.blockedMs, hang.sessionId ?: sessionsByPid[hang.pid.toString()])
            }
        }
    }

    /** The exits newer than the newest one seen before, of any reason; the newest is remembered. None on a first start. */
    private fun newExits(): List<ExitRecord> {
        val source = overrides?.exitSource ?: if (overrides == null) ExitReasons.sourceFor(application) else null
        if (source == null) return emptyList()
        val all = guarded("exit reasons", emptyList()) { source.read() }
        val seen = synchronized(lock) { store.get(KEY_EXITS_SEEN).asLongExact() }
        // With nothing recorded yet, any exit found later is newer than this start.
        val newest = all.maxOfOrNull { it.timestampMs } ?: 0L
        if (seen == null || newest > seen) synchronized(lock) { store.set(KEY_EXITS_SEEN, maxOf(newest, seen ?: 0L)) }
        // The first start keeps no state yet: what Android kept is from before the SDK, or from an earlier install.
        if (seen == null) return emptyList()
        return all.filter { it.timestampMs > seen }
    }

    /** An issue of an earlier run, in its own session, with no trigger of this run's. */
    private fun emitPreviousRun(attrs: LinkedHashMap<String, Any>, timeMs: Long, sessionId: String?) {
        attrs["exception.unhandled"] = true
        attrs[ATTR_PREVIOUS_RUN] = true
        if (sessionId != null) attrs[Events.ATTR_SESSION_ID] = sessionId
        attrs[Events.ATTR_EXCEPTION_ID] = Ids.uuidv4()
        emit(eventName = Events.EXCEPTION, severity = Severity.FATAL, severityText = "FATAL", timeMs = timeMs, attributes = attrs, context = Context.root())
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
        if (options.captureExitReasons || options.captureAppHangs) rememberProcessSession(session.id)
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
        if (signal == "errors" && record.getAttribute(ATTR_UNHANDLED) != true && record.getAttribute(ATTR_PREVIOUS_RUN_KEY) != true) {
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
        if (record.routed == Routed.FILTERED || record.routed == Routed.OFF) {
            val log = (record.item as? TelemetryItem.Log)?.mutable
            val id = log?.getAttribute(AttributeKey.stringKey(Events.ATTR_EXCEPTION_ID))
            recoveringIssues[id]?.delete()
        }
        if (record.signal == Events.SIGNAL_CONTROL || !control.enabled) return
        // An error or message left out by its sample rate is as if it never happened.
        if (record.routed == Routed.FILTERED && (record.signal == "errors" || record.signal == "messages")) return
        val previousRun = (record.item as? TelemetryItem.Log)?.mutable
        if (previousRun != null && previousRun.getAttribute(ATTR_PREVIOUS_RUN_KEY) == true) {
            // An issue of an earlier process: this run's breadcrumbs and replay are not its context.
            previousRun.setAttribute(ATTR_REPLAY_STATUS, "buffer_unavailable")
            flushSoon()
            return
        }
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
        // The recorder first: when a setting became stricter the controller has it capture again, masked already.
        (screenRecorder as? WireframeRecorder)?.let { recorder ->
            recorder.maskAllInputs = settings.replayMaskAllInputs
            recorder.images?.policy = settings.replayImages
        }
        replay.apply(
            control.replayMode,
            maxAgeMs = settings.bufferMaxAgeMs,
            sessionSampled = session != null && Ids.unitHash(session) < settings.replaySessionSampleRate,
            maskAllInputs = settings.replayMaskAllInputs,
            images = settings.replayImages,
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
        consoleMinSeverity = settings.signal("console").minSeverity?.let { Events.severityNumberOf(it) } ?: 0
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

    /** What the hang watchdog feeds: a test plays a hang through it. */
    @VisibleForTesting
    internal val hangsForTest: AppHangs? get() = appHangs

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
        private val ATTR_PREVIOUS_RUN_KEY = AttributeKey.booleanKey(ATTR_PREVIOUS_RUN)
        private const val KEY_PROCESS_SESSIONS = "processSessions"
        private const val KEY_EXITS_SEEN = "exitReasonsSeenMs"

        /** Where the Gradle plugin puts the build's ProGuard UUID in the app's assets. */
        internal const val PROGUARD_UUID_ASSET = "traceway/proguard-uuid.txt"

        @Volatile
        private var current: TracewayClient? = null

        /** Whether a client was ever started in this process: a call after [shutdown] is the app's choice, not a missing init. */
        @Volatile
        internal var everStarted = false

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
         * Starts the SDK, or returns the running client; one that is shutting
         * down is not running any more. Without a
         * [TracewayOptions.token] it runs standalone and sends everything to
         * [TracewayOptions.telemetry]. Nothing is left installed when it
         * fails: [instance] stays null.
         * @throws IllegalArgumentException for an invalid server URL or telemetry setting, or no token and no telemetry.
         * @throws IllegalStateException when the platform lacks what the SDK needs.
         */
        internal fun initialize(
            application: Application,
            options: TracewayOptions,
            overrides: TracewayTestOverrides? = null,
            legacyReportUrl: String? = options.baseUrl,
        ): TracewayClient {
            synchronized(this) {
                current?.let { stopping ->
                    if (!stopping.isShutdown && !stopping.shuttingDown) return stopping
                    // A shutdown still flushing on another thread: this start is a new client, as after it. The old
                    // one's hooks go now, so that a tap or a crash meanwhile is recorded once, by the new client.
                    if (!stopping.isShutdown) stopping.uninstallHooks()
                }
                try {
                    platformCheck()
                } catch (e: LinkageError) {
                    throw IllegalStateException("this device lacks java.util.function; enable core library desugaring ($e)")
                }
                val client = TracewayClient(application, options, overrides, legacyReportUrl)
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
                everStarted = true
                return client
            }
        }

        /**
         * Builds and starts a client without Android instrumentation, against [overrides]; waits until it is ready.
         * A [connectionString] (`token@https://host`) sets the options' token and server.
         */
        @VisibleForTesting
        internal fun initializeForTest(
            connectionString: String?,
            options: TracewayOptions,
            overrides: TracewayTestOverrides = TracewayTestOverrides(),
        ): TracewayClient {
            resetForTest()
            val resolved = connectionString?.let { parseConnectionString(it) }
            val o = if (resolved == null) options else options.copy(token = resolved.token, baseUrl = resolved.baseUrl)
            val client = TracewayClient(null, o, overrides, resolved?.originalUrl ?: options.baseUrl)
            synchronized(this) {
                current = client
                everStarted = true
            }
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
