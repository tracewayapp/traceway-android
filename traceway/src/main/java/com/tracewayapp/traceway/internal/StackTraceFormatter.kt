package com.tracewayapp.traceway.internal

import java.io.PrintWriter
import java.io.StringWriter

/**
 * Formats a Throwable similarly to the Flutter SDK's `formatException`:
 * first line is `<ErrorType>: <message>`, followed by stack frames.
 *
 * Java's default stack trace printer already produces a layout the
 * Traceway backend's stack-frame parser handles, so we delegate to
 * [Throwable.printStackTrace] for the body.
 */
internal fun formatException(error: Throwable): String {
    val sw = StringWriter()
    sw.append(error.javaClass.name)
    val msg = error.localizedMessage
    if (!msg.isNullOrEmpty()) {
        sw.append(": ")
        sw.append(msg)
    }
    sw.append('\n')

    val frames = StringWriter()
    error.printStackTrace(PrintWriter(frames))
    val text = frames.toString()
    val firstNewline = text.indexOf('\n')
    if (firstNewline >= 0) {
        sw.append(text.substring(firstNewline + 1))
    }

    return sw.toString().trimEnd()
}
