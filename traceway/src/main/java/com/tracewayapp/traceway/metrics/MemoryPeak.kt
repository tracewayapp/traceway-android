package com.tracewayapp.traceway.metrics

import com.tracewayapp.traceway.guarded
import com.tracewayapp.traceway.internal.asJsonObject
import com.tracewayapp.traceway.internal.asLongExact
import com.tracewayapp.traceway.persistence.StateStore

/**
 * The peak memory of each session (`app.memory.peak`), observed once the
 * session is known to have ended. The process may be killed in the
 * background, so what a session used so far is kept in the state store and
 * reported by the next run when that session is over.
 */
internal class MemoryPeaks(
    private val recorder: MetricsRecorder,
    private val store: () -> StateStore,
    storageScope: String = "",
    private val readPeak: () -> Long = AppVitals::readMemoryPeakBytes,
) {
    private val key = if (storageScope.isEmpty()) "metricsMemory" else "metricsMemory:$storageScope"

    /** Going to the background or shutting down: [session] may go on in a later run. */
    fun remember(session: String?) {
        session ?: return
        guarded("memory peak") {
            val storage = store()
            val bytes = peak(storage, session)
            if (bytes > 0) storage.set(key, linkedMapOf("s" to session, "b" to bytes))
        }
    }

    /** [session] ended in this run. */
    fun ended(session: String) {
        guarded("memory peak") {
            val storage = store()
            val bytes = peak(storage, session)
            storage.set(key, null)
            recorder.memoryPeak(session, bytes)
        }
    }

    /** A session started: one that ended while the app was not running is reported now. */
    fun settle(current: String) {
        guarded("memory peak") {
            val storage = store()
            val record = storage.get(key).asJsonObject() ?: return@guarded
            val session = record["s"] as? String
            val bytes = record["b"].asLongExact()
            if (session != null && session != current && bytes != null) {
                storage.set(key, null)
                recorder.memoryPeak(session, bytes)
            }
        }
    }

    /** The most memory [session] used: now, or in an earlier run of the app. */
    private fun peak(storage: StateStore, session: String): Long {
        val record = storage.get(key).asJsonObject()
        val remembered = if (record != null && record["s"] == session) record["b"].asLongExact() ?: 0L else 0L
        val now = try {
            readPeak()
        } catch (_: Throwable) {
            0L
        }
        return maxOf(now, remembered)
    }
}
