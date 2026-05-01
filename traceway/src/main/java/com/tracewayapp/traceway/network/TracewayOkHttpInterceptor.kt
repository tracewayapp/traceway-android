package com.tracewayapp.traceway.network

import com.tracewayapp.traceway.TracewayClient
import com.tracewayapp.traceway.events.NetworkEvent
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * OkHttp [Interceptor] that records every HTTP call as a [NetworkEvent] on
 * the Traceway timeline. Add as an *application* interceptor:
 *
 * ```kotlin
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(TracewayOkHttpInterceptor())
 *     .build()
 * ```
 *
 * Has no effect when [com.tracewayapp.traceway.TracewayOptions.captureNetwork]
 * is false. The interceptor never alters the request or response.
 */
class TracewayOkHttpInterceptor : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val start = System.currentTimeMillis()
        val method = request.method
        val url = request.url.toString()
        val requestBytes = request.body?.contentLength()?.takeIf { it >= 0 }

        return try {
            val response = chain.proceed(request)
            val responseBytes = response.body?.contentLength()?.takeIf { it >= 0 }
            emit(
                method = method,
                url = url,
                durationMs = System.currentTimeMillis() - start,
                statusCode = response.code,
                requestBytes = requestBytes,
                responseBytes = responseBytes,
                error = null,
            )
            response
        } catch (e: IOException) {
            emit(
                method = method,
                url = url,
                durationMs = System.currentTimeMillis() - start,
                statusCode = null,
                requestBytes = requestBytes,
                responseBytes = null,
                error = e.toString(),
            )
            throw e
        }
    }

    private fun emit(
        method: String,
        url: String,
        durationMs: Long,
        statusCode: Int?,
        requestBytes: Long?,
        responseBytes: Long?,
        error: String?,
    ) {
        try {
            TracewayClient.instance?.recordNetworkEvent(
                NetworkEvent(
                    method = method.uppercase(),
                    url = url,
                    durationMs = durationMs,
                    statusCode = statusCode,
                    requestBytes = requestBytes,
                    responseBytes = responseBytes,
                    error = error,
                )
            )
        } catch (_: Throwable) {
            // Never let event recording break the host app's networking.
        }
    }
}
