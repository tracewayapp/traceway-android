package com.tracewayapp.traceway

/**
 * One OTLP/HTTP signal endpoint. [url] is the complete signal URL (for
 * example `https://collector.example.com/v1/metrics`); no path is appended.
 * A null or unusable [url] makes [Traceway.init] log the error and return
 * null.
 */
data class TracewayTelemetryEndpoint @JvmOverloads constructor(
    val url: String?,
    /** Override the shared [TracewayTelemetry.headers] key by key. */
    val headers: Map<String, String>? = emptyMap(),
)

/**
 * Sends logs, spans and metrics to another OpenTelemetry (OTLP/HTTP,
 * protobuf) receiver, next to Traceway or, without a token
 * ([TracewayOptions.token]), instead of it.
 *
 * The shared [url] gets `/v1/logs`, `/v1/traces` and `/v1/metrics`
 * appended. Each signal can override it with a [TracewayTelemetryEndpoint]
 * whose URL is used as given. Without a shared [url], the signals not listed
 * keep their Traceway destination. The Traceway token is never sent to these
 * receivers. From Java, [builder] sets one field at a time.
 */
data class TracewayTelemetry @JvmOverloads constructor(
    val url: String? = null,
    val headers: Map<String, String>? = emptyMap(),
    val logs: TracewayTelemetryEndpoint? = null,
    val traces: TracewayTelemetryEndpoint? = null,
    val metrics: TracewayTelemetryEndpoint? = null,
) {
    internal val baseUrl: String?
        get() = url?.trim()?.takeIf { it.isNotEmpty() }

    /** Whether every signal has a destination here, as standalone mode needs. */
    internal val coversAllSignals: Boolean
        get() = baseUrl != null || (logs != null && traces != null && metrics != null)

    /** This destination as a [Builder], to change part of it. */
    fun toBuilder(): Builder = Builder(this)

    /** Builds a [TracewayTelemetry] one field at a time, for Java; null means the field's default. */
    class Builder internal constructor(private var telemetry: TracewayTelemetry) {
        constructor() : this(TracewayTelemetry())

        fun url(url: String?): Builder = apply { telemetry = telemetry.copy(url = url) }
        fun headers(headers: Map<String, String>?): Builder = apply { telemetry = telemetry.copy(headers = headers ?: emptyMap()) }
        fun logs(logs: TracewayTelemetryEndpoint?): Builder = apply { telemetry = telemetry.copy(logs = logs) }
        fun traces(traces: TracewayTelemetryEndpoint?): Builder = apply { telemetry = telemetry.copy(traces = traces) }
        fun metrics(metrics: TracewayTelemetryEndpoint?): Builder = apply { telemetry = telemetry.copy(metrics = metrics) }
        fun build(): TracewayTelemetry = telemetry
    }

    companion object {
        /** A [Builder] with no destination yet, for Java. */
        @JvmStatic
        fun builder(): Builder = Builder()
    }
}
