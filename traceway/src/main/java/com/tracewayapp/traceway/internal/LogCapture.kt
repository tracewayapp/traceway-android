package com.tracewayapp.traceway.internal

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * Mirrors [System.out] / [System.err] into the SDK as console records,
 * while still writing the bytes to the original stream. Each line is one
 * record. Calls to `android.util.Log` do not pass through System.out; apps
 * that want them call `Traceway.log` (from a Timber tree, say).
 */
internal object LogCapture {
    @Volatile
    private var installed = false

    @Volatile
    var sink: ((String, String) -> Unit)? = null

    fun install(sink: (line: String, severity: String) -> Unit) {
        this.sink = sink
        if (installed) return
        installed = true
        try {
            System.setOut(LineCapturingPrintStream(System.out, "info"))
            System.setErr(LineCapturingPrintStream(System.err, "error"))
        } catch (_: Throwable) {
            installed = false
        }
    }

    /** Stops sending lines to [logSink], unless a newer client installed its own meanwhile. */
    fun uninstall(logSink: ((String, String) -> Unit)? = null) {
        if (logSink == null || sink === logSink) sink = null
    }

    internal fun emit(line: String, severity: String) {
        try {
            sink?.invoke(line, severity)
        } catch (_: Throwable) {
            // Never let log capture break the host app.
        }
    }
}

private class LineCapturingPrintStream(
    private val delegate: PrintStream,
    private val severity: String,
) : PrintStream(NoOpOutputStream(), true) {
    private val buffer = ByteArrayOutputStream()
    private val lock = Any()

    override fun write(b: Int) {
        delegate.write(b)
        val line = synchronized(lock) { accept(b) }
        if (line != null) LogCapture.emit(line, severity)
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        delegate.write(buf, off, len)
        var lines: MutableList<String>? = null
        synchronized(lock) {
            for (i in off until off + len) {
                val line = accept(buf[i].toInt() and 0xFF)
                if (line != null) (lines ?: ArrayList<String>().also { lines = it }).add(line)
            }
        }
        lines?.forEach { LogCapture.emit(it, severity) }
    }

    /** Buffers [b]; returns the finished line at a newline. */
    private fun accept(b: Int): String? {
        when (b) {
            '\n'.code -> {
                if (buffer.size() == 0) return null
                val line = buffer.toString(Charsets.UTF_8.name())
                buffer.reset()
                return line
            }
            '\r'.code -> {}
            else -> if (buffer.size() < 16 * 1024) buffer.write(b)
        }
        return null
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}

private class NoOpOutputStream : OutputStream() {
    override fun write(b: Int) {}
}
