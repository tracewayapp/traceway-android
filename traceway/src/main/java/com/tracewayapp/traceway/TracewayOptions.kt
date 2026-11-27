package com.tracewayapp.traceway

/**
 * Configuration for [Traceway.init]. Names and defaults follow the Flutter
 * and browser SDKs, so one Data Collection rule targets every client.
 *
 * The `capture*` flags, [screenCapture], [replayImages] and [sessions] are
 * **ceilings**: remote settings can lower what is collected but never raise
 * it.
 * [sampleRate], [baselineSampleRate] and [recordAllSessions] are defaults
 * that a rule can change either way.
 */
data class TracewayOptions @JvmOverloads constructor(
    /** Share of exceptions and messages that are reported (0.0 - 1.0). */
    val sampleRate: Double = 1.0,

    /** Print debug info to logcat (tag `Traceway`). */
    val debug: Boolean = false,

    /** App version, sent as `service.version` (`unknown` when empty). */
    val version: String = "",

    /** Sent as `service.name`. Defaults to the application id. */
    val serviceName: String? = null,

    /** Sent as `deployment.environment.name`. */
    val environment: String? = null,

    /** Attributes attached to every record, and usable to target this app from Data Collection rules. */
    val attributes: Map<String, String> = emptyMap(),

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
     * default) whatever the collection settings (`replay.images`, Wi-Fi by
     * default) allow. A ceiling: the lower of the two applies.
     */
    val replayImages: TracewayImagePolicy = TracewayImagePolicy.ALWAYS,

    /** Most wireframe frames captured per second (1-30); only frames where something was drawn are captured. */
    val fps: Int = 10,

    /** Wireframe frames held on the device; the upper bound of every clip. */
    val maxBufferFrames: Int = 150,

    /** Keep SDK state and undelivered telemetry on disk across restarts. */
    val persistToDisk: Boolean = true,

    /** Capture `System.out` / `System.err` lines and [Traceway.log] calls. A ceiling for `console`. */
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
    val metricDimensions: Map<String, String> = emptyMap(),

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

    /** Send logs, spans and metrics to another OTLP/HTTP receiver as well, or (standalone) only there. */
    val telemetry: TracewayTelemetry? = null,

    /** URL prefixes whose requests carry a W3C `traceparent` header naming their client span. */
    val propagateTraceHeaderUrls: List<String> = emptyList(),

    /**
     * Exceptions and messages not to report, matched against their first
     * line `Type: message` (stack frames are not): a [String] matches when
     * it occurs in it, a [Regex] when it finds a match. Null uses
     * [DEFAULT_IGNORE_ERRORS]; an empty list reports everything. Crashes
     * (uncaught exceptions that end the process) are always reported.
     */
    val ignoreErrors: List<Any>? = null,

    /**
     * Called before an exception or a message is recorded; return false to
     * drop it. A callback that throws keeps the record (and logs in debug).
     * Crashes are always reported and do not pass through it.
     */
    val beforeCapture: ((TracewayCapturedError) -> Boolean)? = null,

    /**
     * The connection string a 1.x build of the app used. Unsent 1.x reports
     * found on the device are delivered to that project's `/api/report`
     * (and expire after 12 hours, the 1.x default).
     */
    val legacyConnectionString: String? = null,

    /**
     * R8/ProGuard build UUID used to symbolicate obfuscated release crashes.
     * Set it to `BuildConfig.TRACEWAY_PROGUARD_UUID` (injected by the
     * `com.tracewayapp.symbols` Gradle plugin) so reported traces match the
     * uploaded `mapping.txt`. Sent as `app.debug.proguard_uuid`.
     */
    val proguardUuid: String? = null,

    /** Like [propagateTraceHeaderUrls], for URLs matching a pattern. */
    val propagateTraceHeaderUrlPatterns: List<Regex> = emptyList(),
) {
    companion object {
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
