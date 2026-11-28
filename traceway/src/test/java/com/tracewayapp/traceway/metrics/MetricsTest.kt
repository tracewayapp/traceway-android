package com.tracewayapp.traceway.metrics

import com.tracewayapp.traceway.core.ControlState
import com.tracewayapp.traceway.core.MIN_METRICS_FLUSH_SECONDS
import com.tracewayapp.traceway.core.ceilingsFrom
import com.tracewayapp.traceway.core.defaultSettings
import com.tracewayapp.traceway.core.parseSettingsDocument
import com.tracewayapp.traceway.internal.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun doc(settings: Map<String, Any?>) = parseSettingsDocument(Json.stringify(settings))

class HistogramTest {
    @Test
    fun bucketsMatchTheServerAtEveryBoundary() {
        // Go: int32(math.Ceil(math.Log2(v)*8)) - 1
        val expected = mapOf(
            1.0 to -1, 2.0 to 7, 8.0 to 23, 16.0 to 31, 1024.0 to 79, 0.5 to -9,
            3.0 to 12, 100.0 to 53, 16.7 to 32, 2500.0 to 90, 1e9 to 239,
        )
        for ((value, index) in expected) assertEquals("$value", index, bucketIndex(value))
        // Every power of two is a boundary, where log(x) / ln2 can be off by one.
        for (k in -20..40) assertEquals("2^$k", 8 * k - 1, bucketIndex(Math.pow(2.0, k.toDouble())))
    }

    @Test
    fun recordsCountSumMinMaxAndZerosAndRefusesWhatItCannotHold() {
        val h = Histogram()
        for (v in listOf(0.0, 1.5, 100.0, 100.0)) assertTrue(h.record(v))
        assertFalse(h.record(-1.0))
        assertFalse(h.record(Double.NaN))
        assertFalse(h.record(Double.POSITIVE_INFINITY))
        assertEquals(4L, h.count)
        assertEquals(201.5, h.sum, 0.0)
        assertEquals(0.0, h.min, 0.0)
        assertEquals(100.0, h.max, 0.0)
        assertEquals(1L, h.zeroCount)
        assertEquals(mapOf(bucketIndex(1.5) to 1L, bucketIndex(100.0) to 2L), h.buckets)
    }
}

class AggregatorTest {
    @Test
    fun cellsPerNameAndDimensionsAndDrainHandsThemOverOnce() {
        val a = MetricAggregator()
        a.observe("app.screen.ttid", 100.0, mapOf("screen" to "/a"), "ms")
        a.observe("app.screen.ttid", 300.0, mapOf("screen" to "/a"), "ms")
        a.observe("app.screen.ttid", 50.0, mapOf("screen" to "/b"), "ms")
        a.count("app.session")
        a.ratio("app.frames.slow", 3.0, 10.0)
        a.ratio("app.frames.slow", 5.0, 2.0) // part above total is clamped
        val batch = a.drain()!!
        assertEquals(listOf(2L, 1L), batch.histograms.map { it.histogram.count })
        assertEquals(
            mapOf("app.session" to 1.0, "app.frames.slow.total" to 12.0, "app.frames.slow" to 5.0),
            batch.sums.associate { it.name to it.value },
        )
        assertNull(a.drain())
    }

    @Test
    fun pastTheCellLimitTheScreenFoldsToOtherThenCellsDrop() {
        val a = MetricAggregator(maxCells = 2)
        a.count("a")
        a.count("b")
        a.observe("app.screen.ttid", 1.0, mapOf("screen" to "/x"))
        a.observe("app.screen.ttid", 2.0, mapOf("screen" to "/y"))
        a.count("c")
        val batch = a.drain()!!
        assertEquals(mapOf("screen" to "other"), batch.histograms.single().dims)
        assertEquals(2L, batch.histograms.single().histogram.count)
        assertEquals(1, a.dropped)
    }

    @Test
    fun keepsTheThreeHighestExemplars() {
        val a = MetricAggregator()
        for (v in listOf(5.0, 1.0, 9.0, 3.0, 7.0)) a.observe("app.hang.duration", v, exemplarSession = "s$v")
        val exemplars = a.drain()!!.histograms.single().exemplars
        assertEquals(setOf(5.0, 9.0, 7.0), exemplars.map { it.value }.toSet())
    }
}

class OtlpTest {
    @Test
    fun deltaExponentialHistogramsAndMonotonicSums() {
        val a = MetricAggregator(now = { 1_700_000_000_000L })
        a.observe("app.start.duration", 1200.0, mapOf("start_type" to "cold"), "ms", exemplarSession = "sess")
        a.observe("app.start.duration", 1300.0, mapOf("start_type" to "cold"), "ms")
        a.count("app.session", 2.0)
        val body = encodeMetrics(a.drain()!!, mapOf("service.name" to "shop", "n" to 1L), "traceway-android", "9")
        val rm = OtlpDecoder.request(body).resources.single()
        assertEquals(mapOf<String, Any?>("service.name" to "shop", "n" to 1L), rm.attributes)
        val scope = rm.scopes.single()
        assertEquals("traceway-android", scope.name)
        assertEquals("9", scope.version)
        val histogram = scope.metrics.first { it.name == "app.start.duration" }
        assertEquals("ms", histogram.unit)
        assertEquals(TEMPORALITY_DELTA, histogram.temporality)
        val point = histogram.histogramPoints.single()
        assertEquals(HISTOGRAM_SCALE, point.scale)
        assertEquals(2L, point.count)
        assertEquals(2500.0, point.sum, 0.0)
        assertEquals(1200.0, point.min!!, 0.0)
        assertEquals(1300.0, point.max!!, 0.0)
        assertEquals(bucketIndex(1200.0), point.offset)
        assertEquals(2L, point.bucketCounts.sum())
        assertEquals(1_700_000_000_000L * 1_000_000, point.timeNanos)
        assertEquals(mapOf<String, Any?>("start_type" to "cold"), point.attributes)
        assertEquals(mapOf<String, Any?>("session.id" to "sess"), point.exemplars.single().filteredAttributes)
        assertEquals(1200.0, point.exemplars.single().value, 0.0)
        val sum = scope.metrics.first { it.kind == "sum" }
        assertTrue(sum.monotonic)
        assertEquals(TEMPORALITY_DELTA, sum.temporality)
        assertEquals(2.0, sum.sumPoints.single().value, 0.0)
    }

    @Test
    fun aHistogramOfOnlyZerosHasNoBuckets() {
        val a = MetricAggregator()
        a.observe("checkout.duration", 0.0)
        val point = Decoded(listOf(encodeMetrics(a.drain()!!, emptyMap(), "s", "v"))).histograms.values.single()
        assertEquals(1L, point.count)
        assertEquals(1L, point.zeroCount)
        assertTrue(point.bucketCounts.isEmpty())
    }

    @Test
    fun aResendIsRestampedAndMarkedAndKeepsEverythingElse() {
        val a = MetricAggregator()
        a.count("app.crash")
        val original = encodeMetrics(
            a.drain()!!,
            mapOf("traceway.batch_id" to "b1", ATTR_SENT_AT to 1000L, "traceway.sample_rate" to 0.5),
            "s", "v",
        )
        val once = restampForRetry(original, 5000L)!!
        val twice = restampForRetry(once, 9000L)!!
        for ((body, sentAt) in listOf(once to 5000L, twice to 9000L)) {
            val decoded = Decoded(listOf(body))
            val resource = decoded.resource()
            assertEquals("b1", resource["traceway.batch_id"])
            assertEquals(0.5, resource["traceway.sample_rate"])
            assertEquals(sentAt, resource[ATTR_SENT_AT])
            assertEquals(true, resource[ATTR_RETRY])
            assertEquals(4, resource.size)
            assertEquals(mapOf("app.crash" to 1.0), decoded.sums)
        }
        assertNull(restampForRetry(byteArrayOf(0x0a, 0x7f), 1L))
    }

    @Test
    fun base64RoundTrips() {
        val random = java.util.Random(7)
        for (size in listOf(0, 1, 2, 3, 4, 5, 100, 1001)) {
            val bytes = ByteArray(size).also { random.nextBytes(it) }
            val text = Base64.encode(bytes)
            assertEquals(0, text.length % 4)
            assertArrayEquals(bytes, Base64.decode(text))
        }
        assertEquals("Zm9vYmFy", Base64.encode("foobar".toByteArray()))
        assertEquals("Zm9vYg==", Base64.encode("foob".toByteArray()))
        assertNull(Base64.decode("Zm9v!mFy"))
        assertNull(Base64.decode("Zm9"))
    }
}

class RouteTest {
    @Test
    fun routePathsNormalizeLikeTheServer() {
        // The table of backend/app/clientmetrics/route_test.go.
        val table = mapOf(
            "/" to "/",
            "" to "/",
            "/orders/8841" to "/orders/:id",
            "/orders/8841/" to "/orders/:id",
            "/orders/8841?x=1#y" to "/orders/:id",
            "/u/3f2b8c1e-9a4d-4c7e-8b1a-2d3e4f5a6b7c/edit" to "/u/:id/edit",
            "/commit/9fceb02d0ae598e9" to "/commit/:id",
            "/reset/dGhpcyBpcyBhIHRva2VuIHZhbHVl0" to "/reset/:id",
            "/products/blue-suede-shoes" to "/products/blue-suede-shoes",
            "/deadbeef" to "/deadbeef",
            "/a/b/c/d/e/f/g/h" to "/a/b/c/d/e/f/*",
            "/search/hello%20world" to "/search/:id",
            "/endpoints/POST%20%2Ftasks%2F%7BtaskId%7D/9fceb02d0ae598e9" to "/endpoints/:id/:id",
            "/caf%C3%A9/menu" to "/caf%C3%A9/menu",
            "/:lang?/about" to "/:lang?/about",
            "/:locale(en|fr)?/products/:slug" to "/:locale(en|fr)?/products/:slug",
            "/users/:id?" to "/users/:id",
            "/orders/8841?/next" to "/orders/:id",
            "/products/:slug()" to "/products/:slug()",
            "/docs/:slug(.*)*" to "/docs/:slug(.*)*",
            "/docs/:slug*" to "/docs/:slug*",
            "/posts/\$postId" to "/posts/\$postId",
            "/posts/[id]" to "/posts/[id]",
            "/CheckoutPage" to "/CheckoutPage",
        )
        for ((input, want) in table) assertEquals(input, want, normalizePath(input))
        val long = normalizePath("/" + "segment-that-is-long/".repeat(8))
        assertTrue(long.length <= 64)
        assertTrue(long.endsWith("/*"))
    }
}

class MetricsSettingsTest {
    @Test
    fun metricsSettingsMergeClampAndObeyTheKillSwitchAndLimits() {
        var control = ControlState(defaultSettings(), ceilingsFrom())
        assertEquals(1.0, control.metrics!!.sampleRate, 0.0)
        assertEquals(300.0, control.metrics!!.flushSeconds, 0.0)
        control.applyRemote(doc(mapOf("metrics" to mapOf("sampleRate" to 0.25, "flushSeconds" to 5))))
        assertEquals(0.25, control.metrics!!.sampleRate, 0.0)
        assertEquals(MIN_METRICS_FLUSH_SECONDS, control.metrics!!.flushSeconds, 0.0)
        control.applyRemote(doc(mapOf("metrics" to mapOf("enabled" to false))))
        assertNull(control.metrics)
        control.applyRemote(doc(mapOf("enabled" to false)))
        assertNull(control.metrics)
        control.applyRemote(doc(mapOf("limits" to mapOf("metrics" to "off"), "metrics" to mapOf("enabled" to true))))
        assertNull(control.metrics)

        control = ControlState(defaultSettings(), ceilingsFrom(captureMetrics = false))
        control.applyRemote(doc(mapOf("metrics" to mapOf("enabled" to true))))
        assertNull(control.metrics)
    }
}

class CatalogTest {
    @Test
    fun everyMetricTheSdkSendsIsOneTheServerKnows() {
        // backend/app/clientmetrics/catalog.go
        val catalog = setOf(
            "app.start.duration", "app.screen.ttid", "app.screen.ttfd",
            "app.frames.slow", "app.frames.slow.total", "app.frames.frozen",
            "app.frames.frozen.total", "app.hang", "app.hang.duration", "app.crash", "app.anr", "app.oom",
            "app.session", "app.user", "app.user.affected", "app.memory.peak",
            "app.request.duration", "app.request.bytes", "app.request.failed",
            "app.request.failed.total",
        )
        val names = setOf(
            METRIC_APP_START, METRIC_SCREEN_TTID, METRIC_SCREEN_TTFD,
            METRIC_FRAMES_SLOW, "$METRIC_FRAMES_SLOW.total", METRIC_FRAMES_FROZEN, "$METRIC_FRAMES_FROZEN.total",
            METRIC_HANG, METRIC_HANG_DURATION, METRIC_CRASH, METRIC_ANR, METRIC_OOM, METRIC_SESSION, METRIC_USER, METRIC_USER_AFFECTED,
            METRIC_MEMORY_PEAK, METRIC_REQUEST_DURATION, METRIC_REQUEST_BYTES, METRIC_REQUEST_FAILED,
            "$METRIC_REQUEST_FAILED.total",
        )
        assertTrue(catalog.containsAll(names))
        // start_type and issue values the catalog accepts.
        assertTrue(setOf("cold", "warm", "hot").containsAll(listOf(START_COLD, START_WARM, START_HOT)))
        assertTrue(
            setOf("crash", "anr", "hang", "oom", "slow_start", "frozen_frames", "failed_request")
                .containsAll(UserIssue.values().map { it.wire }),
        )
    }

    @Test
    fun deviceDimensionsUseTheServersClosedSets() {
        assertEquals("14", DeviceDimensions.osMajor("14"))
        assertEquals("8", DeviceDimensions.osMajor("8.1.0"))
        assertNull(DeviceDimensions.osMajor("UpsideDownCake"))
        assertEquals("phone", DeviceDimensions.formFactor(411, false))
        assertEquals("tablet", DeviceDimensions.formFactor(600, false))
        assertEquals("desktop", DeviceDimensions.formFactor(411, true))
        val gb = 1024L * 1024 * 1024
        assertEquals("low", DeviceDimensions.deviceClass(2 * gb + gb / 2))
        assertEquals("mid", DeviceDimensions.deviceClass(5 * gb))
        assertEquals("high", DeviceDimensions.deviceClass(8 * gb))
        assertNull(DeviceDimensions.deviceClass(0))
        assertTrue(METRIC_RESOURCE_KEYS.containsAll(listOf("os.major", "device.form_factor", "device.class")))
    }
}
