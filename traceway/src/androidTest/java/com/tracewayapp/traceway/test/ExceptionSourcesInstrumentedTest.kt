package com.tracewayapp.traceway.test

import android.app.Application
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tracewayapp.traceway.TracewayOptions
import com.tracewayapp.traceway.internal.NavigationCapture
import com.tracewayapp.traceway.test.ui.TracewayTestActivity
import org.hamcrest.Matchers.equalTo
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The flagship suite. For every UI source-of-exception the user asked about,
 * we drive the scenario end-to-end and assert that the report posted to the
 * (mock) backend has the expected stack trace.
 *
 * Two production patterns are exercised:
 *
 *   * **Explicit capture** — the click handler wraps in try/catch and calls
 *     [Traceway.captureException] directly. We just flush + assert.
 *
 *   * **Uncaught on a non-test thread** — the click handler schedules work on
 *     a background thread or via a main Handler post; the throw bubbles up to
 *     the default uncaught exception handler. We install our own UEH that
 *     mimics Traceway's production [ErrorHandler] (capture + flush) but does
 *     **not** delegate to the previous handler, otherwise the test runner's
 *     default handler would tear the test process down.
 *
 * Sources covered:
 *   * Direct API call            → [directCapture_isSent], [captureMessage_isSentAsMessageReport]
 *   * Button click (try/catch)   → [throwFromButton_isCaptured]
 *   * Dialog button (try/catch)  → [throwFromDialog_isCaptured]
 *   * Background thread          → [throwFromBackgroundThread_isCaptured]
 *   * Main Handler post          → [throwFromMainHandlerPost_isCaptured]
 *   * After navigation           → [throwAfterNavigation_isCapturedWithNavBreadcrumb]
 *   * println rides along        → [printlnIsAttachedToReport]
 */
@RunWith(AndroidJUnit4::class)
class ExceptionSourcesInstrumentedTest {

    @get:Rule
    val rule = TracewayTestRule(
        options = TracewayOptions(
            debounceMs = 100,
            retryDelayMs = 200,
            persistToDisk = false,
            captureLogs = true,
            captureNavigation = true,
            captureNetwork = false,
            debug = true,
        ),
        installLogCapture = true,
    )

    private val uncaughtLatch = CountDownLatch(1)
    private var savedDefaultUeh: Thread.UncaughtExceptionHandler? = null

    private val navCapture = NavigationCapture()
    private lateinit var app: Application

    @Before
    fun setUp() {
        savedDefaultUeh = Thread.getDefaultUncaughtExceptionHandler()
        // Mimic ErrorHandler.install() — capture + flush — but do NOT delegate
        // to the previous handler, otherwise the test runner kills the process.
        Thread.setDefaultUncaughtExceptionHandler { _, error ->
            try {
                rule.client.captureException(error)
                rule.client.flush(timeoutMs = 3_000)
            } catch (_: Throwable) {
            } finally {
                uncaughtLatch.countDown()
            }
        }

        app = ApplicationProvider.getApplicationContext()
        app.registerActivityLifecycleCallbacks(navCapture)
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(savedDefaultUeh)
        try {
            app.unregisterActivityLifecycleCallbacks(navCapture)
        } catch (_: Throwable) {
        }
    }

    /** For explicit-capture scenarios: flush + take next request. */
    private fun takeReport(timeoutSeconds: Long = 5L): JSONObject {
        val req = rule.flushAndTakeRequest(timeoutSeconds = timeoutSeconds)
            ?: error("MockWebServer never received a report")
        return rule.parseRequestBody(req)
    }

    /**
     * For uncaught-throw scenarios: wait for the UEH to fire (which already
     * called flush), then pull the request the MockWebServer received.
     */
    private fun awaitUncaughtReport(timeoutSeconds: Long = 10L): JSONObject {
        val ok = uncaughtLatch.await(timeoutSeconds, TimeUnit.SECONDS)
        if (!ok) fail("uncaught exception was never captured by our UEH")

        val req = rule.server.takeRequest(timeoutSeconds, TimeUnit.SECONDS)
            ?: error("MockWebServer never received a report")
        return rule.parseRequestBody(req)
    }

    private fun assertPayloadContains(payload: JSONObject, needle: String) {
        val frames = payload.getJSONArray("collectionFrames")
        assertTrue("collectionFrames empty", frames.length() > 0)
        var found = false
        outer@ for (i in 0 until frames.length()) {
            val stacks = frames.getJSONObject(i).getJSONArray("stackTraces")
            for (j in 0 until stacks.length()) {
                val st = stacks.getJSONObject(j).getString("stackTrace")
                if (st.contains(needle)) {
                    found = true
                    break@outer
                }
            }
        }
        assertTrue("payload missing stack containing '$needle'", found)
    }

    // ──────────────────────────────────────────────────────────────────
    // Direct API
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun directCapture_isSent() {
        rule.client.captureException(IllegalArgumentException("direct-capture"))
        val body = takeReport()
        assertPayloadContains(body, "direct-capture")
        assertPayloadContains(body, "IllegalArgumentException")
    }

    @Test
    fun captureMessage_isSentAsMessageReport() {
        rule.client.captureMessage("user_did_thing")
        val body = takeReport()
        val st = body.getJSONArray("collectionFrames").getJSONObject(0)
            .getJSONArray("stackTraces").getJSONObject(0)
        assertTrue("isMessage should be true", st.getBoolean("isMessage"))
        assertTrue("expected message text", st.getString("stackTrace").contains("user_did_thing"))
    }

    // ──────────────────────────────────────────────────────────────────
    // UI: explicit-capture pattern (try/catch + Traceway.captureException)
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun throwFromButton_isCaptured() {
        ActivityScenario.launch(TracewayTestActivity::class.java).use {
            onView(withTagValue(equalTo<Any>("throw_button"))).perform(click())
            val payload = takeReport()
            assertPayloadContains(payload, "ui:throw_button")
            assertPayloadContains(payload, "IllegalStateException")
        }
    }

    @Test
    fun throwFromDialog_isCaptured() {
        ActivityScenario.launch(TracewayTestActivity::class.java).use {
            onView(withTagValue(equalTo<Any>("throw_dialog"))).perform(click())
            onView(withText("ok")).perform(click())
            val payload = takeReport()
            assertPayloadContains(payload, "ui:throw_dialog")
            assertPayloadContains(payload, "RuntimeException")
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // UI: uncaught-on-non-test-thread pattern (default UEH catches)
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun throwFromBackgroundThread_isCaptured() {
        ActivityScenario.launch(TracewayTestActivity::class.java).use {
            onView(withTagValue(equalTo<Any>("throw_background"))).perform(click())
            val payload = awaitUncaughtReport()
            assertPayloadContains(payload, "ui:throw_background")
            assertPayloadContains(payload, "IllegalArgumentException")
        }
    }

    @Test
    fun throwFromMainHandlerPost_isCaptured() {
        ActivityScenario.launch(TracewayTestActivity::class.java).use {
            onView(withTagValue(equalTo<Any>("throw_main_handler"))).perform(click())
            val payload = awaitUncaughtReport()
            assertPayloadContains(payload, "ui:throw_main_handler")
        }
    }

    @Test
    fun throwAfterNavigation_isCapturedWithNavBreadcrumb() {
        ActivityScenario.launch(TracewayTestActivity::class.java).use {
            onView(withTagValue(equalTo<Any>("navigate_then_throw"))).perform(click())

            val payload = awaitUncaughtReport()
            assertPayloadContains(payload, "ui:throw_after_nav_auto")

            val recordings = payload.getJSONArray("collectionFrames")
                .getJSONObject(0)
                .optJSONArray("sessionRecordings")
            assertTrue("expected sessionRecordings", recordings != null && recordings.length() > 0)
            val actions = recordings!!.getJSONObject(0).getJSONArray("actions")

            var sawPush = false
            for (i in 0 until actions.length()) {
                val a = actions.getJSONObject(i)
                if (a.getString("type") == "navigation" &&
                    a.getString("action") == "push" &&
                    a.optString("to") == "TracewayTestActivity2"
                ) {
                    sawPush = true
                    break
                }
            }
            assertTrue("navigation breadcrumb missing in actions: $actions", sawPush)
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Logs ride along
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun printlnIsAttachedToReport() {
        ActivityScenario.launch(TracewayTestActivity::class.java).use {
            onView(withTagValue(equalTo<Any>("println_button"))).perform(click())
            onView(withTagValue(equalTo<Any>("throw_button"))).perform(click())

            val payload = takeReport()
            val recordings = payload.getJSONArray("collectionFrames")
                .getJSONObject(0)
                .optJSONArray("sessionRecordings")
            assertTrue("expected sessionRecordings", recordings != null && recordings.length() > 0)
            val logs = recordings!!.getJSONObject(0).optJSONArray("logs")
            assertTrue("expected logs in recording", logs != null && logs.length() > 0)

            var sawLog = false
            for (i in 0 until logs!!.length()) {
                if (logs.getJSONObject(i).getString("message").contains("ui:println_button")) {
                    sawLog = true
                    break
                }
            }
            assertTrue("println line missing from attached logs", sawLog)
        }
    }
}
