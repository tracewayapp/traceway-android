package com.tracewayapp.traceway.core

import java.security.SecureRandom
import java.util.Random

internal object Ids {
    private val random: Random = try {
        SecureRandom()
    } catch (_: Throwable) {
        Random()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    fun bytesToHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    /** The bytes [hex] spells, or null when it is not an even run of hex digits. */
    fun hexToBytes(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hexDigit(hex[i * 2])
            val lo = hexDigit(hex[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** ASCII only: `Character.digit` also accepts other scripts' digits and full-width letters. */
    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    fun bytesToUuid(bytes: ByteArray): String {
        val hex = bytesToHex(bytes)
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-" +
            "${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    fun uuidToBytes(uuid: String): ByteArray? {
        val hex = uuid.replace("-", "")
        return if (hex.length == 32) hexToBytes(hex) else null
    }

    /** RFC 9562 UUIDv7: a millisecond timestamp then random bits, as OpAMP recommends for instance ids. */
    fun uuidv7(nowMs: Long = System.currentTimeMillis()): String {
        val bytes = ByteArray(16)
        synchronized(random) { random.nextBytes(bytes) }
        var ts = maxOf(0L, nowMs)
        for (i in 5 downTo 0) {
            bytes[i] = (ts and 0xff).toByte()
            ts = ts ushr 8
        }
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        return bytesToUuid(bytes)
    }

    fun uuidv4(): String {
        val bytes = ByteArray(16)
        synchronized(random) { random.nextBytes(bytes) }
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        return bytesToUuid(bytes)
    }

    /**
     * FNV-1a over UTF-16 code units mapped to [0, 1): a stable sampling
     * decision for an id such as a session id. Matches the browser and
     * Flutter SDKs so all of them sample the same sessions.
     */
    fun unitHash(value: String): Double {
        var hash = 0x811c9dc5L
        for (ch in value) {
            hash = hash xor ch.code.toLong()
            hash = (hash * 0x01000193L) and 0xffffffffL
        }
        return hash.toDouble() / 4294967296.0
    }
}
