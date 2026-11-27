package com.tracewayapp.traceway.persistence

import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.Rfc3339
import com.tracewayapp.traceway.internal.asJsonObject
import java.io.File

/**
 * Unsent reports of the 1.x SDK (CONTRACT §16): one JSON file per
 * exception, `{createdAt, exception: {stackTrace, recordedAt, attributes,
 * isMessage, ...}, recording?}`, in `<noBackupFilesDir>/traceway_pending`.
 * The beta SDK claimed files into directories keyed by their
 * report destination. Recover only those that match this client's server and token.
 * The original root-level files have no destination and cannot be attributed.
 */
internal object LegacyReports {
    const val MAX_AGE_MS = 12L * 60 * 60 * 1000

    /** At most this many are reported, the newest; 1.x kept 5 by default. */
    const val MAX_REPORTS = 20

    /** Where 1.x kept its reports. */
    fun directoryIn(noBackupFilesDir: File): File = File(noBackupFilesDir, "traceway_pending")

    /** One 1.x report, as this SDK records it. */
    class Report(
        val file: File,
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

    /** Only the beta's matching destination keys can establish ownership. A caller acknowledges delivery by deleting [Report.file]. */
    fun pending(directory: File, keys: Set<String>, nowMs: Long): List<Report> {
        val cutoff = nowMs - MAX_AGE_MS
        val reports = ArrayList<Report>()
        for (entry in directory.listFiles().orEmpty()) {
            if (entry.isFile) {
                entry.delete() // 1.x root-level records never stored a destination.
                continue
            }
            for (file in entry.listFiles().orEmpty()) {
                if (!file.isFile) continue
                if (entry.name !in keys) {
                    if (file.lastModified() < cutoff) file.delete()
                    continue
                }
                val report = runCatching { if (file.name.endsWith(".json")) read(file, cutoff) else null }.getOrNull()
                if (report == null) file.delete() else reports.add(report)
            }
            entry.delete() // Only succeeds when empty.
        }
        directory.delete()
        val sorted = reports.sortedBy { it.timeMs }
        sorted.dropLast(MAX_REPORTS).forEach { it.file.delete() }
        return sorted.takeLast(MAX_REPORTS)
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
        if (isMessage) return Report(file, timeMs, true, "", stackTrace, stackTrace, attributes)
        val first = stackTrace.lineSequence().first().trim()
        val colon = first.indexOf(": ")
        val type = if (colon > 0) first.substring(0, colon) else first
        val message = if (colon > 0) first.substring(colon + 2) else ""
        return Report(file, timeMs, false, type, message, stackTrace, attributes)
    }

    /** A 1.x report holds a stack trace and maybe a recording: a bigger file is not one worth reading. */
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
}
