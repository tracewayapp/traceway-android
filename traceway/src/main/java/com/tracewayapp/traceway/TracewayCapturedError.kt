package com.tracewayapp.traceway

/**
 * What [TracewayOptions.beforeCapture] sees of an exception or a message
 * before it is recorded. Return false from the callback to drop it.
 */
class TracewayCapturedError internal constructor(
    /** The exception, or null for a [Traceway.captureMessage]. */
    val error: Throwable?,
    /** The exception's class name (`java.net.UnknownHostException`); null for a message. */
    val type: String?,
    /** The exception's message (empty without one), or the message. */
    val message: String,
    /** A message from [Traceway.captureMessage], not an exception. */
    val isMessage: Boolean,
    /** The attributes it will carry: the app's own and the screen it happened on. */
    val attributes: Map<String, Any>,
    /** The exception's stack trace as it will be reported; null for a message. */
    val stackTrace: String?,
) {
    /** The line [TracewayOptions.ignoreErrors] is matched against: `Type: message`, first line only. */
    internal val line: String
        get() {
            val text = when {
                type == null -> message
                message.isEmpty() -> type
                else -> "$type: $message"
            }
            val end = text.indexOf('\n')
            return if (end < 0) text else text.substring(0, end)
        }

    override fun toString(): String = "TracewayCapturedError($line)"
}
