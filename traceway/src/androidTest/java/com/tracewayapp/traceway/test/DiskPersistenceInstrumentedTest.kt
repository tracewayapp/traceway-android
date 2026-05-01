package com.tracewayapp.traceway.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tracewayapp.traceway.TracewayClient
import com.tracewayapp.traceway.TracewayOptions
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Verifies the on-disk pending-exception store: an exception captured while
 * the network is down is persisted, then re-sent on the *next* SDK init from
 * the same persist directory.
 */
@RunWith(AndroidJUnit4::class)
class DiskPersistenceInstrumentedTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        dir = File.createTempFile("traceway-disk-", "").let {
            it.delete()
            it.mkdirs()
            it
        }
    }

    @After
    fun tearDown() {
        TracewayClient.resetForTest()
        try { server.shutdown() } catch (_: Throwable) {}
        dir.deleteRecursively()
    }

    @Test
    fun pendingExceptionIsResentOnNextInitFromSamePersistDir() {
        // First boot: server is configured to return 500, so the report fails
        // and the exception stays both in pending and on disk.
        server.enqueue(MockResponse().setResponseCode(500))

        var client = TracewayClient.initializeForTesting(
            connectionString = "test-token@${server.url("/api/report")}",
            options = TracewayOptions(
                debounceMs = 50,
                retryDelayMs = 100,
                persistToDisk = true,
            ),
            persistDir = dir,
            sender = null,
        )
        client.captureException(IllegalStateException("survive me"))
        client.flush(timeoutMs = 3_000)

        // The 500 response should have been received exactly once.
        val firstReq = server.takeRequest(5, TimeUnit.SECONDS)
        assertTrue("first request never reached server", firstReq != null)

        assertEquals(1, client.pendingExceptionCount())
        val filesOnDisk = dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()
        assertTrue("expected file on disk after failed send, got 0", filesOnDisk.isNotEmpty())

        // Tear the client down without flushing — simulates app restart.
        TracewayClient.resetForTest()

        // Second boot: server now returns 200 — the persisted exception should
        // be re-sent automatically once `loadPendingFromDisk()` is called.
        server.enqueue(MockResponse().setResponseCode(200))

        client = TracewayClient.initializeForTesting(
            connectionString = "test-token@${server.url("/api/report")}",
            options = TracewayOptions(
                debounceMs = 50,
                retryDelayMs = 100,
                persistToDisk = true,
            ),
            persistDir = dir,
            sender = null,
        )
        client.loadPendingFromDisk()
        client.flush(timeoutMs = 3_000)

        val secondReq = server.takeRequest(5, TimeUnit.SECONDS)
        assertTrue("second request never reached server", secondReq != null)

        // The body of the resent request must contain the original message.
        val raw = secondReq!!.body.readByteArray()
        val decompressed = GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
        val body = String(decompressed, Charsets.UTF_8)
        assertTrue("resent payload missing 'survive me': $body", body.contains("survive me"))

        // Disk should now be empty.
        val remaining = dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()
        assertEquals(0, remaining.size)
    }
}
