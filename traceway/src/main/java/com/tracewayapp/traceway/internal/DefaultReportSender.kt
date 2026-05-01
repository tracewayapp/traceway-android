package com.tracewayapp.traceway.internal

import com.tracewayapp.traceway.transport.ReportSender
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

internal object DefaultReportSender : ReportSender {
    override fun send(apiUrl: String, token: String, jsonBody: String): Boolean {
        return try {
            val bytes = jsonBody.toByteArray(Charsets.UTF_8)
            val compressed = ByteArrayOutputStream(bytes.size).also { buf ->
                GZIPOutputStream(buf).use { it.write(bytes) }
            }.toByteArray()

            val url = URL(apiUrl)
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Content-Encoding", "gzip")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setFixedLengthStreamingMode(compressed.size)
                conn.outputStream.use { it.write(compressed) }
                val code = conn.responseCode
                code == 200
            } finally {
                conn.disconnect()
            }
        } catch (_: Throwable) {
            false
        }
    }
}
