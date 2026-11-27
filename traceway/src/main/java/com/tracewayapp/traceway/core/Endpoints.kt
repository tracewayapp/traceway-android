package com.tracewayapp.traceway.core

import com.tracewayapp.traceway.TracewayTelemetry
import com.tracewayapp.traceway.TracewayTelemetryEndpoint
import com.tracewayapp.traceway.internal.Json
import java.security.MessageDigest
import java.util.Locale

internal fun destinationDigest(parts: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(Json.stringify(parts).toByteArray(Charsets.UTF_8))
    return Ids.bytesToHex(digest)
}

/** Where one OTLP signal is sent, and with which headers. */
internal class OtlpDestination(
    val url: String,
    val headers: Map<String, String>,
    /** Not Traceway: a receiver from [TracewayTelemetry]. */
    val external: Boolean = false,
) {
    /**
     * Identifies the destination, for state kept per destination (queued
     * batches, pending metrics), so a changed receiver is never sent what
     * was meant for the previous one.
     */
    val key: String by lazy {
        val normalized = java.util.TreeMap<String, String>()
        for ((k, v) in headers) normalized[k.lowercase(Locale.US)] = v
        val parts = ArrayList<String>()
        parts.add(if (external) "collector" else "traceway")
        parts.add(url)
        for ((k, v) in normalized) {
            parts.add(k)
            parts.add(v)
        }
        destinationDigest(parts)
    }
}

internal class TracewayEndpoints(
    /** Where the Traceway server is reachable, without a trailing slash. */
    val base: String,
    val logs: String,
    val traces: String,
    val metrics: String,
    val opamp: String,
    val recordings: String,
) {
    /** True for the SDK's own requests, which must not be recorded as network telemetry. */
    fun isOwn(url: String): Boolean =
        base.isNotEmpty() && (url.startsWith("$base/api/otel/") || url.startsWith(recordings))
}

/**
 * Derives every endpoint the SDK talks to from the URL half of the
 * connection string. Accepts the bare server URL as well as the 1.x form
 * that ended in `/api/report`, so existing connection strings keep working.
 */
internal fun resolveEndpoints(apiUrl: String): TracewayEndpoints {
    val trimmed = apiUrl.trim()
        .replace(Regex("/+$"), "")
        .replace(Regex("/api/report$"), "")
        .replace(Regex("/api$"), "")
    val base = normalizeUrl(trimmed)
    return TracewayEndpoints(
        base = base,
        logs = "$base/api/otel/v1/logs",
        traces = "$base/api/otel/v1/traces",
        metrics = "$base/api/otel/v1/metrics",
        opamp = "$base/api/otel/v1/opamp",
        recordings = "$base/api/recordings",
    )
}

/**
 * An absolute URL split into its parts. Not `java.net.URI`, which has no
 * host for names with `_` (Docker service names such as `otel_collector`)
 * and parses differently on Android and the JVM.
 */
private class UrlParts(
    val scheme: String,
    val userInfo: String?,
    val host: String,
    val port: Long?,
    val path: String,
    val query: String?,
    val fragment: String?,
) {
    /** As Dart's `Uri.toString()` writes it, which is the form request URLs take: lowercase scheme and host, no default port. */
    fun normalized(): String {
        val out = StringBuilder()
        out.append(scheme.lowercase(Locale.US)).append("://")
        if (!userInfo.isNullOrEmpty()) out.append(userInfo).append('@')
        out.append(host.lowercase(Locale.US))
        val defaultPort = when (scheme.lowercase(Locale.US)) {
            "http" -> 80L
            "https" -> 443L
            else -> null
        }
        if (port != null && port != defaultPort) out.append(':').append(port)
        out.append(path)
        if (query != null) out.append('?').append(query)
        if (fragment != null) out.append('#').append(fragment)
        return out.toString()
    }

    companion object {
        private val PATTERN = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]*)([^?#]*)(\?[^#]*)?(#.*)?$""")
        private val REG_NAME = Regex("""^[A-Za-z0-9\-._~!$&'()*+,;=%]*$""")
        private val IP_LITERAL = Regex("""^\[[0-9A-Fa-f:.]+]$""")

        /** Null when [raw] is not an absolute URL with an authority. */
        fun parse(raw: String): UrlParts? {
            val m = PATTERN.matchEntire(raw) ?: return null
            val authority = m.groupValues[2]
            val at = authority.lastIndexOf('@')
            val userInfo = if (at >= 0) authority.substring(0, at) else null
            val hostPort = authority.substring(at + 1)
            val host: String
            val portText: String?
            if (hostPort.startsWith("[")) {
                val close = hostPort.indexOf(']')
                if (close < 0) return null
                host = hostPort.substring(0, close + 1)
                val rest = hostPort.substring(close + 1)
                if (rest.isNotEmpty() && !rest.startsWith(":")) return null
                portText = if (rest.isEmpty()) null else rest.substring(1)
                if (!IP_LITERAL.matches(host)) return null
            } else {
                val colon = hostPort.indexOf(':')
                host = if (colon >= 0) hostPort.substring(0, colon) else hostPort
                portText = if (colon >= 0) hostPort.substring(colon + 1) else null
                if (!REG_NAME.matches(host)) return null
            }
            val port = when {
                portText.isNullOrEmpty() -> null
                portText.all { it in '0'..'9' } -> portText.toLongOrNull() ?: return null
                else -> return null
            }
            return UrlParts(
                scheme = m.groupValues[1],
                userInfo = userInfo,
                host = host,
                port = port,
                path = m.groupValues[3],
                query = m.groups[4]?.value?.substring(1),
                fragment = m.groups[5]?.value?.substring(1),
            )
        }
    }
}

/** The form request URLs take (lowercase scheme and host, no default port), so [TracewayEndpoints.isOwn] matches. */
private fun normalizeUrl(raw: String): String {
    if (raw.isEmpty()) return raw
    return UrlParts.parse(raw)?.normalized() ?: raw
}

/** The destination of every OTLP signal: Traceway's, or the receiver the app configured in [TracewayTelemetry]. */
internal class TelemetryRoutes private constructor(
    val logs: OtlpDestination,
    val traces: OtlpDestination,
    val metrics: OtlpDestination,
    /**
     * Traceway's own log endpoint, for exceptions, messages and the SDK's
     * control records when ordinary logs go elsewhere. Null when standalone.
     */
    val traceway: OtlpDestination?,
) {
    /** Whether logs go somewhere other than Traceway while Traceway still receives issues and session records. */
    val splitsLogs: Boolean get() = logs.external && traceway != null

    /** True for requests to any external destination of the SDK. */
    fun isOwn(url: String): Boolean =
        (logs.external && url.startsWith(logs.url)) ||
            (traces.external && url.startsWith(traces.url)) ||
            (metrics.external && url.startsWith(metrics.url))

    companion object {
        /**
         * [endpoints] and [token] are null when the SDK runs standalone; then
         * [telemetry] must name a destination for every signal.
         * @throws IllegalArgumentException for an unusable URL or a missing standalone destination.
         */
        fun resolve(endpoints: TracewayEndpoints?, token: String?, telemetry: TracewayTelemetry?): TelemetryRoutes {
            val auth: Map<String, String> = if (token != null) mapOf("Authorization" to "Bearer $token") else emptyMap()
            fun route(kind: String, endpoint: TracewayTelemetryEndpoint?, tracewayUrl: String?): OtlpDestination {
                val base = telemetry?.baseUrl
                if (endpoint == null && base == null) {
                    requireNotNull(tracewayUrl) {
                        "TracewayTelemetry has no destination for $kind: standalone telemetry needs a base url " +
                            "or all three signal endpoints"
                    }
                    return OtlpDestination(tracewayUrl, auth)
                }
                val url = checkedUrl(endpoint?.url ?: base!!)
                val headers = LinkedHashMap<String, String>()
                telemetry?.headers?.let { headers.putAll(it) }
                endpoint?.headers?.let { headers.putAll(it) }
                return OtlpDestination(
                    if (endpoint != null) url else "${url.replace(Regex("/+$"), "")}/v1/$kind",
                    headers,
                    external = true,
                )
            }
            return TelemetryRoutes(
                route("logs", telemetry?.logs, endpoints?.logs),
                route("traces", telemetry?.traces, endpoints?.traces),
                route("metrics", telemetry?.metrics, endpoints?.metrics),
                if (endpoints == null) null else OtlpDestination(endpoints.logs, auth),
            )
        }

        private fun checkedUrl(raw: String): String {
            val url = UrlParts.parse(raw.trim())
            val scheme = url?.scheme?.lowercase(Locale.US)
            require(
                url != null &&
                    (scheme == "http" || scheme == "https") &&
                    url.host.isNotEmpty() &&
                    url.userInfo.isNullOrEmpty() &&
                    url.query == null &&
                    url.fragment == null,
            ) { "TracewayTelemetry url must be an HTTP(S) URL without credentials, query, or fragment: $raw" }
            return url!!.normalized()
        }
    }
}
