package com.tracewayapp.traceway

import android.app.Application
import android.util.Log
import android.view.View

/**
 * The Traceway public facade.
 *
 * Call [init] from `Application.onCreate()`:
 *
 * ```kotlin
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         Traceway.init(
 *             application = this,
 *             connectionString = "your-token@https://your-traceway-instance.com",
 *             options = TracewayOptions(version = BuildConfig.VERSION_NAME, screenCapture = true),
 *         )
 *     }
 * }
 * ```
 *
 * After init, uncaught exceptions on every thread are reported with the
 * breadcrumbs that led to them (logs, HTTP calls, screens, taps, custom
 * events) and a wireframe recording of the last seconds on screen. What is
 * collected follows the project's Data Collection settings, polled over
 * OpAMP. Every method is a no-op before [init].
 */
object Traceway {
    private const val TAG = "Traceway"

    /**
     * Starts Traceway for [application]. Idempotent: the first call wins and
     * later calls return the running client.
     *
     * [connectionString] is the project token and the server URL, as the
     * project's Connection page shows it (`token@https://host`, or the 1.x
     * form ending in `/api/report`). Pass null to run standalone, sending
     * everything to [TracewayOptions.telemetry].
     *
     * An invalid connection string or telemetry setting does not take the
     * app down: it is logged, and the app runs without the SDK (null).
     */
    @JvmStatic
    @JvmOverloads
    fun init(
        application: Application,
        connectionString: String?,
        options: TracewayOptions = TracewayOptions(),
    ): TracewayClient? = try {
        TracewayClient.initialize(application, connectionString, options)
    } catch (e: IllegalArgumentException) {
        Log.e(TAG, "Traceway is not running: ${e.message}")
        null
    }

    /** Starts Traceway standalone: no Traceway server, everything goes to [TracewayOptions.telemetry]. */
    @JvmStatic
    fun init(application: Application, options: TracewayOptions): TracewayClient? = init(application, null, options)

    /** The running client, or null. */
    @JvmStatic
    val client: TracewayClient?
        get() = TracewayClient.instance

    /** The current session's id (`session.id` on every record), or null before the SDK started. */
    @JvmStatic
    val currentSessionId: String?
        get() = client?.currentSessionId

    /**
     * Reports a caught exception, with optional attributes and the screen it
     * happened on. [TracewayOptions.ignoreErrors] and
     * [TracewayOptions.beforeCapture] can drop it.
     */
    @JvmStatic
    @JvmOverloads
    fun captureException(error: Throwable, attributes: Map<String, String>? = null) {
        client?.captureException(error, attributes)
    }

    /** Reports a free-form message as an issue, filtered like [captureException]. */
    @JvmStatic
    @JvmOverloads
    fun captureMessage(message: String, attributes: Map<String, String>? = null) {
        client?.captureMessage(message, attributes)
    }

    /** Records a custom breadcrumb, shown on the session timeline. */
    @JvmStatic
    @JvmOverloads
    fun recordAction(category: String, name: String, data: Map<String, Any?>? = null) {
        client?.recordAction(category, name, data)
    }

    /** Emits a named product event (`checkout_failed`); triggers can match it by name. */
    @JvmStatic
    @JvmOverloads
    fun track(eventName: String, attributes: Map<String, Any>? = null) {
        client?.track(eventName, attributes)
    }

    /** An attribute on every later record, also usable to target this user or device from Data Collection rules. */
    @JvmStatic
    fun setAttribute(key: String, value: String) {
        client?.setAttribute(key, value)
    }

    @JvmStatic
    fun setAttributes(attributes: Map<String, String>) {
        client?.setAttributes(attributes)
    }

    @JvmStatic
    fun removeAttribute(key: String) {
        client?.removeAttribute(key)
    }

    @JvmStatic
    fun clearAttributes() {
        client?.clearAttributes()
    }

    /**
     * Reports the screen the user arrived at, as a path (`/checkout`), and
     * how ([action]: `push`, `pop`, `replace`, or `remove` for a screen taken
     * away without arriving anywhere), from the screen [from]. [route] is the
     * screen's route template (`/orders/{id}`) when the router knows it, so
     * metrics group by it and issues carry it, as with [setRoute]. Activities
     * and AndroidX fragments are reported automatically; call this from
     * Compose Navigation or another router.
     */
    @JvmStatic
    @JvmOverloads
    fun recordNavigation(path: String, action: String = "push", from: String? = null, route: String? = null) {
        client?.recordNavigation(path, action, from, route)
    }

    /**
     * Names the current screen by its route template (`/orders/:id`): issues
     * and navigations carry it as `app.screen.route`, and metrics group by
     * it. Lasts until the next screen; null removes it.
     */
    @JvmStatic
    fun setRoute(template: String?) {
        client?.setRoute(template)
    }

    /**
     * Records a log line at [level] (from a Timber tree, say), shown on the
     * session timeline; the console's minimum severity in the collection
     * settings filters it.
     */
    @JvmStatic
    @JvmOverloads
    fun log(message: String, level: TracewayLogLevel = TracewayLogLevel.INFO, attributes: Map<String, String>? = null) {
        client?.log(message, level, attributes)
    }

    /** Records a log line. [severity]: trace, debug, info, warn, error, fatal. */
    @Deprecated(
        "Use log(message, level), with a TracewayLogLevel.",
        ReplaceWith("Traceway.log(message, TracewayLogLevel.INFO)", "com.tracewayapp.traceway.TracewayLogLevel"),
    )
    @JvmStatic
    @JvmOverloads
    fun recordLog(message: String, severity: String = "info") {
        client?.log(message, TracewayLogLevel.of(severity))
    }

    /**
     * Starts an interaction named [name] (`checkout`): a span that the
     * requests made inside its [TracewayInteraction.run] are children of,
     * and that exceptions and logs recorded there point to. End it with
     * [TracewayInteraction.end]. Before [init] it records nothing.
     */
    @JvmStatic
    @JvmOverloads
    fun startInteraction(name: String, attributes: Map<String, Any>? = null): TracewayInteraction =
        client?.startInteraction(name, attributes) ?: TracewayInteraction(null) { _, _ -> }

    /** The screen on top shows everything it loads: ends its time to full display (`app.screen.ttfd`). */
    @JvmStatic
    fun reportFullyDisplayed() {
        client?.reportFullyDisplayed()
    }

    /** Observes [value] of an app-defined distribution, e.g. `checkout.duration`, charted like the built-in metrics. */
    @JvmStatic
    @JvmOverloads
    fun metric(name: String, value: Double, dimensions: Map<String, Any>? = null) {
        client?.recordMetric(name, value, dimensions)
    }

    /** Adds [n] to an app-defined counter. */
    @JvmStatic
    @JvmOverloads
    fun count(name: String, n: Double = 1.0, dimensions: Map<String, Any>? = null) {
        client?.recordCount(name, n, dimensions)
    }

    /** An app-defined dimension on every metric from now on (`custom.<key>`); null removes it. */
    @JvmStatic
    fun setMetricDimension(key: String, value: String?) {
        client?.setMetricDimension(key, value)
    }

    /**
     * Masks [view] and everything inside it in wireframe recordings and tap
     * records, as [mode] says. The same as setting
     * [android.view.View.tracewayMask]; this form is for Java, where an
     * extension property reads poorly.
     */
    @JvmStatic
    @JvmOverloads
    fun mask(view: View, mode: TracewayMaskMode = TracewayMaskMode.blur()) {
        TracewayMasks.set(view, mode)
    }

    /** Removes the mask [mask] set on [view]; a mask tag from its layout still applies. */
    @JvmStatic
    fun unmask(view: View) {
        TracewayMasks.set(view, null)
    }

    /** The id this installation reports (`service.instance.id`), to target it from the dashboard. */
    @JvmStatic
    fun getInstanceId(): String? = client?.getInstanceId()

    /** Asks the server for collection settings now. */
    @JvmStatic
    fun refreshConfig() {
        client?.refreshConfig()
    }

    /** Sends what is ready now, waiting up to [timeoutMs]. Blocks: call it off the main thread. */
    @JvmStatic
    @JvmOverloads
    fun flush(timeoutMs: Long = 10_000) {
        client?.flush(timeoutMs)
    }

    /** Flushes and stops Traceway. Blocks: call it off the main thread. */
    @JvmStatic
    @JvmOverloads
    fun shutdown(timeoutMs: Long = 10_000) {
        client?.shutdown(timeoutMs)
    }
}
