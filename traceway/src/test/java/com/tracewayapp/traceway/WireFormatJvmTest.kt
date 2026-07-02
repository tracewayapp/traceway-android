package com.tracewayapp.traceway

import com.tracewayapp.traceway.events.CustomEvent
import com.tracewayapp.traceway.events.LogEvent
import com.tracewayapp.traceway.events.NavigationEvent
import com.tracewayapp.traceway.events.NetworkEvent
import com.tracewayapp.traceway.events.TracewayEvent
import com.tracewayapp.traceway.models.CollectionFrame
import com.tracewayapp.traceway.models.ExceptionStackTrace
import com.tracewayapp.traceway.models.ReportRequest
import com.tracewayapp.traceway.models.SessionRecordingPayload
import com.tracewayapp.traceway.models.Span
import com.tracewayapp.traceway.models.Trace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format must match the Flutter SDK exactly so the same backend
 * ingests both. These assertions are the contract — if a key name, casing,
 * or shape changes here, the backend breaks.
 */
class WireFormatJvmTest {

    @Test
    fun reportRequestHasCollectionFramesAppVersionServerName() {
        val req = ReportRequest(
            collectionFrames = listOf(CollectionFrame(stackTraces = emptyList())),
            appVersion = "9.9.9",
            serverName = "srv-1",
        )
        val json = req.toJson()
        assertEquals("9.9.9", json.getString("appVersion"))
        assertEquals("srv-1", json.getString("serverName"))
        assertEquals(1, json.getJSONArray("collectionFrames").length())
    }

    @Test
    fun collectionFrameOmitsSessionRecordingsWhenNull() {
        val frame = CollectionFrame(stackTraces = emptyList(), sessionRecordings = null)
        val json = frame.toJson()
        assertFalse("sessionRecordings must be absent when null", json.has("sessionRecordings"))
        // metrics + traces are always emitted (as empty arrays) for parity.
        assertEquals(0, json.getJSONArray("metrics").length())
        assertEquals(0, json.getJSONArray("traces").length())
    }

    @Test
    fun exceptionStackTraceJsonShape() {
        val ex = ExceptionStackTrace(
            stackTrace = "java.lang.IllegalStateException: boom\n  at X",
            recordedAtMs = 1_700_000_000_000L,
            isMessage = false,
            sessionRecordingId = "rec-id",
            attributes = mapOf("device.model" to "Pixel 8"),
        )
        val json = ex.toJson()

        assertEquals("java.lang.IllegalStateException: boom\n  at X", json.getString("stackTrace"))
        assertEquals(false, json.getBoolean("isMessage"))
        assertEquals(false, json.getBoolean("isTask"))
        assertEquals("2023-11-14T22:13:20.000Z", json.getString("recordedAt"))
        assertEquals("rec-id", json.getString("sessionRecordingId"))
        assertEquals("Pixel 8", json.getJSONObject("attributes").getString("device.model"))
    }

    @Test
    fun sessionRecordingPayloadOmitsEmptyOptionals() {
        val payload = SessionRecordingPayload(exceptionId = "id-1")
        val json = payload.toJson()
        assertEquals("id-1", json.getString("exceptionId"))
        assertNotNull(json.getJSONArray("events"))
        assertFalse(json.has("logs"))
        assertFalse(json.has("actions"))
        assertFalse(json.has("startedAt"))
        assertFalse(json.has("endedAt"))
    }

    @Test
    fun sessionRecordingPayloadEmitsLogsAndActionsWhenPresent() {
        val payload = SessionRecordingPayload(
            exceptionId = "id-2",
            logs = listOf(LogEvent("hello", timestampMs = 1_700_000_000_000L)),
            actions = listOf<TracewayEvent>(
                NetworkEvent(
                    method = "GET",
                    url = "https://api.example.com/users",
                    durationMs = 42L,
                    statusCode = 200,
                    timestampMs = 1_700_000_000_500L,
                ),
            ),
            startedAtMs = 1_700_000_000_000L,
            endedAtMs = 1_700_000_000_500L,
        )
        val json = payload.toJson()

        assertEquals("2023-11-14T22:13:20.000Z", json.getString("startedAt"))
        assertEquals("2023-11-14T22:13:20.500Z", json.getString("endedAt"))

        val logs = json.getJSONArray("logs")
        assertEquals(1, logs.length())
        val log = logs.getJSONObject(0)
        assertEquals("log", log.getString("type"))
        assertEquals("info", log.getString("level"))
        assertEquals("hello", log.getString("message"))

        val actions = json.getJSONArray("actions")
        assertEquals(1, actions.length())
        val net = actions.getJSONObject(0)
        assertEquals("network", net.getString("type"))
        assertEquals("GET", net.getString("method"))
        assertEquals("https://api.example.com/users", net.getString("url"))
        assertEquals(42L, net.getLong("durationMs"))
        assertEquals(200, net.getInt("statusCode"))
    }

    @Test
    fun navigationEventJsonShape() {
        val ev = NavigationEvent(
            action = "push",
            from = "Home",
            to = "Detail",
            timestampMs = 1_700_000_000_000L,
        )
        val json = ev.toJson()
        assertEquals("navigation", json.getString("type"))
        assertEquals("push", json.getString("action"))
        assertEquals("Home", json.getString("from"))
        assertEquals("Detail", json.getString("to"))
    }

    @Test
    fun customEventEmitsDataMap() {
        val ev = CustomEvent(
            category = "cart",
            name = "add_item",
            data = mapOf("sku" to "SKU-1", "qty" to 2),
            timestampMs = 1_700_000_000_000L,
        )
        val json = ev.toJson()
        assertEquals("custom", json.getString("type"))
        assertEquals("cart", json.getString("category"))
        assertEquals("add_item", json.getString("name"))
        val data = json.getJSONObject("data")
        assertEquals("SKU-1", data.getString("sku"))
        assertEquals(2, data.getInt("qty"))
    }

    @Test
    fun networkEventOmitsAbsentOptionals() {
        val ev = NetworkEvent(
            method = "POST",
            url = "https://api/x",
            durationMs = 10,
            // statusCode/requestBytes/responseBytes/error all absent
            timestampMs = 1_700_000_000_000L,
        )
        val json = ev.toJson()
        assertFalse(json.has("statusCode"))
        assertFalse(json.has("requestBytes"))
        assertFalse(json.has("responseBytes"))
        assertFalse(json.has("error"))
    }

    @Test
    fun parseFromJsonRoundTripsExceptionStackTrace() {
        val original = ExceptionStackTrace(
            stackTrace = "msg",
            recordedAtMs = 1_700_000_000_000L,
            isMessage = true,
            attributes = mapOf("a" to "b"),
            sessionRecordingId = "rid",
        )
        val parsed = ExceptionStackTrace.fromJson(original.toJson())
        assertEquals("msg", parsed.stackTrace)
        assertEquals(1_700_000_000_000L, parsed.recordedAtMs)
        assertTrue(parsed.isMessage)
        assertEquals("b", parsed.attributes!!["a"])
        assertEquals("rid", parsed.sessionRecordingId)
    }

    @Test
    fun traceJsonShapeEmitsDurationInNanoseconds() {
        val trace = Trace(
            id = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
            endpoint = "GET /api/users/:id",
            durationMs = 15_234,
            recordedAtMs = 1_700_000_000_000L,
            statusCode = 200,
            bodySize = 1024,
        )
        val json = trace.toJson()

        assertEquals("f47ac10b-58cc-4372-a567-0e02b2c3d479", json.getString("id"))
        assertEquals("GET /api/users/:id", json.getString("endpoint"))
        // The backend reads `duration` as a Go time.Duration (int64 nanoseconds).
        assertEquals(15_234_000_000L, json.getLong("duration"))
        assertEquals("2023-11-14T22:13:20.000Z", json.getString("recordedAt"))
        assertEquals(200, json.getInt("statusCode"))
        assertEquals(1024, json.getInt("bodySize"))
        assertEquals(false, json.getBoolean("isTask"))
    }

    @Test
    fun spanJsonShapeEmitsDurationInNanoseconds() {
        val span = Span(
            id = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
            name = "db.query",
            startTimeMs = 1_700_000_000_100L,
            durationMs = 5_000,
        )
        val json = span.toJson()

        assertEquals("db.query", json.getString("name"))
        assertEquals("2023-11-14T22:13:20.100Z", json.getString("startTime"))
        assertEquals(5_000_000_000L, json.getLong("duration"))
    }

    @Test
    fun parseFromJsonDispatchesEventTypes() {
        val log = TracewayEvent.fromJson(LogEvent("x", timestampMs = 1L).toJson())
        assertTrue(log is LogEvent)

        val nav = TracewayEvent.fromJson(
            NavigationEvent("push", from = "A", to = "B", timestampMs = 1L).toJson()
        )
        assertTrue(nav is NavigationEvent)

        val net = TracewayEvent.fromJson(
            NetworkEvent("GET", "u", 5L, timestampMs = 1L).toJson()
        )
        assertTrue(net is NetworkEvent)

        val custom = TracewayEvent.fromJson(
            CustomEvent("c", "n", data = mapOf("k" to 1), timestampMs = 1L).toJson()
        )
        assertTrue(custom is CustomEvent)
    }
}
