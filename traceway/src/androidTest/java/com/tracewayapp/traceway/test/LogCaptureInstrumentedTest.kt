package com.tracewayapp.traceway.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tracewayapp.traceway.TracewayOptions
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that `println` (and writes to `System.out` / `System.err`) are
 * captured into the rolling log buffer once [LogCapture] is installed, and
 * end up attached to the next exception report.
 */
@RunWith(AndroidJUnit4::class)
class LogCaptureInstrumentedTest {

    @get:Rule
    val rule = TracewayTestRule(
        options = TracewayOptions(
            debounceMs = 50,
            persistToDisk = false,
            captureLogs = true,
            debug = true,
        ),
        installLogCapture = true, // installs LogCapture on System.out/err
    )

    @Test
    fun printlnIsCapturedIntoLogBuffer() {
        println("traceway-test:hello-stdout")
        System.err.println("traceway-test:hello-stderr")

        val logs = rule.client.bufferedLogs().map { it.message }
        assertTrue("missing stdout line in $logs", logs.any { it.contains("hello-stdout") })
        assertTrue("missing stderr line in $logs", logs.any { it.contains("hello-stderr") })
    }

    @Test
    fun capturedLogsRideAlongWithExceptionPayload() {
        println("traceway-test:before-throw")

        rule.client.captureException(IllegalStateException("with-logs"))

        val recorded = rule.flushAndTakeRequest()
        assertTrue("no request received", recorded != null)
        val json = rule.parseRequestBody(recorded!!)
        val firstFrame = json.getJSONArray("collectionFrames").getJSONObject(0)
        val recordings = firstFrame.optJSONArray("sessionRecordings")
        assertTrue("expected sessionRecordings to be present", recordings != null && recordings.length() == 1)
        val logsArr = recordings!!.getJSONObject(0).getJSONArray("logs")
        var found = false
        for (i in 0 until logsArr.length()) {
            if (logsArr.getJSONObject(i).getString("message").contains("before-throw")) {
                found = true
                break
            }
        }
        assertTrue("captured log not attached to exception payload: $json", found)
    }

    @Test
    fun nonAsciiPrintlnRoundTripsAsUtf8() {
        // Verifies the LogCapture buffer decodes UTF-8 instead of byte-by-byte.
        val emoji = "✨"
        val multibyte = "café — naïve résumé"
        println("traceway-test:$emoji-$multibyte")

        val msgs = rule.client.bufferedLogs().map { it.message }
        assertTrue(
            "UTF-8 captured incorrectly: $msgs",
            msgs.any { it.contains(emoji) && it.contains(multibyte) },
        )
    }
}
