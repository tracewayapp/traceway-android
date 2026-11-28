package com.tracewayapp.traceway.internal

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** An HTTP answer. [status] is 0 when none came (no network, timeout). */
internal class HttpResponse(
    val status: Int,
    /** Lowercase header names. */
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    val ok: Boolean get() = status in 200..299

    /** `Retry-After` in milliseconds, when the server sent seconds. */
    val retryAfterMs: Long?
        get() = headers["retry-after"]?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.times(1000)
}

/**
 * Blocking HTTP POST. Implementations never throw: a failure is status 0.
 * Always called from an I/O thread.
 */
internal fun interface HttpTransport {
    fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int): HttpResponse
}

/**
 * The default transport, on [HttpURLConnection]: no third-party HTTP stack
 * is pulled into the app. A response sent gzip-compressed is inflated.
 */
internal object UrlConnectionTransport : HttpTransport {
    override fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int): HttpResponse {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.connectTimeout = minOf(timeoutMs, 15_000)
            conn.readTimeout = timeoutMs
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            val responseHeaders = HashMap<String, String>()
            for ((k, v) in conn.headerFields) {
                if (k != null && v != null && v.isNotEmpty()) {
                    responseHeaders[k.lowercase(Locale.US)] = v.joinToString(",")
                }
            }
            val stream: InputStream? = try {
                if (status >= 400) conn.errorStream else conn.inputStream
            } catch (_: Throwable) {
                null
            }
            var bytes = stream?.use { readAll(it, 4 * 1024 * 1024) } ?: ByteArray(0)
            if (responseHeaders["content-encoding"]?.contains("gzip") == true && bytes.isNotEmpty()) {
                bytes = runCatching { gunzip(bytes) }.getOrDefault(bytes)
            }
            HttpResponse(status, responseHeaders, bytes)
        } catch (_: Throwable) {
            HttpResponse(0)
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun readAll(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) break
        }
        return out.toByteArray()
    }
}

internal fun gzip(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(maxOf(64, bytes.size / 4))
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
}

internal fun gunzip(bytes: ByteArray): ByteArray =
    GZIPInputStream(bytes.inputStream()).use { it.readBytes() }

/** No answer, throttled or a server error: worth sending again later. */
internal fun isTransientFailure(status: Int): Boolean =
    status == 0 || status == 408 || status == 429 || status >= 500
