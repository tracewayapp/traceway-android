package com.tracewayapp.traceway.replay

/**
 * One screen-recording clip as a recorder produced it: the rrweb events of a
 * wireframe recording, from a full snapshot to the end of the window.
 */
internal class SessionRecordingPayload(
    /** Names the clip while it is assembled; not sent. */
    val clipId: String,
    /** rrweb events (`type`, `data`, `timestamp`), JSON values. */
    val events: List<Map<String, Any?>>,
    /** Wall-clock time (epoch ms) of the first frame in this recording. */
    val startedAtMs: Long?,
    /** Wall-clock time (epoch ms) of the end of this recording. */
    val endedAtMs: Long?,
    /** About how many bytes the events take as JSON, when the recorder measured it. */
    val approxBytes: Int? = null,
)

/**
 * What the replay controller needs from a screen recorder (the wireframe
 * recorder), so tests can stand in for it. Every method may be called from
 * any thread; implementations synchronize themselves.
 */
internal interface ReplaySource {
    val isRunning: Boolean
    fun start()
    fun stop()
    fun clearBuffer()

    /** Whether any frame was captured at or after [sinceMs] and before [untilMs] (null: unbounded). */
    fun hasFrames(sinceMs: Long? = null, untilMs: Long? = null): Boolean

    /**
     * Makes one clip of what was on screen at or after [sinceMs] and before
     * [untilMs], or null when nothing was. The frames are chosen when this
     * is called; assembling may take a few milliseconds, so call it off the
     * main thread.
     */
    fun captureRecording(clipId: String, sinceMs: Long? = null, untilMs: Long? = null): SessionRecordingPayload?
}
