package com.tracewayapp.traceway.otel

import io.opentelemetry.sdk.logs.ReadWriteLogRecord
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.trace.data.SpanData

/** A record waiting in the ring buffer or on its way to an exporter. */
internal sealed class TelemetryItem {
    /**
     * A log record. While the SDK routes it, it is the live
     * [ReadWriteLogRecord], so attributes added after routing (the replay
     * status of an exception, set once its triggers ran) are still on it
     * when it is exported or saved; [record] snapshots it.
     */
    class Log private constructor(
        private val live: ReadWriteLogRecord?,
        private val fixed: LogRecordData?,
    ) : TelemetryItem() {
        constructor(record: LogRecordData) : this(null, record)
        constructor(record: ReadWriteLogRecord) : this(record, null)

        /** An immutable snapshot of the record as it is now. */
        val record: LogRecordData get() = fixed ?: live!!.toLogRecordData()

        /** The live record, to add attributes to; null for a snapshot. */
        val mutable: ReadWriteLogRecord? get() = live

        val eventName: String? get() = live?.eventName ?: fixed?.eventName
    }

    class Span(val span: SpanData) : TelemetryItem()
}
