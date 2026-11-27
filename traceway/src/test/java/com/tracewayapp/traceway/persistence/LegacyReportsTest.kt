package com.tracewayapp.traceway.persistence

import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.Rfc3339
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * CONTRACT §16: what a 1.x build left unsent is read once, as exceptions
 * and messages of this SDK, and deleted; nothing is posted to `/api/report`
 * (the client end of it is in `StartApiTest`).
 */
class LegacyReportsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val now = System.currentTimeMillis()

    /** A file as the 1.x ExceptionStore wrote it, with the clip its client added. */
    private fun entry(
        createdAtMs: Long = now,
        stackTrace: String = "java.lang.IllegalStateException: legacy error\n    at com.example.Main.run(Main.kt:10)",
        isMessage: Boolean = false,
    ) = linkedMapOf(
        "createdAt" to Rfc3339.format(createdAtMs),
        "exception" to linkedMapOf(
            "traceId" to null,
            "isTask" to false,
            "stackTrace" to stackTrace,
            "recordedAt" to Rfc3339.format(createdAtMs - 1000),
            "attributes" to linkedMapOf("device.model" to "Pixel"),
            "isMessage" to isMessage,
            "sessionRecordingId" to "clip-1",
            "distributedTraceId" to null,
        ),
        "recording" to linkedMapOf(
            "exceptionId" to "clip-1",
            "events" to emptyList<Any>(),
            "logs" to listOf(linkedMapOf("message" to "line", "level" to "info")),
        ),
    )

    @Test
    fun exceptionsAndMessagesAreReadOnceAndEveryFileIsDeleted() {
        val pending = tmp.newFolder("traceway_pending")
        File(pending, "a.json").writeText(Json.stringify(entry()))
        File(pending, "b.json").writeText(Json.stringify(entry(createdAtMs = now - 5000, stackTrace = "User opened support chat", isMessage = true)))
        // A 2.0 beta moved reports into a folder of the project it meant to send them to.
        val project = File(pending, "0123abcd").apply { mkdirs() }
        File(project, "c.json").writeText(Json.stringify(entry(stackTrace = "java.io.IOException\n    at com.example.Net.call(Net.kt:3)")))
        // Too old for 1.x, unreadable, not a report: deleted unread.
        File(pending, "old.json").writeText(Json.stringify(entry(createdAtMs = now - 13 * 3600_000L)))
        File(pending, "broken.json").writeText("{not json")
        File(pending, "notes.txt").writeText("x")

        val reports = LegacyReports.takeAll(pending, now)
        assertEquals(3, reports.size)
        val message = reports.first()
        assertTrue("oldest first", message.isMessage)
        assertEquals("User opened support chat", message.message)
        assertEquals("", message.type)

        val exception = reports.first { it.type == "java.lang.IllegalStateException" }
        assertFalse(exception.isMessage)
        assertEquals("legacy error", exception.message)
        assertTrue(exception.stackTrace.startsWith("java.lang.IllegalStateException: legacy error\n    at com.example.Main.run"))
        assertEquals("the time 1.x recorded it", now - 1000, exception.timeMs)
        assertEquals(mapOf("device.model" to "Pixel"), exception.attributes)

        val bare = reports.first { it.type == "java.io.IOException" }
        assertEquals("", bare.message)

        assertFalse("the 1.x directory is gone", pending.exists())
        assertTrue("and there is nothing to read again", LegacyReports.takeAll(pending, now).isEmpty())
    }

    @Test
    fun atMostTheNewestReportsAreKept() {
        val pending = tmp.newFolder("traceway_pending")
        for (i in 0 until LegacyReports.MAX_REPORTS + 5) {
            File(pending, "r$i.json").writeText(Json.stringify(entry(createdAtMs = now - (100 - i) * 1000L, stackTrace = "java.lang.Error: $i")))
        }
        val reports = LegacyReports.takeAll(pending, now)
        assertEquals(LegacyReports.MAX_REPORTS, reports.size)
        assertEquals("java.lang.Error: ${LegacyReports.MAX_REPORTS + 4}", reports.last().stackTrace)
        assertFalse(pending.exists())
    }

    @Test
    fun noDirectoryIsNoReport() {
        assertTrue(LegacyReports.takeAll(File(tmp.root, "missing"), now).isEmpty())
        assertEquals("traceway_pending", LegacyReports.directoryIn(tmp.root).name)
    }
}
