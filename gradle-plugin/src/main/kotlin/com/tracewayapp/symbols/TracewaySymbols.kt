package com.tracewayapp.symbols

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

object TracewaySymbols {

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

    fun uploadEndpoint(url: String): String {
        var base = url.trim().removeSuffix("/")
        base = base.removeSuffix("/api/report")
        if (base.endsWith("/api/symbols/upload")) return base
        return "$base/api/symbols/upload"
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
