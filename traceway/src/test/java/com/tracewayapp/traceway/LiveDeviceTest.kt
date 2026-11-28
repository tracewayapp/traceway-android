package com.tracewayapp.traceway

import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.tracewayapp.traceway.network.TracewayOkHttpInterceptor
import com.tracewayapp.traceway.wireframe.RealScreen
import com.tracewayapp.traceway.wireframe.TestActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The whole SDK, started with `Traceway.init` the way an app starts it,
 * against a real Traceway server: an activity of real views under
 * Robolectric, touches dispatched to its window, typing, an OkHttp call, a
 * caught exception with its dialog, a crash on a background thread, then
 * the app going to the background. Everything leaves through the SDK's own
 * HTTP stack and threads: OTLP logs, spans and metrics, OpAMP, and the
 * wireframe clips of what the views showed.
 *
 * Skipped unless `TRACEWAY_E2E_CONNECTION` names a server
 * (`token@http://127.0.0.1:8099`) with an Android project. It prints the
 * ids to look up there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveDeviceTest {
    /** A checkout screen of the kind apps have. */
    internal class CheckoutActivity : TestActivity() {
        lateinit var name: EditText
        lateinit var remember: CheckBox
        lateinit var pay: MaterialButton
        var dialog: AlertDialog? = null
        lateinit var http: OkHttpClient
        var paymentUrl = ""
        var paymentStatus: Int? = null
        var paymentFailure: IOException? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            name = EditText(this).apply { id = android.R.id.edit; hint = "Name on card" }
            remember = CheckBox(this).apply { id = android.R.id.checkbox; text = "Remember me" }
            pay = MaterialButton(this).apply {
                id = android.R.id.button1
                text = "Pay 30.59"
                setOnClickListener { onPay() }
            }
            setContentView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.WHITE)
                    addView(Toolbar(this@CheckoutActivity).apply { title = "Checkout"; setBackgroundColor(0xFF6750A4.toInt()); setTitleTextColor(Color.WHITE) })
                    addView(TextView(this@CheckoutActivity).apply { text = "2 items · 30.59"; textSize = 18f; setPadding(32, 32, 32, 16) })
                    addView(name)
                    addView(remember)
                    addView(pay)
                    addView(
                        ScrollView(this@CheckoutActivity).apply {
                            addView(LinearLayout(this@CheckoutActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                for (i in 1..20) addView(TextView(this@CheckoutActivity).apply { text = "Order #${1000 + i}"; setPadding(32, 24, 32, 24) })
                            })
                        },
                        LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
                    )
                },
            )
        }

        private fun onPay() {
            Traceway.recordAction("checkout", "pay", mapOf("items" to 2))
            Traceway.track("checkout_started", mapOf("total" to "30.59"))
            println("Paying for 2 items")
            val request = Request.Builder().url(paymentUrl).post(okhttp3.RequestBody.create(null, "{}")).build()
            http.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread { paymentFailure = e }
                }

                override fun onResponse(call: Call, response: Response) {
                    val code = response.use { it.code }
                    runOnUiThread {
                        paymentStatus = code
                        Traceway.captureException(IllegalStateException("Payment provider returned $code"), mapOf("checkout.step" to "pay"))
                        dialog = AlertDialog.Builder(this@CheckoutActivity).setTitle("Payment failed")
                            .setMessage("The card was declined.").setPositiveButton("Retry", null).show()
                    }
                }
            })
        }
    }

    @After
    fun tearDown() = TracewayClient.resetForTest()

    private fun frames(ms: Long) {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) {
            // What a device does every frame: draw the windows, then tell their observers.
            for (w in windows()) {
                val bitmap = android.graphics.Bitmap.createBitmap(w.width.coerceAtLeast(1), w.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                w.draw(android.graphics.Canvas(bitmap))
                bitmap.recycle()
                if (w.viewTreeObserver.isAlive) w.viewTreeObserver.dispatchOnDraw()
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<View> {
        val cls = Class.forName("android.view.WindowManagerGlobal")
        val global = cls.getMethod("getInstance").invoke(null)
        val field = cls.getDeclaredField("mViews").apply { isAccessible = true }
        return (field.get(global) as List<View>).filter { it.isAttachedToWindow && it.visibility == View.VISIBLE }
    }

    private fun tap(window: android.view.Window, v: View) {
        val at = IntArray(2)
        v.getLocationInWindow(at)
        val x = at[0] + v.width / 2f
        val y = at[1] + v.height / 2f
        val down = SystemClock.uptimeMillis()
        window.callback.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN })
        frames(80)
        window.callback.dispatchTouchEvent(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN })
        frames(300)
    }

    @Test
    fun aCheckoutSessionReachesTheServer() {
        val connection = System.getenv("TRACEWAY_E2E_CONNECTION")
        assumeTrue("set TRACEWAY_E2E_CONNECTION to run against a server", !connection.isNullOrEmpty())
        RealScreen.framesAt60Hz()
        MockWebServer().use { payments ->
            val receivedPayment = CompletableFuture<RecordedRequest>()
            payments.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.method == "POST" && request.path == "/pay") {
                        receivedPayment.complete(request)
                        MockResponse().setResponseCode(502).setBody("{\"error\":\"declined\"}")
                    } else {
                        MockResponse().setResponseCode(404)
                    }
            }
            payments.start()
            // Local development tools can probe a newly opened port before the checkout tap.
            val probe = Request.Builder().url(payments.url("/")).build()
            OkHttpClient().newCall(probe).execute().use { assertEquals(404, it.code) }
            checkoutSession(requireNotNull(connection), payments)
            val payment = receivedPayment.get(5, TimeUnit.SECONDS)
            assertTrue(payment.getHeader("traceparent")?.startsWith("00-") == true)
        }
    }

    private fun checkoutSession(connection: String, payments: MockWebServer) {
        val app = RuntimeEnvironment.getApplication()
        val client = Traceway.init(
            app,
            connection,
            TracewayOptions(
                version = "2.0.0-e2e",
                environment = "e2e",
                screenCapture = true,
                debug = true,
                propagateTraceHeaderUrls = listOf(payments.url("/").toString()),
                attributes = mapOf("device.model" to "Robolectric Pixel", "device.manufacturer" to "Google"),
            ),
        )
        assertNotNull(client)
        Traceway.setAttribute("user.id", "e2e-android-user")
        // The SDK starts on its own thread and polls OpAMP; give the rules time to arrive.
        val deadline = System.currentTimeMillis() + 10_000
        while (client!!.currentSessionId == null && System.currentTimeMillis() < deadline) frames(100)
        frames(2500)

        val controller = Robolectric.buildActivity(CheckoutActivity::class.java).setup()
        val activity = controller.get()
        activity.http = OkHttpClient.Builder().addInterceptor(TracewayOkHttpInterceptor()).build()
        activity.paymentUrl = payments.url("/pay").toString()
        frames(1500)

        tap(activity.window, activity.name)
        for (ch in "Ada Lovelace") {
            activity.name.append(ch.toString())
            frames(120)
        }
        tap(activity.window, activity.remember)
        assertTrue("the tap reached the checkbox", activity.remember.isChecked)
        frames(500)
        tap(activity.window, activity.pay)
        val paymentDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (activity.dialog == null && activity.paymentFailure == null && System.nanoTime() < paymentDeadline) frames(50)
        activity.paymentFailure?.let { throw AssertionError("Local checkout request failed", it) }
        assertEquals("the payment mock returned its decline", 502, activity.paymentStatus)
        val dialog = requireNotNull(activity.dialog)
        frames(1500)
        tap(dialog.window!!, dialog.getButton(AlertDialog.BUTTON_POSITIVE))
        frames(1000)

        Traceway.captureMessage("User opened support chat")
        frames(500)
        // A crash on a background thread: written to disk and sent, while the test's process lives on.
        Thread({ throw IllegalStateException("Background sync crashed") }, "sync").apply {
            start()
            join(10_000)
        }
        frames(500)
        // The app goes to the background: buffers are saved, metrics flushed.
        controller.pause().stop()
        frames(1500)
        flushOffMain(15_000)
        println("E2E-DEVICE session=${client.currentSessionId} instance=${client.getInstanceId()} console=${client.settings.signal("console").mode}")
        client.shutdown(10_000)
    }

    /** flush() waits 2 s at most on the main thread (an ANR comes at 5 s): the script waits for the whole send, as an app's worker would. */
    private fun flushOffMain(timeoutMs: Long) {
        Thread { Traceway.flush(timeoutMs) }.apply {
            start()
            join(timeoutMs + 5_000)
        }
    }
}
