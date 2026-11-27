package com.tracewayapp.traceway.network

import com.tracewayapp.traceway.TracewayClient
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * OkHttp [Interceptor] that records every HTTP call as an OpenTelemetry
 * client span (signal `network`): method, URL, status, duration and sizes.
 * Add it as an application interceptor:
 *
 * ```kotlin
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(TracewayOkHttpInterceptor())
 *     .build()
 * ```
 *
 * Requests to URLs listed in `TracewayOptions.propagateTraceHeaderUrls`
 * carry a W3C `traceparent` header naming the span, so backend spans join
 * the app's trace. A request that already has a `traceparent` keeps it.
 * The SDK's own requests are never recorded. Has no effect when
 * `captureNetwork` is false or Traceway is not initialized.
 */
class TracewayOkHttpInterceptor : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val client = TracewayClient.instance ?: return chain.proceed(original)
        val handle = try {
            client.startRequest(original.method, original.url.toString(), original.header("traceparent") != null)
        } catch (_: Throwable) {
            null
        }
        val request = handle?.traceparent?.let { original.newBuilder().header("traceparent", it).build() } ?: original
        val requestBytes = try {
            request.body?.contentLength()?.takeIf { it >= 0 }
        } catch (_: Throwable) {
            null
        }
        return try {
            val response = chain.proceed(request)
            if (handle != null) {
                val responseBytes = response.body?.contentLength()?.takeIf { it >= 0 }
                    ?: response.header("Content-Length")?.toLongOrNull()
                safe { client.endRequest(handle, response.code, requestBytes, responseBytes, null) }
            }
            response
        } catch (e: IOException) {
            if (handle != null) safe { client.endRequest(handle, null, requestBytes, null, e.javaClass.simpleName) }
            throw e
        } catch (e: RuntimeException) {
            if (handle != null) safe { client.endRequest(handle, null, requestBytes, null, e.javaClass.simpleName) }
            throw e
        }
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // Never let telemetry break the app's networking.
        }
    }
}
