package com.tracewayapp.symbols

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

object TracewaySymbols {
    /** Traceway Cloud: where mappings go unless `traceway.url` names a self-hosted server. */
    const val DEFAULT_URL = "https://cloud.tracewayapp.com"

    fun deriveProguardUuid(seed: String): String =
        UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()

    data class Part(val field: String, val filename: String?, val contentType: String?, val bytes: ByteArray)

    fun textPart(field: String, value: String) = Part(field, null, null, value.toByteArray(Charsets.UTF_8))

    fun filePart(field: String, file: File, contentType: String) =
        Part(field, file.name, contentType, file.readBytes())

    private fun sanitizeHeader(value: String): String =
        value.replace("\r", "").replace("\n", "").replace("\"", "%22")

    fun buildMultipartBody(boundary: String, parts: List<Part>): ByteArray {
        val out = ByteArrayOutputStream()
        val crlf = "\r\n"
        for (p in parts) {
            out.write("--$boundary$crlf".toByteArray())
            val disposition = StringBuilder("Content-Disposition: form-data; name=\"${sanitizeHeader(p.field)}\"")
            if (p.filename != null) disposition.append("; filename=\"${sanitizeHeader(p.filename)}\"")
            out.write("$disposition$crlf".toByteArray())
            if (p.contentType != null) out.write("Content-Type: ${p.contentType}$crlf".toByteArray())
            out.write(crlf.toByteArray())
            out.write(p.bytes)
            out.write(crlf.toByteArray())
        }
        out.write("--$boundary--$crlf".toByteArray())
        return out.toByteArray()
    }

    /** The server [configured] names (`traceway.url`), or Traceway Cloud when it is absent or blank. */
    fun resolveUrl(configured: String?): String = configured?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_URL

    fun uploadEndpoint(url: String): String {
        val raw = url.trim().substringBefore('#').substringBefore('?')
        val absolute = if ("://" in raw) raw else {
            val authority = raw.substringBefore('/')
            val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
            val local = host.lowercase() in setOf("localhost", "127.0.0.1", "10.0.2.2", "[::1]")
            (if (local) "http://" else "https://") + raw
        }
        val uri = URI(absolute)
        require(uri.scheme.lowercase() in setOf("http", "https") && !uri.rawAuthority.isNullOrEmpty() && uri.toURL().host.isNotEmpty()) {
            "Traceway symbol upload requires an HTTP(S) server URL"
        }
        val origin = "${uri.scheme}://${uri.rawAuthority}"
        val path = uri.rawPath.trimEnd('/')
        val endpoint = "/api/symbols/upload"
        if (path.endsWith(endpoint)) return origin + path
        val prefix = if (path.endsWith("/api/report")) path.removeSuffix("/api/report") else path.removeSuffix("/api")
        return origin + prefix.trimEnd('/') + endpoint
    }

    data class UploadResult(val status: Int, val body: String)

    fun upload(endpoint: String, token: String, parts: List<Part>): UploadResult {
        val boundary = "----traceway" + UUID.randomUUID().toString().replace("-", "")
        val body = buildMultipartBody(boundary, parts)

        val conn = URI(endpoint).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.outputStream.use { it.write(body) }

        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val respBody = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return UploadResult(status, respBody)
    }
}
