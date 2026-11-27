package com.tracewayapp.traceway

/**
 * When session replay records the pictures image views show, rather than
 * placeholders: never, on an unmetered connection (Wi-Fi), or always.
 *
 * The collection settings choose it (`replay.images`, Wi-Fi by default) and
 * [TracewayOptions.replayImages] caps it: the lower of the two applies
 * ([OFF] < [WIFI] < [ALWAYS]). The connection is checked when a frame is
 * captured, so a picture recorded on Wi-Fi stays in its clip.
 */
enum class TracewayImagePolicy(internal val wire: String) {
    OFF("off"),
    WIFI("wifi"),
    ALWAYS("always"),
    ;

    internal companion object {
        fun from(value: Any?): TracewayImagePolicy? = entries.firstOrNull { it.wire == value }

        fun lower(a: TracewayImagePolicy, b: TracewayImagePolicy): TracewayImagePolicy = if (a.ordinal <= b.ordinal) a else b
    }
}
