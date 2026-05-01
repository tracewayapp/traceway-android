package com.tracewayapp.traceway.internal

import com.tracewayapp.traceway.TracewayClient
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * Mirrors [System.out] / [System.err] writes into the Traceway log buffer,
 * while still forwarding the bytes to the original stream. Lines are split on
 * `\n` so each `println` becomes one [com.tracewayapp.traceway.events.LogEvent].
 *
 * This is the closest analog to Flutter's Zone-`print` hook. Calls that go
 * straight to `android.util.Log` bypass System.out, so users who want every
 * Logcat line captured should call [TracewayClient.recordLog] from a Timber
 * tree (or similar) themselves.
 */
internal object LogCapture {
    @Volatile private var installed: Boolean = false

    fun install() {
        if (installed) return
        installed = true
        try {
            System.setOut(LineCapturingPrintStream(System.out, "info"))
            System.setErr(LineCapturingPrintStream(System.err, "error"))
        } catch (_: Throwable) {
            installed = false
        }
    }
}

private class LineCapturingPrintStream(
    private val delegate: PrintStream,
    private val level: String,
) : PrintStream(NoOpOutputStream(), true) {
    private val buffer = ByteArrayOutputStream()
    private val lock = Any()

    override fun write(b: Int) {
        delegate.write(b)
        synchronized(lock) {
            when (b) {
                '\n'.code -> emit()
                '\r'.code -> { /* ignore */ }
                else -> buffer.write(b)
            }
        }
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        delegate.write(buf, off, len)
        synchronized(lock) {
            var i = off
            val end = off + len
            while (i < end) {
                when (val b = buf[i].toInt() and 0xFF) {
                    '\n'.code -> emit()
                    '\r'.code -> { /* ignore */ }
                    else -> buffer.write(b)
                }
                i++
            }
        }
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()

    private fun emit() {
        if (buffer.size() == 0) return
        val line = buffer.toString(Charsets.UTF_8.name())
        buffer.reset()
        try {
            TracewayClient.instance?.recordLog(line, level)
        } catch (_: Throwable) {
            // Never let log capture break the host app.
        }
    }
}

private class NoOpOutputStream : OutputStream() {
    override fun write(b: Int) {}
}
