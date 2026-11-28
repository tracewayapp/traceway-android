package com.tracewayapp.traceway.opamp

import java.io.ByteArrayOutputStream

// The protobuf wire format, only as much as OpAMP needs: varints, length-
// delimited fields and fixed 64-bit doubles.

internal const val WIRE_VARINT = 0
internal const val WIRE_FIXED64 = 1
internal const val WIRE_LENGTH = 2
internal const val WIRE_FIXED32 = 5

/** A message that is not valid protobuf: truncated, or a field it cannot skip. */
internal class WireFormatException(message: String) : IllegalArgumentException(message)

internal class Writer {
    private val out = ByteArrayOutputStream()

    /** An unsigned varint; a negative value is written as zero. */
    fun varint(value: Long) {
        var v = if (value < 0) 0L else value
        while (v >= 0x80) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun tag(field: Int, wireType: Int) = varint(field.toLong() * 8 + wireType)

    /**
     * Writes a varint field; zero is the proto3 default and is omitted unless
     * [always] is set (needed inside a oneof, where presence matters).
     */
    fun uint(field: Int, value: Long, always: Boolean = false) {
        if (value == 0L && !always) return
        tag(field, WIRE_VARINT)
        varint(value)
    }

    fun bytes(field: Int, value: ByteArray) {
        if (value.isEmpty()) return
        tag(field, WIRE_LENGTH)
        varint(value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String, always: Boolean = false) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        if (encoded.isEmpty() && always) {
            tag(field, WIRE_LENGTH)
            varint(0)
            return
        }
        bytes(field, encoded)
    }

    fun float64(field: Int, value: Double) {
        tag(field, WIRE_FIXED64)
        var bits = java.lang.Double.doubleToRawLongBits(value)
        for (i in 0 until 8) {
            out.write((bits and 0xff).toInt())
            bits = bits ushr 8
        }
    }

    /**
     * Writes a nested message. An empty message is still written when
     * [always] is set.
     */
    fun message(field: Int, always: Boolean = false, build: (Writer) -> Unit) {
        val inner = Writer()
        build(inner)
        val encoded = inner.finish()
        if (encoded.isEmpty() && !always) return
        tag(field, WIRE_LENGTH)
        varint(encoded.size.toLong())
        out.write(encoded)
    }

    fun finish(): ByteArray = out.toByteArray()
}

internal class Reader(private val buf: ByteArray) {
    private var pos = 0

    val done: Boolean get() = pos >= buf.size

    /** An unsigned varint; bits past the 63rd are dropped, so it is never negative. */
    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= buf.size) throw WireFormatException("truncated varint")
            val byte = buf[pos++].toInt() and 0xff
            if (shift < 63) result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte < 0x80) return result
            shift += 7
            if (shift >= 70) throw WireFormatException("varint too long")
        }
    }

    /** The field number and wire type of the next field. */
    fun tag(): Pair<Int, Int> {
        val value = varint()
        return Pair((value ushr 3).toInt(), (value and 7).toInt())
    }

    fun bytes(): ByteArray {
        val length = varint()
        if (length < 0 || length > buf.size - pos) throw WireFormatException("truncated field")
        val out = buf.copyOfRange(pos, pos + length.toInt())
        pos += length.toInt()
        return out
    }

    /** Malformed UTF-8 becomes U+FFFD rather than failing the message. */
    fun string(): String = String(bytes(), Charsets.UTF_8)

    fun skip(wireType: Int) {
        when (wireType) {
            WIRE_VARINT -> varint()
            WIRE_FIXED64 -> advance(8)
            WIRE_LENGTH -> bytes()
            WIRE_FIXED32 -> advance(4)
            else -> throw WireFormatException("unsupported wire type $wireType")
        }
    }

    private fun advance(length: Int) {
        if (length > buf.size - pos) throw WireFormatException("truncated field")
        pos += length
    }
}

/**
 * Walks the fields of a message. [handle] returns false for a field it did
 * not consume, which is then skipped.
 */
internal inline fun eachField(buf: ByteArray, handle: (field: Int, wireType: Int, reader: Reader) -> Boolean) {
    val reader = Reader(buf)
    while (!reader.done) {
        val (field, wireType) = reader.tag()
        if (!handle(field, wireType, reader)) reader.skip(wireType)
    }
}
