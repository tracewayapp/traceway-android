package com.tracewayapp.traceway.internal

internal data class ParsedConnectionString(
    val token: String,
    val apiUrl: String,
)

internal fun parseConnectionString(connectionString: String): ParsedConnectionString {
    val atIndex = connectionString.indexOf('@')
    require(atIndex != -1) {
        "Invalid connection string: must be in format {token}@{apiUrl}"
    }
    val token = connectionString.substring(0, atIndex)
    val apiUrl = connectionString.substring(atIndex + 1)
    require(token.isNotEmpty() && apiUrl.isNotEmpty()) {
        "Invalid connection string: token and apiUrl must not be empty"
    }
    return ParsedConnectionString(token = token, apiUrl = apiUrl)
}
