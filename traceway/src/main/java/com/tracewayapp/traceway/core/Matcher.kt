package com.tracewayapp.traceway.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Evaluates the matchers of the settings document against one observed
 * record. The same rules as the browser and Flutter SDKs, so a trigger
 * behaves the same on every client.
 */

/** Decimal numbers only, as Dart's `double.tryParse` reads them: no hex, no `1d`/`1f` suffixes. */
private val DECIMAL = Regex("""^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$""")

private fun asNumber(value: Any?): Double? {
    if (value is Number) {
        val d = value.toDouble()
        return if (d.isNaN() || d.isInfinite()) null else d
    }
    if (value is String) {
        val text = value.trim()
        if (text.isEmpty() || !DECIMAL.matches(text)) return null
        val n = text.toDoubleOrNull() ?: return null
        return if (n.isNaN() || n.isInfinite()) null else n
    }
    return null
}

/** Equality across JSON types: `503` equals `"503"` and `503.0`, as on the other SDKs. */
private fun same(actual: Any?, expected: Any?): Boolean {
    if (actual == expected) return true
    if (actual == null) return false
    val a = asNumber(actual)
    val e = asNumber(expected)
    if (a != null && e != null) return a == e
    return actual.toString() == expected.toString()
}

private val VERSION_PATTERN = Regex("""^v?(\d+(?:\.\d+)*)""")

private fun versionParts(version: String): List<Long>? {
    val match = VERSION_PATTERN.find(version.trim()) ?: return null
    val parts = ArrayList<Long>()
    for (part in match.groupValues[1].split('.')) {
        // A part too long for a 64-bit int is not a version, as on the server.
        parts.add(part.toLongOrNull() ?: return null)
    }
    return parts
}

/** -1, 0 or 1; null when either side is not a version. Missing parts count as 0. */
internal fun compareVersions(a: String, b: String): Int? {
    val pa = versionParts(a) ?: return null
    val pb = versionParts(b) ?: return null
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val diff = (if (i < pa.size) pa[i] else 0L).compareTo(if (i < pb.size) pb[i] else 0L)
        if (diff != 0) return if (diff < 0) -1 else 1
    }
    return 0
}

private val COMPARATOR_PATTERN = Regex("""^(>=|<=|>|<|==|=|!=)?(.+)$""")

/** Dart's `\s` is Unicode whitespace (JavaScript semantics); Java's is ASCII only. */
private val CONSTRAINT_SEPARATOR =
    Regex("""[\s\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff,]+""")

/**
 * Checks a version against a constraint such as `>=2.1 <3` (comparators
 * separated by spaces or commas, all must hold).
 */
internal fun satisfiesVersion(version: String, constraint: String): Boolean {
    val comparators = constraint.split(CONSTRAINT_SEPARATOR).filter { it.isNotEmpty() }
    if (comparators.isEmpty()) return false
    for (comparator in comparators) {
        val match = COMPARATOR_PATTERN.find(comparator) ?: return false
        val cmp = compareVersions(version, match.groupValues[2]) ?: return false
        val ok = when (match.groupValues[1]) {
            ">=" -> cmp >= 0
            "<=" -> cmp <= 0
            ">" -> cmp > 0
            "<" -> cmp < 0
            "!=" -> cmp != 0
            else -> cmp == 0
        }
        if (!ok) return false
    }
    return true
}

/** Text conditions look at most at this much of a value, so a huge log line cannot stall the app. */
private const val MAX_MATCH_TEXT = 4096

private fun text(value: Any?): String {
    val s = value as? String ?: value.toString()
    return if (s.length > MAX_MATCH_TEXT) s.substring(0, MAX_MATCH_TEXT) else s
}

/**
 * Case-insensitive wildcard match: `*` is any run of characters, `?` one
 * character, everything else literal. Greedy with a single backtrack point,
 * so it runs in O(text × pattern) at worst.
 */
internal fun globMatch(value: String, pattern: String): Boolean {
    val t = value.lowercase()
    val p = pattern.lowercase()
    var ti = 0
    var pi = 0
    var star = -1
    var mark = 0
    while (ti < t.length) {
        if (pi < p.length && (p[pi] == '?' || p[pi] == t[ti])) {
            ti++
            pi++
        } else if (pi < p.length && p[pi] == '*') {
            star = pi++
            mark = ti
        } else if (star >= 0) {
            pi = star + 1
            ti = ++mark
        } else {
            return false
        }
    }
    while (pi < p.length && p[pi] == '*') pi++
    return pi == p.length
}

private fun evaluate(condition: Condition, lookup: FieldLookup): Boolean {
    val actual = lookup(condition.key)
    val present = actual != null && actual != ""
    val value = condition.value
    return when (condition.op) {
        "exists" -> present
        "not_exists" -> !present
        "eq" -> same(actual, value)
        "neq" -> !same(actual, value)
        "in" -> (condition.values ?: emptyList()).any { same(actual, it) }
        "not_in" -> !(condition.values ?: emptyList()).any { same(actual, it) }
        "contains" -> present && value != null && text(actual).contains(value.toString())
        "icontains" -> present && value != null &&
            text(actual).lowercase().contains(value.toString().lowercase())
        "starts_with" -> present && value != null && text(actual).startsWith(value.toString())
        "glob" -> present && value != null && globMatch(text(actual), value.toString())
        "gt", "gte", "lt", "lte" -> {
            val a = asNumber(actual)
            val e = asNumber(value)
            if (a == null || e == null) {
                false
            } else {
                when (condition.op) {
                    "gt" -> a > e
                    "gte" -> a >= e
                    "lt" -> a < e
                    else -> a <= e
                }
            }
        }
        "version" -> present && value != null && satisfiesVersion(actual.toString(), value.toString())
        else -> false
    }
}

private val ROUTE_PARAMETER = Regex("""^\{[A-Za-z_][A-Za-z0-9_]*\}$""")

/** Percent-decodes [segment] as Dart's `Uri.decodeComponent`; the segment itself when that would fail. */
private fun decoded(segment: String): String {
    if (segment.indexOf('%') < 0 && segment.all { it.code <= 0x7f }) return segment
    val bytes = ByteArray(segment.length)
    var n = 0
    var i = 0
    while (i < segment.length) {
        val c = segment[i]
        if (c.code > 0x7f) return segment
        if (c == '%') {
            if (i + 3 > segment.length) return segment
            val hi = hexValue(segment[i + 1])
            val lo = hexValue(segment[i + 2])
            if (hi < 0 || lo < 0) return segment
            bytes[n++] = ((hi shl 4) or lo).toByte()
            i += 3
        } else {
            bytes[n++] = c.code.toByte()
            i++
        }
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, n))
            .toString()
    } catch (_: CharacterCodingException) {
        segment
    }
}

private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

private val SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*$""")

/**
 * The path part of a navigation target such as `/users/42?tab=1`, as the
 * browser SDK reads `location.pathname`: the target resolved against
 * `https://traceway.invalid/`, percent-encoded and without dot segments,
 * the way Dart's `Uri.resolve(...).path` gives it. Null for an empty
 * target or one without a usable scheme.
 */
internal fun pathOf(url: Any?): String? {
    if (url == null || url == "") return null
    return try {
        resolvePath(url.toString())
    } catch (_: Exception) {
        null
    }
}

private fun resolvePath(reference: String): String? {
    // Dart reads a backslash as a slash, as browsers do for http(s) URLs.
    var s = reference.replace('\\', '/')
    val end = s.indexOfFirst { it == '?' || it == '#' }
    if (end >= 0) s = s.substring(0, end)
    val colon = s.indexOf(':')
    val slash = s.indexOf('/')
    if (colon >= 0 && (slash < 0 || colon < slash)) {
        val scheme = s.substring(0, colon)
        if (!SCHEME.matches(scheme)) return null
        val rest = s.substring(colon + 1)
        var path = if (rest.startsWith("//")) afterAuthority(rest) else rest
        if (scheme.equals("file", ignoreCase = true) && !path.startsWith("/")) path = "/$path"
        return removeDotSegments(normalizePath(path))
    }
    if (s.startsWith("//")) return removeDotSegments(normalizePath(afterAuthority(s)))
    if (s.isEmpty()) return "/"
    if (s.startsWith("/")) return removeDotSegments(normalizePath(s))
    return removeDotSegments(normalizePath("/$s"))
}

/** What follows `//authority` in [s], which starts with `//`. */
private fun afterAuthority(s: String): String {
    val pathStart = s.indexOf('/', 2)
    return if (pathStart < 0) "" else s.substring(pathStart)
}

private const val UNRESERVED_MARKS = "-._~"
private const val PATH_MARKS = "-._~!$&'()*+,;=:@/"

private fun isAlphaNumeric(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

private val UPPER_HEX = "0123456789ABCDEF".toCharArray()

/**
 * Dart's path normalization: characters a path may not hold are
 * percent-encoded as UTF-8, escapes of unreserved characters are decoded
 * and the rest are written in upper case, and a stray `%` becomes `%25`.
 */
private fun normalizePath(path: String): String {
    val out = StringBuilder(path.length)
    var i = 0
    while (i < path.length) {
        val c = path[i]
        if (c == '%') {
            val hi = if (i + 2 < path.length) hexValue(path[i + 1]) else -1
            val lo = if (i + 2 < path.length) hexValue(path[i + 2]) else -1
            if (hi >= 0 && lo >= 0) {
                val v = (hi shl 4) or lo
                val ch = v.toChar()
                if (v < 0x80 && (isAlphaNumeric(ch) || ch in UNRESERVED_MARKS)) {
                    out.append(ch)
                } else {
                    out.append('%').append(UPPER_HEX[hi]).append(UPPER_HEX[lo])
                }
                i += 3
            } else {
                out.append("%25")
                i++
            }
        } else if (c.code < 0x80 && (isAlphaNumeric(c) || c in PATH_MARKS)) {
            out.append(c)
            i++
        } else {
            val codePoint: Int
            if (Character.isHighSurrogate(c) && i + 1 < path.length && Character.isLowSurrogate(path[i + 1])) {
                codePoint = Character.toCodePoint(c, path[i + 1])
                i += 2
            } else {
                codePoint = if (Character.isSurrogate(c)) 0xFFFD else c.code
                i++
            }
            for (b in String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)) {
                val v = b.toInt() and 0xff
                out.append('%').append(UPPER_HEX[v ushr 4]).append(UPPER_HEX[v and 0x0f])
            }
        }
    }
    return out.toString()
}

/** RFC 3986 dot-segment removal, in the form Dart's `Uri` applies it. */
private fun removeDotSegments(path: String): String {
    if (!path.startsWith(".") && path.indexOf("/.") < 0) return path
    val output = ArrayList<String>()
    var appendSlash = false
    for (segment in path.split('/')) {
        appendSlash = false
        if (segment == "..") {
            if (output.isNotEmpty()) {
                output.removeAt(output.size - 1)
                if (output.isEmpty()) output.add("")
            }
            appendSlash = true
        } else if (segment == ".") {
            appendSlash = true
        } else {
            output.add(segment)
        }
    }
    if (appendSlash) output.add("")
    return output.joinToString("/")
}

/**
 * `/users/{id}` matches exactly one non-empty segment in that place; other
 * segments compare as written or percent-decoded, case-sensitively.
 */
internal fun matchesRoute(pattern: String, path: String): Boolean {
    val expected = pattern.split('/')
    val actual = path.split('/')
    if (expected.size != actual.size) return false
    for (i in expected.indices) {
        val part = expected[i]
        if (ROUTE_PARAMETER.matches(part)) {
            if (actual[i].isEmpty()) return false
        } else if (part != actual[i] && part != decoded(actual[i])) {
            return false
        }
    }
    return true
}

private fun matchesEvent(matcher: EventMatcher, lookup: FieldLookup): Boolean {
    when (matcher.type) {
        "click", "hover" -> {
            if (lookup(KEY_POINTER_ACTION) != matcher.type) return false
            val test = lookup(KEY_TARGET_MATCHES) as? Function1<*, *> ?: return false
            return try {
                @Suppress("UNCHECKED_CAST")
                (test as (String) -> Any?)(matcher.value) == true
            } catch (_: Exception) {
                // The selector test is the app's view tree; a failure there is no match.
                false
            }
        }
        "log" -> {
            val name = lookup("event.name")
            val signal = lookup("signal")
            if (name != "browser.console" &&
                name != "traceway.message" &&
                signal != "console" &&
                signal != "messages"
            ) {
                return false
            }
            var body = lookup("body")?.toString() ?: ""
            if (body.length > MAX_MATCH_TEXT) body = body.substring(0, MAX_MATCH_TEXT)
            return if (matcher.mode == "contains") body.contains(matcher.value) else body == matcher.value
        }
        "navigation" -> {
            if (lookup("event.name") != "browser.navigation") return false
            val path = pathOf(lookup("attributes.url.full"))
            return path != null && matchesRoute(matcher.value, path)
        }
    }
    return false
}

internal fun matches(matcher: Matcher, lookup: FieldLookup): Boolean = when (matcher) {
    is EventMatcher -> matchesEvent(matcher, lookup)
    is Condition -> evaluate(matcher, lookup)
    is AllMatcher -> matcher.items.all { matches(it, lookup) }
    is AnyMatcher -> matcher.items.any { matches(it, lookup) }
    is NotMatcher -> !matches(matcher.inner, lookup)
}
