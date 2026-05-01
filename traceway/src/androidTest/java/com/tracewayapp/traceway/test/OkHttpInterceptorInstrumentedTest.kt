package com.tracewayapp.traceway.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tracewayapp.traceway.TracewayOptions
import com.tracewayapp.traceway.events.NetworkEvent
import com.tracewayapp.traceway.network.TracewayOkHttpInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OkHttpInterceptorInstrumentedTest {

    @get:Rule
    val rule = TracewayTestRule(
        options = TracewayOptions(
            debounceMs = 50,
            persistToDisk = false,
            captureNetwork = true,
            debug = true,
        ),
    )

    private lateinit var appServer: MockWebServer

    @Before
    fun setUpAppServer() {
        appServer = MockWebServer().apply { start() }
    }

    @After
    fun tearDownAppServer() {
        try { appServer.shutdown() } catch (_: Throwable) {}
    }

    @Test
    fun successfulHttpCallRecordsNetworkEvent() {
        appServer.enqueue(MockResponse().setResponseCode(204).setBody(""))
        val client = OkHttpClient.Builder()
            .addInterceptor(TracewayOkHttpInterceptor())
            .build()

        val url = appServer.url("/v1/items").toString()
        val res = client.newCall(Request.Builder().url(url).build()).execute()
        res.close()

        val net = rule.client.bufferedActions()
            .filterIsInstance<NetworkEvent>()
            .firstOrNull { it.url == url }
        assertTrue("no NetworkEvent for $url", net != null)
        assertEquals("GET", net!!.method)
        assertEquals(204, net.statusCode)
        assertTrue("durationMs should be >= 0", net.durationMs >= 0)
    }

    @Test
    fun failingHttpCallRecordsErrorString() {
        // Server isn't even reachable — close it and reuse the URL.
        val deadUrl = appServer.url("/dead").toString()
        appServer.shutdown()

        val client = OkHttpClient.Builder()
            .addInterceptor(TracewayOkHttpInterceptor())
            .build()

        try {
            client.newCall(Request.Builder().url(deadUrl).build()).execute()
        } catch (_: Throwable) {
            // expected
        }

        val net = rule.client.bufferedActions()
            .filterIsInstance<NetworkEvent>()
            .firstOrNull { it.url == deadUrl }
        assertTrue("no NetworkEvent recorded for failed call", net != null)
        assertTrue("error string missing", !net!!.error.isNullOrEmpty())
    }

    @Test
    fun networkEventsAttachedToExceptionReport() {
        appServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val client = OkHttpClient.Builder()
            .addInterceptor(TracewayOkHttpInterceptor())
            .build()

        val url = appServer.url("/ride-along").toString()
        client.newCall(Request.Builder().url(url).build()).execute().close()

        rule.client.captureException(RuntimeException("with-network-event"))

        val recorded = rule.flushAndTakeRequest()
        val json = rule.parseRequestBody(recorded!!)
        val recordings = json.getJSONArray("collectionFrames")
            .getJSONObject(0)
            .getJSONArray("sessionRecordings")
        val actions = recordings.getJSONObject(0).getJSONArray("actions")

        var found = false
        for (i in 0 until actions.length()) {
            val a = actions.getJSONObject(i)
            if (a.getString("type") == "network" && a.getString("url") == url) {
                found = true
                assertEquals(200, a.getInt("statusCode"))
                break
            }
        }
        assertTrue("network event not attached to payload", found)
    }
}
