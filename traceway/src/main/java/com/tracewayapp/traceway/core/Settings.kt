package com.tracewayapp.traceway.core

import com.tracewayapp.traceway.TracewayImagePolicy
import com.tracewayapp.traceway.internal.Json

/**
 * The collection settings document. The server sends a partial overlay of it
 * over OpAMP (`config_map["traceway"]`); the SDK merges that onto its
 * built-in defaults and clamps the result to the ceilings set at init.
 * The schema is shared with the browser and Flutter SDKs.
 */

/** `OFF` drops a signal, `BUFFER` keeps it on the device until a trigger or capture flushes it, `STREAM` sends it. */
internal enum class Mode {
    OFF, BUFFER, STREAM;

    val wire: String get() = name.lowercase()

    companion object {
        fun from(value: Any?): Mode? = when (value) {
            "off" -> OFF
            "buffer" -> BUFFER
            "stream" -> STREAM
            else -> null
        }
    }
}

internal fun lowerMode(a: Mode, b: Mode): Mode = if (a.ordinal <= b.ordinal) a else b

/**
 * Signal names are shared with the browser SDK so one settings schema serves
 * both. `webVitals`, `resourceTiming`, `longTasks` and `routeTiming` have no
 * Android source; they are known only so documents naming them apply.
 */
internal val SIGNAL_NAMES: List<String> = listOf(
    "errors",
    "messages",
    "network",
    "console",
    "navigation",
    "userActions",
    "webVitals",
    "resourceTiming",
    "longTasks",
    "routeTiming",
    "custom",
)

internal val SEVERITIES: List<String> = listOf("trace", "debug", "info", "warn", "error", "fatal")

internal class SignalSettings(
    var mode: Mode,
    var sampleRate: Double = 1.0,
    /** Console only: records below it are dropped. */
    var minSeverity: String? = null,
) {
    fun copy() = SignalSettings(mode, sampleRate, minSeverity)
}

internal sealed class Matcher {
    abstract fun toJson(): Any
}

internal class Condition(
    val key: String,
    val op: String,
    val value: Any? = null,
    val values: List<Any>? = null,
) : Matcher() {
    override fun toJson(): Any {
        // Not built in `LinkedHashMap().apply {}`: there `values` would be the map's own values view.
        val out = LinkedHashMap<String, Any?>()
        out["key"] = key
        out["op"] = op
        if (value != null) out["value"] = value
        if (values != null) out["values"] = values
        return out
    }
}

/**
 * A step of a recorded journey: a tap (`click`), a pointer entering a view
 * (`hover`), a screen the app arrived at (`navigation`) or a log line (`log`).
 * Click and hover values are selectors over the view tree; navigation values
 * are paths with optional `{param}` segments.
 */
internal class EventMatcher(
    /** `click`, `hover`, `navigation` or `log`. */
    val type: String,
    val value: String,
    /** `exact` (default) or `contains`, for `log`. */
    val mode: String? = null,
) : Matcher() {
    override fun toJson(): Any = mapOf(
        "event" to LinkedHashMap<String, Any?>().apply {
            put("type", type)
            put("value", value)
            if (mode != null) put("mode", mode)
        },
    )
}

internal class AllMatcher(val items: List<Matcher>) : Matcher() {
    override fun toJson(): Any = mapOf("all" to items.map { it.toJson() })
}

internal class AnyMatcher(val items: List<Matcher>) : Matcher() {
    override fun toJson(): Any = mapOf("any" to items.map { it.toJson() })
}

internal class NotMatcher(val inner: Matcher) : Matcher() {
    override fun toJson(): Any = mapOf("not" to inner.toJson())
}

/** One action of a trigger. [type] is `flush`, `stream`, `startReplay`, `emit` or `capture`. */
internal class TriggerAction(
    val type: String,
    val lookbackSeconds: Double? = null,
    val durationSeconds: Double? = null,
    /** `null` means every signal; an empty list means none. */
    val signals: List<String>? = null,
    val replay: Boolean = false,
    val eventName: String? = null,
    val attributes: Map<String, String>? = null,
) {
    fun toJson(): Any = LinkedHashMap<String, Any?>().apply {
        put("type", type)
        if (lookbackSeconds != null) put("lookbackSeconds", lookbackSeconds)
        if (durationSeconds != null) put("durationSeconds", durationSeconds)
        if (signals != null) put("signals", signals)
        if (replay) put("replay", true)
        if (eventName != null) put("eventName", eventName)
        if (!attributes.isNullOrEmpty()) put("attributes", attributes)
    }
}

internal class TriggerStep(
    val match: Matcher,
    /** Longest gap allowed since the previous step; null means no limit. */
    val withinSeconds: Double? = null,
    /** Matching records needed before the step completes. Default 1. */
    val count: Int? = null,
    /** With [count]: all of them within this many seconds of each other. */
    val periodSeconds: Double? = null,
) {
    fun toJson(): Any = LinkedHashMap<String, Any?>().apply {
        put("match", match.toJson())
        put("withinSeconds", withinSeconds)
        if (count != null) {
            put("count", count)
            put("periodSeconds", periodSeconds)
        }
    }
}

internal class Trigger(
    val id: String,
    val name: String? = null,
    val enabled: Boolean? = null,
    val sampleRate: Double? = null,
    val cooldownSeconds: Double? = null,
    val maxPerSession: Int? = null,
    val steps: List<TriggerStep>,
    val actions: List<TriggerAction>,
) {
    /** Changes whenever the steps or actions do, so progress made under an older version is not carried over. */
    val signature: String by lazy {
        Json.stringify(listOf(steps.map { it.toJson() }, actions.map { it.toJson() }))
    }

    fun stepsJson(): List<Any> = steps.map { it.toJson() }

    fun copyWith(
        name: String? = null,
        enabled: Boolean? = null,
        sampleRate: Double? = null,
        cooldownSeconds: Double? = null,
        maxPerSession: Int? = null,
        steps: List<TriggerStep>? = null,
        actions: List<TriggerAction>? = null,
    ) = Trigger(
        id = id,
        name = name ?: this.name,
        enabled = enabled ?: this.enabled,
        sampleRate = sampleRate ?: this.sampleRate,
        cooldownSeconds = cooldownSeconds ?: this.cooldownSeconds,
        maxPerSession = maxPerSession ?: this.maxPerSession,
        steps = steps ?: this.steps,
        actions = actions ?: this.actions,
    )
}

/** An on-demand capture requested from the dashboard; only the server creates these. */
internal class Capture(
    val id: String,
    /** Epoch milliseconds. */
    val untilMs: Long,
    val lookbackSeconds: Double? = null,
    val signals: List<String>? = null,
    val replay: Boolean = false,
)

internal const val DEFAULT_METRICS_FLUSH_SECONDS = 300.0
internal const val MIN_METRICS_FLUSH_SECONDS = 30.0
internal const val MAX_METRICS_FLUSH_SECONDS = 3600.0

/**
 * The aggregates: histograms and counters built on the device and uploaded
 * as OTLP metrics. Separate from the per-signal settings, which govern the
 * detailed events: a signal can be off while its numbers are still charted.
 */
internal class MetricsSettings(
    var enabled: Boolean = true,
    /** Share of sessions that record metrics; each payload states it so the server weights counts by 1/rate. */
    var sampleRate: Double = 1.0,
    var flushSeconds: Double = DEFAULT_METRICS_FLUSH_SECONDS,
) {
    fun copy() = MetricsSettings(enabled, sampleRate, flushSeconds)
}

internal class Settings(
    /** Kill switch: when false nothing but the OpAMP poll leaves the device. */
    var enabled: Boolean,
    var signals: MutableMap<String, SignalSettings>,
    var sessionsEnabled: Boolean,
    var baselineSampleRate: Double,
    var replayMode: Mode,
    var replaySessionSampleRate: Double,
    /** Wireframe recordings mask what is typed into text fields (`replay.maskAllInputs`). */
    var replayMaskAllInputs: Boolean = true,
    /**
     * When wireframe recordings carry the pictures image views show
     * (`replay.images`): never, on an unmetered connection, or always.
     */
    var replayImages: TracewayImagePolicy = TracewayImagePolicy.WIFI,
    var bufferMaxBytes: Long,
    var bufferMaxAgeSeconds: Double,
    var persist: Boolean,
    var triggers: MutableList<Trigger>,
    var captures: MutableList<Capture>,
    var metrics: MetricsSettings = MetricsSettings(),
) {
    fun signal(name: String): SignalSettings = signals.getValue(name)

    fun copy() = Settings(
        enabled = enabled,
        signals = LinkedHashMap<String, SignalSettings>().apply {
            for ((k, v) in signals) put(k, v.copy())
        },
        sessionsEnabled = sessionsEnabled,
        baselineSampleRate = baselineSampleRate,
        replayMode = replayMode,
        replaySessionSampleRate = replaySessionSampleRate,
        replayMaskAllInputs = replayMaskAllInputs,
        replayImages = replayImages,
        bufferMaxBytes = bufferMaxBytes,
        bufferMaxAgeSeconds = bufferMaxAgeSeconds,
        persist = persist,
        triggers = ArrayList(triggers),
        captures = ArrayList(captures),
        metrics = metrics.copy(),
    )

    val bufferMaxAgeMs: Long get() = Math.round(bufferMaxAgeSeconds * 1000)
}

/** The most a signal may do in this app, set from the init options. Remote settings can only go lower. */
internal class Ceilings(
    val enabled: Boolean,
    val signals: Map<String, Mode>,
    /** [Mode.OFF] when screen capture is not allowed. */
    val replay: Mode,
    val sessions: Boolean,
    /** False when the app or the plan does not allow metrics. */
    val metrics: Boolean = true,
    /** The most `replay.images` may allow ([com.tracewayapp.traceway.TracewayOptions.replayImages]). */
    val replayImages: TracewayImagePolicy = TracewayImagePolicy.ALWAYS,
)

internal const val DEFAULT_EXCEPTION_TRIGGER_ID = "default-exception"

/** Lookback of the default replay clip. The frame ring holds less than this, so the clip is "everything it has". */
internal const val DEFAULT_REPLAY_LOOKBACK_SECONDS = 30.0

internal fun defaultExceptionTrigger() = Trigger(
    id = DEFAULT_EXCEPTION_TRIGGER_ID,
    name = "Error clip",
    enabled = true,
    sampleRate = 1.0,
    cooldownSeconds = 5.0,
    maxPerSession = 20,
    steps = listOf(TriggerStep(match = Condition("event.name", "eq", value = "exception"))),
    actions = listOf(
        TriggerAction(type = "flush", lookbackSeconds = 600.0, signals = listOf("*")),
        TriggerAction(
            type = "flush",
            lookbackSeconds = DEFAULT_REPLAY_LOOKBACK_SECONDS,
            signals = emptyList(),
            replay = true,
        ),
    ),
)

/**
 * The built-in collection policy, the same as the browser and Flutter SDKs':
 * errors and messages stream, breadcrumbs and replay stay on the device
 * until an error or a capture asks for them. [recordAllSessions] streams
 * breadcrumbs and replay instead.
 */
internal fun defaultSettings(
    baselineSampleRate: Double = 0.0,
    recordAllSessions: Boolean = false,
): Settings {
    fun s(mode: Mode, minSeverity: String? = null) = SignalSettings(mode, minSeverity = minSeverity)
    val breadcrumbs = if (recordAllSessions) Mode.STREAM else Mode.BUFFER
    return Settings(
        enabled = true,
        signals = linkedMapOf(
            "errors" to s(Mode.STREAM),
            "messages" to s(Mode.STREAM),
            "network" to s(breadcrumbs),
            "console" to s(breadcrumbs, minSeverity = "info"),
            "navigation" to s(breadcrumbs),
            "userActions" to s(breadcrumbs),
            "webVitals" to s(Mode.BUFFER),
            "resourceTiming" to s(Mode.OFF),
            "longTasks" to s(Mode.OFF),
            "routeTiming" to s(Mode.OFF),
            "custom" to s(breadcrumbs),
        ),
        sessionsEnabled = true,
        baselineSampleRate = if (baselineSampleRate.isFinite()) baselineSampleRate.coerceIn(0.0, 1.0) else 0.0,
        replayMode = if (recordAllSessions) Mode.STREAM else Mode.BUFFER,
        replaySessionSampleRate = 1.0,
        bufferMaxBytes = 2L * 1024 * 1024,
        bufferMaxAgeSeconds = 600.0,
        persist = true,
        triggers = mutableListOf(defaultExceptionTrigger()),
        captures = mutableListOf(),
    )
}

internal fun ceilingsFrom(
    enabled: Boolean = true,
    screenCapture: Boolean = false,
    captureLogs: Boolean = true,
    captureNetwork: Boolean = true,
    captureNavigation: Boolean = true,
    captureUserActions: Boolean = true,
    sessions: Boolean = true,
    captureMetrics: Boolean = true,
    replayImages: TracewayImagePolicy = TracewayImagePolicy.ALWAYS,
): Ceilings {
    fun allow(flag: Boolean) = if (flag) Mode.STREAM else Mode.OFF
    val signals = LinkedHashMap<String, Mode>()
    for (name in SIGNAL_NAMES) signals[name] = Mode.STREAM
    signals["console"] = allow(captureLogs)
    signals["network"] = allow(captureNetwork)
    signals["navigation"] = allow(captureNavigation)
    signals["userActions"] = allow(captureUserActions)
    return Ceilings(
        enabled = enabled,
        signals = signals,
        replay = allow(screenCapture),
        sessions = sessions,
        metrics = captureMetrics,
        replayImages = replayImages,
    )
}
