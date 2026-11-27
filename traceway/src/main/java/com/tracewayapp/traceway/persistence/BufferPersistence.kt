package com.tracewayapp.traceway.persistence

import com.tracewayapp.traceway.core.BufferEntry
import com.tracewayapp.traceway.internal.TaskScheduler
import com.tracewayapp.traceway.otel.Events
import com.tracewayapp.traceway.otel.OtlpEncoding
import com.tracewayapp.traceway.otel.TelemetryItem
import io.opentelemetry.sdk.resources.Resource
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * The ring buffer on disk, so an app the system killed in the background
 * still has its breadcrumbs when the same session goes on after a restart,
 * like the browser SDK's buffer survives a reload. It is written when the
 * app goes to the background and on `flush()`, never on a timer: encoding
 * the whole buffer is work a phone should not do every few seconds.
 *
 * What a restart restores is only uploaded by a trigger or capture, like
 * the rest of the buffer, and only within the current session.
 *
 * Each record is kept as the scope part of a one-record OTLP request, its
 * resource saved once: a restart after an app update keeps the version the
 * record was recorded with. Thread-safe; saves are encoded and written on
 * the I/O pool, one after the other.
 */
internal class BufferPersistence(
    val directory: File?,
    private val scheduler: TaskScheduler,
    /**
     * Where restored records would be sent. A file saved for other
     * destinations is dropped: a changed receiver is never sent what was
     * meant for the previous one.
     */
    val destination: String = "",
) {
    /** A record of the ring buffer as it was saved. */
    class RestoredEntry(
        val timeMs: Long,
        val signal: String,
        /** `logs` or `traces`. */
        val kind: String,
        /** Goes to Traceway even when ordinary logs go to another receiver ([Events.isTracewayOnly]). */
        val tracewayOnly: Boolean,
        /** The raw scope fields of a resource container (see [OtlpEncoding.Container]). */
        val bytes: ByteArray,
        /** The raw resource fields the record was made under; empty when unknown. */
        val resource: ByteArray,
    )

    /** OTLP request bodies taken from the restored records. */
    class Bodies(
        val logs: List<ByteArray>,
        val traces: List<ByteArray>,
        /** Logs for Traceway when [take] was asked to split them; otherwise they are in [logs]. */
        val tracewayLogs: List<ByteArray>,
    ) {
        val isEmpty: Boolean get() = logs.isEmpty() && traces.isEmpty() && tracewayLogs.isEmpty()
    }

    private class SaveJob(
        val seq: Long,
        val sessionId: String?,
        val entries: List<BufferEntry<TelemetryItem>>,
        val restored: List<RestoredEntry>,
    )

    private val lock = Any()
    private val fileLock = Any()
    private val restored = ArrayList<RestoredEntry>()
    private var pendingSave: SaveJob? = null
    private var saving = false
    private var saveSeq = 0L
    private var writtenSeq = -1L

    private val file: File? get() = directory?.let { File(it, FILE_NAME) }

    val restoredCount: Int get() = synchronized(lock) { restored.size }

    /** A copy of the restored records still held, oldest first. */
    fun restoredEntries(): List<RestoredEntry> = synchronized(lock) { ArrayList(restored) }

    /**
     * Reads what the last run saved for [sessionId], within [maxAgeMs] and
     * [maxBytes]; anything else is dropped. The file is removed: what was
     * restored is written again by the next save. Blocks on disk.
     */
    fun load(sessionId: String?, maxAgeMs: Long, maxBytes: Long) {
        val f = file ?: return
        if (sessionId == null) return
        try {
            if (!f.exists() || f.length() > MAX_FILE_BYTES) return
            val entries = read(f, sessionId, scheduler.now() - maxAgeMs) ?: return
            // The newest records win when the saved buffer is larger than the
            // current one may be.
            var bytes = 0L
            var first = 0
            for (i in entries.indices.reversed()) {
                bytes += entries[i].bytes.size
                if (bytes > maxBytes) {
                    first = i + 1
                    break
                }
            }
            synchronized(lock) { restored.addAll(entries.subList(first, entries.size)) }
        } catch (_: Throwable) {
            // A file this run cannot read only costs the breadcrumbs it held.
        } finally {
            clear(keepRestored = true)
        }
    }

    private fun read(f: File, sessionId: String, cutoffMs: Long): List<RestoredEntry>? {
        DataInputStream(BufferedInputStream(FileInputStream(f))).use { input ->
            val limit = f.length()
            if (input.readInt() != MAGIC) return null
            val savedDestination = input.readUTF()
            val savedSession = input.readUTF()
            if (savedDestination != destination || savedSession != sessionId) return null
            val resourceCount = input.readInt()
            if (resourceCount < 0 || resourceCount > 1024) return null
            val resources = ArrayList<ByteArray>(resourceCount)
            repeat(resourceCount) { resources.add(readBytes(input, limit)) }
            val count = input.readInt()
            if (count < 0) return null
            val out = ArrayList<RestoredEntry>()
            repeat(count) {
                val t = input.readLong()
                val signal = input.readUTF()
                val kind = when (input.readByte().toInt()) {
                    0 -> OtlpEncoding.KIND_LOGS
                    1 -> OtlpEncoding.KIND_TRACES
                    else -> null
                }
                val flags = input.readByte().toInt()
                val resourceIndex = input.readInt()
                val bytes = readBytes(input, limit)
                if (kind != null && t >= cutoffMs) {
                    out.add(
                        RestoredEntry(
                            t,
                            signal,
                            kind,
                            flags and FLAG_TRACEWAY_ONLY != 0,
                            bytes,
                            resources.getOrNull(resourceIndex) ?: EMPTY,
                        ),
                    )
                }
            }
            return out
        }
    }

    private fun readBytes(input: DataInputStream, limit: Long): ByteArray {
        val n = input.readInt()
        if (n < 0 || n > limit) throw EOFException("bad length")
        val bytes = ByteArray(n)
        input.readFully(bytes)
        return bytes
    }

    /**
     * Saves the restored records still held and the buffer's [entries], for
     * [sessionId], on the I/O pool. Saves run one after the other, each with
     * what is held when it is asked for; one asked for later always wins.
     */
    fun save(sessionId: String?, entries: List<BufferEntry<TelemetryItem>>) {
        if (file == null || sessionId == null) return
        synchronized(lock) {
            pendingSave = SaveJob(++saveSeq, sessionId, ArrayList(entries), ArrayList(restored))
            if (saving) return
            saving = true
        }
        scheduler.io { saveLoop() }
    }

    /** [save] on the calling thread, for a process about to die. */
    fun saveNow(sessionId: String?, entries: List<BufferEntry<TelemetryItem>>) {
        if (file == null || sessionId == null) return
        val job = synchronized(lock) { SaveJob(++saveSeq, sessionId, ArrayList(entries), ArrayList(restored)) }
        write(job)
    }

    private fun saveLoop() {
        while (true) {
            val job = synchronized(lock) {
                val next = pendingSave
                pendingSave = null
                if (next == null) saving = false
                next
            } ?: return
            write(job)
        }
    }

    /** Writes [job], or removes the file when it holds nothing (or no session). */
    private fun write(job: SaveJob) {
        val f = file ?: return
        val encoded = if (job.sessionId == null) null else try {
            encode(job)
        } catch (_: Throwable) {
            return
        }
        synchronized(fileLock) {
            if (job.seq < writtenSeq) return
            writtenSeq = job.seq
            try {
                if (encoded == null) {
                    f.delete()
                    return
                }
                f.parentFile?.mkdirs()
                val tmp = File(f.path + ".tmp")
                FileOutputStream(tmp).use { it.write(encoded) }
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            } catch (_: Throwable) {
                // Best effort.
            }
        }
    }

    private fun encode(job: SaveJob): ByteArray? {
        val resources = ArrayList<ByteArray>()
        fun resourceIndex(bytes: ByteArray): Int {
            if (bytes.isEmpty()) return -1
            for (i in resources.indices) if (resources[i].contentEquals(bytes)) return i
            resources.add(bytes)
            return resources.size - 1
        }

        class Row(val t: Long, val signal: String, val kind: Int, val flags: Int, val resource: Int, val bytes: ByteArray)

        val rows = ArrayList<Row>(job.restored.size + job.entries.size)
        for (e in job.restored) {
            rows.add(
                Row(
                    e.timeMs,
                    e.signal,
                    if (e.kind == OtlpEncoding.KIND_TRACES) 1 else 0,
                    if (e.tracewayOnly) FLAG_TRACEWAY_ONLY else 0,
                    resourceIndex(e.resource),
                    e.bytes,
                ),
            )
        }
        // Resources are encoded once per distinct resource, records without theirs.
        val prefixes = HashMap<Resource, ByteArray>()
        for (entry in job.entries) {
            try {
                when (val item = entry.item) {
                    is TelemetryItem.Log -> {
                        val record = item.record
                        val prefix = prefixes.getOrPut(record.resource) {
                            OtlpEncoding.containers(OtlpEncoding.logsRequest(listOf(record))).firstOrNull()?.prefix ?: EMPTY
                        }
                        val scopes = OtlpEncoding.containers(
                            OtlpEncoding.logsRequest(listOf(OtlpEncoding.withoutResource(record))),
                        ).firstOrNull()?.scopes ?: continue
                        val own = Events.isTracewayOnly(item.eventName ?: record.eventName)
                        rows.add(Row(entry.timeMs, entry.signal, 0, if (own) FLAG_TRACEWAY_ONLY else 0, resourceIndex(prefix), scopes))
                    }
                    is TelemetryItem.Span -> {
                        val span = item.span
                        val prefix = prefixes.getOrPut(span.resource) {
                            OtlpEncoding.containers(OtlpEncoding.tracesRequest(listOf(span))).firstOrNull()?.prefix ?: EMPTY
                        }
                        val scopes = OtlpEncoding.containers(
                            OtlpEncoding.tracesRequest(listOf(OtlpEncoding.withoutResource(span))),
                        ).firstOrNull()?.scopes ?: continue
                        rows.add(Row(entry.timeMs, entry.signal, 1, 0, resourceIndex(prefix), scopes))
                    }
                }
            } catch (_: Throwable) {
                // A record that cannot be encoded is left out, not the buffer.
            }
        }
        if (rows.isEmpty()) return null
        val buffer = java.io.ByteArrayOutputStream()
        DataOutputStream(BufferedOutputStream(buffer)).use { out ->
            out.writeInt(MAGIC)
            out.writeUTF(destination)
            out.writeUTF(job.sessionId ?: "")
            out.writeInt(resources.size)
            for (r in resources) {
                out.writeInt(r.size)
                out.write(r)
            }
            out.writeInt(rows.size)
            for (row in rows) {
                out.writeLong(row.t)
                out.writeUTF(row.signal)
                out.writeByte(row.kind)
                out.writeByte(row.flags)
                out.writeInt(row.resource)
                out.writeInt(row.bytes.size)
                out.write(row.bytes)
            }
        }
        return buffer.toByteArray()
    }

    /**
     * Removes the restored records in the window whose signal is selected
     * and returns the exportable ones as OTLP request bodies. `signals ==
     * null` selects every signal; a selected record whose signal may not
     * leave the device ([canExport]) is removed without being returned. A
     * body holds about [maxBodyBytes] of records at most, so a large buffer
     * is several requests, each small enough to be queued and accepted.
     * With [splitTracewayLogs], logs that belong to Traceway come back in
     * [Bodies.tracewayLogs].
     */
    fun take(
        sinceMs: Long,
        signals: Set<String>?,
        untilMs: Long,
        canExport: (String) -> Boolean,
        maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
        splitTracewayLogs: Boolean = false,
    ): Bodies {
        val logs = ArrayList<RestoredEntry>()
        val traceway = ArrayList<RestoredEntry>()
        val traces = ArrayList<RestoredEntry>()
        synchronized(lock) {
            if (restored.isEmpty()) return Bodies(emptyList(), emptyList(), emptyList())
            val kept = ArrayList<RestoredEntry>(restored.size)
            for (entry in restored) {
                val selected = entry.timeMs in sinceMs..untilMs && (signals == null || entry.signal in signals)
                if (!selected) {
                    kept.add(entry)
                    continue
                }
                if (!canExport(entry.signal)) continue
                when {
                    entry.kind == OtlpEncoding.KIND_TRACES -> traces.add(entry)
                    splitTracewayLogs && entry.tracewayOnly -> traceway.add(entry)
                    else -> logs.add(entry)
                }
            }
            restored.clear()
            restored.addAll(kept)
        }
        return Bodies(chunks(logs, maxBodyBytes), chunks(traces, maxBodyBytes), chunks(traceway, maxBodyBytes))
    }

    private fun chunks(entries: List<RestoredEntry>, maxBodyBytes: Int): List<ByteArray> {
        if (entries.isEmpty()) return emptyList()
        val out = ArrayList<ByteArray>()
        var chunk = ArrayList<RestoredEntry>()
        var size = 0L
        for (entry in entries) {
            if (size + entry.bytes.size > maxBodyBytes && chunk.isNotEmpty()) {
                out.add(request(chunk))
                chunk = ArrayList()
                size = 0
            }
            chunk.add(entry)
            size += entry.bytes.size
        }
        if (chunk.isNotEmpty()) out.add(request(chunk))
        return out
    }

    /** One request with a resource container per distinct resource of [entries]. */
    private fun request(entries: List<RestoredEntry>): ByteArray {
        val groups = ArrayList<Pair<ByteArray, MutableList<ByteArray>>>()
        for (entry in entries) {
            val group = groups.firstOrNull { it.first === entry.resource || it.first.contentEquals(entry.resource) }
            if (group != null) {
                group.second.add(entry.bytes)
            } else {
                groups.add(entry.resource to mutableListOf(entry.bytes))
            }
        }
        return OtlpEncoding.request(groups)
    }

    /** Drops restored records whose signal may not leave the device any more. */
    fun retain(keep: (String) -> Boolean) {
        synchronized(lock) { restored.removeAll { !keep(it.signal) } }
    }

    /** Forgets the restored records (a new session started); the file is rewritten by the next save. */
    fun clearRestored() {
        synchronized(lock) { restored.clear() }
    }

    /** Removes the file, on the I/O pool, and the restored records unless [keepRestored]. */
    fun clear(keepRestored: Boolean = false) {
        if (file == null) {
            if (!keepRestored) clearRestored()
            return
        }
        synchronized(lock) {
            if (!keepRestored) restored.clear()
            pendingSave = SaveJob(++saveSeq, null, emptyList(), emptyList())
            if (saving) return
            saving = true
        }
        scheduler.io { saveLoop() }
    }

    companion object {
        const val FILE_NAME = "buffer.bin"
        const val DEFAULT_MAX_BODY_BYTES = 1024 * 1024
        private const val MAGIC = 0x54574231 // "TWB1"
        private const val FLAG_TRACEWAY_ONLY = 1
        private const val MAX_FILE_BYTES = 64L * 1024 * 1024
        private val EMPTY = ByteArray(0)
    }
}
