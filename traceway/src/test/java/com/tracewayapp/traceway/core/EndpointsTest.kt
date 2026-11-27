package com.tracewayapp.traceway.core

import com.tracewayapp.traceway.TracewayTelemetry
import com.tracewayapp.traceway.TracewayTelemetryEndpoint
import com.tracewayapp.traceway.internal.parseConnectionString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EndpointsTest {

    @Test
    fun `accepts the bare url and the 1_x report url`() {
        for (url in listOf(
            "https://host",
            "https://host/",
            "https://host/api",
            "https://host/api/report",
            "https://host/api/report/",
            "  https://host//  ",
        )) {
            val e = resolveEndpoints(url)
            assertEquals(url, "https://host", e.base)
            assertEquals(url, "https://host/api/otel/v1/logs", e.logs)
            assertEquals(url, "https://host/api/otel/v1/traces", e.traces)
            assertEquals(url, "https://host/api/otel/v1/metrics", e.metrics)
            assertEquals(url, "https://host/api/otel/v1/opamp", e.opamp)
            assertEquals(url, "https://host/api/recordings", e.recordings)
        }
    }

    @Test
    fun `keeps a path prefix and a port`() {
        val e = resolveEndpoints("http://10.0.2.2:8082/traceway/api/report")
        assertEquals("http://10.0.2.2:8082/traceway", e.base)
        assertEquals("http://10.0.2.2:8082/traceway/api/otel/v1/logs", e.logs)
        assertEquals("http://otel_collector:4318", resolveEndpoints("http://OTEL_Collector:4318/api").base)
        assertEquals("http://[::1]:8080", resolveEndpoints("http://[::1]:8080").base)
    }

    @Test
    fun `recognises the SDK's own requests`() {
        val e = resolveEndpoints("https://host/api/report")
        assertTrue(e.isOwn("https://host/api/otel/v1/logs"))
        assertTrue(e.isOwn("https://host/api/recordings"))
        assertFalse(e.isOwn("https://host/api/users"))
        assertFalse(e.isOwn("https://other/api/otel/v1/logs"))
    }

    @Test
    fun `recognises its own requests whatever the case of the host or a default port`() {
        // Request URLs come normalized: the host lowercased, the default port left out.
        for (url in listOf("https://Traceway.Test/api/report", "https://traceway.test:443", "HTTPS://TRACEWAY.TEST:443/")) {
            val e = resolveEndpoints(url)
            assertTrue(url, e.isOwn("https://traceway.test/api/otel/v1/logs"))
            assertTrue(url, e.isOwn("https://traceway.test/api/recordings"))
        }
        assertEquals("http://host:8080", resolveEndpoints("http://host:8080").base)
        assertEquals("http://host:443", resolveEndpoints("http://host:443").base)
        assertEquals("http://host", resolveEndpoints("http://host:80").base)
    }

    @Test
    fun `connection strings split at the first at sign`() {
        for ((connection, token, base) in listOf(
            Triple("token@https://traceway.example.com", "token", "https://traceway.example.com"),
            Triple("mytoken@https://traceway.example.com/api/report", "mytoken", "https://traceway.example.com"),
            Triple("abc-123_def@https://api.example.com/report", "abc-123_def", "https://api.example.com/report"),
            Triple("token@https://user@host.com/api", "token", "https://user@host.com"),
        )) {
            val parsed = parseConnectionString(connection)
            assertEquals(connection, token, parsed.token)
            assertEquals(connection, base, parsed.baseUrl)
            assertEquals(connection, base, resolveEndpoints(parsed.baseUrl).base)
        }
        for (bad in listOf("notokenhere", "@https://example.com", "token@")) {
            try {
                parseConnectionString(bad)
                fail("accepted $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    /** CONTRACT §16.1: the shared table of connection-string variations, the same on every SDK. */
    @Test
    fun `every connection string of the shared table parses to its token and server`() {
        for ((input, base) in listOf(
            "tok@https://cloud.tracewayapp.com" to "https://cloud.tracewayapp.com",
            "tok@https://cloud.tracewayapp.com/" to "https://cloud.tracewayapp.com",
            "tok@https://cloud.tracewayapp.com/api" to "https://cloud.tracewayapp.com",
            "tok@https://cloud.tracewayapp.com/api/" to "https://cloud.tracewayapp.com",
            "tok@https://cloud.tracewayapp.com/api/report" to "https://cloud.tracewayapp.com",
            "tok@https://cloud.tracewayapp.com/api/report/" to "https://cloud.tracewayapp.com",
            "tok@cloud.tracewayapp.com" to "https://cloud.tracewayapp.com",
            "tok@cloud.tracewayapp.com/api/report" to "https://cloud.tracewayapp.com",
            "  tok@https://cloud.tracewayapp.com/api/report  " to "https://cloud.tracewayapp.com",
            "tok@http://localhost:8082/api/report" to "http://localhost:8082",
            "tok@localhost:8082" to "http://localhost:8082",
            "tok@https://corp.example/traceway/api/report" to "https://corp.example/traceway",
            "tok@https://cloud.tracewayapp.com/api/report?x=1" to "https://cloud.tracewayapp.com",
        )) {
            val parsed = parseConnectionString(input)
            assertEquals(input, "tok", parsed.token)
            assertEquals(input, base, parsed.baseUrl)
        }
        for (bad in listOf("@https://cloud.tracewayapp.com", "tok", "tok@", "tok@ftp://host")) {
            try {
                parseConnectionString(bad)
                fail("accepted $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `a server url alone is normalized by the same rules`() {
        for ((input, base) in listOf(
            "https://cloud.tracewayapp.com/api/report" to "https://cloud.tracewayapp.com",
            "cloud.tracewayapp.com" to "https://cloud.tracewayapp.com",
            " https://cloud.tracewayapp.com/ " to "https://cloud.tracewayapp.com",
            "10.0.2.2:8082" to "http://10.0.2.2:8082",
            "127.0.0.1:8082/api" to "http://127.0.0.1:8082",
            "[::1]:8082" to "http://[::1]:8082",
            "http://10.0.2.2:8082" to "http://10.0.2.2:8082",
            "https://corp.example/traceway/" to "https://corp.example/traceway",
            "https://cloud.tracewayapp.com#top" to "https://cloud.tracewayapp.com",
            "https://cloud.tracewayapp.com/api/api" to "https://cloud.tracewayapp.com/api",
        )) {
            assertEquals(input, base, normalizeBaseUrl(input))
            assertEquals(input, base, resolveEndpoints(input).base)
        }
        for (bad in listOf("", "   ", "https://", "ftp://h", "https:///path")) {
            try {
                resolveEndpoints(bad)
                fail("accepted \"$bad\"")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    private val traceway = resolveEndpoints("https://traceway.test/api/report")

    @Test
    fun `without telemetry every signal goes to Traceway with the token`() {
        val routes = TelemetryRoutes.resolve(traceway, "tok", null)
        assertEquals("https://traceway.test/api/otel/v1/logs", routes.logs.url)
        assertEquals("https://traceway.test/api/otel/v1/traces", routes.traces.url)
        assertEquals("https://traceway.test/api/otel/v1/metrics", routes.metrics.url)
        assertEquals(mapOf("Authorization" to "Bearer tok"), routes.logs.headers)
        assertFalse(routes.logs.external)
        assertFalse(routes.splitsLogs)
        // Traceway requests are recognised by TracewayEndpoints.
        assertFalse(routes.isOwn("https://traceway.test/api/otel/v1/logs"))
        assertEquals("https://traceway.test/api/otel/v1/logs", routes.traceway!!.url)
    }

    @Test
    fun `a base url appends the signal paths and never sends the token`() {
        val routes = TelemetryRoutes.resolve(
            traceway,
            "tok",
            TracewayTelemetry(url = "https://collector.test/otlp/", headers = mapOf("x-key" to "k")),
        )
        assertEquals("https://collector.test/otlp/v1/logs", routes.logs.url)
        assertEquals("https://collector.test/otlp/v1/traces", routes.traces.url)
        assertEquals("https://collector.test/otlp/v1/metrics", routes.metrics.url)
        for (d in listOf(routes.logs, routes.traces, routes.metrics)) {
            assertEquals(mapOf("x-key" to "k"), d.headers)
            assertTrue(d.external)
        }
        assertTrue(routes.splitsLogs)
        assertEquals(mapOf("Authorization" to "Bearer tok"), routes.traceway!!.headers)
        assertTrue(routes.isOwn("https://collector.test/otlp/v1/traces"))
        assertFalse(routes.isOwn("https://api.example.com/cart"))
    }

    @Test
    fun `a signal endpoint is used as given and overrides headers`() {
        val routes = TelemetryRoutes.resolve(
            traceway,
            "tok",
            TracewayTelemetry(
                headers = mapOf("x-key" to "shared", "x-team" to "app"),
                metrics = TracewayTelemetryEndpoint(url = "https://metrics.test/ingest", headers = mapOf("x-key" to "metrics")),
            ),
        )
        assertEquals("https://metrics.test/ingest", routes.metrics.url)
        assertEquals(mapOf("x-key" to "metrics", "x-team" to "app"), routes.metrics.headers)
        // Signals without a destination stay on Traceway.
        assertEquals("https://traceway.test/api/otel/v1/logs", routes.logs.url)
        assertFalse(routes.traces.external)
        assertFalse(routes.splitsLogs)
    }

    @Test
    fun `collector urls are normalized as requests carry them`() {
        val routes = TelemetryRoutes.resolve(
            traceway,
            "tok",
            TracewayTelemetry(
                url = "HTTPS://Collector.Test:443",
                logs = TracewayTelemetryEndpoint(url = " http://otel_collector:4318/v1/logs "),
            ),
        )
        assertEquals("http://otel_collector:4318/v1/logs", routes.logs.url)
        assertEquals("https://collector.test/v1/traces", routes.traces.url)
        assertTrue(routes.isOwn("https://collector.test/v1/traces"))
    }

    @Test
    fun `rejects URLs that are not plain HTTP(S)`() {
        for (url in listOf(
            "ftp://collector.test",
            "https://user:pw@collector.test",
            "https://collector.test/?key=1",
            "https://collector.test/?",
            "https://collector.test/#x",
            "collector.test",
            "https://",
            "https://:4318/v1",
            "https://collector.test:port/v1",
            "https://bad host/v1",
            "https://[::1/v1",
        )) {
            try {
                TelemetryRoutes.resolve(traceway, "tok", TracewayTelemetry(url = url))
                fail("accepted $url")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `standalone needs a destination for every signal`() {
        try {
            TelemetryRoutes.resolve(
                null,
                null,
                TracewayTelemetry(logs = TracewayTelemetryEndpoint(url = "https://c.test/v1/logs")),
            )
            fail("standalone without traces and metrics")
        } catch (_: IllegalArgumentException) {
        }
        val routes = TelemetryRoutes.resolve(null, null, TracewayTelemetry(url = "https://c.test"))
        assertNull(routes.traceway)
        assertFalse(routes.splitsLogs)
        assertTrue(routes.logs.headers.isEmpty())
        assertEquals("https://c.test/v1/logs", routes.logs.url)
    }

    @Test
    fun `an empty base url counts as unset`() {
        assertFalse(TracewayTelemetry(url = "").coversAllSignals)
        val routes = TelemetryRoutes.resolve(resolveEndpoints("https://traceway.test"), "tok", TracewayTelemetry(url = " "))
        assertFalse(routes.logs.external)
    }

    @Test
    fun `collector identity includes tenant headers and canonicalizes their order and names`() {
        val url = "https://collector.test/v1/logs"
        val current = OtlpDestination(url, mapOf("Authorization" to "one", "X-Tenant" to "a"), external = true)
        assertEquals(current.key, OtlpDestination(url, mapOf("x-tenant" to "a", "authorization" to "one"), external = true).key)
        assertNotEquals(current.key, OtlpDestination(url, mapOf("Authorization" to "one", "X-Tenant" to "b"), external = true).key)
        assertNotEquals(current.key, OtlpDestination(url, mapOf("Authorization" to "one", "X-Tenant" to "a")).key)
        assertNotEquals(current.key, OtlpDestination("$url/", mapOf("Authorization" to "one", "X-Tenant" to "a"), external = true).key)
        assertEquals(64, current.key.length)
    }
}
