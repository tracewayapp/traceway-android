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
    private val logLineSink: (String, String) -> Unit = { line, severity -> recordLog(line, severity) }
    private var recorderCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var lifecycle: AppLifecycle? = null
    private var touchTracker: TouchTracker? = null

    init {
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
        globalAttributes.putAll(options.attributes)

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
            ),
            now = scheduler::now,
        )
        val settings = control.settings
        buffer = RingBuffer(settings.bufferMaxBytes, settings.bufferMaxAgeMs, scheduler::now)
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
            dimensions = options.metricDimensions,
            deferUntilStart = true,
            scheduler = scheduler,
        )
        memoryPeaks = MemoryPeaks(metrics, { store }, storageScope = routes.metrics.key)
        control.onChange { onSettingsChanged(it) }
    }

    val debug: Boolean get() = options.debug

    /** Whether the screen is recorded: screen capture is on and there is a Traceway to upload it to. */
    private val recordsScreen: Boolean get() = options.screenCapture && !standalone

    private val remoteConfig: Boolean get() = options.remoteConfig && !standalone

    // ------------------------------------------------------------- startup

    /** Installs the Android instrumentation; on the main thread, from [Traceway.init]. */
    internal fun instrument(app: Application) {
        val life = AppLifecycle(app)
        lifecycle = life
        life.addListener(object : AppLifecycle.Listener {
            override fun onForeground() = onLifecycle(foreground = true)
            override fun onBackground() = onLifecycle(foreground = false)
        })
        if (options.captureNavigation) {
            life.addListener(NavigationCapture { path, action, from -> recordNavigation(path, action, from) })
        }
        if (options.captureLogs) LogCapture.install(logLineSink)
        ErrorHandler(onCrash = { thread, error -> captureCrash(thread, error) }).install()

        val tracker = TouchTracker()
        touchTracker = tracker
        tracker.addTapListener(object : TapListener {
            override fun onTap(root: View, xPx: Float, yPx: Float, xDp: Float, yDp: Float) = recordTap(root, xPx, yPx, xDp, yDp)
            override fun onHover(root: View, xPx: Float, yPx: Float, xDp: Float, yDp: Float) = recordHover(root, xPx, yPx)
        })
        tracker.install(app)

        if (recordsScreen) {
            val recorder = WireframeRecorder.create(
                app,
                maxFrames = options.maxBufferFrames,
                fps = options.fps.coerceIn(1, 30),
                debug = options.debug,
                clock = scheduler::now,
            )
            recorder.screenName = { currentScreen }
            // The recorder follows the activities and the foreground itself.
            recorderCallbacks = recorder.attach(app)
            tracker.addPointerListener(recorder)
            setScreenRecorder(recorder)
        }
        if (options.captureMetrics) {
            val v = AppVitals(metrics, currentScreen = { metricsScreen }, scheduler = scheduler)
            vitals = v
            v.watchColdStart(sdkStartElapsed)
            v.start(app)
        }
        life.install()
    }

    private fun start() {
        try {
            startUnguarded()
        } catch (e: Throwable) {
            log("failed to start: $e")
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
                log("invalid 1.x connection string; legacy reports will expire on the device")
            }
            legacyReports = LegacyReports(legacyDirectory, target, http, scheduler).also { it.drain() }
        }

        val storedUid = s.get("instanceUid") as? String
        val uid = storedUid?.let { Ids.uuidToBytes(it) }
        if (uid != null) {
            instanceUid = uid
        } else {
            instanceUid = Ids.uuidToBytes(Ids.uuidv7())!!
            s.set("instanceUid", Ids.bytesToUuid(instanceUid))
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
                p.load(sessions?.currentId, maxAgeMs = settings.bufferMaxAgeMs, maxBytes = settings.bufferMaxBytes)
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
                onStopped = { code -> log("remote config stopped (HTTP $code)") },
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
        log("started, instance ${Ids.bytesToUuid(instanceUid)}")
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

    /** The id this installation reports to the server (`service.instance.id`), usable to target it from the dashboard. */
    fun getInstanceId(): String = synchronized(lock) { Ids.bytesToUuid(instanceUid) }

    /** The current session, or null before the SDK started. */
    val currentSessionId: String? get() = synchronized(lock) { sessions?.currentId }

    /** Reports a caught exception. */
    @JvmOverloads
    fun captureException(error: Throwable, attributes: Map<String, String>? = null) {
        emit(
            eventName = Events.EXCEPTION,
            severity = Severity.ERROR,
            severityText = "ERROR",
            attributes = exceptionAttributes(error, attributes),
        )
    }

    private fun exceptionAttributes(error: Throwable, attributes: Map<String, String>?): Map<String, Any> {
        val attrs = LinkedHashMap<String, Any>()
        attributes?.let { attrs.putAll(it) }
        attrs["exception.type"] = error.javaClass.name
        attrs["exception.message"] = safeMessage(error) ?: ""
        attrs["exception.stacktrace"] = safeStackTrace(error)
        attrs[Events.ATTR_EXCEPTION_ID] = Ids.uuidv4()
        return attrs
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

    /** Reports a free-form message as an issue. */
    @JvmOverloads
    fun captureMessage(message: String, attributes: Map<String, String>? = null) {
        val attrs = LinkedHashMap<String, Any>()
        attributes?.let { attrs.putAll(it) }
        attrs[Events.ATTR_EXCEPTION_ID] = Ids.uuidv4()
        emit(eventName = Events.MESSAGE, severity = Severity.INFO, severityText = "INFO", body = message, attributes = attrs)
    }

    /** Records a custom breadcrumb, shown on the session timeline. */
    @JvmOverloads
    fun recordAction(category: String, name: String, data: Map<String, Any?>? = null) {
        markActivity()
        val attrs = LinkedHashMap<String, Any>()
        attrs["traceway.action.category"] = category
        attrs["traceway.action.name"] = name
        if (!data.isNullOrEmpty()) {
            attrs["traceway.action.data"] = try {
                Json.stringify(data)
            } catch (_: Throwable) {
                runCatching { data.toString() }.getOrDefault("")
            }
        }
        emit(eventName = Events.ACTION, severity = Severity.INFO, attributes = attrs)
    }

    /** Emits a named product event, e.g. `checkout_failed`. Triggers can match it by `event.name`. */
    @JvmOverloads
    fun track(eventName: String, attributes: Map<String, Any>? = null) {
        if (eventName.isEmpty()) return
        // A copy: the record may wait for the SDK to start, and the app may change its map meanwhile.
        val attrs = try {
            LinkedHashMap(attributes ?: emptyMap())
        } catch (_: Throwable) {
            return
        }
        emit(eventName = eventName, severity = Severity.INFO, attributes = attrs)
    }

    /** Sets an attribute attached to every later record and reported to the server for remote-config targeting. */
    fun setAttribute(key: String, value: String) {
        if (key.isEmpty()) return
        synchronized(lock) {
            if (!globalAttributes.containsKey(key) && globalAttributes.size >= MAX_GLOBAL_ATTRIBUTES) return
            globalAttributes[key] = value
            scheduleRefresh()
        }
    }

    fun setAttributes(attributes: Map<String, String>) = attributes.forEach { (k, v) -> setAttribute(k, v) }

    fun removeAttribute(key: String) = synchronized(lock) {
        if (globalAttributes.remove(key) != null) scheduleRefresh()
    }

    fun clearAttributes() = synchronized(lock) {
        if (globalAttributes.isEmpty()) return
        globalAttributes.clear()
        scheduleRefresh()
    }

    /** Asks the server for collection settings now instead of at the next poll. */
    fun refreshConfig() {
        opamp?.refresh()
    }

    /** Records a console line (`System.out`, or the app's own logger). */
    @JvmOverloads
    fun recordLog(message: String, severity: String = "info") {
        // The SDK's own output is not app telemetry, nor is a line printed while a record is emitted.
        if (message.startsWith("Traceway: ") || (emitDepth > 0 && Thread.holdsLock(lock))) return
        val (number, text) = when (severity.lowercase()) {
            "trace", "verbose" -> Severity.TRACE to "TRACE"
            "debug" -> Severity.DEBUG to "DEBUG"
            "warn", "warning" -> Severity.WARN to "WARN"
            "error" -> Severity.ERROR to "ERROR"
            "fatal", "assert" -> Severity.FATAL to "FATAL"
            else -> Severity.INFO to "INFO"
        }
        emit(severity = number, severityText = text, body = message)
    }

    /**
     * Reports the screen the user arrived at: `/checkout` or `/orders/{id}`
     * as the route reads. Activities and AndroidX fragments are reported
     * automatically; call this from Compose Navigation or another router.
     */
    @JvmOverloads
    fun recordNavigation(path: String, action: String = "push", from: String? = null) {
        markActivity()
        val nowMs = scheduler.now()
        // A removal takes a screen away without the user arriving anywhere: on the timeline, not a navigation.
        val arrived = action != "remove"
        val to = if (arrived) path else null
        if (!to.isNullOrEmpty()) currentScreen = to
        if (options.captureMetrics && arrived) {
            vitals?.navigated(if (to.isNullOrEmpty()) null else normalizePath(to), action, nowMs)
        }
        val attrs = LinkedHashMap<String, Any>()
        attrs["url.full"] = to ?: ""
        attrs["navigation.action"] = action
        attrs["browser.navigation.type"] = action
        if (from != null) attrs["navigation.from"] = from
        if (to != null) attrs["navigation.to"] = to
        if (!to.isNullOrEmpty()) attrs["app.screen.name"] = to
        emit(eventName = Events.NAVIGATION, severity = Severity.INFO, timeMs = nowMs, attributes = attrs)
    }

    /** The screen on top shows everything it loads: ends its time to full display (`app.screen.ttfd`). */
    fun reportFullyDisplayed() {
        if (options.captureMetrics) vitals?.fullyDisplayed()
    }

    /** Observes [value] of an app-defined distribution, e.g. `checkout.duration`. */
    @JvmOverloads
    fun recordMetric(name: String, value: Double, dimensions: Map<String, Any>? = null) {
        if (options.captureMetrics) runCatching { metrics.metric(name, value, dimensions) }
    }

    /** Adds [n] to an app-defined counter. */
    @JvmOverloads
    fun recordCount(name: String, n: Double = 1.0, dimensions: Map<String, Any>? = null) {
        if (options.captureMetrics) runCatching { metrics.count(name, n, dimensions) }
    }

    /** Sets an app-defined dimension on every metric from now on (`custom.<key>`); null removes it. */
    fun setMetricDimension(key: String, value: String?) = metrics.setDimension(key, value)

    /** Notes user activity so the session's inactivity timeout restarts. */
    fun markActivity() {
        synchronized(lock) { sessions?.markActivity() }
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
    fun startRequest(method: String, url: String, hasTraceparent: Boolean = false): RequestHandle? {
        if (!options.captureNetwork || isShutdown || isOwnRequest(url)) return null
        // Before the SDK is ready there is no tracer yet: the request is still timed, and recorded once it is.
        val t = tracer ?: return RequestHandle(null, method.uppercase(), url, scheduler.now(), null)
        return try {
            val startMs = scheduler.now()
            val span = t.spanBuilder(method.uppercase())
                .setSpanKind(SpanKind.CLIENT)
                .setNoParent()
                .setStartTimestamp(startMs, TimeUnit.MILLISECONDS)
                .startSpan()
            val header = if (!hasTraceparent && propagates(url)) {
                val c = span.spanContext
                "00-${c.traceId}-${c.spanId}-${if (c.isSampled) "01" else "00"}"
            } else {
                null
            }
            RequestHandle(span, method.uppercase(), url, startMs, header)
        } catch (e: Throwable) {
            log("request span failed: $e")
            null
        }
    }

    /** Ends a request started with [startRequest]. [error] names the failure when no response came. */
    fun endRequest(handle: RequestHandle, statusCode: Int?, requestBytes: Long?, responseBytes: Long?, error: String?) {
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
            log("network span failed: $e")
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
            log("tap lookup failed: $e")
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
        val deadline = System.currentTimeMillis() + timeoutMs
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
            log("flush failed: $e")
        }
    }

    /** Flushes (up to [timeoutMs]) and stops the SDK. [Traceway.init] starts it again. Blocks. */
    @JvmOverloads
    fun shutdown(timeoutMs: Long = 10_000) {
        if (isShutdown) return
        awaitQuietly(readyLatch, timeoutMs)
        synchronized(lock) {
            sessions?.current?.let { emitSessionEnd(it, scheduler.now()) }
            // The session may go on after a restart: its memory is observed when it ends.
            if (options.captureMetrics) memoryPeaks.remember(sessions?.currentId)
        }
        vitals?.stop()
        flush(timeoutMs)
        metrics.stop()
        isShutdown = true
        synchronized(lock) {
            sessions?.stop()
            refreshTimer?.cancel()
            overrideTimer?.cancel()
            progressTimer?.cancel()
        }
        opamp?.stop()
        synchronized(lock) { replay.stop() }
        touchTracker?.uninstall()
        lifecycle?.uninstall()
        recorderCallbacks?.let { application?.unregisterActivityLifecycleCallbacks(it) }
        recorderCallbacks = null
        LogCapture.uninstall(logSink = logLineSink)
        store.flush()
        // What the processors still export from here is written to disk, for the next start, not sent.
        (logExporter as? PersistingExporter)?.persistOnly = true
        (spanExporter as? PersistingExporter)?.persistOnly = true
        try {
            loggerProvider?.shutdown()?.join(2, TimeUnit.SECONDS)
            tracerProvider?.shutdown()?.join(2, TimeUnit.SECONDS)
        } catch (_: Throwable) {
        }
        // After the processors' last export: a batch that fails then is still queued on disk for the next start.
        outbox?.dispose()
        tracewayOutbox?.dispose()
        scheduler.shutdown()
        synchronized(Companion) { if (current === this) current = null }
    }

    // ------------------------------------------------------------- crashes

    /**
     * An uncaught exception is about to end the process: report it, write it
     * with its breadcrumbs and replay clip to disk (nothing in flight would
     * survive), then try to send it all for a moment. What does not make it
     * is sent at the next start.
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
            // Nothing in flight would survive the process: from here on every export is written to the outbox.
            (logExporter as? PersistingExporter)?.persistOnly = true
            (spanExporter as? PersistingExporter)?.persistOnly = true
            synchronized(lock) {
                if (options.captureMetrics) metrics.crash()
                val attrs = LinkedHashMap(exceptionAttributes(error, mapOf("thread.name" to thread.name)))
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
            // Everything is on disk now. Off the main thread, try to send it before the process ends; on it, the
            // next start sends it rather than the screen staying frozen.
            if (!isMainThread()) {
                val pending = listOfNotNull(outbox?.drain(), tracewayOutbox?.drain(), recordings.flush())
                CompletableResultCode.ofAll(pending).join(left(), TimeUnit.MILLISECONDS)
            }
        } catch (_: Throwable) {
            // Never let our own failure stop the crash from reaching the previous handler.
        } finally {
            // The app's own handler may swallow the crash and keep running.
            crashing = false
            (logExporter as? PersistingExporter)?.persistOnly = false
            (spanExporter as? PersistingExporter)?.persistOnly = false
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

    private fun emit(
        eventName: String? = null,
        severity: Severity? = null,
        severityText: String? = null,
        body: String? = null,
        attributes: Map<String, Any> = emptyMap(),
        timeMs: Long? = null,
    ) {
        if (isShutdown) return
        val timestamp = timeMs ?: scheduler.now()
        synchronized(lock) {
            if (!ready) {
                queue(eventName in KEPT_BEFORE_READY) {
                    emit(eventName, severity, severityText, body, attributes, timestamp)
                }
                return
            }
            val l = logger ?: return
            emitDepth++
            try {
                val b = l.logRecordBuilder()
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
                log("emit failed: $e")
            } finally {
                emitDepth--
            }
        }
    }

    private fun emitSessionEnd(session: SessionInfo, endedAtMs: Long) {
        if (!reportedSessions.remove(session.id)) return
        emit(eventName = Events.SESSION_END, severity = Severity.INFO, timeMs = endedAtMs, attributes = sessionAttributes(session))
    }

    /** What every session event carries: the session and how much of its diagnostic budget replay used. */
    private fun sessionAttributes(session: SessionInfo): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        out[Events.ATTR_SESSION_ID] = session.id
        out[Events.ATTR_SESSION_STARTED_AT] = session.startedAtMs
        for ((k, v) in replay.budget.snapshot(session.id)) out[k] = v
        out["traceway.replay.failed_segments"] = recordings.failedSegments.toLong()
        out["traceway.context.evicted"] = buffer.evictedCount.toLong()
        return out
    }

    private fun onSessionStart(session: SessionInfo, resumed: Boolean) {
        // What an earlier session left behind is not this one's context.
        if (!resumed) {
            buffer.clear()
            persisted?.clearRestored()
        }
        triggers.resetSession()
        val limits = store.get("triggerLimits").asJsonObject()
        if (limits != null && limits["sessionId"] == session.id) triggers.restoreSessionLimits(limits["limits"])
        restoreTriggerProgress(session.id)
        replay.onSessionStart()
        if (options.captureMetrics) {
            memoryPeaks.settle(session.id)
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
        if (options.captureMetrics) memoryPeaks.ended(session.id)
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

    private fun enrichLog(record: ReadWriteLogRecord, signal: String): Boolean {
        val session = sessions?.current
        if (session != null && record.getAttribute(ATTR_STARTED_AT) == null) {
            record.setAttribute(ATTR_STARTED_AT, session.startedAtMs)
        }
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
            suppressedErrors++
            return false
        }
        errorTimes.removeAll { nowMs - it > 60_000 }
        if (errorTimes.size >= MAX_ERRORS_PER_MINUTE) {
            suppressedErrors++
            return false
        }
        errorTimes.add(nowMs)
        lastErrorKey = key
        lastErrorMs = nowMs
        return true
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
        (screenRecorder as? WireframeRecorder)?.maskAllInputs = settings.replayMaskAllInputs
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
        buffer.configure(maxBytes = settings.bufferMaxBytes, maxAgeMs = settings.bufferMaxAgeMs)
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
        log("remote config applied ($hash)")
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

    private val metricsScreen: String?
        get() = currentScreen?.takeIf { it.isNotEmpty() }?.let { normalizePath(it) }

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

    private fun onLifecycle(foreground: Boolean) {
        if (isShutdown) return
        if (options.captureMetrics) vitals?.lifecycleChanged(foreground)
        if (foreground) {
            markActivity()
            opamp?.poll()
            legacyReports?.drain()
            outbox?.drain()
            tracewayOutbox?.drain()
        } else {
            synchronized(lock) {
                replay.onPaused()
                if (options.captureMetrics && started) {
                    memoryPeaks.remember(sessions?.currentId)
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

    private fun setScreenRecorder(recorder: ReplaySource?) = synchronized(lock) {
        screenRecorder = recorder
        (recorder as? WireframeRecorder)?.maskAllInputs =
            control.settings.replayMaskAllInputs
        replay.attach(recorder)
    }

    private fun log(message: String) {
        if (options.debug) Log.d(TAG, message)
    }

    // ------------------------------------------------------------ testing

    @VisibleForTesting
    internal val settings: Settings get() = synchronized(lock) { control.settings }

    @VisibleForTesting
    internal val bufferedCount: Int get() = synchronized(lock) { buffer.length }

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
        private val ATTR_STARTED_AT = AttributeKey.longKey(Events.ATTR_SESSION_STARTED_AT)
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
         * Starts the SDK, or returns the running client. Without a
         * [connectionString] it runs standalone and sends everything to
         * [TracewayOptions.telemetry].
         * @throws IllegalArgumentException for an invalid connection string or telemetry setting.
         */
        internal fun initialize(application: Application, connectionString: String?, options: TracewayOptions): TracewayClient {
            synchronized(this) {
                current?.let { if (!it.isShutdown) return it }
                val client = TracewayClient(application, connectionString, options, null)
                current = client
                client.instrument(application)
                Thread({ client.start() }, "TracewayStart").apply { isDaemon = true }.start()
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
