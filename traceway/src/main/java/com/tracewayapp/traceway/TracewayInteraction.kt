package com.tracewayapp.traceway

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey

/**
 * One user action traced from start to end, e.g. a checkout: a span named
 * after it (`traceway.interaction: true`) that the requests made in [run]
 * are children of, and that the exceptions, messages and logs recorded in
 * [run] point to. Start one with [Traceway.startInteraction] and [end] it
 * when the action is over.
 *
 * ```kotlin
 * val checkout = Traceway.startInteraction("checkout")
 * try {
 *     checkout.run { api.placeOrder(cart) }
 * } catch (e: IOException) {
 *     checkout.captureException(e)
 * } finally {
 *     checkout.end()
 * }
 * ```
 *
 * [run] makes the interaction current on the calling thread while its block
 * runs: a request made on another thread (an OkHttp `enqueue`, a coroutine
 * that moved) is not part of it. Before the SDK has started, or after it
 * shut down, an interaction records nothing; [run] still runs its block.
 * A failed interaction is sent at once, with the exception that failed it.
 */
class TracewayInteraction internal constructor(
    private val span: Span?,
    private val capture: (Throwable, Map<String, String>?) -> Unit,
) {
    private val context: Context? = span?.let { Context.current().with(it).with(KEY, this) }

    @Volatile
    private var failed = false

    @Volatile
    private var ended = false

    /** The trace this interaction starts (32 hex digits), or null when it records nothing. */
    val traceId: String? get() = span?.spanContext?.traceId

    /** The interaction's own span id (16 hex digits), or null. */
    val spanId: String? get() = span?.spanContext?.spanId

    /** Whether [end] was called. */
    val isEnded: Boolean get() = ended

    /**
     * Runs [block] as part of this interaction: requests it makes on this
     * thread are child spans of it, and exceptions, messages and logs it
     * records carry its trace. An interaction started inside is its child.
     */
    fun <T> run(block: () -> T): T {
        val c = context ?: return block()
        return c.makeCurrent().use { block() }
    }

    /** Sets an attribute on the interaction's span: a String, Boolean, whole number or decimal; anything else as text. */
    fun setAttribute(key: String, value: Any) {
        val s = span ?: return
        if (ended || key.isEmpty()) return
        try {
            when (value) {
                is String -> s.setAttribute(key, value)
                is Boolean -> s.setAttribute(key, value)
                is Int -> s.setAttribute(key, value.toLong())
                is Long -> s.setAttribute(key, value)
                is Float -> s.setAttribute(key, value.toDouble())
                is Double -> s.setAttribute(key, value)
                else -> s.setAttribute(key, value.toString())
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Reports [error] as an exception of this interaction and marks the
     * interaction failed: the exception carries its trace, and the span is
     * sent with it.
     */
    @JvmOverloads
    fun captureException(error: Throwable, attributes: Map<String, String>? = null) {
        failed = true
        run { capture(error, attributes) }
    }

    /** Ends the interaction; only the first call counts. [failed] marks it failed even without a [captureException]. */
    @JvmOverloads
    fun end(failed: Boolean = false) {
        if (ended) return
        ended = true
        val s = span ?: return
        try {
            s.setStatus(if (this.failed || failed) StatusCode.ERROR else StatusCode.OK)
            s.end()
        } catch (_: Throwable) {
            // Ending a span must never break the app.
        }
    }

    internal companion object {
        /** Where [run] keeps the interaction in the OpenTelemetry context. */
        val KEY: ContextKey<TracewayInteraction> = ContextKey.named("traceway.interaction")

        /** The interaction the code running now belongs to, if any. */
        val current: TracewayInteraction? get() = Context.current().get(KEY)

        /** The OpenTelemetry context of [current], for spans and records made inside it. */
        fun currentContext(): Context? = current?.context
    }
}
