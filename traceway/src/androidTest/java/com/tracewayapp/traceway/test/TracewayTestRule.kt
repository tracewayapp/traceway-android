package com.tracewayapp.traceway.test

import com.tracewayapp.traceway.TracewayClient
import com.tracewayapp.traceway.TracewayOptions
import com.tracewayapp.traceway.internal.LogCapture
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.rules.ExternalResource
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * JUnit rule that boots a [MockWebServer] and points a fresh [TracewayClient]
 * at it for the duration of one test.
 *
 * Tests own the default uncaught exception handler themselves — installing
 * Traceway's [com.tracewayapp.traceway.internal.ErrorHandler] from a JUnit
 * rule would re-delegate to the test runner's previous handler and crash the
 * test process. Tests that need to drive uncaught-exception scenarios should
 * set their own UEH that calls `client.captureException(...)` and
 * `client.flush(...)` (see ExceptionSourcesInstrumentedTest).
 */
class TracewayTestRule(
    private val options: TracewayOptions = TracewayOptions(
        debounceMs = 50,
        retryDelayMs = 200,
        debug = true,
        persistToDisk = false,
    ),
    private val installLogCapture: Boolean = false,
) : ExternalResource() {

    val server: MockWebServer = MockWebServer()
    lateinit var persistDir: File
        private set

    override fun before() {
        server.start()
        // Default to 200 OK for all incoming requests; tests can enqueue more.
        repeat(8) { server.enqueue(MockResponse().setResponseCode(200)) }

        persistDir = File.createTempFile("traceway-test-", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }

        TracewayClient.initializeForTesting(
            connectionString = "test-token@${server.url("/api/report")}",
            options = options,
            persistDir = persistDir,
            sender = null, // use real DefaultReportSender → MockWebServer
        )

        if (installLogCapture && options.captureLogs) {
            LogCapture.install()
        }
    }

    override fun after() {
        try {
            TracewayClient.instance?.flush(2_000)
        } catch (_: Throwable) {
        }
        TracewayClient.resetForTest()

        try {
            server.shutdown()
        } catch (_: Throwable) {
        }

        if (::persistDir.isInitialized) {
            persistDir.deleteRecursively()
        }
    }

    /** Convenience accessor — fails the test if the client wasn't initialized. */
    val client: TracewayClient
        get() = TracewayClient.instance
            ?: error("TracewayClient.instance is null — did before() run?")

    /**
     * Force a flush, then return the next request the [MockWebServer] received,
     * waiting up to [timeoutSeconds] for it to arrive. Returns null on timeout.
     */
    fun flushAndTakeRequest(timeoutSeconds: Long = 5L): RecordedRequest? {
        client.flush(2_000)
        return server.takeRequest(timeoutSeconds, TimeUnit.SECONDS)
    }

    /** Decompress + parse the gzipped report body that the SDK posted. */
    fun parseRequestBody(req: RecordedRequest): JSONObject {
        val raw = req.body.readByteArray()
        val decompressed = GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
        return JSONObject(String(decompressed, Charsets.UTF_8))
    }
}
