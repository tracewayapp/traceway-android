package com.tracewayapp.traceway.otel

/**
 * Event names and attribute keys shared with the browser and Flutter SDKs,
 * so the backend and the settings schema treat every client the same.
 */
internal object Events {
    const val EXCEPTION = "exception"
    const val MESSAGE = "traceway.message"
    const val ACTION = "traceway.action"
    const val NAVIGATION = "browser.navigation"

    /** A tap, under the browser SDK's name so the dashboard shows it the same. */
    const val CLICK = "browser.user_action.click"
    const val CONSOLE = "browser.console"
    const val SESSION_START = "session.start"
    const val SESSION_END = "session.end"
    const val SESSION_UPDATE = "traceway.session.update"
    const val TRIGGER_FIRED = "traceway.trigger.fired"
    const val CAPTURE_STARTED = "traceway.capture.started"
    const val CONFIG_APPLIED = "traceway.config.applied"
    const val COLLECTION_HEALTH = "traceway.collection.health"

    const val ATTR_SESSION_ID = "session.id"
    const val ATTR_SESSION_STARTED_AT = "traceway.session.started_at"
    const val ATTR_EXCEPTION_ID = "traceway.exception.id"
    const val ATTR_REPLAY_STATUS = "traceway.replay.status"
    const val ATTR_CAPTURE_IDS = "traceway.capture.ids"

    /** `control` records describe the SDK itself (sessions, triggers, config) and are always sent. */
    const val SIGNAL_CONTROL = "control"

    private val CONTROL_EVENTS = setOf(
        SESSION_START,
        SESSION_END,
        SESSION_UPDATE,
        TRIGGER_FIRED,
        CAPTURE_STARTED,
        CONFIG_APPLIED,
        COLLECTION_HEALTH,
    )

    /** Records that go to Traceway even when ordinary logs are redirected to another receiver. */
    fun isTracewayOnly(eventName: String?): Boolean =
        eventName == EXCEPTION || eventName == MESSAGE || (eventName != null && eventName in CONTROL_EVENTS)

    /** Maps a log record's event name to the signal whose settings govern it. */
    fun classifyLog(eventName: String?): String = when (eventName) {
        EXCEPTION -> "errors"
        MESSAGE -> "messages"
        CONSOLE, null, "" -> "console"
        NAVIGATION -> "navigation"
        CLICK -> "userActions"
        "browser.web_vital" -> "webVitals"
        "browser.resource_timing", "browser.navigation_timing" -> "resourceTiming"
        else -> if (eventName in CONTROL_EVENTS) SIGNAL_CONTROL else "custom"
    }

    private val SEVERITY_NUMBERS = mapOf(
        "trace" to 1,
        "debug" to 5,
        "info" to 9,
        "warn" to 13,
        "error" to 17,
        "fatal" to 21,
    )

    fun severityNumberOf(severity: String): Int = SEVERITY_NUMBERS[severity] ?: 0
}
