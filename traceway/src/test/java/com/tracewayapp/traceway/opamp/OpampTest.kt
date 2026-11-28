package com.tracewayapp.traceway.opamp

import com.tracewayapp.traceway.core.Ids
import com.tracewayapp.traceway.internal.ExecutorScheduler
import com.tracewayapp.traceway.internal.HttpResponse
import com.tracewayapp.traceway.internal.HttpTransport
import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.ManualScheduler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal fun hex(bytes: ByteArray): String = Ids.bytesToHex(bytes)

internal fun fromHex(s: String): ByteArray = if (s.isEmpty()) ByteArray(0) else Ids.hexToBytes(s)!!

internal val testUid = fromHex("0195f2a0000070008000000000000001")

/** A port of the Flutter SDK's test/opamp_test.dart, and more. */
class OpampTest {

    // ------------------------------------------------------------- codec

    @Test
    fun `encodes AgentToServer byte for byte like opamp-go`() {
        val bytes = encodeAgentToServer(
            AgentToServer(
                instanceUid = testUid,
                sequenceNum = 3,
                agentDescription = AgentDescription(
                    identifying = mapOf("service.name" to "shop-web"),
                    nonIdentifying = mapOf(
                        "service.version" to "2.3.1",
                        "browser.mobile" to true,
                        "seats" to 12,
                        "ratio" to 0.25,
                    ),
                ),
                capabilities = 0x1L or 0x2L or 0x1000L or 0x2000L,
                remoteConfigStatus = RemoteConfigStatus(fromHex("abcd"), RemoteConfigStatuses.APPLIED),
                customCapabilities = listOf("io.traceway.sdk.buffer/v1", "io.traceway.sdk.capture/v1"),
            ),
        )
        assertEquals(OpampGoldens.A2S_SHARED, hex(bytes))
    }

    @Test
    fun `writes false, zero and empty values explicitly`() {
        val bytes = encodeAgentToServer(
            AgentToServer(
                instanceUid = testUid,
                sequenceNum = 0,
                agentDescription = AgentDescription(
                    identifying = emptyMap(),
                    nonIdentifying = mapOf("flag" to false, "n" to 0),
                ),
                capabilities = 0,
            ),
        )
        // KeyValue{key:"flag", value:AnyValue{bool_value:false}}
        assertTrue(hex(bytes).contains("0a04666c616712021000"))
        // KeyValue{key:"n", value:AnyValue{int_value:0}}
        assertTrue(hex(bytes).contains("0a016e12021800"))
    }

    @Test
    fun `decodes a ServerToAgent written by opamp-go`() {
        val msg = decodeServerToAgent(fromHex(OpampGoldens.S2A_SHARED))
        assertEquals(hex(testUid), hex(msg.instanceUid!!))
        assertEquals(1L, msg.flags)
        assertEquals(0x23L, msg.capabilities)
        assertEquals(300L, msg.heartbeatIntervalSeconds)
        assertEquals("ffeeddccbbaa99887766554433221100", hex(msg.newInstanceUid!!))
        assertEquals("010203", hex(msg.remoteConfig!!.configHash))
        val doc = msg.remoteConfig!!.configMap["traceway"]!!
        assertEquals("application/json", doc.contentType)
        assertEquals(
            mapOf("schemaVersion" to 1L, "replay" to mapOf("mode" to "stream")),
            Json.parse(String(doc.body, Charsets.UTF_8)),
        )
    }

    @Test
    fun `decodes an error response with retry info`() {
        val error = decodeServerToAgent(fromHex(OpampGoldens.S2A_SHARED_ERROR)).errorResponse!!
        assertEquals(ServerErrorTypes.UNAVAILABLE, error.type)
        assertEquals("busy", error.message)
        assertEquals(60_000L, error.retryAfterMs)
    }

    @Test
    fun `skips fields it does not know`() {
        assertEquals(1L, decodeServerToAgent(fromHex("f801073001")).flags)
    }

    @Test
    fun `rejects a message cut off inside a field it skips`() {
        // Field 31 as fixed32 and fixed64, with fewer bytes than they take.
        assertThrows(WireFormatException::class.java) { decodeServerToAgent(fromHex("fd010000")) }
        assertThrows(WireFormatException::class.java) { decodeServerToAgent(fromHex("f901000000")) }
    }

    @Test
    fun `rejects a varint longer than ten bytes`() {
        assertThrows(WireFormatException::class.java) {
            decodeServerToAgent(fromHex("f801" + "ff".repeat(10) + "01"))
        }
    }

    @Test
    fun `rejects truncated and unsupported fields`() {
        // instance_uid claims 16 bytes, carries 2.
        assertThrows(WireFormatException::class.java) { decodeServerToAgent(fromHex("0a100102")) }
        // A varint that never ends.
        assertThrows(WireFormatException::class.java) { decodeServerToAgent(fromHex("3080")) }
        // Wire type 3 (start group) cannot be skipped.
        assertThrows(WireFormatException::class.java) { decodeServerToAgent(fromHex("fb01")) }
        // A huge length must not overflow the bounds check.
        assertThrows(WireFormatException::class.java) {
            decodeServerToAgent(fromHex("0a" + "ffffffffffffffff7f" + "00"))
        }
    }

    @Test
    fun `a field of the wrong wire type is skipped, not misread`() {
        // flags (6) as length-delimited, then capabilities (7) = 5.
        val msg = decodeServerToAgent(fromHex("32020101" + "3805"))
        assertEquals(0L, msg.flags)
        assertEquals(5L, msg.capabilities)
    }

    @Test
    fun `varints round-trip at their edges`() {
        for (value in listOf(0L, 1L, 127L, 128L, 300L, 16_383L, 16_384L, 1L shl 35, Long.MAX_VALUE)) {
            val w = Writer()
            w.varint(value)
            assertEquals(value, Reader(w.finish()).varint())
        }
        val negative = Writer()
        negative.varint(-1)
        assertEquals("00", hex(negative.finish()))
        val big = Writer()
        big.varint(300)
        assertEquals("ac02", hex(big.finish()))
    }

    @Test
    fun `doubles are little-endian fixed64`() {
        val w = Writer()
        w.float64(4, 0.25)
        assertEquals("21000000000000d03f", hex(w.finish()))
    }

    @Test
    fun `encodeServerToAgent round-trips through the decoder`() {
        val msg = decodeServerToAgent(
            encodeServerToAgent(
                flags = 1,
                capabilities = 0x23,
                heartbeatIntervalSeconds = 120,
                remoteConfig = RemoteConfig(
                    configMap = mutableMapOf(
                        "traceway" to AgentConfigObject(
                            body = "{\"schemaVersion\":1}".toByteArray(),
                            contentType = "application/json",
                        ),
                    ),
                    configHash = fromHex("0a0b"),
                ),
            ),
        )
        assertEquals(1L, msg.flags)
        assertEquals(120L, msg.heartbeatIntervalSeconds)
        assertEquals("0a0b", hex(msg.remoteConfig!!.configHash))
        assertEquals(
            "{\"schemaVersion\":1}",
            String(msg.remoteConfig!!.configMap["traceway"]!!.body, Charsets.UTF_8),
        )
    }

    // ------------------------------------------------------------ client

    private class Request(
        val url: String,
        val headers: Map<String, String>,
        val body: ByteArray,
        val timeoutMs: Int,
    )

    private lateinit var requests: MutableList<Request>
    private lateinit var scheduler: ManualScheduler

    private fun client(
        ioInline: Boolean = true,
        onRemoteConfig: (RemoteConfig) -> ApplyResult = { ApplyResult.applied() },
        onStopped: ((Int) -> Unit)? = null,
        onInstanceUidChange: ((ByteArray) -> Unit)? = null,
        appliedConfigHash: ByteArray? = null,
        headers: Map<String, String> = mapOf("Authorization" to "Bearer tok"),
        minPollIntervalMs: Long = 30_000,
        describe: () -> AgentDescription = {
            AgentDescription(identifying = mapOf("service.name" to "app"), nonIdentifying = emptyMap())
        },
        respond: (Request, Int) -> HttpResponse,
    ): OpampHttpClient {
        val list = Collections.synchronizedList(ArrayList<Request>())
        requests = list
        scheduler = ManualScheduler(ioInline = ioInline)
        return OpampHttpClient(
            endpoint = "https://traceway.test/api/otel/v1/opamp",
            headers = headers,
            instanceUid = testUid,
            capabilities = 0x3003,
            describe = describe,
            onRemoteConfig = onRemoteConfig,
            onStopped = onStopped,
            onInstanceUidChange = onInstanceUidChange,
            appliedConfigHash = appliedConfigHash,
            minPollIntervalMs = minPollIntervalMs,
            scheduler = scheduler,
            http = HttpTransport { url, h, body, timeoutMs ->
                val request = Request(url, h, body, timeoutMs)
                list.add(request)
                respond(request, list.size - 1)
            },
        )
    }

    private fun ok(body: ByteArray) =
        HttpResponse(200, mapOf("content-type" to "application/x-protobuf"), body)

    private fun status(code: Int, headers: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0)) =
        HttpResponse(code, headers, body)

    private fun config(hash: ByteArray) = ok(
        encodeServerToAgent(
            heartbeatIntervalSeconds = 60,
            remoteConfig = RemoteConfig(
                configMap = mutableMapOf(
                    "traceway" to AgentConfigObject("{}".toByteArray(), "application/json"),
                ),
                configHash = hash,
            ),
        ),
    )

    private fun heartbeat(seconds: Long) = ok(encodeServerToAgent(heartbeatIntervalSeconds = seconds))

    /** AgentToServer.sequence_num of a request body. */
    private fun sequenceNum(body: ByteArray): Long {
        var seq = 0L
        eachField(body) { field, wireType, r ->
            if (field != 2 || wireType != WIRE_VARINT) return@eachField false
            seq = r.varint()
            true
        }
        return seq
    }

    @Test
    fun `sends the protobuf headers and the instance uid`() {
        val c = client { _, _ -> heartbeat(300) }
        c.start()
        c.stop()
        val request = requests.first()
        assertEquals("https://traceway.test/api/otel/v1/opamp", request.url)
        assertEquals("application/x-protobuf", request.headers["Content-Type"])
        assertEquals("Bearer tok", request.headers["Authorization"])
        assertEquals("0195f2a0-0000-7000-8000-000000000001", request.headers["OpAMP-Instance-UID"])
        assertTrue(hex(request.body).startsWith("0a10${hex(testUid)}"))
        assertEquals(10_000, request.timeoutMs)
        assertEquals(300_000L, c.pollIntervalMs)
    }

    @Test
    fun `the protocol headers win over the caller's`() {
        val c = client(
            headers = mapOf(
                "content-type" to "text/plain",
                "opamp-instance-uid" to "nope",
                "X-Extra" to "1",
            ),
        ) { _, _ -> heartbeat(300) }
        c.start()
        c.stop()
        val headers = requests.first().headers
        assertEquals(
            mapOf(
                "X-Extra" to "1",
                "Content-Type" to "application/x-protobuf",
                "OpAMP-Instance-UID" to "0195f2a0-0000-7000-8000-000000000001",
            ),
            headers,
        )
    }

    @Test
    fun `applies a new config and reports it on an immediate second poll`() {
        val applied = ArrayList<String>()
        val c = client(
            onRemoteConfig = { cfg ->
                applied.add(hex(cfg.configHash))
                ApplyResult.applied()
            },
        ) { _, index -> if (index == 0) config(byteArrayOf(1, 2, 3)) else heartbeat(60) }
        c.start()
        scheduler.runDue()
        c.stop()
        assertEquals(listOf("010203"), applied)
        assertEquals(2, requests.size)
        // remote_config_status{last_remote_config_hash: 010203, status: APPLIED}
        assertTrue(hex(requests[1].body).contains("3a070a030102031001"))
        assertEquals(60_000L, c.pollIntervalMs)
    }

    @Test
    fun `reports FAILED with the error when the config cannot be applied`() {
        val c = client(onRemoteConfig = { ApplyResult.failed("bad") }) { _, index ->
            if (index == 0) config(byteArrayOf(9)) else ok(encodeServerToAgent())
        }
        c.start()
        scheduler.runDue()
        c.stop()
        assertTrue(hex(requests[1].body).contains("3a0a0a010910031a03${hex("bad".toByteArray())}"))
    }

    @Test
    fun `a config that throws is reported FAILED with the exception`() {
        val c = client(onRemoteConfig = { throw IllegalStateException("boom") }) { _, index ->
            if (index == 0) config(byteArrayOf(9)) else heartbeat(60)
        }
        c.start()
        scheduler.runDue()
        c.stop()
        val message = "java.lang.IllegalStateException: boom"
        assertTrue(hex(requests[1].body).contains("1003" + "1a" + "%02x".format(message.length) + hex(message.toByteArray())))
    }

    @Test
    fun `does not re-apply a config whose hash is already applied`() {
        var applied = 0
        val c = client(
            appliedConfigHash = byteArrayOf(1, 2, 3),
            onRemoteConfig = {
                applied++
                ApplyResult.applied()
            },
        ) { _, _ -> config(byteArrayOf(1, 2, 3)) }
        c.start()
        c.stop()
        assertEquals(0, applied)
        assertEquals(1, requests.size)
        // The applied hash goes out from the first poll on.
        assertTrue(hex(requests[0].body).contains("3a070a030102031001"))
    }

    @Test
    fun `stops for good on 401, 403, 404, 405 and 501`() {
        for (code in listOf(401, 403, 404, 405, 501)) {
            var stopped: Int? = null
            val c = client(onStopped = { stopped = it }) { _, _ -> status(code) }
            c.start()
            assertEquals(code, stopped)
            assertFalse(c.isRunning)
            c.poll()
            scheduler.advance(24 * 3_600_000L)
            assertEquals(1, requests.size)
            assertEquals(0, scheduler.pendingTimers)
        }
    }

    @Test
    fun `a restart from onStopped waits a poll interval`() {
        lateinit var c: OpampHttpClient
        c = client(onStopped = { c.start() }) { _, _ -> status(401) }
        c.start()
        assertEquals(1, requests.size)
        assertTrue(c.isRunning)
        scheduler.advance(299_999)
        assertEquals(1, requests.size)
        scheduler.advance(1)
        assertEquals(2, requests.size)
        c.stop()
    }

    @Test
    fun `backs off instead of polling again after a server error`() {
        val c = client { _, _ -> status(503, mapOf("retry-after" to "60")) }
        c.start()
        c.poll()
        assertEquals(1, requests.size)
        assertTrue(c.isRunning)
        scheduler.advance(59_999)
        assertEquals(1, requests.size)
        scheduler.advance(1)
        assertEquals(2, requests.size)
        c.stop()
    }

    @Test
    fun `takes the retry delay from the error response when there is no header`() {
        val c = client { _, _ -> status(503, body = fromHex(OpampGoldens.S2A_UNAVAILABLE)) }
        c.start()
        scheduler.advance(59_999)
        assertEquals(1, requests.size)
        scheduler.advance(1)
        assertEquals(2, requests.size)
        c.stop()
    }

    @Test
    fun `an Unavailable error backs off even with a 200`() {
        val c = client { _, _ -> ok(fromHex(OpampGoldens.S2A_SHARED_ERROR)) }
        c.start()
        c.poll()
        scheduler.advance(59_999)
        assertEquals(1, requests.size)
        scheduler.advance(1)
        assertEquals(2, requests.size)
        c.stop()
    }

    @Test
    fun `the Retry-After header wins over the error response`() {
        val c = client { _, _ ->
            status(429, mapOf("retry-after" to "5"), fromHex(OpampGoldens.S2A_UNAVAILABLE))
        }
        c.start()
        scheduler.advance(5_000)
        assertEquals(2, requests.size)
        c.stop()
    }

    @Test
    fun `backs off exponentially without an answer, up to 30 minutes`() {
        val times = ArrayList<Long>()
        val c = client { _, _ ->
            times.add(scheduler.now())
            HttpResponse(0)
        }
        c.start()
        scheduler.advance(4 * 3_600_000L)
        c.stop()
        val gaps = times.zipWithNext { a, b -> (b - a) / 1000 }
        assertEquals(listOf(30L, 60L, 120L, 240L, 480L, 960L, 1800L, 1800L), gaps.take(8))
    }

    @Test
    fun `a bad request backs off, then a good answer resets the backoff`() {
        val c = client { _, index ->
            when (index) {
                0 -> status(400, body = fromHex(OpampGoldens.S2A_BAD_REQUEST))
                1 -> status(302)
                else -> heartbeat(60)
            }
        }
        c.start()
        scheduler.advance(30_000)
        assertEquals(2, requests.size)
        scheduler.advance(59_999)
        assertEquals(2, requests.size)
        scheduler.advance(1)
        assertEquals(3, requests.size)
        // Not backing off any more: an explicit poll goes out at once.
        c.poll()
        assertEquals(4, requests.size)
        c.stop()
    }

    @Test
    fun `a 413 is not retried early and does not back off`() {
        val c = client { _, index -> if (index == 0) status(413) else heartbeat(300) }
        c.start()
        scheduler.advance(299_999)
        assertEquals(1, requests.size)
        c.poll()
        assertEquals(2, requests.size)
        c.stop()
    }

    @Test
    fun `follows the server's heartbeat, no faster than the minimum`() {
        val c = client { _, index -> heartbeat(if (index == 0) 60 else 5) }
        c.start()
        scheduler.advance(59_999)
        assertEquals(1, requests.size)
        scheduler.advance(1)
        assertEquals(2, requests.size)
        assertEquals(30_000L, c.pollIntervalMs)
        scheduler.advance(30_000)
        assertEquals(3, requests.size)
        c.stop()

        val fast = client(minPollIntervalMs = 1_000) { _, _ -> heartbeat(5) }
        fast.start()
        assertEquals(5_000L, fast.pollIntervalMs)
        fast.stop()
    }

    @Test
    fun `numbers its messages`() {
        val c = client { _, _ -> heartbeat(30) }
        c.start()
        scheduler.advance(30_000)
        c.poll()
        c.stop()
        assertEquals(listOf(0L, 1L, 2L), requests.map { sequenceNum(it.body) })
    }

    @Test
    fun `polls again at once when the server asks for the full state`() {
        val c = client { _, index ->
            if (index == 0) ok(fromHex(OpampGoldens.S2A_REPORT_FULL_STATE)) else heartbeat(60)
        }
        c.start()
        assertEquals(1, requests.size)
        scheduler.runDue()
        assertEquals(2, requests.size)
        c.stop()
    }

    @Test
    fun `a server that always asks for the full state gets no tight poll loop`() {
        val c = client { _, index ->
            // Bounded, so a regression fails instead of spinning forever.
            check(index < 50) { "polled $index times" }
            ok(fromHex(OpampGoldens.S2A_REPORT_FULL_STATE))
        }
        c.start()
        scheduler.runDue()
        assertEquals("the first answer is followed at once", 2, requests.size)
        scheduler.runDue()
        scheduler.advance(29_999)
        assertEquals("then no faster than the minimum interval", 2, requests.size)
        scheduler.advance(1)
        assertEquals(3, requests.size)
        c.stop()
    }

    @Test
    fun `takes a new instance uid from the server`() {
        val changes = ArrayList<String>()
        val c = client(onInstanceUidChange = { changes.add(hex(it)) }) { _, index ->
            when (index) {
                0 -> ok(fromHex(OpampGoldens.S2A_NEW_INSTANCE_UID))
                // A uid that is not 16 bytes is ignored.
                1 -> ok(encodeServerToAgent(newInstanceUid = byteArrayOf(1, 2), heartbeatIntervalSeconds = 300))
                else -> heartbeat(300)
            }
        }
        c.start()
        c.poll()
        c.poll()
        c.stop()
        assertEquals(listOf("ffeeddccbbaa99887766554433221100"), changes)
        assertEquals("ffeeddccbbaa99887766554433221100", hex(c.instanceUid))
        assertEquals("ffeeddcc-bbaa-9988-7766-554433221100", requests[2].headers["OpAMP-Instance-UID"])
        assertTrue(hex(requests[2].body).startsWith("0a10ffeeddccbbaa99887766554433221100"))
    }

    @Test
    fun `a description that throws backs off without a request`() {
        var fail = true
        val c = client(describe = {
            if (fail) throw IllegalStateException("not ready")
            AgentDescription(mapOf("service.name" to "app"), emptyMap())
        }) { _, _ -> heartbeat(300) }
        c.start()
        assertEquals(0, requests.size)
        fail = false
        c.poll() // Backing off.
        assertEquals(0, requests.size)
        scheduler.advance(30_000)
        assertEquals(1, requests.size)
        c.stop()
    }

    @Test
    fun `callbacks that throw do not stop polling`() {
        val c = client(onInstanceUidChange = { throw IllegalStateException() }) { _, index ->
            if (index == 0) ok(fromHex(OpampGoldens.S2A_NEW_INSTANCE_UID)) else heartbeat(300)
        }
        c.start()
        assertEquals(1, scheduler.pendingTimers)
        c.poll { throw IllegalStateException() }
        scheduler.advance(300_000)
        assertEquals(3, requests.size)
        c.stop()

        val stopped = client(onStopped = { throw IllegalStateException() }) { _, _ -> status(401) }
        stopped.start()
        assertFalse(stopped.isRunning)
    }

    @Test
    fun `concurrent polls share one request`() {
        val c = client(ioInline = false) { _, _ -> heartbeat(300) }
        var a = 0
        var b = 0
        c.start()
        c.poll { a++ }
        c.poll { b++ }
        scheduler.runIo()
        assertEquals(1, requests.size)
        assertEquals(1, a)
        assertEquals(1, b)
        c.stop()
    }

    @Test
    fun `onDone runs when there is nothing to poll`() {
        val c = client(ioInline = false) { _, _ -> status(503) }
        var done = 0
        c.poll { done++ } // Not started.
        c.start()
        scheduler.runIo()
        c.poll { done++ } // Backing off.
        scheduler.runIo()
        assertEquals(2, done)
        assertEquals(1, requests.size)
        c.stop()
    }

    @Test
    fun `refresh polls again after the poll under way`() {
        var describes = 0
        val c = client(
            ioInline = false,
            describe = {
                describes++
                AgentDescription(mapOf("service.name" to "app", "n" to describes), emptyMap())
            },
        ) { _, _ -> heartbeat(300) }
        var done = 0
        c.start()
        c.refresh { done++ }
        scheduler.runIo()
        assertEquals(2, requests.size)
        assertEquals(2, describes)
        assertEquals(1, done)
        // The refresh replaced the timer rather than adding one.
        assertEquals(1, scheduler.pendingTimers)
        c.stop()
    }

    @Test
    fun `an answer that comes after stop is dropped`() {
        var applied = 0
        val c = client(
            ioInline = false,
            onRemoteConfig = {
                applied++
                ApplyResult.applied()
            },
        ) { _, _ -> config(byteArrayOf(1)) }
        c.start()
        c.stop()
        scheduler.runIo()
        assertEquals(1, requests.size)
        assertEquals(0, applied)
        assertEquals(0, scheduler.pendingTimers)
        assertFalse(c.isRunning)
    }

    @Test
    fun `a restart while a poll is under way keeps polling`() {
        lateinit var c: OpampHttpClient
        c = client { _, index ->
            if (index == 0) {
                c.stop()
                c.start()
            }
            heartbeat(300)
        }
        c.start()
        assertEquals(1, requests.size)
        assertTrue(c.isRunning)
        assertEquals(1, scheduler.pendingTimers)
        scheduler.advance(300_000)
        assertEquals(2, requests.size)
        c.stop()
    }

    @Test
    fun `callbacks run without the client's lock held`() {
        lateinit var c: OpampHttpClient
        // Another thread must be able to use the client while a callback runs.
        fun otherThreadCanReadState(): Boolean {
            val reader = Thread { c.pollIntervalMs }
            reader.start()
            reader.join(5_000)
            return !reader.isAlive
        }
        var duringDescribe = false
        var duringApply = false
        c = client(
            describe = {
                duringDescribe = otherThreadCanReadState()
                AgentDescription(mapOf("service.name" to "app"), emptyMap())
            },
            onRemoteConfig = {
                duringApply = otherThreadCanReadState()
                ApplyResult.applied()
            },
        ) { _, _ -> config(byteArrayOf(4)) }
        c.start()
        c.stop()
        assertTrue(duringDescribe)
        assertTrue(duringApply)
    }

    @Test
    fun `polls on real threads and calls back on the I-O pool`() {
        val executor = ExecutorScheduler()
        val reported = CountDownLatch(2)
        val threads = Collections.synchronizedList(ArrayList<String>())
        val bodies = Collections.synchronizedList(ArrayList<ByteArray>())
        val c = OpampHttpClient(
            endpoint = "https://traceway.test/api/otel/v1/opamp",
            instanceUid = testUid,
            capabilities = 0x3003,
            describe = {
                threads.add(Thread.currentThread().name)
                AgentDescription(mapOf("service.name" to "app"), emptyMap())
            },
            onRemoteConfig = {
                threads.add(Thread.currentThread().name)
                ApplyResult.applied()
            },
            scheduler = executor,
            http = { _, _, body, _ ->
                bodies.add(body)
                reported.countDown()
                if (bodies.size == 1) config(byteArrayOf(7)) else heartbeat(300)
            },
        )
        try {
            c.start()
            assertTrue(reported.await(10, TimeUnit.SECONDS))
            val done = CountDownLatch(1)
            var doneThread = ""
            c.poll {
                doneThread = Thread.currentThread().name
                done.countDown()
            }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertTrue(hex(bodies[1]).contains("3a050a01071001"))
            assertTrue(threads.all { it.startsWith("TracewayIO") })
            assertTrue(doneThread.startsWith("TracewayIO"))
        } finally {
            c.stop()
            executor.shutdown()
        }
    }

    @Test
    fun `polls from many threads never overlap and every onDone runs`() {
        val executor = ExecutorScheduler()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val sent = AtomicInteger()
        val c = OpampHttpClient(
            endpoint = "https://traceway.test/api/otel/v1/opamp",
            instanceUid = testUid,
            capabilities = 0x3003,
            describe = { AgentDescription(mapOf("service.name" to "app"), emptyMap()) },
            onRemoteConfig = { ApplyResult.applied() },
            scheduler = executor,
            http = { _, _, _, _ ->
                val now = active.incrementAndGet()
                synchronized(maxActive) { if (now > maxActive.get()) maxActive.set(now) }
                Thread.sleep(2)
                sent.incrementAndGet()
                active.decrementAndGet()
                heartbeat(300)
            },
        )
        try {
            c.start()
            val done = CountDownLatch(8 * 25)
            val callers = (1..8).map { n ->
                Thread {
                    repeat(25) { i ->
                        if ((n + i) % 2 == 0) c.poll { done.countDown() } else c.refresh { done.countDown() }
                    }
                }
            }
            callers.forEach(Thread::start)
            callers.forEach { it.join() }
            assertTrue(done.await(30, TimeUnit.SECONDS))
            assertEquals(1, maxActive.get())
            assertTrue(sent.get() in 1..201)
            assertTrue(c.isRunning)
        } finally {
            c.stop()
            executor.shutdown()
        }
    }

    @Test
    fun `a client that was never started does not poll`() {
        val c = client { _, _ -> heartbeat(300) }
        assertFalse(c.isRunning)
        c.poll()
        c.refresh()
        assertEquals(0, requests.size)
        assertEquals(0, scheduler.pendingTimers)
        assertArrayEquals(testUid, c.instanceUid)
    }
}
