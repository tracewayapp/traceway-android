package com.tracewayapp.traceway.persistence

import com.tracewayapp.traceway.core.OtlpDestination
import com.tracewayapp.traceway.internal.HttpTransport
import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.Rfc3339
import com.tracewayapp.traceway.internal.TaskScheduler
import com.tracewayapp.traceway.internal.asJsonObject
import com.tracewayapp.traceway.internal.gzip
import com.tracewayapp.traceway.internal.isTransientFailure
import com.tracewayapp.traceway.internal.parseConnectionString
import io.opentelemetry.sdk.common.CompletableResultCode
import java.io.File

/**
 * Unsent reports of the 1.x SDK, delivered as 1.x did: one JSON file per
 * exception (`{createdAt, exception, recording?}`) in
 * `<noBackupFilesDir>/traceway_pending`, posted as a 1.x `ReportRequest`
 * to the 1.x connection string's URL. 1.x saved each exception together
 * with its clip, without saving its project: a file is first moved into a
 * directory of the project it is sent to, so a later change of
 * [destination] never sends it to another project. Reports expire after
 * 12 hours, the 1.x default.
 */
internal class LegacyReports(
    val pending: File,
    /** The 1.x project: its report URL and Bearer token. Null leaves reports to expire. */
    val destination: OtlpDestination?,
    private val http: HttpTransport,
    private val scheduler: TaskScheduler,
) {
    companion object {
        const val MAX_AGE_MS = 12L * 60 * 60 * 1000
        const val TIMEOUT_MS = 30_000

        /** Where 1.x kept its reports. */
        fun directoryIn(noBackupFilesDir: File): File = File(noBackupFilesDir, "traceway_pending")

        /** The 1.x connection string's project, or null when there is none or it does not parse. */
        fun destinationOf(connectionString: String?): OtlpDestination? {
            if (connectionString.isNullOrBlank()) return null
            return try {
                val parsed = parseConnectionString(connectionString.trim())
                OtlpDestination(parsed.apiUrl, mapOf("Authorization" to "Bearer ${parsed.token}"))
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    private val drainer = SingleFlight(scheduler) { drainNow() }

    /** Sends what can be sent and removes what expired, on the I/O pool; one run at a time. */
    fun drain(): CompletableResultCode = drainer.run()

    fun drainBlocking(timeoutMs: Long): Boolean = drain().await(timeoutMs)

    private fun drainNow() {
        val target = destination
        val claimed = target?.let { File(pending, it.key) }
        val cutoff = scheduler.now() - MAX_AGE_MS
        try {
            for (entry in pending.listFiles() ?: emptyArray()) {
                if (entry.isDirectory) {
                    if (entry.path == claimed?.path) continue
                    for (old in entry.listFiles() ?: emptyArray()) {
                        if (old.isFile && old.name.endsWith(".json") && old.lastModified() < cutoff) old.delete()
                    }
                    if (entry.list()?.isEmpty() == true) entry.delete()
                    continue
                }
                if (!entry.isFile || !entry.name.endsWith(".json")) continue
                if (entry.lastModified() < cutoff) {
                    entry.delete()
                } else if (claimed != null) {
                    claimed.mkdirs()
                    entry.renameTo(File(claimed, entry.name))
                }
            }
            if (target == null || claimed == null || !claimed.isDirectory) return
            val files = (claimed.listFiles() ?: emptyArray())
                .filter { it.isFile && it.name.endsWith(".json") }
                .sortedBy { it.lastModified() }
            for (file in files) {
                if (file.lastModified() < cutoff) {
                    file.delete()
                    continue
                }
                val body = reportBody(file, cutoff)
                if (body == null) {
                    file.delete()
                    continue
                }
                val headers = LinkedHashMap(target.headers)
                headers["Content-Type"] = "application/json"
                headers["Content-Encoding"] = "gzip"
                val status = try {
                    http.post(target.url, headers, gzip(body.toByteArray(Charsets.UTF_8)), TIMEOUT_MS).status
                } catch (_: Throwable) {
                    0
                }
                // Not now: the next start, resume or flush tries again.
                if (isTransientFailure(status)) return
                // Delivered, or rejected for good.
                file.delete()
            }
        } catch (_: Throwable) {
            // An interrupted run is retried on the next start or resume.
        }
    }

    /** The 1.x `ReportRequest` of one saved exception and its clip, or null for a file not worth sending. */
    private fun reportBody(file: File, cutoffMs: Long): String? {
        val entry = try {
            Json.parse(file.readText(Charsets.UTF_8)).asJsonObject()
        } catch (_: Throwable) {
            null
        } ?: return null
        val exception = entry["exception"].asJsonObject() ?: return null
        val createdAt = (entry["createdAt"] as? String)?.let { Rfc3339.parse(it) } ?: return null
        if (createdAt <= cutoffMs) return null
        val frame = LinkedHashMap<String, Any?>()
        frame["stackTraces"] = listOf(exception)
        entry["recording"].asJsonObject()?.let { frame["sessionRecordings"] = listOf(it) }
        return Json.stringify(mapOf("collectionFrames" to listOf(frame)))
    }
}
