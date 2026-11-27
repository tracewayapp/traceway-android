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

/**
 * Whether one of [patterns] ([TracewayOptions.ignoreErrors]) matches [line]:
 * a [String] that occurs in it, a [Regex] or a [java.util.regex.Pattern]
 * that finds a match in it. An empty string, or a pattern of any other
 * type, matches nothing.
 */
internal fun ignoreErrorsMatch(patterns: List<Any?>, line: String): Boolean {
    for (pattern in patterns) {
        val hit = when (pattern) {
            is String -> pattern.isNotEmpty() && line.contains(pattern)
            is Regex -> pattern.containsMatchIn(line)
            is java.util.regex.Pattern -> pattern.matcher(line).find()
            else -> false
        }
        if (hit) return true
    }
    return false
}
