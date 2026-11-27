package com.tracewayapp.traceway

import io.opentelemetry.api.logs.Severity

/**
 * How serious a log line from [Traceway.log] is. The session timeline shows
 * it, and the console's minimum severity in the collection settings
 * (`signals.console.minSeverity`) filters by it. A line printed to
 * `System.out` is [INFO], one printed to `System.err` [ERROR].
 */
enum class TracewayLogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL,
    ;

    internal val severity: Severity
        get() = when (this) {
            TRACE -> Severity.TRACE
            DEBUG -> Severity.DEBUG
            INFO -> Severity.INFO
            WARN -> Severity.WARN
            ERROR -> Severity.ERROR
            FATAL -> Severity.FATAL
        }

    /** The level's name as the record's severity text (`WARN`). */
    internal val severityText: String get() = name

    internal companion object {
        /** The level a 1.x-style severity name stands for (`warning`, `verbose`, `assert` included); [INFO] otherwise. */
        fun of(severity: String): TracewayLogLevel = when (severity.lowercase()) {
            "trace", "verbose" -> TRACE
            "debug" -> DEBUG
            "warn", "warning" -> WARN
            "error" -> ERROR
            "fatal", "assert" -> FATAL
            else -> INFO
        }
    }
}
