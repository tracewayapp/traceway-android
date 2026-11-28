package com.tracewayapp.traceway

import java.util.regex.Pattern

/**
 * Configuration for [Traceway.init]: the project [token], the server
 * ([baseUrl], Traceway Cloud unless you host Traceway yourself) and what is
 * collected. Names and defaults follow the Flutter and browser SDKs, so one
 * Data Collection rule targets every client.
 *
 * ```kotlin
 * Traceway.init(this, TracewayOptions(token = "YOUR_TOKEN", screenCapture = true))
 * ```
 *
 * From Java, build them with [builder]; a `null` passed for any option, to
 * the builder or the constructor, means its default:
 *
 * ```java
 * Traceway.init(this, TracewayOptions.builder().token("YOUR_TOKEN").screenCapture(true).build());
 * ```
 *
 * The `capture*` flags, [screenCapture], [replayImages] and [sessions] are
 * **ceilings**: remote settings can lower what is collected but never raise
 * it.
 * [sampleRate], [baselineSampleRate] and [recordAllSessions] are defaults
 * that a rule can change either way.
 */
data class TracewayOptions @JvmOverloads constructor(
    /**
     * The project token, from the project's Connection page in Traceway.
     * Without one (null or blank) the SDK runs standalone and sends
     * everything to [telemetry].
     */
    val token: String? = null,

    /**
     * The Traceway server: Traceway Cloud ([DEFAULT_BASE_URL]) unless you
     * host Traceway yourself (`https://traceway.mycompany.com`). `https://`
     * is added when it has no scheme; null means the default.
     */
    val baseUrl: String? = DEFAULT_BASE_URL,

    /** Share of exceptions and messages that are reported (0.0 - 1.0). */
    val sampleRate: Double = 1.0,

    /** Print debug info to logcat (tag `Traceway`). */
    val debug: Boolean = false,

    /**
     * App version, sent as `service.version`. Null or empty: the app's
     * `versionName` (`unknown` when it has none).
     */
    val version: String? = null,

    /** Sent as `service.name`. Defaults to the application id. */
    val serviceName: String? = null,

    /** Sent as `deployment.environment.name`. */
    val environment: String? = null,

    /** Attributes attached to every record, and usable to target this app from Data Collection rules. */
    val attributes: Map<String, String>? = emptyMap(),

    /**
     * Record the screen as a wireframe: the view tree (and Jetpack Compose
     * semantics) replayed as HTML by the dashboard's rrweb player. The last
     * frames go up with an exception, and captures stream it. A ceiling for
     * `replay`.
     */
    val screenCapture: Boolean = false,

    /**
     * The most session replay records of the pictures image views show:
     * [TracewayImagePolicy.OFF] draws placeholders only, [TracewayImagePolicy.WIFI]
     * pictures on an unmetered connection, [TracewayImagePolicy.ALWAYS] (the
     * default, also for null) whatever the collection settings
     * (`replay.images`, Wi-Fi by default) allow. A ceiling: the lower of the
     * two applies.
     */
    val replayImages: TracewayImagePolicy? = TracewayImagePolicy.ALWAYS,

    /** Most wireframe frames captured per second (1-30); only frames where something was drawn are captured. */
    val fps: Int = 10,

    /** Wireframe frames held on the device; the upper bound of every clip. */
    val maxBufferFrames: Int = 150,

    /** Keep SDK state and undelivered telemetry on disk across restarts. */
    val persistToDisk: Boolean = true,

    /**
     * Capture `System.out` / `System.err` lines, [Traceway.log] calls and,
     * with the Gradle plugin, `android.util.Log` (logcat) calls. A ceiling
     * for `console`.
     */
    val captureLogs: Boolean = true,

    /** Record HTTP calls made through [com.tracewayapp.traceway.network.TracewayOkHttpInterceptor]. */
    val captureNetwork: Boolean = true,

    /** Record activity and fragment transitions, and [Traceway.recordNavigation] calls. */
    val captureNavigation: Boolean = true,

    /** Record taps with the selector of the view, and feed taps to recording triggers. */
    val captureUserActions: Boolean = true,

    /**
     * Measure app start, screen display times, slow and frozen frames,
     * hangs, crashes, sessions, users and requests, and upload them as
     * OTLP metrics.
     */
    val captureMetrics: Boolean = true,

    /** Up to two app-defined dimensions on every metric (`custom.<key>`). */
    val metricDimensions: Map<String, String>? = emptyMap(),

    /** Stream breadcrumbs and the screen recording all the time instead of keeping them for errors. */
    val recordAllSessions: Boolean = false,

    /**
     * Local collection settings: the same document as a Data Collection
     * rule (`signals`, `buffer`, `replay`, `triggers`, ...), laid over the
     * built-in defaults. With [remoteConfig] the server's rules apply on top.
     */
    val collection: Map<String, Any?>? = null,

    /** Send session records (`session.start` / `session.end`). The session id exists either way. */
    val sessions: Boolean = true,

    /** Share of sessions whose breadcrumbs are sent continuously, not only around errors. */
    val baselineSampleRate: Double = 0.0,

    /** Poll the server for collection settings over OpAMP. */
    val remoteConfig: Boolean = true,

    /** How long finished records wait to be sent as one batch. */
    val exportDelayMs: Long = 1000,

    /**
     * Send logs, spans and metrics to another OpenTelemetry (OTLP/HTTP)
     * receiver as well as Traceway, or, without a [token], only there.
     */
    val telemetry: TracewayTelemetry? = null,

    /** URL prefixes whose requests carry a W3C `traceparent` header naming their client span. */
    val propagateTraceHeaderUrls: List<String>? = emptyList(),

    /**
     * Exceptions and messages not to report, matched against their first
     * line `Type: message` (stack frames are not): a [String] matches when
     * it occurs in it, a [Regex] or a [java.util.regex.Pattern] when it
     * finds a match. Null uses [DEFAULT_IGNORE_ERRORS]; an empty list
     * reports everything. Crashes (uncaught exceptions that end the
     * process) are always reported.
     */
    val ignoreErrors: List<Any>? = null,

    /**
     * Called before an exception or a message is recorded; return false to
     * drop it. A callback that throws keeps the record (and logs in debug).
     * Crashes are always reported and do not pass through it.
     */
    val beforeCapture: ((TracewayCapturedError) -> Boolean)? = null,

    /**
     * R8/ProGuard build UUID used to symbolicate obfuscated release crashes.
     * Null reads the one the `com.tracewayapp.symbols` Gradle plugin puts
     * into the app's assets, so applying the plugin is enough; set it only
     * to pin your own (`BuildConfig.TRACEWAY_PROGUARD_UUID` is the plugin's
     * too). Sent as `app.debug.proguard_uuid`, so reported traces match the
     * uploaded `mapping.txt`.
     */
    val proguardUuid: String? = null,

    /** Like [propagateTraceHeaderUrls], for URLs matching a pattern. */
    val propagateTraceHeaderUrlPatterns: List<Regex>? = emptyList(),

    /**
     * Report, at the next start, how earlier runs of the process ended, as
     * Android recorded it (`ApplicationExitInfo`, API 30+): a native crash
     * as a `NativeCrash` issue (with the tombstone's signal, abort message
     * and frames on API 31+), an ANR as an `ApplicationNotResponding` issue
     * with the main thread's stack and an `app.anr` count, a low-memory kill
     * as an `app.oom` count. Needs [persistToDisk].
     */
    val captureExitReasons: Boolean = true,

    /**
     * Report a main thread blocked for [appHangThresholdMs] or more in the
     * foreground as an `AppHang` issue, with the main thread's stack taken
     * while it was blocked. A hang the process does not survive is reported
     * at the next start (`app.hang.fatal = true`), unless Android recorded
     * it as an ANR. Not under a debugger, unless [reportHangsInDebug].
     */
    val captureAppHangs: Boolean = true,

    /** How long the main thread must be blocked for an `AppHang` issue (at least 250 ms). */
    val appHangThresholdMs: Long = 2000,

    /**
     * Report hangs (the `app.hang` metric and `AppHang` issues) while a
     * debugger is attached too, to try them from Android Studio's Debug run.
     * Off by default: a breakpoint blocks the main thread as a hang does.
     */
    val reportHangsInDebug: Boolean = false,

    /**
     * Run in every process of the app. By default the SDK runs in the main
     * process only (the one named after the application id): another one
     * (`:sync`, `:remote`) gets no client, as [Traceway.init] there returns
     * null. With it on, each process keeps its state in a directory of its
     * own.
     */
    val allProcesses: Boolean = false,

    /**
     * How long without user activity ends a session: 15 minutes by default,
     * at least a second. A session also ends after an hour in all. The next
     * activity starts a new one; an app opened again after this long starts
     * a new one too, after it ended the one it left (`session.end` at its
     * last activity, with its counts).
     */
    val sessionTimeoutMs: Long = DEFAULT_SESSION_TIMEOUT_MS,
) {
    /** These options as a [Builder], to change some of them (from Java, where [copy] takes every parameter). */
    fun toBuilder(): Builder = Builder(this)

    /**
     * Builds [TracewayOptions] one option at a time, for Java. Each setter
     * has the option's name; a null value means the option's default.
     *
     * ```java
     * TracewayOptions options = TracewayOptions.builder()
     *     .token(BuildConfig.TRACEWAY_TOKEN)
     *     .screenCapture(true)
     *     .attributes(Map.of("tier", "gold"))
     *     .build();
     * ```
     */
    class Builder internal constructor(private var options: TracewayOptions) {
        constructor() : this(TracewayOptions())

        private val defaults = TracewayOptions()

        private inline fun set(change: TracewayOptions.() -> TracewayOptions): Builder {
            options = options.change()
            return this
        }

        fun token(token: String?): Builder = set { copy(token = token) }
        fun baseUrl(baseUrl: String?): Builder = set { copy(baseUrl = baseUrl ?: defaults.baseUrl) }
        fun sampleRate(sampleRate: Double?): Builder = set { copy(sampleRate = sampleRate ?: defaults.sampleRate) }
        fun debug(debug: Boolean?): Builder = set { copy(debug = debug ?: defaults.debug) }
        fun version(version: String?): Builder = set { copy(version = version) }
        fun serviceName(serviceName: String?): Builder = set { copy(serviceName = serviceName) }
        fun environment(environment: String?): Builder = set { copy(environment = environment) }
        fun attributes(attributes: Map<String, String>?): Builder = set { copy(attributes = attributes ?: defaults.attributes) }
        fun screenCapture(screenCapture: Boolean?): Builder = set { copy(screenCapture = screenCapture ?: defaults.screenCapture) }
        fun replayImages(replayImages: TracewayImagePolicy?): Builder = set { copy(replayImages = replayImages ?: defaults.replayImages) }
        fun fps(fps: Int?): Builder = set { copy(fps = fps ?: defaults.fps) }
        fun maxBufferFrames(maxBufferFrames: Int?): Builder = set { copy(maxBufferFrames = maxBufferFrames ?: defaults.maxBufferFrames) }
        fun persistToDisk(persistToDisk: Boolean?): Builder = set { copy(persistToDisk = persistToDisk ?: defaults.persistToDisk) }
        fun captureLogs(captureLogs: Boolean?): Builder = set { copy(captureLogs = captureLogs ?: defaults.captureLogs) }
        fun captureNetwork(captureNetwork: Boolean?): Builder = set { copy(captureNetwork = captureNetwork ?: defaults.captureNetwork) }
        fun captureNavigation(captureNavigation: Boolean?): Builder =
            set { copy(captureNavigation = captureNavigation ?: defaults.captureNavigation) }
        fun captureUserActions(captureUserActions: Boolean?): Builder =
            set { copy(captureUserActions = captureUserActions ?: defaults.captureUserActions) }
        fun captureMetrics(captureMetrics: Boolean?): Builder = set { copy(captureMetrics = captureMetrics ?: defaults.captureMetrics) }
        fun metricDimensions(metricDimensions: Map<String, String>?): Builder =
            set { copy(metricDimensions = metricDimensions ?: defaults.metricDimensions) }
        fun recordAllSessions(recordAllSessions: Boolean?): Builder =
            set { copy(recordAllSessions = recordAllSessions ?: defaults.recordAllSessions) }
        fun collection(collection: Map<String, Any?>?): Builder = set { copy(collection = collection) }
        fun sessions(sessions: Boolean?): Builder = set { copy(sessions = sessions ?: defaults.sessions) }
        fun baselineSampleRate(baselineSampleRate: Double?): Builder =
            set { copy(baselineSampleRate = baselineSampleRate ?: defaults.baselineSampleRate) }
        fun remoteConfig(remoteConfig: Boolean?): Builder = set { copy(remoteConfig = remoteConfig ?: defaults.remoteConfig) }
        fun exportDelayMs(exportDelayMs: Long?): Builder = set { copy(exportDelayMs = exportDelayMs ?: defaults.exportDelayMs) }
        fun telemetry(telemetry: TracewayTelemetry?): Builder = set { copy(telemetry = telemetry) }
        fun propagateTraceHeaderUrls(propagateTraceHeaderUrls: List<String>?): Builder =
            set { copy(propagateTraceHeaderUrls = propagateTraceHeaderUrls ?: defaults.propagateTraceHeaderUrls) }

        /** `String`s, [Regex]es and [java.util.regex.Pattern]s; null entries are skipped, a null list is [DEFAULT_IGNORE_ERRORS]. */
        fun ignoreErrors(ignoreErrors: List<*>?): Builder = set { copy(ignoreErrors = ignoreErrors?.filterNotNull()) }
        fun beforeCapture(beforeCapture: ((TracewayCapturedError) -> Boolean)?): Builder = set { copy(beforeCapture = beforeCapture) }
        fun proguardUuid(proguardUuid: String?): Builder = set { copy(proguardUuid = proguardUuid) }
        fun propagateTraceHeaderUrlPatterns(propagateTraceHeaderUrlPatterns: List<Regex>?): Builder =
            set { copy(propagateTraceHeaderUrlPatterns = propagateTraceHeaderUrlPatterns ?: defaults.propagateTraceHeaderUrlPatterns) }

        /** [propagateTraceHeaderUrlPatterns] as `java.util.regex.Pattern`s, for Java. */
        fun propagateTraceHeaderUrlPatterns(vararg patterns: Pattern?): Builder =
            set { copy(propagateTraceHeaderUrlPatterns = patterns.filterNotNull().map { it.toRegex() }) }
        fun captureExitReasons(captureExitReasons: Boolean?): Builder =
            set { copy(captureExitReasons = captureExitReasons ?: defaults.captureExitReasons) }
        fun captureAppHangs(captureAppHangs: Boolean?): Builder = set { copy(captureAppHangs = captureAppHangs ?: defaults.captureAppHangs) }
        fun appHangThresholdMs(appHangThresholdMs: Long?): Builder =
            set { copy(appHangThresholdMs = appHangThresholdMs ?: defaults.appHangThresholdMs) }
        fun reportHangsInDebug(reportHangsInDebug: Boolean?): Builder =
            set { copy(reportHangsInDebug = reportHangsInDebug ?: defaults.reportHangsInDebug) }
        fun allProcesses(allProcesses: Boolean?): Builder = set { copy(allProcesses = allProcesses ?: defaults.allProcesses) }
        fun sessionTimeoutMs(sessionTimeoutMs: Long?): Builder =
            set { copy(sessionTimeoutMs = sessionTimeoutMs ?: defaults.sessionTimeoutMs) }

        fun build(): TracewayOptions = options
    }

    companion object {
        /** Traceway Cloud, the default [baseUrl]. */
        const val DEFAULT_BASE_URL = "https://cloud.tracewayapp.com"

        /** 15 minutes, the default [sessionTimeoutMs], as in the other Traceway SDKs. */
        const val DEFAULT_SESSION_TIMEOUT_MS = 15L * 60 * 1000

        /** A [Builder] of the default options, for Java. */
        @JvmStatic
        fun builder(): Builder = Builder()

        /**
         * Errors not reported unless [ignoreErrors] says otherwise: a phone
         * losing its connection, a request timing out or a server saying no
         * is the network's doing, not a bug in the app.
         */
        @JvmField
        val DEFAULT_IGNORE_ERRORS: List<Any> = listOf(
            "UnknownHostException",
            "SocketTimeoutException",
            "ConnectException",
            "SSLHandshakeException",
            "NoRouteToHostException",
            "Connection reset",
            Regex("timeout", RegexOption.IGNORE_CASE),
            Regex("status code (of )?4\\d{2}"),
            Regex("failed: 4\\d{2}"),
        )
    }
}
