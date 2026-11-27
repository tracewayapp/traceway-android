package com.tracewayapp.traceway.diagnostics

import com.tracewayapp.traceway.guarded
import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.asJsonList
import com.tracewayapp.traceway.internal.asJsonObject
import com.tracewayapp.traceway.internal.asLongExact
import com.tracewayapp.traceway.metrics.HangListener
import java.io.File
import java.util.Locale
import java.util.UUID

/** A main-thread hang, from when it passed the threshold. */
internal class Hang(
    /** The process it happened in. */
    val pid: Int,
    /** When the main thread stopped responding, epoch ms. */
    val startedAtMs: Long,
    /** How long it was blocked: until it ended, or until last seen blocked. */
    @Volatile var blockedMs: Long,
    /** The main thread's frames, `at …`, taken while it was blocked. */
    val frames: List<String>,
    /** The screen it happened on (`app.screen.*`, `viewport`). */
    val screen: Map<String, Any>,
    /** The session it happened in. */
    val sessionId: String?,
) {
    /** `Main thread blocked for 3.2 s`. */
    val message: String get() = message(blockedMs)

    fun stackTrace(): String {
        val sb = StringBuilder(TYPE).append(": ").append(message)
        for (f in frames) sb.append("\n\t").append(f)
        return sb.toString()
    }

    internal fun toJson(runId: String): String = Json.stringify(
        linkedMapOf(
            "run" to runId,
            "pid" to pid,
            "startedAtMs" to startedAtMs,
            "blockedMs" to blockedMs,
            "frames" to frames,
            "screen" to screen,
            "sessionId" to sessionId,
        ),
    )

    companion object {
        const val TYPE = "AppHang"

        fun message(blockedMs: Long): String = String.format(Locale.US, "Main thread blocked for %.1f s", blockedMs / 1000.0)

        internal fun fromJson(text: String): Hang? {
            val o = Json.parse(text).asJsonObject() ?: return null
            val startedAt = o["startedAtMs"].asLongExact() ?: return null
            val blocked = o["blockedMs"].asLongExact() ?: return null
            val screen = LinkedHashMap<String, Any>()
            o["screen"].asJsonObject()?.forEach { (k, v) -> if (v is String || v is Number || v is Boolean) screen[k] = v }
            return Hang(
                pid = o["pid"].asLongExact()?.toInt() ?: 0,
                startedAtMs = startedAt,
                blockedMs = blocked,
                frames = o["frames"].asJsonList()?.filterIsInstance<String>()?.take(MAX_FRAMES) ?: emptyList(),
                screen = screen,
                sessionId = o["sessionId"] as? String,
            )
        }

        const val MAX_FRAMES = 256
    }
}

/**
 * Turns main-thread hangs into `AppHang` issues (CONTRACT §14.2), fed by
 * the hang watchdog: when the main thread has been blocked for
 * [thresholdMs] its stack is taken (from the watchdog's thread, while it is
 * still blocked) and the hang is written to [directory], so that a hang the
 * process does not survive is reported at the next start
 * ([takePrevious]); when it ends it is reported through [report], and its
 * file goes.
 *
 * [stalled] runs on the watchdog's thread and writes the file there, at
 * once: the system may kill the process a few seconds later. [ended] runs
 * on the main thread and only hands the rest to [background]. Never throws.
 */
internal class AppHangs(
    override val thresholdMs: Long,
    /** Where pending hangs are kept, one file per run; null keeps them in memory only. */
    private val directory: File?,
    private val pid: Int,
    /** The main thread's stack now; null when it cannot be read. */
    private val mainStack: () -> Array<StackTraceElement>?,
    /** The screen now (`app.screen.*`, `viewport`). */
    private val screen: () -> Map<String, Any>,
    private val sessionId: () -> String?,
    private val now: () -> Long,
    /** Runs a task off the main thread (the SDK's disk thread). */
    private val background: (() -> Unit) -> Unit,
    private val report: (Hang) -> Unit,
) : HangListener {
    private val lock = Any()
    private val runId = UUID.randomUUID().toString()
    private var pending: Hang? = null
    private var lastWriteMs = 0L

    private val file: File? get() = directory?.let { File(it, "$runId.json") }

    override fun stalled(blockedMs: Long) {
        guarded("hang stalled") {
            synchronized(lock) {
                val p = pending
                if (p == null) {
                    val frames = guarded("hang stack", emptyList()) {
                        mainStack()?.take(Hang.MAX_FRAMES)?.map { "at $it" } ?: emptyList()
                    }
                    val hang = Hang(
                        pid = pid,
                        startedAtMs = now() - blockedMs,
                        blockedMs = blockedMs,
                        frames = frames,
                        screen = guarded("hang screen", emptyMap()) { LinkedHashMap(screen()) },
                        sessionId = guarded("hang session", null) { sessionId() },
                    )
                    pending = hang
                    write(hang)
                } else {
                    p.blockedMs = blockedMs
                    // The duration a fatal hang reports: kept up to date, a write a second at most.
                    if (now() - lastWriteMs >= 1000) write(p)
                }
            }
        }
    }

    override fun ended(blockedMs: Long, report: Boolean) {
        guarded("hang ended") {
            val hang = synchronized(lock) { pending.also { pending = null } } ?: return
            hang.blockedMs = blockedMs
            background {
                guarded("hang report") {
                    // A newer hang may have started since: its file stays.
                    synchronized(lock) { if (pending == null) delete() }
                    if (report) this.report(hang)
                }
            }
        }
    }

    private fun write(hang: Hang) {
        val f = file ?: return
        lastWriteMs = now()
        guarded("hang write") {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(hang.toJson(runId), Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    private fun delete() {
        guarded("hang delete") { file?.delete() }
    }

    /**
     * The hangs earlier runs did not survive, oldest first, at most four;
     * their files are removed. Not this run's. Off the main thread.
     */
    fun takePrevious(): List<Hang> {
        val dir = directory ?: return emptyList()
        val own = "$runId.json"
        val files = guarded("hang list", null) { dir.listFiles() } ?: return emptyList()
        val out = ArrayList<Hang>()
        for (f in files) {
            if (f.name == own || f.name == "$own.tmp") continue
            guarded("hang read") {
                if (f.name.endsWith(".json") && f.length() <= MAX_FILE_BYTES) Hang.fromJson(f.readText(Charsets.UTF_8))?.let { out.add(it) }
            }
            guarded("hang delete") { f.delete() }
        }
        out.sortBy { it.startedAtMs }
        return if (out.size > MAX_PREVIOUS) out.subList(out.size - MAX_PREVIOUS, out.size) else out
    }

    private companion object {
        const val MAX_PREVIOUS = 4
        const val MAX_FILE_BYTES = 256L * 1024
    }
}
