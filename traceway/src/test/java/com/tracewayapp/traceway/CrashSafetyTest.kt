package com.tracewayapp.traceway

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.tracewayapp.traceway.interaction.PointerListener
import com.tracewayapp.traceway.interaction.TapListener
import com.tracewayapp.traceway.interaction.TouchTracker
import com.tracewayapp.traceway.internal.Cancellable
import com.tracewayapp.traceway.internal.ErrorHandler
import com.tracewayapp.traceway.internal.HttpResponse
import com.tracewayapp.traceway.internal.HttpTransport
import com.tracewayapp.traceway.internal.ManualScheduler
import com.tracewayapp.traceway.internal.NavigationCapture
import com.tracewayapp.traceway.metrics.HangWatchdog
import com.tracewayapp.traceway.network.TracewayOkHttpInterceptor
import com.tracewayapp.traceway.otel.FakeHttp
import com.tracewayapp.traceway.wireframe.TestActivity
import com.tracewayapp.traceway.wireframe.ViewFrameSource
import com.tracewayapp.traceway.wireframe.WireframeImages
import com.tracewayapp.traceway.wireframe.WireframeRoots
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

private const val CONNECTION = "tok@https://traceway.test"

/** Keeps what is exported, or fails every call when [failing]. */
private class Logs : LogRecordExporter {
    @Volatile
    var failing = false
    private val items = Collections.synchronizedList(ArrayList<LogRecordData>())
    val all: List<LogRecordData> get() = synchronized(items) { ArrayList(items) }

    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        if (failing) throw IllegalStateException("exporter")
        items.addAll(logs)
        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode = if (failing) throw IllegalStateException("exporter") else CompletableResultCode.ofSuccess()
    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

private class Spans : SpanExporter {
    @Volatile
    var failing = false

    override fun export(spans: Collection<SpanData>): CompletableResultCode =
        if (failing) throw IllegalStateException("exporter") else CompletableResultCode.ofSuccess()

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

/**
 * CONTRACT §13: the SDK never takes the app down. Internal pieces are made
 * to fail (exporter, transport, wireframe capture, image encoder, settings,
 * the app's own callbacks) and the app's operation must still complete,
 * with the SDK working afterwards; every public API is called with Java
 * nulls, before init, after shutdown and from many threads at once; and
 * the app's own crashes still reach the handler that was there before.
 */
@RunWith(RobolectricTestRunner::class)
class CrashSafetyTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val scheduler = ManualScheduler(ioInline = true)
    private val http = FakeHttp()
    private val logs = Logs()
    private val spans = Spans()
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun tearDown() {
        TracewayClient.resetForTest()
        Traceway.shutdown(2000)
        Thread.setDefaultUncaughtExceptionHandler(defaultHandler)
    }

    private fun start(
        options: TracewayOptions = TracewayOptions(),
        transport: HttpTransport = http,
    ): TracewayClient = TracewayClient.initializeForTest(
        CONNECTION,
        options,
        TracewayTestOverrides(logExporter = logs, spanExporter = spans, http = transport, scheduler = scheduler, random = { 0.5 }),
    )

    private fun exceptions(): List<String?> =
        logs.all.filter { it.eventName == "exception" }.map { r ->
            var message: String? = null
            r.attributes.forEach { k, v -> if (k.key == "exception.message") message = v as String }
            message
        }

    // ---- the public API ---------------------------------------------------------

    /** A value for each parameter: null for objects (what a Java caller can pass), zero for primitives. */
    private fun nullArgs(m: Method): Array<Any?> = m.parameterTypes.map { type ->
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0f
            else -> null
        }
    }.toTypedArray()

    /** Calls every method [methods] holds with Java nulls; the ones that throw into the caller. */
    private fun callWithNulls(target: Any?, methods: List<Method>): List<String> {
        val thrown = ArrayList<String>()
        for (m in methods) {
            try {
                m.invoke(target, *nullArgs(m))
            } catch (e: InvocationTargetException) {
                thrown.add("${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): ${e.targetException}")
            }
        }
        return thrown
    }

    private fun facade(): List<Method> = Traceway::class.java.declaredMethods.filter {
        Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && !it.isSynthetic && '$' !in it.name &&
            it.name != "shutdown"
    }.sortedBy { it.name + it.parameterCount }

    private fun clientMethods(): List<Method> = TracewayClient::class.java.declaredMethods.filter {
        !Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && !it.isSynthetic && '$' !in it.name &&
            it.name != "shutdown" && it.name != "flush"
    }.sortedBy { it.name + it.parameterCount }

    @Test
    fun javaNullsNeverReachTheApp() {
        assertTrue(facade().size > 30)
        assertEquals("before init", emptyList<String>(), callWithNulls(null, facade()))
        val client = start()
        assertEquals("on the facade", emptyList<String>(), callWithNulls(null, facade()))
        assertEquals("on the client", emptyList<String>(), callWithNulls(client, clientMethods()))
        val interaction = Traceway.startInteraction("checkout")
        val interactionMethods = TracewayInteraction::class.java.declaredMethods.filter {
            Modifier.isPublic(it.modifiers) && !it.isSynthetic && '$' !in it.name && it.name != "run"
        }
        assertEquals("on an interaction", emptyList<String>(), callWithNulls(interaction, interactionMethods))

        // A Java map with null keys and values, in the options and in calls.
        val nulls = HashMap<String?, String?>().apply {
            put(null, "x")
            put("k", null)
            put("tier", "gold")
        }
        @Suppress("UNCHECKED_CAST")
        val hostile = nulls as Map<String, String>
        Traceway.captureException(RuntimeException("with nulls"), hostile)
        Traceway.setAttributes(hostile)
        Traceway.log("line", TracewayLogLevel.INFO, hostile)
        client.flush(2000)
        assertTrue(exceptions().contains("with nulls"))
        val restarted = start(TracewayOptions(attributes = hostile, metricDimensions = hostile))
        restarted.captureException(RuntimeException("after"))
        restarted.flush(2000)
        assertTrue(exceptions().contains("after"))
        // The OpAMP description is built from those attributes on every poll.
        assertTrue(http.requests.any { it.url.endsWith("/opamp") && String(it.body, Charsets.ISO_8859_1).contains("gold") })
    }

    @Test
    fun aJavaMapWithANullKeyDoesNotStopInit() {
        val dims = HashMap<String?, String>().apply { put(null, "x") }
        @Suppress("UNCHECKED_CAST")
        val options = TracewayOptions(metricDimensions = dims as Map<String, String>, remoteConfig = false)
        val client = Traceway.init(app, CONNECTION, options)
        assertNotNull("the SDK runs without the bad entry", client)
        assertSame(client, Traceway.client)
    }

    private val hostileMap = object : AbstractMap<String, String>() {
        override val entries: Set<Map.Entry<String, String>> get() = throw IllegalStateException("map")
        override val size get() = 1
    }

    @Test
    fun hostileAttributeMapsAreContained() {
        val c = start()
        c.captureException(RuntimeException("x"), hostileMap)
        c.captureMessage("m", hostileMap)
        c.log("l", TracewayLogLevel.INFO, hostileMap)
        c.setAttributes(hostileMap)
        c.track("event", hostileMap)
        Traceway.startInteraction("i").captureException(RuntimeException("y"), hostileMap)
        c.captureException(RuntimeException("after"))
        c.flush(2000)
        assertTrue("an exception with an unreadable map is still reported", exceptions().contains("x"))
        assertTrue(exceptions().contains("after"))
    }

    @Test
    fun everyCallBeforeInitAndAfterShutdownIsANoOp() {
        fun everything() {
            Traceway.captureException(RuntimeException("e"))
            Traceway.captureMessage("m")
            Traceway.recordAction("c", "n", mapOf("k" to 1))
            Traceway.track("t")
            Traceway.setAttribute("k", "v")
            Traceway.setAttributes(mapOf("a" to "b"))
            Traceway.removeAttribute("k")
            Traceway.clearAttributes()
            Traceway.recordNavigation("/a")
            Traceway.setRoute("/a/:id")
            Traceway.log("l")
            val i = Traceway.startInteraction("i")
            assertEquals(7, i.run { 7 })
            i.setAttribute("k", 1)
            i.end()
            Traceway.reportFullyDisplayed()
            Traceway.metric("custom.x", 1.0)
            Traceway.count("custom.y")
            Traceway.setMetricDimension("d", "v")
            Traceway.mask(View(app))
            Traceway.unmask(View(app))
            Traceway.getInstanceId()
            Traceway.currentSessionId
            Traceway.refreshConfig()
            Traceway.flush(100)
        }
        everything()
        Traceway.shutdown(100)
        val c = start()
        everything()
        c.shutdown(2000)
        c.shutdown(2000)
        val began = System.nanoTime()
        everything()
        c.captureException(RuntimeException("after shutdown"))
        c.flush(5000)
        assertTrue("nothing waits after shutdown", System.nanoTime() - began < TimeUnit.SECONDS.toNanos(2))
        assertFalse(exceptions().contains("after shutdown"))
        // And it starts again.
        start().captureException(RuntimeException("again"))
        Traceway.flush(2000)
        assertTrue(exceptions().contains("again"))
    }

    @Test
    fun concurrentCallsFromManyThreadsNeverThrow() {
        val c = start()
        val failures = Collections.synchronizedList(ArrayList<Throwable>())
        val go = CountDownLatch(1)
        val threads = List(8) { n ->
            Thread {
                go.await()
                try {
                    repeat(150) { i ->
                        Traceway.setAttribute("k$n", "$i")
                        Traceway.captureException(RuntimeException("t$n-$i"), mapOf("i" to "$i"))
                        Traceway.log("line $i")
                        Traceway.recordNavigation("/s$i")
                        Traceway.startInteraction("i$n").run { Traceway.track("e") }
                        Traceway.metric("custom.m", i.toDouble())
                        if (i % 50 == 0) Traceway.removeAttribute("k$n")
                    }
                } catch (t: Throwable) {
                    failures.add(t)
                }
            }.apply { start() }
        }
        go.countDown()
        // Shut down while they are still calling.
        Thread.sleep(20)
        c.shutdown(2000)
        threads.forEach { it.join(20_000) }
        assertEquals(emptyList<Throwable>(), failures)
    }

    // ---- fault injection ----------------------------------------------------------

    @Test
    fun aThrowingExporterNeverReachesTheApp() {
        val c = start()
        logs.failing = true
        spans.failing = true
        c.captureException(RuntimeException("lost"))
        c.log("l")
        Traceway.startInteraction("i").apply { run { } ; end() }
        c.flush(2000)
        logs.failing = false
        spans.failing = false
        c.captureException(RuntimeException("after"))
        c.flush(2000)
        assertTrue(exceptions().contains("after"))
    }

    @Test
    fun aFailingTransportNeverReachesTheApp() {
        val transport = object : HttpTransport {
            override fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int): HttpResponse =
                throw OutOfMemoryError("transport")
        }
        val c = TracewayClient.initializeForTest(
            CONNECTION,
            TracewayOptions(),
            TracewayTestOverrides(http = transport, scheduler = scheduler),
        )
        c.captureException(RuntimeException("x"))
        c.recordNavigation("/a")
        c.flush(2000)
        c.refreshConfig()
        c.shutdown(2000)
    }

    @Test
    fun garbageFromTheServerLeavesTheSdkWorking() {
        var answer = 0
        http.handler = { request ->
            if (request.url.endsWith("/opamp")) {
                answer++
                // Random bytes, then a body that is not protobuf at all.
                HttpResponse(200, body = if (answer % 2 == 1) ByteArray(64) { (it * 37).toByte() } else "{\"a\":".toByteArray())
            } else {
                HttpResponse(200)
            }
        }
        val c = start()
        c.refreshConfig()
        c.refreshConfig()
        c.captureException(RuntimeException("still reported"))
        c.flush(2000)
        assertTrue(exceptions().contains("still reported"))
    }

    @Test
    fun theAppsCallbacksThatThrowAreContained() {
        val c = start(
            TracewayOptions(
                beforeCapture = { throw OutOfMemoryError("callback") },
                // The app's list of patterns cannot even be read.
                ignoreErrors = object : AbstractList<Any>() {
                    override val size: Int get() = 1
                    override fun get(index: Int): Any = throw IllegalStateException("patterns")
                },
            ),
        )
        c.captureException(RuntimeException("kept too"))
        c.captureMessage("kept")
        c.flush(2000)
        assertEquals("a callback that throws keeps the record", 2, logs.all.count { it.eventName == "exception" || it.eventName == "message" })
    }

    @Test
    fun wireframeCaptureOfViewsThatThrowNeverReachesTheApp() {
        val controller = Robolectric.buildActivity(TestActivity::class.java).setup()
        val activity = controller.get()
        activity.setContentView(
            FrameLayout(activity).apply {
                addView(object : TextView(activity) {
                    override fun getText(): CharSequence = throw IllegalStateException("app view")
                })
                addView(object : View(activity) {
                    override fun getContentDescription(): CharSequence = throw IllegalStateException("app view")
                })
            },
        )
        shadowOf(Looper.getMainLooper()).idle()
        val source = ViewFrameSource(WireframeRoots(), activity)
        source.observe { }
        source.capture(1_000L, null)
        source.capture(2_000L, null)
        source.stopObserving()
        controller.pause().stop().destroy()
    }

    @Test
    fun anEncoderThatFailsDropsThePictureOnly() {
        val rejecting = WireframeImages(onReady = {}, encoder = Executor { throw RejectedExecutionException("no thread") }).apply {
            policy = TracewayImagePolicy.ALWAYS
            beginCapture()
        }
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        assertNull(rejecting.lookup(bitmap, 50f, 50f))
        assertTrue(rejecting.all.single().failed)
        assertNull("given up for good", rejecting.lookup(bitmap, 50f, 50f))

        val throwing = WireframeImages(onReady = { throw IllegalStateException("onReady") }, encoder = Executor { it.run() }).apply {
            policy = TracewayImagePolicy.ALWAYS
            beginCapture()
        }
        throwing.lookup(bitmap, 50f, 50f)
        assertTrue(throwing.all.single().failed)
    }

    // ---- hooks ---------------------------------------------------------------------

    @Test
    fun touchListenersThatThrowNeverChangeTheAppsTouch() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val seen = ArrayList<Int>()
        activity.setContentView(object : View(activity) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                seen.add(event.actionMasked)
                return true
            }
        })
        shadowOf(Looper.getMainLooper()).idle()
        val content = activity.findViewById<View>(android.R.id.content)
        val at = IntArray(2)
        content.getLocationInWindow(at)
        val x = at[0] + content.width / 2f
        val y = at[1] + content.height / 2f
        val tracker = TouchTracker()
        tracker.addTapListener(object : TapListener {
            override fun onTap(root: View, xPx: Float, yPx: Float, xDp: Float, yDp: Float) = throw OutOfMemoryError("tap")
        })
        tracker.addPointerListener(object : PointerListener {
            override fun pointerDown(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean) = throw IllegalStateException("down")
            override fun pointerMove(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean) = throw IllegalStateException("move")
            override fun pointerUp(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean, tap: Boolean) =
                throw IllegalStateException("up")
            override fun pointerCancel(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean) = throw IllegalStateException("cancel")
        })
        tracker.install(app)
        tracker.track(activity.window)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0)
        assertTrue(activity.window.callback.dispatchTouchEvent(down))
        assertTrue(activity.window.callback.dispatchTouchEvent(up))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), seen)
        tracker.uninstall()
    }

    private class Chain(private val request: Request, private val answer: (Request) -> Response) : Interceptor.Chain {
        override fun request(): Request = request
        override fun proceed(request: Request): Response = answer(request)
        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 10_000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 10_000
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 10_000
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    @Test
    fun theOkHttpInterceptorGivesTheAppItsOwnResponseAndItsOwnError() {
        start()
        val request = Request.Builder().url("https://api.example.com/cart").build()
        // An inner interceptor's body whose length cannot be read: the SDK reads it, the app never does.
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = throw IllegalStateException("closed source")
            override fun source(): BufferedSource = Buffer()
        }
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        assertSame(response, TracewayOkHttpInterceptor().intercept(Chain(request) { response }))

        val io = IOException("offline")
        try {
            TracewayOkHttpInterceptor().intercept(Chain(request) { throw io })
            fail()
        } catch (e: IOException) {
            assertSame(io, e)
        }
        val runtime = IllegalArgumentException("app's")
        try {
            TracewayOkHttpInterceptor().intercept(Chain(request) { throw runtime })
            fail()
        } catch (e: IllegalArgumentException) {
            assertSame(runtime, e)
        }
    }

    @Test
    fun mainThreadHooksContainTheirFailures() {
        val main = Handler(Looper.getMainLooper())
        val watchdog = HangWatchdog(
            clock = { SystemClock.uptimeMillis() },
            postToMain = { main.post(it) },
            schedule = { _, _ -> Cancellable { } },
            thresholdMs = 0,
            intervalMs = 1000,
            onHang = { throw IllegalStateException("aggregator") },
        )
        watchdog.start()
        // Runs the probe in the app's main looper: nothing may come out of it.
        shadowOf(Looper.getMainLooper()).idle()
        watchdog.stop()
    }

    @Test
    fun theAppsCrashStillReachesThePreviousHandlerAndShutdownGivesItBack() {
        val chained = ArrayList<Pair<Thread, Throwable>>()
        val previous = Thread.UncaughtExceptionHandler { t, e -> chained.add(t to e) }
        Thread.setDefaultUncaughtExceptionHandler(previous)
        val client = Traceway.init(app, CONNECTION, TracewayOptions(remoteConfig = false))
        assertNotNull(client)
        val installed = Thread.getDefaultUncaughtExceptionHandler()!!
        assertNotEquals(previous, installed)
        val crash = IllegalStateException("app crash")
        val thread = Thread("worker")
        installed.uncaughtException(thread, crash)
        assertEquals("once, the same thread and the same object", listOf<Pair<Thread, Throwable>>(thread to crash), chained)

        Traceway.shutdown(2000)
        assertSame("shutdown gives the handler back", previous, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun aFailedInitLeavesNothingBehind() {
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        val callbacks = lifecycleCallbacks()
        try {
            TracewayClient.initialize(
                app,
                CONNECTION,
                TracewayOptions(),
                TracewayTestOverrides(instrument = true, startThread = { throw OutOfMemoryError("pthread_create (1040KB stack) failed") }),
            )
            fail()
        } catch (_: OutOfMemoryError) {
        }
        assertNull(TracewayClient.instance)
        assertSame("the crash handler is given back", handler, Thread.getDefaultUncaughtExceptionHandler())
        assertEquals("no activity callbacks left", callbacks, lifecycleCallbacks())
        // Through the facade the failure is contained, and the next init works.
        assertNotNull(Traceway.init(app, CONNECTION, TracewayOptions(remoteConfig = false)))
    }

    @Test
    fun aHookThatCannotBeInstalledLeavesTheOthers() {
        val client = TracewayClient.initialize(
            app,
            CONNECTION,
            TracewayOptions(remoteConfig = false),
            TracewayTestOverrides(instrument = true, beforeInstallStep = { if (it == "taps") throw IllegalStateException("taps") }),
        )
        assertSame(client, TracewayClient.instance)
        assertNotSame(defaultHandler, Thread.getDefaultUncaughtExceptionHandler())
        client.shutdown(2000)
    }

    private fun assertNotSame(a: Any?, b: Any?) = assertFalse(a === b)

    /** The activity lifecycle callbacks registered on the application now. */
    private fun lifecycleCallbacks(): Int {
        val field = Application::class.java.getDeclaredField("mActivityLifecycleCallbacks")
        field.isAccessible = true
        return (field.get(app) as java.util.ArrayList<*>).size
    }

    @Test
    fun withoutJavaUtilFunctionInitDoesNothingInsteadOfCrashing() {
        val check = TracewayClient.platformCheck
        TracewayClient.platformCheck = { throw NoClassDefFoundError("java/util/function/Function") }
        try {
            assertNull(Traceway.init(app, CONNECTION))
            assertNull(TracewayClient.instance)
            Traceway.captureException(RuntimeException("x"))
            Traceway.log("l")
        } finally {
            TracewayClient.platformCheck = check
        }
    }

    @Test
    fun theInstanceIdIsNeverTheNilUuid() {
        val client = Traceway.init(app, CONNECTION, TracewayOptions(remoteConfig = false))!!
        assertNotEquals("00000000-0000-0000-0000-000000000000", Traceway.getInstanceId())
        assertTrue(client.awaitReady())
        val id = Traceway.getInstanceId()
        assertNotNull(id)
        assertNotEquals("00000000-0000-0000-0000-000000000000", id)
    }

    // ---- shutdown leaves nothing behind ------------------------------------------------

    class ScreenFragment : Fragment() {
        override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, state: android.os.Bundle?): View =
            View(inflater.context)
    }

    @Test
    fun anUninstalledNavigationCaptureNoLongerFollowsFragments() {
        val seen = ArrayList<String>()
        val navigation = NavigationCapture { path, _, _ -> seen.add(path) }
        val controller = Robolectric.buildActivity(TestActivity::class.java).create()
        val activity = controller.get()
        navigation.onActivityCreated(activity, null)
        controller.start().resume()
        navigation.onActivityResumed(activity)
        navigation.uninstall()
        val container = FrameLayout(activity).apply { id = View.generateViewId() }
        activity.setContentView(container)
        activity.supportFragmentManager.beginTransaction().add(container.id, ScreenFragment()).commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(seen.toString(), seen.contains("/ScreenFragment"))
        controller.pause().stop().destroy()
    }

    @Test
    fun theWireframeSourceForgetsItsWindowsWhenItStops() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val source = ViewFrameSource(WireframeRoots(), app)
        source.observe { }
        source.capture(1_000L, null)
        source.stopObserving()
        val field = ViewFrameSource::class.java.getDeclaredField("lastRoots")
        field.isAccessible = true
        assertEquals("nothing keeps the destroyed activity's window", 0, (field.get(source) as Array<*>).size)
        controller.pause().stop().destroy()
    }

    // ---- the helper itself -----------------------------------------------------------

    @Test
    fun guardedContainsEverythingAndOnlyThat() {
        guarded("test") { throw OutOfMemoryError("ours") }
        assertEquals(3, guarded("test", 3) { throw IllegalStateException("ours") })
        assertEquals(4, guarded("test", 3) { 4 })
        guarded("test") { throw InterruptedException() }
        assertTrue("the interrupt is put back", Thread.interrupted())
        Guard.debug = true
        try {
            repeat(3) { guarded("logged once") { throw IllegalStateException("x") } }
        } finally {
            Guard.debug = false
        }
        // What runs in an interaction is the app's: it gets its own exception back.
        val error = IllegalStateException("app's")
        try {
            start().startInteraction("i").run { throw error }
            fail()
        } catch (e: IllegalStateException) {
            assertSame(error, e)
        }
    }
}
