package com.tracewayapp.traceway

import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.replay.ReplaySource
import com.tracewayapp.traceway.replay.SessionRecordingPayload
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The real client against a real Traceway server: OTLP logs and spans,
 * OpAMP, and a wireframe clip on `/api/recordings`, over the SDK's own HTTP
 * transport and threads. Skipped unless `TRACEWAY_E2E_CONNECTION` names a
 * server (`token@http://127.0.0.1:8099`) with an Android project.
 *
 * The clip is the session of real views `WireframeSessionTest` records to
 * `build/wireframe-sessions/views/events.json`, else the sample wireframe
 * `WireframeSampleClipTest` writes to `build/wireframe-sample/events.json`.
 * It prints the ids to look up. [LiveDeviceTest] runs the whole SDK on real
 * views instead.
 */
class LiveServerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() = TracewayClient.resetForTest()

    @Test
    fun deliversAnExceptionWithItsBreadcrumbsAndClip() {
        val connection = System.getenv("TRACEWAY_E2E_CONNECTION")
        assumeTrue("set TRACEWAY_E2E_CONNECTION to run against a server", !connection.isNullOrEmpty())
        val sample = listOf(File("build/wireframe-sessions/views/events.json"), File("build/wireframe-sample/events.json")).firstOrNull { it.exists() }
            ?: File("build/wireframe-sample/events.json")
        @Suppress("UNCHECKED_CAST")
        val clip = if (sample.exists()) Json.parse(sample.readText()) as List<Map<String, Any?>> else emptyList()

        val client = TracewayClient.initializeForTest(
            connection,
            TracewayOptions(version = "2.0.0-e2e", environment = "e2e", screenCapture = true, debug = true),
            TracewayTestOverrides(
                stateDirectory = tmp.newFolder("state"),
                deviceAttributes = mapOf(
                    "os.name" to "android",
                    "os.version" to "16",
                    "os.major" to "16",
                    "device.model" to "Pixel E2E",
                    "device.manufacturer" to "Google",
                    "device.form_factor" to "phone",
                ),
            ),
        )
        client.setReplaySource(SampleClip(clip))
        client.setAttribute("user.id", "e2e-user")
        // Give OpAMP a moment: a rule streaming console lines applies before the first breadcrumbs.
        Thread.sleep(1500)

        client.recordNavigation("/checkout")
        client.recordLog("Paying for 2 items")
        client.recordAction("cart", "add_item", mapOf("sku" to "SKU-123"))
        val request = client.startRequest("POST", "https://api.example.com/pay")!!
        Thread.sleep(40)
        client.endRequest(request, 502, 120, 64, null)
        client.track("checkout_failed", mapOf("reason" to "card_declined"))
        client.captureException(IllegalStateException("Payment provider returned 502"), mapOf("checkout.step" to "pay"))
        client.captureMessage("User opened support chat")
        client.flush(15_000)

        val session = client.currentSessionId
        println("E2E session=$session instance=${client.getInstanceId()} settingsConsole=${client.settings.signal("console").mode}")
        assertTrue(session != null)
        client.shutdown(10_000)
    }

    private class SampleClip(private val events: List<Map<String, Any?>>) : ReplaySource {
        private var running = false
        override val isRunning: Boolean get() = running
        override fun start() {
            running = true
        }

        override fun stop() {
            running = false
        }

        override fun clearBuffer() {}
        override fun hasFrames(sinceMs: Long?, untilMs: Long?): Boolean = events.isNotEmpty()

        /** The sample screen, moved to end now, as a clip of the last seconds. */
        override fun captureRecording(clipId: String, sinceMs: Long?, untilMs: Long?): SessionRecordingPayload? {
            if (events.isEmpty()) return null
            val end = untilMs ?: System.currentTimeMillis()
            val last = (events.last()["timestamp"] as Number).toLong()
            val shift = end - last
            val moved = events.map { e -> LinkedHashMap(e).apply { put("timestamp", (e["timestamp"] as Number).toLong() + shift) } }
            return SessionRecordingPayload(
                clipId = clipId,
                events = moved,
                startedAtMs = (moved.first()["timestamp"] as Number).toLong(),
                endedAtMs = end,
            )
        }
    }
}
