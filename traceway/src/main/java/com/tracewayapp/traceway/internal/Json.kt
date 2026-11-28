package com.tracewayapp.traceway.internal

/**
 * A small JSON reader and writer over plain Kotlin values, used for the
 * settings document, the SDK's state file, rrweb events and recording
 * uploads. Pure Kotlin, so JVM unit tests and the device behave the same.
 *
 * Values: `null`, [Boolean], [String], [Long] (integral numbers that fit),
 * [Double] (every other number), [List] and [Map] with [String] keys. Maps
 * keep their key order.
 */
internal object Json {

    class ParseException(message: String) : IllegalArgumentException(message)

    /** Parses [text]; throws [ParseException] when it is not one JSON value. */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.value(0)
        reader.skipWhitespace()
        if (!reader.atEnd()) throw ParseException("trailing characters at ${reader.pos}")
        return value
    }

    /** The JSON text of [value]. Non-finite doubles become `null`. */
    fun stringify(value: Any?): String {
        val out = StringBuilder()
        write(out, value)
        return out.toString()
    }

    fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Double -> writeDouble(out, value)
            is Float -> writeDouble(out, value.toDouble())
            is Number -> writeDouble(out, value.toDouble())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, k.toString())
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (v in value) {
                    if (!first) out.append(',')
                    first = false
                    write(out, v)
                }
                out.append(']')
            }
            is Array<*> -> write(out, value.asList())
            is IntArray -> write(out, value.asList())
            is LongArray -> write(out, value.asList())
            is DoubleArray -> write(out, value.asList())
            else -> writeString(out, value.toString())
        }
    }

    private fun writeDouble(out: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) {
            out.append("null")
        } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            out.append(d.toLong().toString())
        } else {
            out.append(d.toString())
        }
    }

    private fun writeString(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                // U+2028 and U+2029 are escaped so the text is also valid JavaScript.
                else -> if (c < ' ' || c == '\u2028' || c == '\u2029') {
                    out.append("\\u")
                    val hex = Integer.toHexString(c.code)
                    for (i in hex.length until 4) out.append('0')
                    out.append(hex)
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }

    private class Reader(val text: String) {
        var pos = 0

        fun atEnd() = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length) {
                val c = text[pos]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else break
            }
        }

        fun value(depth: Int): Any? {
            if (depth > 512) throw ParseException("nesting too deep")
            if (atEnd()) throw ParseException("unexpected end")
            return when (val c = text[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else throw ParseException("unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!text.startsWith(word, pos)) throw ParseException("unexpected token at $pos")
            pos += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && text[pos] == '}') {
                pos++
                return out
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || text[pos] != '"') throw ParseException("expected key at $pos")
                val key = string()
                skipWhitespace()
                if (atEnd() || text[pos] != ':') throw ParseException("expected ':' at $pos")
                pos++
                skipWhitespace()
                out[key] = value(depth + 1)
                skipWhitespace()
                if (atEnd()) throw ParseException("unexpected end")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return out
                    }
                    else -> throw ParseException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (!atEnd() && text[pos] == ']') {
                pos++
                return out
            }
            while (true) {
                skipWhitespace()
                out.add(value(depth + 1))
                skipWhitespace()
                if (atEnd()) throw ParseException("unexpected end")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return out
                    }
                    else -> throw ParseException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun string(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw ParseException("unterminated string")
                val c = text[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw ParseException("unterminated escape")
                        when (val e = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw ParseException("bad unicode escape")
                                var code = 0
                                for (i in pos until pos + 4) {
                                    val digit = Character.digit(text[i], 16)
                                    // Character.digit also takes non-ASCII digits; JSON does not.
                                    if (digit < 0 || text[i].code > 0x7f) throw ParseException("bad unicode escape")
                                    code = code * 16 + digit
                                }
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw ParseException("bad escape '\\$e'")
                        }
                    }
                    else -> {
                        if (c < ' ') throw ParseException("control character in string at ${pos - 1}")
                        sb.append(c)
                    }
                }
            }
        }

        /** The JSON number grammar exactly: no leading zeros, no bare `.` or exponent, no `+` sign. */
        private fun number(): Any {
            val start = pos
            if (text[pos] == '-') pos++
            if (atEnd() || text[pos] !in '0'..'9') throw ParseException("bad number at $start")
            if (text[pos] == '0') pos++ else digits()
            var isDouble = false
            if (!atEnd() && text[pos] == '.') {
                isDouble = true
                pos++
                if (atEnd() || text[pos] !in '0'..'9') throw ParseException("bad number at $start")
                digits()
            }
            if (!atEnd() && (text[pos] == 'e' || text[pos] == 'E')) {
                isDouble = true
                pos++
                if (!atEnd() && (text[pos] == '+' || text[pos] == '-')) pos++
                if (atEnd() || text[pos] !in '0'..'9') throw ParseException("bad number at $start")
                digits()
            }
            val raw = text.substring(start, pos)
            if (!isDouble) {
                // Integers beyond Long fall through to a Double, as JavaScript reads them.
                raw.toLongOrNull()?.let { return it }
            }
            return raw.toDouble()
        }

        private fun digits() {
            while (pos < text.length && text[pos] in '0'..'9') pos++
        }
    }
}

/** [this] as a JSON object, or null. */
@Suppress("UNCHECKED_CAST")
internal fun Any?.asJsonObject(): Map<String, Any?>? = this as? Map<String, Any?>

internal fun Any?.asJsonList(): List<Any?>? = this as? List<Any?>

/** A finite number as a double, or null. */
internal fun Any?.asFiniteDouble(): Double? {
    val n = this as? Number ?: return null
    val d = n.toDouble()
    return if (d.isNaN() || d.isInfinite()) null else d
}

/** An integral number (Long, or a Double without a fraction) as a Long, or null. */
internal fun Any?.asLongExact(): Long? = when (this) {
    is Long -> this
    is Int -> this.toLong()
    is Short -> this.toLong()
    is Byte -> this.toLong()
    is Double -> if (this == Math.rint(this) && !this.isInfinite()) this.toLong() else null
    is Float -> if (this.toDouble() == Math.rint(this.toDouble()) && !this.isInfinite()) this.toLong() else null
    else -> null
}
