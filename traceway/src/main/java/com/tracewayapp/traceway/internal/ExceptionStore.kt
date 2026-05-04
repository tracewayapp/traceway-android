package com.tracewayapp.traceway.internal

import android.util.Log
import com.tracewayapp.traceway.models.ExceptionStackTrace
import com.tracewayapp.traceway.models.SessionRecordingPayload
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class PendingEntry(
    val id: String,
    val createdAtMs: Long,
    val exception: ExceptionStackTrace,
    val recording: SessionRecordingPayload?,
)

/**
 * On-disk pending-exception store. Mirrors the Flutter SDK's behavior:
 * one JSON file per pending exception under the app's no-backup files dir.
 */
internal class ExceptionStore(
    private val dir: File,
    private val maxLocalFiles: Int,
    private val maxAgeHours: Int,
    private val debug: Boolean,
) {
    @Volatile
    private var available: Boolean = false

    val isAvailable: Boolean get() = available

    /** Idempotent — safe to call multiple times. */
    fun init() {
        if (available) return
        try {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            available = dir.exists() && dir.isDirectory
            pruneExpired()
            pruneExcess()
            if (debug && available) {
                Log.d(TAG, "exception store ready at ${dir.absolutePath}")
            }
        } catch (e: Throwable) {
            available = false
            if (debug) Log.w(TAG, "disk storage unavailable: $e")
        }
    }

    /** Writes an exception to disk. Returns the file ID, or null on failure. */
    fun write(exception: ExceptionStackTrace): String? {
        if (!available) return null
        return try {
            val id = UUID.randomUUID().toString()
            val data = JSONObject()
            data.put("createdAt", Iso8601.format(System.currentTimeMillis()))
            data.put("exception", exception.toJson())
            File(dir, "$id.json").writeText(data.toString(), Charsets.UTF_8)
            if (debug) Log.d(TAG, "persisted exception $id")
            id
        } catch (e: Throwable) {
            if (debug) Log.w(TAG, "failed to write exception to disk: $e")
            null
        }
    }

    /** Adds recording data to an existing exception file on disk. */
    fun writeRecording(fileId: String, recording: SessionRecordingPayload) {
        if (!available) return
        try {
            val file = File(dir, "$fileId.json")
            if (!file.exists()) return
            val data = JSONObject(file.readText(Charsets.UTF_8))
            data.put("recording", recording.toJson())
            file.writeText(data.toString(), Charsets.UTF_8)
            if (debug) Log.d(TAG, "persisted recording for $fileId")
        } catch (e: Throwable) {
            if (debug) Log.w(TAG, "failed to write recording to disk: $e")
        }
    }

    /** Removes files for the given IDs after a successful sync. */
    fun remove(fileIds: List<String>) {
        if (!available) return
        for (id in fileIds) {
            try {
                val file = File(dir, "$id.json")
                if (file.exists() && file.delete()) {
                    if (debug) Log.d(TAG, "removed synced file $id")
                }
            } catch (e: Throwable) {
                if (debug) Log.w(TAG, "failed to remove file $id: $e")
            }
        }
    }

    /** Loads all pending entries from disk, ordered oldest-first. */
    fun loadAll(): List<PendingEntry> {
        if (!available) return emptyList()
        val entries = mutableListOf<PendingEntry>()
        try {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
            for (file in files) {
                try {
                    val data = JSONObject(file.readText(Charsets.UTF_8))
                    val createdAt = Iso8601.parse(data.optString("createdAt", ""))
                    val exception = ExceptionStackTrace.fromJson(
                        data.optJSONObject("exception") ?: continue,
                    )
                    val recording = data.optJSONObject("recording")
                        ?.let { SessionRecordingPayload.fromJson(it) }
                    val id = file.nameWithoutExtension
                    exception.fileId = id
                    entries.add(PendingEntry(id, createdAt, exception, recording))
                } catch (e: Throwable) {
                    runCatching { file.delete() }
                    if (debug) Log.w(TAG, "removed corrupt file ${file.name}: $e")
                }
            }
            entries.sortBy { it.createdAtMs }
        } catch (e: Throwable) {
            if (debug) Log.w(TAG, "failed to load pending entries: $e")
        }
        return entries
    }

    private fun pruneExpired() {
        if (!available) return
        try {
            val cutoff = System.currentTimeMillis() - maxAgeHours * 3600_000L
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
            for (file in files) {
                try {
                    val data = JSONObject(file.readText(Charsets.UTF_8))
                    val createdAt = Iso8601.parse(data.optString("createdAt", ""))
                    if (createdAt < cutoff) {
                        if (file.delete() && debug) Log.d(TAG, "pruned expired file ${file.name}")
                    }
                } catch (_: Throwable) {
                    runCatching { file.delete() }
                }
            }
        } catch (e: Throwable) {
            if (debug) Log.w(TAG, "error pruning expired files: $e")
        }
    }

    private fun pruneExcess() {
        if (!available) return
        try {
            val files = (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return)
                .sortedBy { it.lastModified() }
            if (files.size <= maxLocalFiles) return
            val toRemove = files.size - maxLocalFiles
            for (i in 0 until toRemove) {
                runCatching {
                    if (files[i].delete() && debug) Log.d(TAG, "pruned excess file ${files[i].name}")
                }
            }
        } catch (e: Throwable) {
            if (debug) Log.w(TAG, "error pruning excess files: $e")
        }
    }

    private companion object {
        const val TAG = "Traceway"
    }
}
