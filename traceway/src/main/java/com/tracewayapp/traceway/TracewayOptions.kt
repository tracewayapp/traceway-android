package com.tracewayapp.traceway

/**
 * Configuration for [Traceway].
 *
 * Field names mirror the Flutter SDK so an app can be ported without changing
 * its config layer. Screen-capture-specific fields are kept for parity but
 * have no effect — this Android library does not record video.
 */
data class TracewayOptions(
    /** Error sampling rate (0.0 - 1.0). */
    val sampleRate: Double = 1.0,

    /** Print debug info to logcat. */
    val debug: Boolean = false,

    /** App version string emitted on every report. */
    val version: String = "",

    /**
     * R8/ProGuard build UUID used to symbolicate obfuscated release crashes.
     * Set this to `BuildConfig.TRACEWAY_PROGUARD_UUID` (injected by the
     * `com.tracewayapp.symbols` Gradle plugin) so reported traces match the
     * uploaded `mapping.txt`.
     */
    val proguardUuid: String? = null,

    /** Milliseconds before sending batched events. */
    val debounceMs: Long = 1500,

    /** Retry delay on failed uploads. */
    val retryDelayMs: Long = 10_000,

    /** Maximum exceptions held in memory before the oldest is dropped. */
    val maxPendingExceptions: Int = 5,

    /** Persist pending exceptions to disk so they survive app restarts. */
    val persistToDisk: Boolean = true,

    /** Max exception files stored on disk awaiting sync. */
    val maxLocalFiles: Int = 5,

    /** Hours after which unsynced local files are deleted. */
    val localFileMaxAgeHours: Int = 12,

    /** Capture every `Log.*` / `println` line as a log event. */
    val captureLogs: Boolean = true,

    /**
     * When true, HTTP calls routed through
     * [com.tracewayapp.traceway.network.TracewayOkHttpInterceptor] are recorded
     * as network events. Enabled by default.
     */
    val captureNetwork: Boolean = true,

    /** Record activity transitions as navigation events. */
    val captureNavigation: Boolean = true,

    /** Length in ms of the rolling log/action window snapshotted with each exception. */
    val eventsWindowMs: Long = 10_000,

    /** Hard cap applied independently to each rolling buffer. */
    val eventsMaxCount: Int = 200,

    // ------------------------------------------------------------------
    // Screen-capture parity fields. Accepted for API parity with the Flutter
    // SDK but have no effect on Android — this library does not record video.
    // ------------------------------------------------------------------

    /** Has no effect on Android. */
    val screenCapture: Boolean = false,

    /** Has no effect on Android. */
    val capturePixelRatio: Double = 0.75,

    /** Has no effect on Android. */
    val maxBufferFrames: Int = 150,

    /** Has no effect on Android. */
    val fps: Int = 15,
)
