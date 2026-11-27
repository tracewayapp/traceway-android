package com.tracewayapp.traceway.persistence

import com.tracewayapp.traceway.core.OtlpDestination
import com.tracewayapp.traceway.internal.HttpResponse
import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.ManualScheduler
import com.tracewayapp.traceway.internal.Rfc3339
import com.tracewayapp.traceway.internal.asJsonList
import com.tracewayapp.traceway.internal.asJsonObject
import com.tracewayapp.traceway.otel.FakeHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyReportsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // Real time: expiry looks at file modification times.
    private val scheduler = ManualScheduler(nowMs = System.currentTimeMillis())

    /** A file as the 1.x ExceptionStore wrote it, with the clip its client added. */
    private fun entry(createdAtMs: Long = scheduler.now()) = linkedMapOf(
        "createdAt" to Rfc3339.format(createdAtMs),
        "exception" to linkedMapOf(
            "traceId" to null,
            "isTask" to false,
            "stackTrace" to "java.lang.IllegalStateException: legacy error\n    at com.example.Main.run(Main.kt:10)",
            "recordedAt" to Rfc3339.format(createdAtMs),
            "attributes" to linkedMapOf("device.model" to "Pixel"),
            "isMessage" to false,
            "sessionRecordingId" to "clip-1",
            "distributedTraceId" to null,
        ),
        "recording" to linkedMapOf(
            "exceptionId" to "clip-1",
            "events" to emptyList<Any>(),
            "logs" to listOf(linkedMapOf("message" to "line", "level" to "info")),
        ),
    )

    private val target = OtlpDestination("https://old.test/api/report", mapOf("Authorization" to "Bearer original"))

    @Test
    fun legacyErrorsAndTheirClipsRetryOnlyAgainstTheirOriginalProject() {
        val pending = tmp.newFolder("traceway_pending")
        val saved = entry()
        File(pending, "report.json").writeText(Json.stringify(saved))
        val old = File(pending, "expired.json").apply {
            writeText(Json.stringify(saved))
            setLastModified(System.currentTimeMillis() - 2 * 24 * 3600_000L)
        }
        var status = 503
        val http = FakeHttp { HttpResponse(status) }

        LegacyReports(pending, null, http, scheduler).drain()
        assertFalse(old.exists())
        assertTrue(http.requests.isEmpty())

        LegacyReports(pending, target, http, scheduler).drain()
        LegacyReports(pending, OtlpDestination("https://new.test/api/report", emptyMap()), http, scheduler).drain()
        assertEquals(1, http.requests.size)

        status = 200
        val next = LegacyReports(pending, target, http, scheduler)
        next.drain()
        next.drain()
        assertEquals(2, http.requests.size)
        for (request in http.requests) {
            assertEquals(target.url, request.url)
            assertEquals("Bearer original", request.headers["Authorization"])
            assertEquals("application/json", request.headers["Content-Type"])
            val frame = Json.parse(request.plain.toString(Charsets.UTF_8)).asJsonObject()!!["collectionFrames"]
                .asJsonList()!!.single().asJsonObject()!!
            assertEquals(listOf(saved["exception"]), frame["stackTraces"])
            assertEquals(listOf(saved["recording"]), frame["sessionRecordings"])
        }
        assertTrue(File(pending, target.key).listFiles()!!.isEmpty())
    }

    @Test
    fun aPermanentFailureDeletesTheReport() {
        val pending = tmp.newFolder("traceway_pending")
        File(pending, "a.json").writeText(Json.stringify(entry()))
        val http = FakeHttp { HttpResponse(401) }
        LegacyReports(pending, target, http, scheduler).drain()
        assertEquals(1, http.requests.size)
        assertTrue(File(pending, target.key).listFiles()!!.isEmpty())
    }

    @Test
    fun reportsCreatedMoreThanTwelveHoursAgoOrUnreadableAreDropped() {
        val pending = tmp.newFolder("traceway_pending")
        File(pending, "old.json").writeText(Json.stringify(entry(scheduler.now() - 13 * 3600_000L)))
        File(pending, "corrupt.json").writeText("{not json")
        File(pending, "noexception.json").writeText("""{"createdAt":"${Rfc3339.format(scheduler.now())}"}""")
        val http = FakeHttp()
        LegacyReports(pending, target, http, scheduler).drain()
        assertTrue(http.requests.isEmpty())
        assertTrue(File(pending, target.key).listFiles()!!.isEmpty())
    }

    @Test
    fun aReportWithoutAClipIsSentWithoutRecordings() {
        val pending = tmp.newFolder("traceway_pending")
        val saved = entry().apply { remove("recording") }
        File(pending, "a.json").writeText(Json.stringify(saved))
        val http = FakeHttp()
        assertTrue(LegacyReports(pending, target, http, scheduler).drainBlocking(1000))
        val frame = Json.parse(http.requests.single().plain.toString(Charsets.UTF_8)).asJsonObject()!!["collectionFrames"]
            .asJsonList()!!.single().asJsonObject()!!
        assertFalse(frame.containsKey("sessionRecordings"))
    }

    @Test
    fun theOneXConnectionStringNamesTheProject() {
        val destination = LegacyReports.destinationOf("original@https://old.test/prefix/api/report")!!
        assertEquals("https://old.test/prefix/api/report", destination.url)
        assertEquals("Bearer original", destination.headers["Authorization"])
        assertNull(LegacyReports.destinationOf("no-separator"))
        assertNull(LegacyReports.destinationOf(null))
        assertEquals(File("/data/nb", "traceway_pending"), LegacyReports.directoryIn(File("/data/nb")))
    }

    @Test
    fun aMissingDirectoryIsNothingToDo() {
        val http = FakeHttp()
        assertTrue(LegacyReports(File(tmp.root, "absent"), target, http, scheduler).drainBlocking(100))
        assertTrue(http.requests.isEmpty())
    }
}
