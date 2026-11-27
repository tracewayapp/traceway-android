package com.tracewayapp.traceway.persistence

import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.Rfc3339
import com.tracewayapp.traceway.internal.asJsonObject
import java.io.File

/**
 * Unsent reports of the 1.x SDK (CONTRACT §16): one JSON file per
 * exception, `{createdAt, exception: {stackTrace, recordedAt, attributes,
 * isMessage, ...}, recording?}`, in `<noBackupFilesDir>/traceway_pending`
 * (2.0 betas moved them into a folder per project there). They are read
 * once, handed to the client as exceptions and messages of this SDK, sent
 * as OTLP records like any other, and deleted. Nothing is ever posted to
 * 1.x's `/api/report`. Reports older than 12 hours (the 1.x limit), files
 * that do not parse and 1.x recordings are deleted unread.
 */
internal object LegacyReports {
    const val MAX_AGE_MS = 12L * 60 * 60 * 1000

    /** At most this many are reported, the newest; 1.x kept 5 by default. */
    const val MAX_REPORTS = 20

    /** Where 1.x kept its reports. */
    fun directoryIn(noBackupFilesDir: File): File = File(noBackupFilesDir, "traceway_pending")

    /** One 1.x report, as this SDK records it. */
    class Report(
        val timeMs: Long,
        val isMessage: Boolean,
        /** The exception's class (`java.lang.IllegalStateException`), from its first line; empty for a message. */
        val type: String,
        /** The exception's message, or the message's text. */
        val message: String,
        /** 1.x's formatted stack trace: `Type: message`, then the frames. */
        val stackTrace: String,
        val attributes: Map<String, String>,
    )

    /**
     * Reads every report in [directory] and deletes it, with the directory:
     * the ones still worth reporting, oldest first. Empty when there is none.
     */
    fun takeAll(directory: File, nowMs: Long): List<Report> {
        if (!directory.isDirectory) return emptyList()
        val files = ArrayList<File>()
        for (entry in directory.listFiles() ?: emptyArray()) {
            if (entry.isDirectory) entry.listFiles()?.let { files.addAll(it) } else files.add(entry)
        }
        val reports = ArrayList<Report>()
        val cutoff = nowMs - MAX_AGE_MS
        for (file in files) {
            try {
                if (file.isFile && file.name.endsWith(".json")) read(file, cutoff)?.let { reports.add(it) }
            } catch (_: Throwable) {
                // Unreadable: deleted below like the rest.
            }
        }
        deleteTree(directory)
        return reports.sortedBy { it.timeMs }.takeLast(MAX_REPORTS)
    }

    private fun read(file: File, cutoffMs: Long): Report? {
        if (file.length() > MAX_FILE_BYTES) return null
        val entry = Json.parse(file.readText(Charsets.UTF_8)).asJsonObject() ?: return null
        val exception = entry["exception"].asJsonObject() ?: return null
        val createdAt = (entry["createdAt"] as? String)?.let { Rfc3339.parse(it) }
        val recordedAt = (exception["recordedAt"] as? String)?.let { Rfc3339.parse(it) }
        val timeMs = recordedAt ?: createdAt ?: return null
        if ((createdAt ?: timeMs) <= cutoffMs) return null
        val stackTrace = (exception["stackTrace"] as? String)?.trimEnd().orEmpty()
        if (stackTrace.isEmpty()) return null
        val attributes = LinkedHashMap<String, String>()
        exception["attributes"].asJsonObject()?.forEach { (k, v) -> if (k.isNotEmpty() && v != null) attributes[k] = v.toString() }
        val isMessage = exception["isMessage"] == true
        if (isMessage) return Report(timeMs, true, "", stackTrace, stackTrace, attributes)
        val first = stackTrace.lineSequence().first().trim()
        val colon = first.indexOf(": ")
        val type = if (colon > 0) first.substring(0, colon) else first
        val message = if (colon > 0) first.substring(colon + 2) else ""
        return Report(timeMs, false, type, message, stackTrace, attributes)
    }

    private fun deleteTree(dir: File) {
        try {
            dir.listFiles()?.forEach { if (it.isDirectory) deleteTree(it) else it.delete() }
            dir.delete()
        } catch (_: Throwable) {
        }
    }

    /** A 1.x report holds a stack trace and maybe a recording: a bigger file is not one worth reading. */
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
}
