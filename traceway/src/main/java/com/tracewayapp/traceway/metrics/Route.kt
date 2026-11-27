package com.tracewayapp.traceway.metrics

private const val MAX_SEGMENTS = 6
private const val MAX_LENGTH = 64
private const val ID = ":id"

private val NUMBER = Regex("^\\d+$")
private val UUID = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
    RegexOption.IGNORE_CASE,
)
private val HEX = Regex("^[0-9a-f]{8,}$", RegexOption.IGNORE_CASE)
private val TOKEN = Regex("^[A-Za-z0-9_\\-=.~]{20,}$")
private val DIGIT = Regex("\\d")

// A percent-encoded ASCII character (a space, a slash, a brace) is a value
// somebody typed, never a route literal. Encoded UTF-8 bytes (%C3%A9) stay.
private val ENCODED_ASCII = Regex("%[0-7][0-9a-f]", RegexOption.IGNORE_CASE)
private val TRAILING_SLASHES = Regex("/+$")

private fun isId(segment: String): Boolean {
    if (NUMBER.matches(segment) || UUID.matches(segment)) return true
    if (ENCODED_ASCII.containsMatchIn(segment)) return true
    if (!DIGIT.containsMatchIn(segment)) return false
    return HEX.matches(segment) || TOKEN.matches(segment)
}

/**
 * Where the query string or fragment begins, or -1. In a route template a
 * `?` ends an optional parameter (`/:lang?/about`) and is kept.
 */
private fun queryStart(path: String): Int {
    for (i in path.indices) {
        val c = path[i]
        if (c == '#') return i
        if (c != '?') continue
        val segment = if (i == 0) 0 else path.lastIndexOf('/', i - 1) + 1
        val optionalParam = path[segment] == ':' && i + 1 < path.length && path[i + 1] == '/'
        if (!optionalParam) return i
    }
    return -1
}

/**
 * Turns a route path into something close to its template, so
 * `/orders/8841` and `/orders/8842` are one series: segments that are
 * numbers, UUIDs, long hex or token strings, or that carry a
 * percent-encoded ASCII character become `:id`. The same rules as the
 * browser and Flutter SDKs and the server (`backend/app/clientmetrics/route.go`).
 */
internal fun normalizePath(path: String): String {
    val end = queryStart(path)
    val clean = TRAILING_SLASHES.replaceFirst(if (end < 0) path else path.substring(0, end), "")
    if (clean.isEmpty()) return "/"
    val segments = clean.split('/').filter { it.isNotEmpty() }
    var truncated = segments.size > MAX_SEGMENTS
    val kept = segments.take(MAX_SEGMENTS).mapTo(ArrayList()) { if (isId(it)) ID else it }
    var out = "/" + kept.joinToString("/")
    while (kept.size > 1 && out.length + (if (truncated) 2 else 0) > MAX_LENGTH) {
        kept.removeAt(kept.size - 1)
        truncated = true
        out = "/" + kept.joinToString("/")
    }
    if (truncated) out += "/*"
    return if (out.length > MAX_LENGTH) out.substring(0, MAX_LENGTH) else out
}
