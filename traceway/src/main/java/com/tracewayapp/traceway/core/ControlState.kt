package com.tracewayapp.traceway.core

internal class StreamOverride(
    /** `"*"` covers every signal that is not off; listing a signal also turns it on, within ceilings and limits. */
    val signals: List<String>,
    val untilMs: Long,
    /** Streams the screen recording too, as segments, while it lasts. */
    val replay: Boolean = false,
    /** A capture: `"*"` also brings back the signals rules switched off. */
    val includeOff: Boolean = false,
)

/**
 * The effective collection settings at any moment: built-in defaults (with
 * the app's local collection settings), then the remote overlay, clamped to
 * the app's ceilings, then any temporary "stream for a while" overrides from
 * triggers and captures.
 *
 * Not thread-safe: the client calls it under its lock.
 */
internal class ControlState(
    private val defaults: Settings,
    private val appCeilings: Ceilings,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var ceilings: Ceilings = appCeilings
    private var effective: Settings = clampToCeilings(defaults, appCeilings)
    private val overrides = LinkedHashMap<String, MutableList<StreamOverride>>()
    private var overrideSequence = 0
    private val listeners = ArrayList<(Settings) -> Unit>()

    val settings: Settings get() = effective

    /** Applies a parsed remote document, or `null` to fall back to defaults. */
    fun applyRemote(overlay: JsonObject?) {
        ceilings = applyLimits(appCeilings, overlay)
        val merged = if (overlay == null) defaults else applyOverlay(defaults, overlay)
        effective = clampToCeilings(merged, ceilings)
        for (listener in ArrayList(listeners)) {
            try {
                listener(effective)
            } catch (_: Throwable) {
                // A listener must not stop the others from seeing the change.
            }
        }
    }

    fun onChange(listener: (Settings) -> Unit) {
        listeners.add(listener)
    }

    fun addOverride(override: StreamOverride) = setOverrides("stream:${overrideSequence++}", listOf(override))

    fun setOverrides(owner: String, list: List<StreamOverride>) {
        if (list.isEmpty()) overrides.remove(owner) else overrides[owner] = list.toMutableList()
        activeOverrides()
    }

    /** When the next override ends, so the caller can re-apply replay then. */
    val nextOverrideExpiryMs: Long?
        get() = activeOverrides().minOfOrNull { it.untilMs }

    private fun activeOverrides(): List<StreamOverride> {
        val t = now()
        val it = overrides.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            entry.value.removeAll { o -> o.untilMs <= t }
            if (entry.value.isEmpty()) it.remove()
        }
        return overrides.values.flatten()
    }

    fun canExport(signal: String): Boolean =
        enabled && ceilings.signals[signal] == Mode.STREAM && modeFor(signal) != Mode.OFF

    val enabled: Boolean get() = effective.enabled

    fun modeFor(signal: String): Mode {
        if (!effective.enabled) return Mode.OFF
        val base = effective.signals[signal]?.mode ?: return Mode.OFF
        if (base == Mode.STREAM) return base
        for (override in activeOverrides()) {
            if (covers(override, signal, base)) {
                return lowerMode(Mode.STREAM, ceilings.signals.getValue(signal))
            }
        }
        return base
    }

    private fun covers(override: StreamOverride, signal: String, base: Mode): Boolean {
        if (signal in override.signals) return true
        if ("*" !in override.signals) return false
        return base != Mode.OFF || (override.includeOff && defaults.signal(signal).mode != Mode.OFF)
    }

    val replayMode: Mode
        get() {
            if (!effective.enabled) return Mode.OFF
            val base = effective.replayMode
            if (base != Mode.STREAM && activeOverrides().any { it.replay }) {
                return lowerMode(Mode.STREAM, ceilings.replay)
            }
            return base
        }

    fun sampleRate(signal: String): Double = effective.signals[signal]?.sampleRate ?: 1.0

    /** The aggregates setting, or null when metrics are off: kill switch, `captureMetrics`, the plan or a rule. */
    val metrics: MetricsSettings?
        get() {
            val m = effective.metrics
            return if (effective.enabled && m.enabled) m else null
        }
}
