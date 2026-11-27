package com.tracewayapp.traceway.metrics

import com.tracewayapp.traceway.core.ControlState
import com.tracewayapp.traceway.core.Ids
import com.tracewayapp.traceway.core.ceilingsFrom
import com.tracewayapp.traceway.core.defaultSettings
import com.tracewayapp.traceway.internal.ManualScheduler
import com.tracewayapp.traceway.persistence.StateStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class Recorded(
    val recorder: MetricsRecorder,
    val bodies: MutableList<ByteArray>,
    val store: StateStore,
    val scheduler: ManualScheduler,
    val control: ControlState,
    val dir: File?,
) {
    val decoded: Decoded get() = Decoded(ArrayList(bodies))
}

/** A session id whose hash falls under [rate], or above it. */
internal fun sessionFor(rate: Double, sampled: Boolean): String {
    var i = 0
    while (true) {
        val id = "session-$i"
        if ((Ids.unitHash(id) < rate) == sampled) return id
        i++
    }
}

private val tempDirs = ArrayList<File>()

internal fun recorder(
    overlay: Map<String, Any?>? = null,
    session: () -> String? = { "session-a" },
    persistent: Boolean = false,
    statuses: List<Int> = emptyList(),
    scheduler: ManualScheduler = ManualScheduler(),
    deferUntilStart: Boolean = false,
    onSend: (ByteArray) -> Unit = {},
): Recorded {
    val control = ControlState(defaultSettings(), ceilingsFrom())
    if (overlay != null) control.applyRemote(doc(overlay))
    val bodies = Collections.synchronizedList(ArrayList<ByteArray>())
    val queue = ArrayList(statuses)
    val dir = if (persistent) Files.createTempDirectory("traceway-metrics").toFile().also { tempDirs.add(it) } else null
    val store = StateStore(dir)
    val r = MetricsRecorder(
        control = control,
        sessionId = session,
        installationId = { "install-1" },
        resource = {
            mapOf(
                "service.name" to "shop",
                "service.version" to "1.2.3",
                "service.instance.id" to "must-not-be-sent",
                "os.name" to "android",
                "os.major" to "14",
                "device.form_factor" to "phone",
                "device.model" to "must-not-be-sent",
            )
        },
        send = { body ->
            onSend(body)
            bodies.add(body)
            synchronized(queue) { if (queue.isEmpty()) 200 else queue.removeAt(0) }
        },
        store = { store },
        scheduler = scheduler,
        scopeName = "traceway-android",
        scopeVersion = "test",
        deferUntilStart = deferUntilStart,
    )
    return Recorded(r, bodies, store, scheduler, control, dir)
}

class MetricsRecorderTest {
    @After
    fun cleanUp() {
        for (dir in tempDirs) dir.deleteRecursively()
        tempDirs.clear()
    }

    @Test
    fun aPayloadCarriesTheDeviceDimensionsAndNothingThatIdentifies() {
        val r = recorder()
        r.recorder.setDimension("plan", "pro")
        r.recorder.setDimension("arm", "b")
        r.recorder.setDimension("third", "ignored")
        r.recorder.sessionStarted("session-a")
        r.recorder.flush()
        val resource = r.decoded.resource()
        assertEquals("aggregated", resource["traceway.metrics"])
        assertEquals(1.0, resource["traceway.sample_rate"])
        assertTrue(resource["traceway.batch_id"] is String)
        assertTrue(resource["traceway.sent_at"] is Long)
        assertEquals("shop", resource["service.name"])
        assertEquals("14", resource["os.major"])
        assertEquals("phone", resource["device.form_factor"])
        assertEquals("pro", resource["custom.plan"])
        assertEquals("b", resource["custom.arm"])
        assertFalse(resource.containsKey("custom.third"))
        assertFalse(resource.containsKey("service.instance.id"))
        assertFalse(resource.containsKey("device.model"))
        assertEquals(mapOf("app.session" to 1.0), r.decoded.sums)
        val scope = r.decoded.requests.single().resources.single().scopes.single()
        assertEquals("traceway-android", scope.name)
    }

    @Test
    fun aSessionIsCountedOnceAndNotAgainWhenItResumes() {
        val r = recorder()
        r.recorder.sessionStarted("session-a")
        r.recorder.sessionStarted("session-a")
        r.recorder.sessionStarted("session-b", resumed = true)
        r.recorder.flush()
        assertEquals(mapOf("app.session" to 1.0), r.decoded.sums)
    }

    @Test
    fun aSessionOutsideTheSampleRecordsNothingOneInsideStatesItsRate() {
        val outside = sessionFor(0.5, sampled = false)
        val out = recorder(overlay = mapOf("metrics" to mapOf("sampleRate" to 0.5)), session = { outside })
        out.recorder.sessionStarted(outside)
        out.recorder.request("https://api.shop.test/x", 120.0, failed = false)
        out.recorder.flush()
        assertTrue(out.bodies.isEmpty())

        val inside = sessionFor(0.5, sampled = true)
        val r = recorder(overlay = mapOf("metrics" to mapOf("sampleRate" to 0.5)), session = { inside })
        r.recorder.sessionStarted(inside)
        r.recorder.flush()
        assertEquals(0.5, r.decoded.resource()["traceway.sample_rate"])
    }

    @Test
    fun requestsByHostWithFailuresAsARatioAndAtMost20Hosts() {
        val r = recorder()
        r.recorder.request("https://api.shop.test/a", 100.0, failed = false, responseBytes = 2048)
        r.recorder.request("https://api.shop.test:8443/b", 300.0, failed = true)
        for (i in 0 until 25) r.recorder.request("https://h$i.test/", 10.0, failed = false)
        r.recorder.flush()
        val sums = r.decoded.sums
        assertEquals(1.0, sums["app.request.failed.total|host=api.shop.test"])
        assertNull("zero counts are not sent", sums["app.request.failed|host=api.shop.test"])
        assertEquals(1.0, sums["app.request.failed|host=api.shop.test:8443"])
        assertEquals(7.0, sums["app.request.failed.total|host=other"])
        val histograms = r.decoded.histograms
        assertEquals(1L, histograms["app.request.duration|host=api.shop.test"]!!.count)
        assertEquals(2048.0, histograms["app.request.bytes|host=api.shop.test"]!!.sum, 0.0)
    }

    @Test
    fun hostsAreLowercaseWithoutDefaultPortsOrCredentials() {
        assertEquals("api.shop.test", MetricsRecorder.hostOf("https://API.Shop.test:443/x?y#z"))
        assertEquals("api.shop.test:8080", MetricsRecorder.hostOf("http://user:pw@api.shop.test:8080"))
        assertEquals("api.shop.test", MetricsRecorder.hostOf("http://api.shop.test:80/"))
        assertEquals("[::1]:3000", MetricsRecorder.hostOf("http://[::1]:3000/a"))
        assertEquals("h.test", MetricsRecorder.hostOf("https://h.test/a b|c"))
        assertNull(MetricsRecorder.hostOf("/relative/path"))
        assertNull(MetricsRecorder.hostOf("https:///nohost"))
    }

    @Test
    fun anrsAndLowMemoryKillsOfEarlierRunsAreCountedWithTheUsersTheyAffected() {
        val r = recorder(persistent = true, deferUntilStart = true)
        // Read at the next start, before the recorder runs: kept until it does.
        r.recorder.anr()
        r.recorder.start()
        r.recorder.anr()
        r.recorder.oom()
        r.recorder.flush()
        assertEquals(
            mapOf(
                "app.user" to 1.0,
                "app.user.affected|issue=anr" to 1.0,
                "app.user.affected|issue=oom" to 1.0,
                "app.anr" to 2.0,
                "app.oom" to 1.0,
            ),
            r.decoded.sums,
        )
    }

    @Test
    fun usersAndAffectedUsersOnceADayOnlyWithLastingStorage() {
        val scheduler = ManualScheduler(nowMs = 20000L * 86_400_000L)
        val r = recorder(persistent = true, scheduler = scheduler)
        r.recorder.start()
        r.recorder.crash()
        r.recorder.crash()
        r.recorder.hang(2500.0, "/cart")
        r.recorder.flush()
        r.recorder.stop()
        assertEquals(
            mapOf(
                "app.user" to 1.0,
                "app.user.affected|issue=crash" to 1.0,
                "app.user.affected|issue=hang" to 1.0,
                "app.crash" to 2.0,
                "app.hang|screen=/cart" to 1.0,
            ),
            r.decoded.sums,
        )
        assertEquals(
            mapOf<String, Any?>("session.id" to "session-a"),
            r.decoded.histograms["app.hang.duration|screen=/cart"]!!.exemplars.single().filteredAttributes,
        )

        r.bodies.clear()
        r.recorder.crash()
        r.recorder.flush()
        assertEquals(mapOf("app.crash" to 1.0), r.decoded.sums)

        scheduler.nowMs += 86_400_000L
        r.bodies.clear()
        r.recorder.crash()
        r.recorder.flush()
        assertEquals(
            mapOf("app.user" to 1.0, "app.user.affected|issue=crash" to 1.0, "app.crash" to 1.0),
            r.decoded.sums,
        )

        // A new run reads the day it was counted from the store.
        r.store.flush()
        val reloaded = StateStore(r.dir).also { it.load() }
        val next = MetricsRecorder(
            control = r.control,
            sessionId = { "session-b" },
            installationId = { "install-1" },
            resource = { emptyMap() },
            send = { r.bodies.add(it); 200 },
            store = { reloaded },
            scheduler = scheduler,
        )
        r.bodies.clear()
        next.crash()
        next.flush()
        assertEquals(mapOf("app.crash" to 1.0), r.decoded.sums)

        val memory = recorder()
        memory.recorder.crash()
        memory.recorder.flush()
        assertEquals(mapOf("app.crash" to 1.0), memory.decoded.sums)
    }

    @Test
    fun aFailedUploadIsResentOnceRestampedAndMarkedAsARetry() {
        val r = recorder(statuses = listOf(503, 200, 200))
        r.recorder.sessionStarted("session-a")
        r.recorder.flush()
        assertEquals(1, r.bodies.size)
        r.scheduler.nowMs += 60_000
        r.recorder.crash()
        r.recorder.flush()
        assertEquals("the new payload, then the retry", 3, r.bodies.size)
        val retry = r.decoded.resource(2)
        assertEquals(true, retry["traceway.retry"])
        assertEquals(r.scheduler.nowMs, retry["traceway.sent_at"])
        assertEquals(r.decoded.resource(0)["traceway.batch_id"], retry["traceway.batch_id"])
        assertEquals(mapOf("app.session" to 1.0), Decoded(listOf(r.bodies[2])).sums)
        r.recorder.flush()
        assertEquals("never a third attempt", 3, r.bodies.size)
    }

    @Test
    fun aPermanentRejectionIsNotResent() {
        val r = recorder(statuses = listOf(400, 200))
        r.recorder.sessionStarted("session-a")
        r.recorder.flush()
        r.recorder.crash()
        r.recorder.flush()
        assertEquals(2, r.bodies.size)
        assertNull(r.store.get("metricsPending"))
    }

    @Test
    fun aPayloadSentAsTheAppLeavesIsStoredUntilItIsAnswered() {
        val r = recorder(statuses = listOf(0, 200, 200))
        r.recorder.sessionStarted("session-a")
        r.recorder.flush(final = true)
        assertEquals(1, (r.store.get("metricsPending") as List<*>).size)
        r.recorder.crash()
        r.recorder.flush()
        assertEquals(true, r.decoded.resource(2)["traceway.retry"])
        assertNull(r.store.get("metricsPending"))
    }

    @Test
    fun everyPayloadIsInTheStoreWhileItIsSent() {
        // A process killed during the request resends it on the next start.
        var storedDuringSend: Any? = null
        lateinit var r: Recorded
        r = recorder(onSend = { storedDuringSend = r.store.get("metricsPending") })
        r.recorder.sessionStarted("session-a")
        r.recorder.flush()
        assertEquals(1, (storedDuringSend as List<*>).size)
        assertNull(r.store.get("metricsPending"))

        // The next run of the app finds it and sends it once more.
        val left = recorder(statuses = listOf(0))
        left.recorder.sessionStarted("session-a")
        left.recorder.flush()
        val next = MetricsRecorder(
            control = left.control,
            sessionId = { "session-b" },
            installationId = { "i" },
            resource = { emptyMap() },
            send = { left.bodies.add(it); 200 },
            store = { left.store },
            scheduler = left.scheduler,
            deferUntilStart = true,
        )
        next.start()
        assertEquals(2, left.bodies.size)
        assertEquals(true, left.decoded.resource(1)["traceway.retry"])
        assertNull(left.store.get("metricsPending"))
    }

    @Test
    fun pendingPayloadsAreFewSmallAndShortLived() {
        val scheduler = ManualScheduler()
        val store = StateStore(null)
        val pending = PendingMetrics(scheduler::now, "dest")
        for (i in 0 until 5) pending.add(store, "p$i", ByteArray(10) { i.toByte() })
        assertEquals("metricsPending:dest", pending.key)
        assertEquals(3, (store.get(pending.key) as List<*>).size)
        for (id in listOf("x", "y", "z")) pending.add(store, id, ByteArray(30 * 1024) { id[0].code.toByte() })
        val ids = (store.get(pending.key) as List<*>).map { (it as Map<*, *>)["id"] }
        assertEquals("three large payloads do not fit: the oldest goes", listOf("y", "z"), ids)
        val taken = pending.take(store, setOf("z"))
        assertEquals(listOf("y"), taken.map { it.id })
        assertEquals('y'.code.toByte(), taken.single().body[0])
        assertEquals(30 * 1024, taken.single().body.size)
        assertEquals(1, (store.get(pending.key) as List<*>).size)
        scheduler.nowMs += 25L * 60 * 60 * 1000
        assertTrue("a day later it is gone", pending.take(store, emptySet()).isEmpty())
    }

    @Test
    fun aSampleRateChangeUploadsWhatWasTakenUnderTheOldRate() {
        val r = recorder()
        r.recorder.sessionStarted("session-a")
        r.control.applyRemote(doc(mapOf("metrics" to mapOf("sampleRate" to 0.99))))
        r.recorder.configure()
        r.recorder.flush()
        assertEquals(1, r.bodies.size)
        assertEquals(1.0, r.decoded.resource()["traceway.sample_rate"])

        r.control.applyRemote(doc(mapOf("metrics" to mapOf("enabled" to false))))
        r.recorder.crash()
        r.recorder.configure()
        r.recorder.flush()
        assertEquals("switched off sends nothing more", 1, r.bodies.size)
    }

    @Test
    fun aPayloadOverTheBodyLimitIsSplitNothingLost() {
        val r = recorder()
        // Every bucket from 2^-12 to 2^50: about 500 buckets a cell.
        for (i in 0 until 150) {
            for (k in -96 until 400) r.recorder.aggregator.observe("custom_$i", Math.pow(2.0, (k + 0.5) / 8))
        }
        r.recorder.flush()
        assertTrue(r.bodies.size > 1)
        assertTrue(r.bodies.all { it.size <= MAX_METRICS_BODY_BYTES })
        assertEquals(150, r.decoded.histograms.size)
        assertEquals(150L * 496, r.decoded.histograms.values.sumOf { it.count })
    }

    @Test
    fun whatIsObservedBeforeTheStartWaitsForTheFirstSession() {
        var session: String? = null
        val r = recorder(session = { session }, deferUntilStart = true)
        // The first frame and the first screen come before the SDK started.
        r.recorder.appStart(850.0, START_COLD, slow = false)
        r.recorder.screenDisplayed("/", 40.0)
        r.recorder.crash()
        assertEquals(0, r.recorder.aggregator.size)
        session = "session-a"
        r.recorder.sessionStarted("session-a")
        r.recorder.start()
        r.recorder.flush()
        r.recorder.stop()
        assertEquals(1, r.bodies.size)
        assertEquals(mapOf("app.session" to 1.0, "app.crash" to 1.0), r.decoded.sums)
        assertEquals(
            setOf("app.start.duration|start_type=cold", "app.screen.ttid|screen=/"),
            r.decoded.histograms.keys,
        )
    }

    @Test
    fun customMetricsKeepToTheirNamesAndTwoDimensions() {
        val r = recorder()
        r.recorder.metric("checkout.duration", 420.0, mapOf("plan" to "pro", "b" to 1, "c" to 2))
        r.recorder.metric("app.start.duration", 1.0)
        r.recorder.metric("Bad Name", 1.0)
        r.recorder.count("cart.add", 2.0)
        r.recorder.flush()
        assertEquals(listOf("checkout.duration|b=1,c=2"), r.decoded.histograms.keys.toList())
        assertEquals(mapOf("cart.add" to 2.0), r.decoded.sums)
    }

    @Test
    fun theTimerUploadsEveryFlushIntervalUntilStopped() {
        val r = recorder(overlay = mapOf("metrics" to mapOf("flushSeconds" to 60)))
        r.recorder.start()
        r.recorder.crash()
        r.scheduler.advance(59_000)
        assertTrue(r.bodies.isEmpty())
        r.scheduler.advance(1_000)
        assertEquals(1, r.bodies.size)
        r.recorder.crash()
        r.scheduler.advance(60_000)
        assertEquals(2, r.bodies.size)
        r.recorder.stop()
        r.recorder.crash()
        r.scheduler.advance(120_000)
        assertEquals(2, r.bodies.size)
        assertEquals(0, r.scheduler.pendingTimers)
    }

    @Test
    fun uploadsRunOnTheIoPoolAndCanBeAwaited() {
        val r = recorder(scheduler = ManualScheduler(ioInline = false))
        r.recorder.crash()
        r.recorder.flush()
        assertTrue(r.bodies.isEmpty())
        assertFalse(r.recorder.awaitUploads(10))
        r.scheduler.runIo()
        assertEquals(1, r.bodies.size)
        assertTrue(r.recorder.awaitUploads(0))
    }

    @Test
    fun aSendThatThrowsCountsAsNoAnswer() {
        val control = ControlState(defaultSettings(), ceilingsFrom())
        val store = StateStore(null)
        val r = MetricsRecorder(
            control = control,
            sessionId = { "session-a" },
            installationId = { "i" },
            resource = { throw IllegalStateException("resource") },
            send = { throw java.io.IOException("offline") },
            store = { store },
            scheduler = ManualScheduler(),
        )
        r.crash()
        r.flush()
        assertEquals(1, (store.get("metricsPending") as List<*>).size)
    }

    @Test
    fun recordingFromManyThreadsLosesNothing() {
        val r = recorder()
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(8)
        for (t in 0 until 8) {
            pool.execute {
                for (i in 0 until 500) {
                    r.recorder.request("https://h${i % 5}.test/", i.toDouble(), failed = i % 2 == 0)
                    r.recorder.frames("/s$t", 10, 1, 0)
                    if (i % 100 == 0) r.recorder.flush()
                }
                done.countDown()
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        r.recorder.flush()
        val sums = r.decoded.sums
        assertEquals(4000.0, (0 until 5).sumOf { sums["app.request.failed.total|host=h$it.test"] ?: 0.0 }, 0.0)
        assertEquals(2000.0, (0 until 5).sumOf { sums["app.request.failed|host=h$it.test"] ?: 0.0 }, 0.0)
        assertEquals(40_000.0, (0 until 8).sumOf { sums["app.frames.slow.total|screen=/s$it"] ?: 0.0 }, 0.0)
        // Every payload has its own points: count all of them, not one per key.
        val durations = r.decoded.requests
            .flatMap { req -> req.resources.flatMap { rm -> rm.scopes.flatMap { it.metrics } } }
            .filter { it.name == METRIC_REQUEST_DURATION }
            .flatMap { it.histogramPoints }
        assertEquals(4000L, durations.sumOf { it.count })
    }

    @Test
    fun aSessionThatEndedWhileTheAppWasNotRunningReportsItsPeakMemory() {
        val r = recorder()
        var peak = 1000L
        val peaks = MemoryPeaks(r.recorder, { r.store }, "dest", readPeak = { peak })
        // An earlier run went to the background in session "killed-session" and never came back.
        r.store.set("metricsMemory:dest", mapOf("s" to "killed-session", "b" to 123456789L))
        peaks.settle("session-a")
        assertNull(r.store.get("metricsMemory:dest"))
        r.recorder.flush()
        val memory = r.decoded.histograms["app.memory.peak"]!!
        assertEquals(1L, memory.count)
        assertEquals(123456789.0, memory.sum, 0.0)

        // Going to the background keeps this session's peak for later.
        peaks.remember("session-a")
        assertEquals(mapOf("s" to "session-a", "b" to 1000L), r.store.get("metricsMemory:dest"))
        peaks.settle("session-a")
        assertNotNull("the current session is not settled", r.store.get("metricsMemory:dest"))
        peak = 500L
        r.bodies.clear()
        peaks.ended("session-a")
        r.recorder.flush()
        assertEquals("the highest of this run and the remembered one", 1000.0,
            r.decoded.histograms["app.memory.peak"]!!.sum, 0.0)
        assertNull(r.store.get("metricsMemory:dest"))
    }
}
