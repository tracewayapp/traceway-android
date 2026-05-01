package com.tracewayapp.traceway

import com.tracewayapp.traceway.internal.formatException
import org.junit.Assert.assertTrue
import org.junit.Test

class StackTraceFormatterTest {

    @Test
    fun startsWithTypeAndMessageHeader() {
        val ex = IllegalStateException("missing key")
        val formatted = formatException(ex)

        val firstLine = formatted.lineSequence().first()
        assertTrue(
            "expected '<type>: <msg>' header, got: $firstLine",
            firstLine.startsWith("java.lang.IllegalStateException: missing key"),
        )
    }

    @Test
    fun includesStackFrames() {
        val ex = try {
            error("nested")
        } catch (t: Throwable) {
            t
        }
        val formatted = formatException(ex)
        // Frames look like "at com.x.Y.method(File.kt:NN)". At least one must appear.
        assertTrue(
            "expected at least one stack frame in:\n$formatted",
            formatted.lineSequence().drop(1).any { it.trim().startsWith("at ") },
        )
    }

    @Test
    fun handlesExceptionWithoutMessage() {
        val ex = RuntimeException()
        val formatted = formatException(ex)
        assertTrue(formatted.startsWith("java.lang.RuntimeException"))
    }
}
