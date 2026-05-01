package com.tracewayapp.traceway

import com.tracewayapp.traceway.events.NetworkEvent
import com.tracewayapp.traceway.transport.ReportSender
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TracewayClientFakeSenderTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("traceway-client-test-").toFile()
    }

    @After
    fun tearDown() {
        TracewayClient.resetForTest()
        dir.deleteRecursively()
    }

    private class CapturingSender : ReportSender {
        val received: MutableList<String> = mutableListOf()
        val sendCount = AtomicInteger(0)
        var responseSuccess: Boolean = true

        @Volatile var sendLatch: CountDownLatch? = null

        override fun send(apiUrl: String, token: String, jsonBody: String): Boolean {
            sendCount.incrementAndGet()
            synchronized(received) { received.add(jsonBody) }
            sendLatch?.countDown()
            return responseSuccess
        }
    }

    private fun newClient(
        sender: ReportSender,
        debounceMs: Long = 50,
    ): TracewayClient = TracewayClient.initializeForTesting(
        connectionString = "test-token@http://localhost:0/api/report",
        options = TracewayOptions(
            debounceMs = debounceMs,
            retryDelayMs = 100,
            persistToDisk = true,
            debug = false,
        ),
        persistDir = dir,
        sender = sender,
    )

    @Test
    fun captureExceptionDeliversReportThroughSender() {
        val sender = CapturingSender().apply { sendLatch = CountDownLatch(1) }
        val client = newClient(sender)

        client.captureException(IllegalStateException("boom"))
        assertTrue(
            "sender never received a report",
            sender.sendLatch!!.await(5, TimeUnit.SECONDS),
        )

        assertEquals(1, sender.received.size)
        val body = sender.received.first()
        assertTrue("payload missing exception type: $body", body.contains("IllegalStateException"))
        assertTrue("payload missing message: $body", body.contains("boom"))
        assertTrue("payload missing collectionFrames: $body", body.contains("collectionFrames"))
    }

    @Test
    fun captureMessageMarksIsMessageTrue() {
        val sender = CapturingSender().apply { sendLatch = CountDownLatch(1) }
        val client = newClient(sender)

        client.captureMessage("user_did_thing")
        assertTrue(sender.sendLatch!!.await(5, TimeUnit.SECONDS))

        val body = sender.received.first()
        assertTrue("payload missing isMessage:true: $body", body.contains("\"isMessage\":true"))
        assertTrue("payload missing message text: $body", body.contains("user_did_thing"))
    }

    @Test
    fun bufferedLogsAndActionsAttachedAsSessionRecording() {
        val sender = CapturingSender().apply { sendLatch = CountDownLatch(1) }
        val client = newClient(sender)

        client.recordLog("greeting from user", level = "info")
        client.recordNetworkEvent(
            NetworkEvent(method = "GET", url = "https://api/x", durationMs = 7L, statusCode = 204),
        )
        client.recordAction(category = "ui", name = "tapped", data = mapOf("btn" to "ok"))

        client.captureException(IllegalArgumentException("with timeline"))

        assertTrue(sender.sendLatch!!.await(5, TimeUnit.SECONDS))
        val body = sender.received.first()

        assertTrue("missing sessionRecordings", body.contains("sessionRecordings"))
        assertTrue("missing log msg", body.contains("greeting from user"))
        assertTrue("missing http url", body.contains("https://api/x"))
        assertTrue("missing custom name", body.contains("tapped"))
    }

    @Test
    fun failingSenderRetainsPendingExceptions() {
        val sender = CapturingSender().apply {
            responseSuccess = false
            sendLatch = CountDownLatch(1)
        }
        val client = newClient(sender)

        client.captureException(RuntimeException("retry me"))
        assertTrue(sender.sendLatch!!.await(5, TimeUnit.SECONDS))

        // Sender returned false, so the exception should still be queued.
        assertEquals(1, client.pendingExceptionCount())
    }

    @Test
    fun pendingExceptionsSurviveRestartFromDisk() {
        val failing = CapturingSender().apply {
            responseSuccess = false
            sendLatch = CountDownLatch(1)
        }
        var client = newClient(failing)
        client.captureException(IllegalStateException("survive me"))
        assertTrue(failing.sendLatch!!.await(5, TimeUnit.SECONDS))

        // The exception should remain in pending and on disk.
        assertEquals(1, client.pendingExceptionCount())

        TracewayClient.resetForTest()

        val success = CapturingSender().apply {
            sendLatch = CountDownLatch(1)
        }
        client = newClient(success)
        client.loadPendingFromDisk()

        assertEquals(1, client.pendingExceptionCount())
        client.flush(timeoutMs = 5_000)

        assertTrue(success.sendLatch!!.await(5, TimeUnit.SECONDS))
        val body = success.received.first()
        assertTrue("disk-reloaded payload missing message: $body", body.contains("survive me"))
    }

    @Test
    fun samplingRateZeroDropsAllExceptions() {
        val sender = CapturingSender()
        val client = TracewayClient.initializeForTesting(
            connectionString = "test-token@http://localhost:0/api/report",
            options = TracewayOptions(sampleRate = 0.0, debounceMs = 50),
            persistDir = dir,
            sender = sender,
        )

        client.captureException(RuntimeException("dropped"))
        // Give the scheduler a chance to fire (or not).
        Thread.sleep(300)
        assertEquals(0, sender.sendCount.get())
        assertEquals(0, client.pendingExceptionCount())
    }

    @Test
    fun trimDropsOldestWhenBufferFull() {
        val sender = CapturingSender().apply { responseSuccess = false }
        val client = TracewayClient.initializeForTesting(
            connectionString = "test-token@http://localhost:0/api/report",
            options = TracewayOptions(
                debounceMs = 5_000, // never naturally fires during this test
                maxPendingExceptions = 2,
                persistToDisk = false,
            ),
            persistDir = null,
            sender = sender,
        )

        client.captureException(RuntimeException("a"))
        client.captureException(RuntimeException("b"))
        client.captureException(RuntimeException("c"))
        client.captureException(RuntimeException("d"))

        assertEquals(2, client.pendingExceptionCount())
        val pending = client.pendingExceptionsSnapshot()
        assertNotNull(pending.find { it.stackTrace.contains("c") })
        assertNotNull(pending.find { it.stackTrace.contains("d") })
    }
}
