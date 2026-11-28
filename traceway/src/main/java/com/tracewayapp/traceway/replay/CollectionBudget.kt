package com.tracewayapp.traceway.replay

import com.tracewayapp.traceway.internal.asJsonObject
import com.tracewayapp.traceway.internal.asLongExact
import com.tracewayapp.traceway.persistence.StateStore

/**
 * Bounds what replay uploads per session, as the browser SDK does: 50 MiB
 * and 10,000 events. A streaming capture that runs for hours stops
 * uploading once a session has used its budget; the counts are reported on
 * the session's lifecycle events. The pictures replay records ([admitImage])
 * have a budget of their own inside it, 4 MiB of encoded image data per
 * session. Kept in the [StateStore], so a restart inside the session goes
 * on counting. Thread-safe.
 */
internal class CollectionBudget(
    val maxBytes: Long = 50L * 1024 * 1024,
    val maxEvents: Long = 10_000,
    store: StateStore? = null,
    val maxImageBytes: Long = DEFAULT_MAX_IMAGE_BYTES,
) {
    private var sessionId: String? = null
    private var bytes = 0L
    private var events = 0L
    private var dropped = 0L
    private var imageBytes = 0L
    private var loaded = false

    /** Called (without the budget's lock) when a segment was refused: the session used its budget. */
    @Volatile
    var onRejected: (() -> Unit)? = null

    @Volatile
    var store: StateStore? = store
        set(value) {
            synchronized(this) {
                field = value
                // Counts saved for the session are read from the new store.
                loaded = false
            }
        }

    fun admit(sessionId: String?, bytes: Long, events: Int = 1): Boolean {
        val admitted = synchronized(this) {
            switchTo(sessionId)
            if (this.bytes + bytes > maxBytes || this.events + events > maxEvents) {
                dropped += events
                save()
                false
            } else {
                this.bytes += bytes
                this.events += events
                save()
                true
            }
        }
        if (!admitted) {
            try {
                onRejected?.invoke()
            } catch (_: Throwable) {
            }
        }
        return admitted
    }

    /** Whether [sessionId] may still record a picture. */
    @Synchronized
    fun hasImageRoom(sessionId: String?): Boolean {
        switchTo(sessionId)
        return imageBytes < maxImageBytes
    }

    /** Counts a picture of [bytes] encoded bytes against [sessionId]'s image budget; false when it does not fit. */
    @Synchronized
    fun admitImage(sessionId: String?, bytes: Long): Boolean {
        switchTo(sessionId)
        if (imageBytes + bytes > maxImageBytes) return false
        imageBytes += bytes
        save()
        return true
    }

    private fun switchTo(sessionId: String?) {
        if (loaded && sessionId == this.sessionId) return
        loaded = true
        this.sessionId = sessionId
        bytes = 0
        events = 0
        dropped = 0
        imageBytes = 0
        val saved = store?.get(STORE_KEY).asJsonObject()
        if (saved != null && sessionId != null && saved["sessionId"] == sessionId) {
            fun read(key: String): Long = saved[key].asLongExact()?.takeIf { it >= 0 } ?: 0L
            bytes = read("bytes")
            events = read("events")
            dropped = read("dropped")
            imageBytes = read("imageBytes")
        }
    }

    private fun save() {
        val id = sessionId ?: return
        store?.set(
            STORE_KEY,
            linkedMapOf("sessionId" to id, "bytes" to bytes, "events" to events, "dropped" to dropped, "imageBytes" to imageBytes),
        )
    }

    /** Whether [sessionId] has uploaded any replay. */
    @Synchronized
    fun hasUploaded(sessionId: String): Boolean {
        switchTo(sessionId)
        return events > 0
    }

    /** The counts of [sessionId], as session event attributes. */
    @Synchronized
    fun snapshot(sessionId: String?): Map<String, Long> {
        switchTo(sessionId)
        return linkedMapOf(
            "traceway.collection.bytes" to bytes,
            "traceway.collection.events" to events,
            "traceway.collection.dropped" to dropped,
            "traceway.collection.max_bytes" to maxBytes,
            "traceway.collection.max_events" to maxEvents,
            "traceway.replay.image_bytes" to imageBytes,
        )
    }

    companion object {
        private const val STORE_KEY = "budget"
        const val DEFAULT_MAX_IMAGE_BYTES = 4L * 1024 * 1024
    }
}
