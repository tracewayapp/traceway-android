package com.tracewayapp.traceway.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tracewayapp.traceway.TracewayOptions
import com.tracewayapp.traceway.events.NetworkEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the over-the-wire request as a real HTTP/1.1 POST: gzip encoding,
 * Bearer auth header, and the full Flutter-compatible JSON wire shape.
 */
@RunWith(AndroidJUnit4::class)
class WireFormatInstrumentedTest {

    @get:Rule
    val rule = TracewayTestRule(
        options = TracewayOptions(
            debounceMs = 50,
            persistToDisk = false,
            captureLogs = true,
            captureNetwork = true,
            captureNavigation = true,
            version = "9.9.9",
            debug = true,
        ),
    )

    @Test
    fun wireFormat_matchesFlutterContract() {
        rule.client.recordLog("hello world", level = "info")
        rule.client.recordNetworkEvent(
            NetworkEvent(
                method = "POST",
                url = "https://api.example.com/foo",
                durationMs = 33L,
                statusCode = 201,
            ),
        )
        rule.client.recordAction(
            category = "cart",
            name = "add_item",
            data = mapOf("sku" to "SKU-7", "qty" to 2),
        )
        rule.client.captureException(IllegalStateException("the boom"))

        val req = rule.flushAndTakeRequest()
            ?: error("MockWebServer never received a report")

        // Headers
        assertEquals("POST", req.method)
        assertEquals("application/json", req.getHeader("Content-Type"))
        assertEquals("gzip", req.getHeader("Content-Encoding"))
        assertEquals("Bearer test-token", req.getHeader("Authorization"))

        // Body
        val payload = rule.parseRequestBody(req)
        assertEquals("9.9.9", payload.getString("appVersion"))
        assertEquals("", payload.getString("serverName"))

        val frames = payload.getJSONArray("collectionFrames")
        assertEquals(1, frames.length())
        val frame = frames.getJSONObject(0)

        // metrics + traces are always present, both empty here.
        assertEquals(0, frame.getJSONArray("metrics").length())
        assertEquals(0, frame.getJSONArray("traces").length())

        val stacks = frame.getJSONArray("stackTraces")
        assertEquals(1, stacks.length())
        val stack = stacks.getJSONObject(0)
        assertEquals(false, stack.getBoolean("isMessage"))
        assertTrue(
            "stackTrace must contain exception text: ${stack.getString("stackTrace")}",
            stack.getString("stackTrace").contains("IllegalStateException") &&
                stack.getString("stackTrace").contains("the boom"),
        )
        assertNotNull(stack.optString("recordedAt").takeIf { it.isNotEmpty() })
        // sessionRecordingId links the stack trace to the matching recording.
        val recId = stack.getString("sessionRecordingId")
        assertTrue("expected sessionRecordingId set", recId.isNotEmpty())

        val recordings = frame.getJSONArray("sessionRecordings")
        assertEquals(1, recordings.length())
        val recording = recordings.getJSONObject(0)
        assertEquals(recId, recording.getString("exceptionId"))

        val logs = recording.getJSONArray("logs")
        assertEquals(1, logs.length())
        assertEquals("log", logs.getJSONObject(0).getString("type"))
        assertEquals("info", logs.getJSONObject(0).getString("level"))
        assertEquals("hello world", logs.getJSONObject(0).getString("message"))

        val actions = recording.getJSONArray("actions")
        // Two actions: 1 network + 1 custom
        assertEquals(2, actions.length())
        // Find by type to avoid ordering brittleness.
        var sawNetwork = false
        var sawCustom = false
        for (i in 0 until actions.length()) {
            val a = actions.getJSONObject(i)
            when (a.getString("type")) {
                "network" -> {
                    sawNetwork = true
                    assertEquals("POST", a.getString("method"))
                    assertEquals("https://api.example.com/foo", a.getString("url"))
                    assertEquals(33L, a.getLong("durationMs"))
                    assertEquals(201, a.getInt("statusCode"))
                }
                "custom" -> {
                    sawCustom = true
                    assertEquals("cart", a.getString("category"))
                    assertEquals("add_item", a.getString("name"))
                    val data = a.getJSONObject("data")
                    assertEquals("SKU-7", data.getString("sku"))
                    assertEquals(2, data.getInt("qty"))
                }
            }
        }
        assertTrue("network event missing from actions", sawNetwork)
        assertTrue("custom event missing from actions", sawCustom)

        // Recording bracket times — set via fallback to event timestamps.
        assertTrue(recording.has("startedAt"))
        assertTrue(recording.has("endedAt"))
    }
}
