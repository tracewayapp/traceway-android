package com.tracewayapp.traceway.transport

/**
 * Pluggable transport for report bodies. The default implementation gzips the
 * body and POSTs it to the parsed `apiUrl` from the connection string with a
 * `Bearer <token>` header.
 *
 * Tests can supply a fake [ReportSender] via [com.tracewayapp.traceway.TracewayClient.initializeForTesting].
 */
fun interface ReportSender {
    /** Returns true on success (HTTP 200), false otherwise. Must not throw. */
    fun send(apiUrl: String, token: String, jsonBody: String): Boolean
}
