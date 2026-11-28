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
    private val context: Context? = span?.let { guarded("interaction", null) { Context.current().with(it).with(KEY, this) } }

    @Volatile
    private var failed = false

    @Volatile
    private var ended = false

    /** The trace this interaction starts (32 hex digits), or null when it records nothing. */
    val traceId: String? get() = guarded("interaction.traceId", null) { span?.spanContext?.traceId }

    /** The interaction's own span id (16 hex digits), or null. */
    val spanId: String? get() = guarded("interaction.spanId", null) { span?.spanContext?.spanId }

    /** Whether [end] was called. */
    val isEnded: Boolean get() = ended

    /**
     * Runs [block] as part of this interaction: requests it makes on this
     * thread are child spans of it, and exceptions, messages and logs it
     * records carry its trace. An interaction started inside is its child.
     * What [block] throws reaches the caller unchanged; a failure of the SDK
     * only runs it outside the interaction.
     */
    fun <T> run(block: () -> T): T {
        val c = context ?: return block()
        val scope = guarded("interaction.run", null) { c.makeCurrent() } ?: return block()
        try {
            return block()
        } finally {
            guarded("interaction.run") { scope.close() }
        }
    }

    /**
     * Makes this interaction current on the calling thread until the
     * returned scope is closed, as [run] does for its block: the form for
     * Java's try-with-resources, where a lambda cannot throw checked
     * exceptions.
     *
     * ```java
     * try (TracewayScope ignored = checkout.makeCurrent()) {
     *     api.placeOrder(cart);
     * }
     * ```
     *
     * Close it on the same thread, before the scope that was current when it
     * was made. An interaction that records nothing returns a scope that
     * does nothing.
     */
    fun makeCurrent(): TracewayScope {
        val c = context ?: return TracewayScope(null)
        return TracewayScope(guarded("interaction.makeCurrent", null) { c.makeCurrent() })
    }

    /** Sets an attribute on the interaction's span: a String, Boolean, whole number or decimal; anything else as text. */
    fun setAttribute(key: String?, value: Any?) {
        val s = span ?: return
        if (ended || key.isNullOrEmpty() || value == null) return
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
    fun captureException(error: Throwable?, attributes: Map<String, String>? = null) {
        if (error == null) return
        failed = true
        guarded("interaction.captureException") { run { capture(error, attributes) } }
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
        val current: TracewayInteraction? get() = guarded("interaction.current", null) { Context.current().get(KEY) }

        /** The OpenTelemetry context of [current], for spans and records made inside it. */
        fun currentContext(): Context? = current?.context
    }
}

/** The time an interaction is current, from [TracewayInteraction.makeCurrent] until [close]. */
class TracewayScope internal constructor(private val scope: io.opentelemetry.context.Scope?) : AutoCloseable {
    @Volatile
    private var closed = false

    /** Ends the scope; only the first call counts. */
    override fun close() {
        if (closed) return
        closed = true
        guarded("interaction.scope") { scope?.close() }
    }
}
