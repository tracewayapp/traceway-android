package com.tracewayapp.traceway.persistence

import com.tracewayapp.traceway.internal.Cancellable
import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.TaskScheduler
import com.tracewayapp.traceway.internal.asJsonObject
import java.io.File

/**
 * Small key-value state the SDK keeps between app starts: the OpAMP
 * instance uid, the last applied remote config, the current session,
 * trigger progress. One JSON file; with no directory it only lives in
 * memory. Values are JSON values (see [Json]).
 *
 * Thread-safe. Writes are debounced (250 ms) and done on the I/O pool,
 * atomically (temp file + rename).
 */
internal class StateStore(
    private val directory: File?,
    private val scheduler: TaskScheduler? = null,
    /** Values saved for another destination (project, receiver) are not read back. */
    val destination: String? = null,
) {
    private val lock = Any()
    private var values = LinkedHashMap<String, Any?>()
    private var writeTimer: Cancellable? = null
    private val fileLock = Any()

    /** Whether values outlive this run. */
    val persistent: Boolean get() = directory != null

    private val file: File? get() = directory?.let { File(it, "state.json") }

    fun load() {
        val f = file ?: return
        try {
            if (!f.exists()) return
            val decoded = Json.parse(f.readText(Charsets.UTF_8)).asJsonObject()
            synchronized(lock) {
                values = if (decoded != null && decoded["destination"] == destination) {
                    LinkedHashMap(decoded)
                } else {
                    LinkedHashMap()
                }
            }
        } catch (_: Throwable) {
            synchronized(lock) { values = LinkedHashMap() }
        }
    }

    fun get(key: String): Any? = synchronized(lock) { values[key] }

    fun set(key: String, value: Any?) {
        synchronized(lock) {
            if (value == null) values.remove(key) else values[key] = value
            if (writeTimer == null && scheduler != null && directory != null) {
                writeTimer = scheduler.schedule(250) { scheduler.io { flush() } }
            }
        }
    }

    /** Writes now, on the calling thread. */
    fun flush() {
        val body: String
        synchronized(lock) {
            writeTimer?.cancel()
            writeTimer = null
            val f = file ?: return
            val out = LinkedHashMap<String, Any?>(values)
            if (destination != null) out["destination"] = destination
            body = Json.stringify(out)
            f.parentFile?.mkdirs()
        }
        synchronized(fileLock) {
            try {
                val f = file ?: return
                val tmp = File(f.path + ".tmp")
                tmp.writeText(body, Charsets.UTF_8)
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            } catch (_: Throwable) {
                // State is a convenience; losing it only costs a fresh id or config.
            }
        }
    }
}
