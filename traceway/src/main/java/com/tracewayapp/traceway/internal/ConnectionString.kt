package com.tracewayapp.traceway.internal

import com.tracewayapp.traceway.core.normalizeBaseUrl

/** An old connection string as the start options it stands for (CONTRACT §16). */
internal data class ParsedConnectionString(
    val token: String,
    /** The server, `https://host` (any `/api` or `/api/report` dropped, `https://` added when it had no scheme). */
    val baseUrl: String,
)

/**
 * Splits an old connection string, `token@https://host[/api[/report]]`, the
 * one way every SDK does (CONTRACT §16.1): trimmed, split at the first `@`
 * into the token (trimmed, not empty) and the server's URL, which
 * [normalizeBaseUrl] turns into its base URL.
 * @throws IllegalArgumentException for a string without a token or with an unusable URL.
 */
internal fun parseConnectionString(connectionString: String): ParsedConnectionString {
    val trimmed = connectionString.trim()
    val atIndex = trimmed.indexOf('@')
    val token = if (atIndex < 0) "" else trimmed.substring(0, atIndex).trim()
    require(token.isNotEmpty()) {
        "Invalid connection string: expected YOUR_TOKEN@https://host; better, pass TracewayOptions(token = \"YOUR_TOKEN\")"
    }
    return ParsedConnectionString(token = token, baseUrl = normalizeBaseUrl(trimmed.substring(atIndex + 1)))
}
