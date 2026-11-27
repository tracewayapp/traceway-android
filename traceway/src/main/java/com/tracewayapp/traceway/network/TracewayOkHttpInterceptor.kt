package com.tracewayapp.traceway.network

import com.tracewayapp.traceway.TracewayClient
import com.tracewayapp.traceway.guarded
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
    /**
     * The app's call goes through unchanged whatever happens in the SDK: the
     * response it gets is the one the chain returned, and the exception it
     * gets is the one the chain threw, the same object.
     */
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val client = TracewayClient.instance ?: return chain.proceed(original)
        val handle = guarded("okhttp start", null) {
            client.startRequest(original.method, original.url.toString(), original.header("traceparent") != null)
        }
        val request = guarded("okhttp traceparent", original) {
            handle?.traceparent?.let { original.newBuilder().header("traceparent", it).build() } ?: original
        }
        val requestBytes = guarded("okhttp request size", null) { request.body?.contentLength()?.takeIf { it >= 0 } }
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (handle != null) guarded("okhttp end") { client.endRequest(handle, null, requestBytes, null, e.javaClass.simpleName) }
            throw e
        } catch (e: RuntimeException) {
            if (handle != null) guarded("okhttp end") { client.endRequest(handle, null, requestBytes, null, e.javaClass.simpleName) }
            throw e
        }
        // Reading the response for the span is the SDK's business: a failure there never fails the app's call.
        if (handle != null) {
            guarded("okhttp end") {
                val responseBytes = response.body?.contentLength()?.takeIf { it >= 0 }
                    ?: response.header("Content-Length")?.toLongOrNull()
                client.endRequest(handle, response.code, requestBytes, responseBytes, null)
            }
        }
        return response
    }
}
