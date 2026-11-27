package com.tracewayapp.traceway.diagnostics

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Input that is not the protobuf it should be: what was read before it stays. */
internal class MalformedProto : RuntimeException("malformed protobuf")

/**
 * The few parts of protobuf the tombstone needs, by hand (no dependency):
 * varints, length-delimited fields, and skipping fixed-width ones. Every
 * read is checked against the end of the message; anything else (a group,
 * a field number 0, a length past the end) is [MalformedProto].
 */
internal class ProtoReader(private val buf: ByteArray, private var pos: Int, private val end: Int) {
    constructor(buf: ByteArray) : this(buf, 0, buf.size)

    val atEnd: Boolean get() = pos >= end

    /** The next field's tag: `field << 3 | wire type`. */
    fun tag(): Int {
        val tag = varint()
        if (tag <= 0 || tag > Int.MAX_VALUE || (tag ushr 3) == 0L) throw MalformedProto()
        return tag.toInt()
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (pos >= end) throw MalformedProto()
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw MalformedProto()
    }

    /** A length-delimited field as a reader of its own bytes. */
    fun message(): ProtoReader {
        val length = length()
        val inner = ProtoReader(buf, pos, pos + length)
        pos += length
        return inner
    }

    fun string(): String {
        val length = length()
        val s = String(buf, pos, length, Charsets.UTF_8)
        pos += length
        return s
    }

    private fun length(): Int {
        val length = varint()
        if (length < 0 || length > end - pos) throw MalformedProto()
        return length.toInt()
    }

    fun skip(tag: Int) {
        when (tag and 7) {
            0 -> varint()
            1 -> advance(8)
            2 -> advance(length())
            5 -> advance(4)
            else -> throw MalformedProto()
        }
    }

    private fun advance(n: Int) {
        if (n > end - pos) throw MalformedProto()
        pos += n
    }
}

/** One frame of a native backtrace, as the tombstone records it. */
internal class NativeFrame(
    val relPc: Long,
    val functionName: String?,
    val functionOffset: Long,
    val fileName: String?,
    val fileMapOffset: Long,
    val buildId: String?,
)

/**
 * What a tombstone (`debuggerd`'s protobuf, `tombstone.proto`) says about a
 * native crash: the signal, the abort message, the cause the unwinder
 * guessed, and the crashing thread's frames.
 */
internal class Tombstone(
    val signalNumber: Int?,
    val signalName: String?,
    val codeName: String?,
    val faultAddress: Long?,
    val abortMessage: String?,
    val cause: String?,
    val threadName: String?,
    val frames: List<NativeFrame>,
    val is64Bit: Boolean,
) {
    /** `SIGSEGV (SEGV_MAPERR), fault addr 0x0: null pointer dereference`, `SIGABRT: <abort message>`. */
    val message: String
        get() = buildString {
            append(signalName?.takeIf { it.isNotEmpty() } ?: signalNumber?.let { "signal $it" } ?: "Native crash")
            codeName?.takeIf { it.isNotEmpty() && it != "SI_USER" && it != "SI_TKILL" && it != "SI_QUEUE" }?.let {
                append(" (").append(it).append(')')
            }
            faultAddress?.let { append(", fault addr 0x").append(java.lang.Long.toHexString(it)) }
            val detail = abortMessage?.takeIf { it.isNotBlank() } ?: cause?.takeIf { it.isNotBlank() }
            if (detail != null) append(": ").append(detail.trim())
        }

    /** The issue's stack: `NativeCrash: <message>`, then the frames as `debuggerd` prints them. */
    fun stackTrace(type: String): String {
        val sb = StringBuilder(type).append(": ").append(message)
        for ((i, f) in frames.withIndex()) sb.append('\n').append(formatFrame(i, f, is64Bit))
        return sb.toString()
    }

    companion object {
        /** The most of a tombstone read: the signal, abort message and threads come first; memory maps and logs after. */
        const val MAX_BYTES = 4 * 1024 * 1024
        const val MAX_FRAMES = 128
        private const val MAX_TEXT = 1024

        /** `    #00 pc 000000000004f8c8  /apex/…/libc.so (abort+164) (BuildId: …)`. */
        fun formatFrame(index: Int, f: NativeFrame, is64Bit: Boolean): String {
            val sb = StringBuilder("    #")
            if (index < 10) sb.append('0')
            sb.append(index).append(" pc ")
            val hex = java.lang.Long.toHexString(f.relPc)
            for (k in hex.length until if (is64Bit) 16 else 8) sb.append('0')
            sb.append(hex).append("  ").append(f.fileName?.takeIf { it.isNotEmpty() } ?: "<unknown>")
            if (f.fileMapOffset != 0L) sb.append(" (offset 0x").append(java.lang.Long.toHexString(f.fileMapOffset)).append(')')
            if (!f.functionName.isNullOrEmpty()) {
                sb.append(" (").append(f.functionName)
                if (f.functionOffset != 0L) sb.append('+').append(f.functionOffset)
                sb.append(')')
            }
            if (!f.buildId.isNullOrEmpty()) sb.append(" (BuildId: ").append(f.buildId).append(')')
            return sb.toString()
        }

        /** Reads at most [MAX_BYTES] of [stream] and parses them; null when nothing useful is in them. */
        fun read(stream: InputStream): Tombstone? = parse(readAtMost(stream, MAX_BYTES))

        /**
         * Parses a tombstone. Unknown fields are skipped; a message cut short
         * or malformed keeps what was read before it. Null when it holds
         * neither a signal nor frames.
         */
        fun parse(bytes: ByteArray): Tombstone? {
            var arch = -1
            var tid = -1
            var signal: Signal? = null
            var abort: String? = null
            var cause: String? = null
            // Thread entries are kept as readers: the crashing thread's id may come after them.
            val threads = ArrayList<Pair<Int, ProtoReader>>()
            try {
                val r = ProtoReader(bytes)
                while (!r.atEnd) {
                    val tag = r.tag()
                    when (tag) {
                        (1 shl 3) or 0 -> arch = r.varint().toInt()
                        (6 shl 3) or 0 -> tid = r.varint().toInt()
                        (10 shl 3) or 2 -> signal = parseSignal(r.message())
                        (14 shl 3) or 2 -> abort = clip(r.string())
                        (15 shl 3) or 2 -> {
                            val c = parseCause(r.message())
                            if (cause == null && !c.isNullOrEmpty()) cause = c
                        }
                        (16 shl 3) or 2 -> threadEntry(r.message())?.let { if (threads.size < 512) threads.add(it) }
                        else -> r.skip(tag)
                    }
                }
            } catch (_: MalformedProto) {
                // Cut short: what came before stays.
            }
            var name: String? = null
            var frames: List<NativeFrame> = emptyList()
            val crashing = threads.firstOrNull { it.first == tid } ?: threads.firstOrNull().takeIf { tid < 0 }
            if (crashing != null) {
                val t = parseThread(crashing.second)
                name = t.first
                frames = t.second
            }
            val s = signal
            if (s == null && frames.isEmpty() && abort == null) return null
            return Tombstone(
                signalNumber = s?.number,
                signalName = s?.name,
                codeName = s?.codeName,
                faultAddress = s?.faultAddress,
                abortMessage = abort,
                cause = cause,
                threadName = name,
                frames = frames,
                // ARM64, X86_64, RISCV64.
                is64Bit = arch == 1 || arch == 3 || arch == 4,
            )
        }

        private class Signal(val number: Int?, val name: String?, val codeName: String?, val faultAddress: Long?)

        private fun parseSignal(r: ProtoReader): Signal {
            var number: Int? = null
            var name: String? = null
            var codeName: String? = null
            var hasFault = false
            var fault = 0L
            try {
                while (!r.atEnd) {
                    val tag = r.tag()
                    when (tag) {
                        (1 shl 3) or 0 -> number = r.varint().toInt()
                        (2 shl 3) or 2 -> name = clip(r.string())
                        (4 shl 3) or 2 -> codeName = clip(r.string())
                        (8 shl 3) or 0 -> hasFault = r.varint() != 0L
                        (9 shl 3) or 0 -> fault = r.varint()
                        else -> r.skip(tag)
                    }
                }
            } catch (_: MalformedProto) {
            }
            return Signal(number, name, codeName, if (hasFault) fault else null)
        }

        private fun parseCause(r: ProtoReader): String? {
            try {
                while (!r.atEnd) {
                    val tag = r.tag()
                    if (tag == (1 shl 3) or 2) return clip(r.string())
                    r.skip(tag)
                }
            } catch (_: MalformedProto) {
            }
            return null
        }

        /** A `map<uint32, Thread>` entry: its key and its value, unread. */
        private fun threadEntry(r: ProtoReader): Pair<Int, ProtoReader>? {
            var key = 0
            var value: ProtoReader? = null
            try {
                while (!r.atEnd) {
                    val tag = r.tag()
                    when (tag) {
                        (1 shl 3) or 0 -> key = r.varint().toInt()
                        (2 shl 3) or 2 -> value = r.message()
                        else -> r.skip(tag)
                    }
                }
            } catch (_: MalformedProto) {
            }
            return value?.let { key to it }
        }

        private fun parseThread(r: ProtoReader): Pair<String?, List<NativeFrame>> {
            var name: String? = null
            val frames = ArrayList<NativeFrame>()
            try {
                while (!r.atEnd) {
                    val tag = r.tag()
                    when (tag) {
                        (2 shl 3) or 2 -> name = clip(r.string())
                        (4 shl 3) or 2 -> if (frames.size < MAX_FRAMES) frames.add(parseFrame(r.message())) else r.skip(tag)
                        else -> r.skip(tag)
                    }
                }
            } catch (_: MalformedProto) {
            }
            return name to frames
        }

        private fun parseFrame(r: ProtoReader): NativeFrame {
            var relPc = 0L
            var function: String? = null
            var offset = 0L
            var file: String? = null
            var mapOffset = 0L
            var buildId: String? = null
            try {
                while (!r.atEnd) {
                    val tag = r.tag()
                    when (tag) {
                        (1 shl 3) or 0 -> relPc = r.varint()
                        (4 shl 3) or 2 -> function = clip(r.string())
                        (5 shl 3) or 0 -> offset = r.varint()
                        (6 shl 3) or 2 -> file = clip(r.string())
                        (7 shl 3) or 0 -> mapOffset = r.varint()
                        (8 shl 3) or 2 -> buildId = clip(r.string())
                        else -> r.skip(tag)
                    }
                }
            } catch (_: MalformedProto) {
            }
            return NativeFrame(relPc, function, offset, file, mapOffset, buildId)
        }

        private fun clip(s: String): String = if (s.length > MAX_TEXT) s.substring(0, MAX_TEXT) else s
    }
}

/** At most [limit] bytes of [stream]; closes it. */
internal fun readAtMost(stream: InputStream, limit: Int): ByteArray {
    stream.use { input ->
        val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (total < limit) {
            val n = input.read(chunk, 0, minOf(chunk.size, limit - total))
            if (n < 0) break
            out.write(chunk, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
