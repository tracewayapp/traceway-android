package com.tracewayapp.traceway.diagnostics

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * The main thread of an ANR dump, as `ApplicationExitInfo.getTraceInputStream()`
 * gives it for `REASON_ANR`: ART's thread dump of the process when it was
 * found not responding.
 *
 * ```
 * Subject: Input dispatching timed out (… is not responding. Waited 5001ms for MotionEvent)
 * ----- pid 4321 at 2026-11-20 10:15:02.123456789+0100 -----
 * DALVIK THREADS (23):
 * "main" prio=5 tid=1 Sleeping
 *   | group="main" sCount=1 …
 *   at java.lang.Thread.sleep(Native method)
 *   - sleeping on <0x0e2a1b3c> (a java.lang.Object)
 *   at com.example.CheckoutActivity.onPay(CheckoutActivity.kt:42)
 *   native: #00 pc 000000000009e3c8  /apex/com.android.runtime/lib64/bionic/libc.so (nanosleep+8)
 * ```
 */
internal class AnrTrace(
    /** The `Subject:` line: why the system found the app not responding. */
    val subject: String?,
    /** The main thread's state in the dump (`Blocked`, `Sleeping`, `Native`, `Runnable`). */
    val mainThreadState: String?,
    /** The main thread's frames, as `at …` and `native: #00 pc …` lines. */
    val frames: List<String>,
) {
    companion object {
        /** The most read of a dump: the main thread comes first, after the header. */
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_FRAMES = 256
        private const val MAX_LINE = 1024

        fun read(stream: InputStream): AnrTrace? =
            parse(BufferedReader(InputStreamReader(LimitedInputStream(stream, MAX_BYTES.toLong()), Charsets.UTF_8)))

        fun parse(text: String): AnrTrace? = parse(BufferedReader(java.io.StringReader(text)))

        /** Reads until the main thread's block ends; null when the dump holds no main thread and no subject. */
        fun parse(reader: BufferedReader): AnrTrace? {
            var subject: String? = null
            var state: String? = null
            val frames = ArrayList<String>()
            var inMain = false
            var sawMain = false
            reader.use { r ->
                while (true) {
                    val raw = r.readLine() ?: break
                    val line = if (raw.length > MAX_LINE) raw.substring(0, MAX_LINE) else raw
                    if (!sawMain && subject == null && line.startsWith("Subject:")) {
                        subject = line.substring("Subject:".length).trim().ifEmpty { null }
                        continue
                    }
                    if (line.startsWith("\"")) {
                        if (inMain) break
                        val close = line.indexOf('"', 1)
                        if (close > 0 && line.substring(1, close) == "main") {
                            inMain = true
                            sawMain = true
                            state = line.substring(close + 1).trim().split(' ').lastOrNull()?.takeIf { it.isNotEmpty() && '=' !in it }
                        }
                        continue
                    }
                    if (!inMain) continue
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("----- end")) break
                    if ((trimmed.startsWith("at ") || trimmed.startsWith("native: ")) && frames.size < MAX_FRAMES) {
                        frames.add(trimmed)
                    }
                }
            }
            if (!sawMain && subject == null) return null
            return AnrTrace(subject, state, frames)
        }
    }
}

/** [input], ending after [limit] bytes. */
internal class LimitedInputStream(private val input: InputStream, private var left: Long) : InputStream() {
    override fun read(): Int {
        if (left <= 0) return -1
        val b = input.read()
        if (b >= 0) left--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = input.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) left -= n
        return n
    }

    override fun close() = input.close()
}
