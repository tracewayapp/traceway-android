package com.tracewayapp.traceway.opamp

// The OpAMP messages this SDK exchanges, encoded by hand for the fields it
// uses (opamp.proto v0.25). Byte for byte what opamp-go's generated code
// writes for the same values; checked against its output in OpampInteropTest.

/** AgentCapabilities from the OpAMP spec. */
internal object AgentCapabilities {
    const val REPORTS_STATUS = 0x1L
    const val ACCEPTS_REMOTE_CONFIG = 0x2L
    const val REPORTS_REMOTE_CONFIG = 0x1000L
    const val REPORTS_HEARTBEAT = 0x2000L
}

internal const val SERVER_FLAG_REPORT_FULL_STATE = 0x1L

internal object RemoteConfigStatuses {
    const val UNSET = 0
    const val APPLIED = 1
    const val APPLYING = 2
    const val FAILED = 3
}

internal object ServerErrorTypes {
    const val UNKNOWN = 0
    const val BAD_REQUEST = 1
    const val UNAVAILABLE = 2
}

internal class AgentDescription(
    /** Values are [String], [Boolean], integral or floating [Number], or a [List] of strings. */
    val identifying: Map<String, Any>,
    val nonIdentifying: Map<String, Any>,
)

internal class RemoteConfigStatus(
    val lastRemoteConfigHash: ByteArray,
    val status: Int,
    val errorMessage: String? = null,
)

internal class AgentToServer(
    val instanceUid: ByteArray,
    val sequenceNum: Long,
    val agentDescription: AgentDescription? = null,
    val capabilities: Long,
    val remoteConfigStatus: RemoteConfigStatus? = null,
    val customCapabilities: List<String> = emptyList(),
)

internal class AgentConfigObject(
    var body: ByteArray = ByteArray(0),
    var contentType: String = "",
)

internal class RemoteConfig(
    val configMap: MutableMap<String, AgentConfigObject> = LinkedHashMap(),
    var configHash: ByteArray = ByteArray(0),
)

internal class ServerErrorResponse(
    var type: Int = ServerErrorTypes.UNKNOWN,
    var message: String = "",
    var retryAfterMs: Long? = null,
)

internal class ServerToAgent {
    var instanceUid: ByteArray? = null
    var errorResponse: ServerErrorResponse? = null
    var remoteConfig: RemoteConfig? = null
    var heartbeatIntervalSeconds: Long? = null
    var flags: Long = 0
    var capabilities: Long = 0
    var newInstanceUid: ByteArray? = null
}

/**
 * AnyValue. Only non-negative integers are int_value: the varint writer has
 * no negative form, so a negative integer goes out as double_value.
 */
private fun writeAnyValue(w: Writer, value: Any) {
    when {
        value is String -> w.string(1, value, always = true)
        value is Boolean -> w.uint(2, if (value) 1 else 0, always = true)
        value is Long || value is Int || value is Short || value is Byte -> {
            val n = (value as Number).toLong()
            if (n >= 0) w.uint(3, n, always = true) else w.float64(4, n.toDouble())
        }
        value is Number -> w.float64(4, value.toDouble())
        value is Iterable<*> -> writeArray(w, value)
        value is Array<*> -> writeArray(w, value.asList())
        else -> w.string(1, value.toString(), always = true)
    }
}

private fun writeArray(w: Writer, items: Iterable<*>) {
    w.message(5, always = true) { array ->
        for (item in items) {
            array.message(1, always = true) { v -> v.string(1, item.toString(), always = true) }
        }
    }
}

private fun writeAttributes(w: Writer, field: Int, attrs: Map<String, Any>) {
    for ((key, value) in attrs) {
        w.message(field) { kv ->
            kv.string(1, key)
            kv.message(2, always = true) { v -> writeAnyValue(v, value) }
        }
    }
}

internal fun encodeAgentToServer(msg: AgentToServer): ByteArray {
    val w = Writer()
    w.bytes(1, msg.instanceUid)
    w.uint(2, msg.sequenceNum)
    val description = msg.agentDescription
    if (description != null) {
        // Written even with no attributes: the server takes a missing
        // description for an omitted one and asks for the full state again.
        w.message(3, always = true) { d ->
            writeAttributes(d, 1, description.identifying)
            writeAttributes(d, 2, description.nonIdentifying)
        }
    }
    w.uint(4, msg.capabilities)
    val status = msg.remoteConfigStatus
    if (status != null) {
        w.message(7) { s ->
            s.bytes(1, status.lastRemoteConfigHash)
            s.uint(2, status.status.toLong())
            val error = status.errorMessage
            if (!error.isNullOrEmpty()) s.string(3, error)
        }
    }
    if (msg.customCapabilities.isNotEmpty()) {
        w.message(12) { c ->
            for (capability in msg.customCapabilities) c.string(1, capability)
        }
    }
    return w.finish()
}

private fun decodeConfigObject(buf: ByteArray): AgentConfigObject {
    val obj = AgentConfigObject()
    eachField(buf) { field, wireType, r ->
        when {
            wireType != WIRE_LENGTH -> false
            field == 1 -> { obj.body = r.bytes(); true }
            field == 2 -> { obj.contentType = r.string(); true }
            else -> false
        }
    }
    return obj
}

private fun decodeConfigMap(buf: ByteArray, into: MutableMap<String, AgentConfigObject>) {
    eachField(buf) { field, wireType, r ->
        if (field != 1 || wireType != WIRE_LENGTH) return@eachField false
        var key = ""
        var value = AgentConfigObject()
        eachField(r.bytes()) { entryField, entryWire, er ->
            when {
                entryWire != WIRE_LENGTH -> false
                entryField == 1 -> { key = er.string(); true }
                entryField == 2 -> { value = decodeConfigObject(er.bytes()); true }
                else -> false
            }
        }
        into[key] = value
        true
    }
}

private fun decodeRemoteConfig(buf: ByteArray): RemoteConfig {
    val config = RemoteConfig()
    eachField(buf) { field, wireType, r ->
        when {
            wireType != WIRE_LENGTH -> false
            field == 2 -> { config.configHash = r.bytes(); true }
            field == 1 -> { decodeConfigMap(r.bytes(), config.configMap); true }
            else -> false
        }
    }
    return config
}

private fun decodeErrorResponse(buf: ByteArray): ServerErrorResponse {
    val error = ServerErrorResponse()
    eachField(buf) { field, wireType, r ->
        when {
            field == 1 && wireType == WIRE_VARINT -> { error.type = r.varint().toInt(); true }
            field == 2 && wireType == WIRE_LENGTH -> { error.message = r.string(); true }
            field == 3 && wireType == WIRE_LENGTH -> {
                eachField(r.bytes()) { rf, rw, rr ->
                    if (rf != 1 || rw != WIRE_VARINT) return@eachField false
                    error.retryAfterMs = rr.varint() / 1_000_000
                    true
                }
                true
            }
            else -> false
        }
    }
    return error
}

/** ConnectionSettingsOffers.opamp.heartbeat_interval_seconds, when sent. */
private fun decodeHeartbeat(buf: ByteArray, msg: ServerToAgent) {
    eachField(buf) { field, wireType, r ->
        if (field != 2 || wireType != WIRE_LENGTH) return@eachField false
        eachField(r.bytes()) { sf, sw, sr ->
            if (sf != 4 || sw != WIRE_VARINT) return@eachField false
            msg.heartbeatIntervalSeconds = sr.varint()
            true
        }
        true
    }
}

/** Throws [WireFormatException] when [buf] is not a valid message. */
internal fun decodeServerToAgent(buf: ByteArray): ServerToAgent {
    val msg = ServerToAgent()
    eachField(buf) { field, wireType, r ->
        when (field) {
            1 -> if (wireType != WIRE_LENGTH) false else {
                msg.instanceUid = r.bytes()
                true
            }
            2 -> if (wireType != WIRE_LENGTH) false else {
                msg.errorResponse = decodeErrorResponse(r.bytes())
                true
            }
            3 -> if (wireType != WIRE_LENGTH) false else {
                msg.remoteConfig = decodeRemoteConfig(r.bytes())
                true
            }
            4 -> if (wireType != WIRE_LENGTH) false else {
                decodeHeartbeat(r.bytes(), msg)
                true
            }
            6 -> if (wireType != WIRE_VARINT) false else {
                msg.flags = r.varint()
                true
            }
            7 -> if (wireType != WIRE_VARINT) false else {
                msg.capabilities = r.varint()
                true
            }
            8 -> if (wireType != WIRE_LENGTH) false else {
                eachField(r.bytes()) { f, w, ir ->
                    if (f != 1 || w != WIRE_LENGTH) return@eachField false
                    msg.newInstanceUid = ir.bytes()
                    true
                }
                true
            }
            else -> false
        }
    }
    return msg
}

/**
 * Encodes the parts of a ServerToAgent this client reads; for mock servers
 * in tests.
 */
internal fun encodeServerToAgent(
    instanceUid: ByteArray? = null,
    errorResponse: ServerErrorResponse? = null,
    remoteConfig: RemoteConfig? = null,
    heartbeatIntervalSeconds: Long? = null,
    flags: Long = 0,
    capabilities: Long = 0,
    newInstanceUid: ByteArray? = null,
): ByteArray {
    val w = Writer()
    if (instanceUid != null) w.bytes(1, instanceUid)
    if (errorResponse != null) {
        w.message(2, always = true) { e ->
            e.uint(1, errorResponse.type.toLong())
            e.string(2, errorResponse.message)
            val retryAfter = errorResponse.retryAfterMs
            if (retryAfter != null) {
                e.message(3, always = true) { ri -> ri.uint(1, retryAfter * 1_000_000) }
            }
        }
    }
    if (remoteConfig != null) {
        w.message(3) { rc ->
            rc.message(1) { map ->
                for ((key, obj) in remoteConfig.configMap) {
                    map.message(1) { entry ->
                        entry.string(1, key)
                        entry.message(2, always = true) { o ->
                            o.bytes(1, obj.body)
                            o.string(2, obj.contentType)
                        }
                    }
                }
            }
            rc.bytes(2, remoteConfig.configHash)
        }
    }
    if (heartbeatIntervalSeconds != null && heartbeatIntervalSeconds > 0) {
        w.message(4) { cs -> cs.message(2) { o -> o.uint(4, heartbeatIntervalSeconds) } }
    }
    w.uint(6, flags)
    w.uint(7, capabilities)
    if (newInstanceUid != null) {
        w.message(8) { id -> id.bytes(1, newInstanceUid) }
    }
    return w.finish()
}
