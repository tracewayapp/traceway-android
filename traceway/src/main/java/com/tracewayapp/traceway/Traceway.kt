package com.tracewayapp.traceway

import android.app.Application
import android.util.Log
import android.view.View
import com.tracewayapp.traceway.internal.ProcessName
import com.tracewayapp.traceway.internal.parseConnectionString
import io.opentelemetry.api.trace.Tracer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Traceway public facade.
 *
 * Call [init] from `Application.onCreate()`:
 *
 * ```kotlin
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         Traceway.init(this, TracewayOptions(token = "YOUR_TOKEN", screenCapture = true))
 *     }
 * }
 * ```
 *
 * The token is the project's, from its Connection page; a self-hosted
 * server is `baseUrl = "https://traceway.mycompany.com"` (Traceway Cloud,
 * `https://cloud.tracewayapp.com`, by default).
 *
 * After init, uncaught exceptions on every thread are reported with the
 * breadcrumbs that led to them (logs, HTTP calls, screens, taps, custom
 * events) and a wireframe recording of the last seconds on screen. What is
 * collected follows the project's Data Collection settings, polled over
 * OpAMP. Every method is a no-op before [init], and none of them throws
 * into the app: a failure inside the SDK, a null from Java included, is
 * contained and the call does nothing.
 */
object Traceway {
    private const val TAG = "Traceway"

    /**
     * Starts Traceway for [application] with [options]: the project's
     * [TracewayOptions.token] and, for a self-hosted server, its
     * [TracewayOptions.baseUrl]. Idempotent: the first call wins and later
     * calls return the running client.
     *
     * Without a token it runs standalone, sending everything to
     * [TracewayOptions.telemetry]. It runs in the app's main process only,
     * unless [TracewayOptions.allProcesses].
     *
     * An invalid server URL or telemetry setting, no token and no telemetry,
     * or any failure while starting does not take the app down: it is
     * logged, and the app runs without the SDK (null). So does a null
     * [application].
     */
    @JvmStatic
    fun init(application: Application?, options: TracewayOptions?): TracewayClient? = start(application, options ?: TracewayOptions())

    /**
     * Starts Traceway from an old connection string,
     * `token@https://host[/api[/report]]`: the token and the server go into
     * [options] (any `/api` or `/api/report` is dropped, `https://` added
     * when there is no scheme), and one warning names the new form. A null
     * [connectionString] uses [options] as they are.
     */
    @Deprecated(
        "Pass the token in the options, Traceway.init(application, TracewayOptions(token = \"YOUR_TOKEN\")), " +
            "and a self-hosted server as TracewayOptions.baseUrl",
    )
    @JvmStatic
    @JvmOverloads
    fun init(
        application: Application?,
        connectionString: String?,
        options: TracewayOptions? = TracewayOptions(),
    ): TracewayClient? = try {
        val base = options ?: TracewayOptions()
        val resolved = if (connectionString == null) {
            base
        } else {
            val parsed = parseConnectionString(connectionString)
            warnConnectionString(parsed.baseUrl)
            base.copy(token = parsed.token, baseUrl = parsed.baseUrl)
        }
        start(application, resolved)
    } catch (e: Throwable) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        logNotRunning(e)
        null
    }

    private fun start(application: Application?, options: TracewayOptions): TracewayClient? = try {
        if (application == null) throw IllegalArgumentException("application is null")
        if (!options.allProcesses && !isMainProcess(application)) {
            if (options.debug) logDebug("Traceway runs in the app's main process only (this is ${processName(application)}); set allProcesses to run here")
            null
        } else {
            TracewayClient.initialize(application, options)
        }
    } catch (e: Throwable) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        logNotRunning(e)
        null
    }

    private val connectionStringWarned = AtomicBoolean(false)

    /** Once per process: the old start form works, and this is the new one. */
    private fun warnConnectionString(baseUrl: String) {
        if (!connectionStringWarned.compareAndSet(false, true)) return
        val server = if (baseUrl == TracewayOptions.DEFAULT_BASE_URL) "" else ", baseUrl = \"$baseUrl\""
        try {
            Log.w(
                TAG,
                "Traceway: connection strings are deprecated; start with " +
                    "Traceway.init(application, TracewayOptions(token = \"YOUR_TOKEN\"$server))",
            )
        } catch (_: Throwable) {
        }
    }

    private fun isMainProcess(application: Application): Boolean = ProcessName.isMain(application)

    private fun processName(application: Application): String? = ProcessName.of(application)

    /** Forgets the once-per-process warnings, for tests. */
    @androidx.annotation.VisibleForTesting
    internal fun resetWarningsForTest() {
        connectionStringWarned.set(false)
        notStartedWarned.set(false)
    }

    private fun logDebug(message: String) {
        try {
            Log.d(TAG, message)
        } catch (_: Throwable) {
        }
    }

    private val notStartedWarned = AtomicBoolean(false)

    /** Once per process, whatever `debug` says: a call that records nothing because the SDK was never started. */
    private fun notStarted(method: String) {
        if (notStartedWarned.get() || !notStartedWarned.compareAndSet(false, true)) return
        try {
            Log.w(
                TAG,
                "Traceway.$method was called before Traceway.init, so nothing is recorded. Call Traceway.init in " +
                    "Application.onCreate (is android:name set on <application> in AndroidManifest.xml?)",
            )
        } catch (_: Throwable) {
        }
    }

    /** The running client, or [notStarted] once when there is none (not after a shutdown: that was the app's choice). */
    private fun recording(method: String): TracewayClient? {
        val c = TracewayClient.instance
        if (c == null && !TracewayClient.everStarted) notStarted(method)
        return c
    }

    private fun logNotRunning(e: Throwable) {
        try {
            if (e is IllegalArgumentException) Log.w(TAG, "Traceway is not running: ${e.message}") else Log.e(TAG, "Traceway is not running: $e")
        } catch (_: Throwable) {
        }
    }

    /** The running client, or null. */
    @JvmStatic
    val client: TracewayClient?
        get() = guarded("client", null) { TracewayClient.instance }

    /** The current session's id (`session.id` on every record), or null before the SDK started. */
    @JvmStatic
    val currentSessionId: String?
        get() = guarded("currentSessionId", null) { client?.currentSessionId }

    /**
     * Traceway's OpenTelemetry tracer, for spans of your own (Java:
     * `Traceway.getTracer()`): they are sent with Traceway's other spans,
     * carry the session id, and are children of the interaction whose
     * [TracewayInteraction.run] they start in. Null before [init] (until the
     * SDK has started, just after it) and after [shutdown]. Traceway does
     * not register itself as the global OpenTelemetry: an app with its own
     * keeps it.
     *
     * ```kotlin
     * val span = Traceway.tracer?.spanBuilder("decode-image")?.startSpan()
     * try { decode() } finally { span?.end() }
     * ```
     */
    @JvmStatic
    val tracer: Tracer?
        get() = guarded("tracer", null) { client?.tracer }

    /**
     * Reports a caught exception, with optional attributes and the screen it
     * happened on. [TracewayOptions.ignoreErrors] and
     * [TracewayOptions.beforeCapture] can drop it.
     */
    @JvmStatic
    @JvmOverloads
    fun captureException(error: Throwable?, attributes: Map<String, String>? = null) {
        guarded("captureException") { recording("captureException")?.captureException(error, attributes) }
    }

    /** Reports a free-form message as an issue, filtered like [captureException]. */
    @JvmStatic
    @JvmOverloads
    fun captureMessage(message: String?, attributes: Map<String, String>? = null) {
        guarded("captureMessage") { recording("captureMessage")?.captureMessage(message, attributes) }
    }

    /** Records a custom breadcrumb, shown on the session timeline. */
    @JvmStatic
    @JvmOverloads
    fun recordAction(category: String?, name: String?, data: Map<String, Any?>? = null) {
        guarded("recordAction") { recording("recordAction")?.recordAction(category, name, data) }
    }

    /** Emits a named product event (`checkout_failed`); triggers can match it by name. */
    @JvmStatic
    @JvmOverloads
    fun track(eventName: String?, attributes: Map<String, Any>? = null) {
        guarded("track") { recording("track")?.track(eventName, attributes) }
    }

    /** An attribute on every later record, also usable to target this user or device from Data Collection rules. */
    @JvmStatic
    fun setAttribute(key: String?, value: String?) {
        guarded("setAttribute") { client?.setAttribute(key, value) }
    }

    @JvmStatic
    fun setAttributes(attributes: Map<String, String>?) {
        guarded("setAttributes") { client?.setAttributes(attributes) }
    }

    @JvmStatic
    fun removeAttribute(key: String?) {
        guarded("removeAttribute") { client?.removeAttribute(key) }
    }

    @JvmStatic
    fun clearAttributes() {
        guarded("clearAttributes") { client?.clearAttributes() }
    }

    /**
     * Reports the screen the user arrived at, as a path (`/checkout`), and
     * how ([action]: `push`, `pop`, `replace`, or `remove` for a screen taken
     * away without arriving anywhere), from the screen [from]. [route] is the
     * screen's route template (`/orders/:id`) when the router knows it, so
     * metrics group by it and issues carry it, as with [setRoute]. Activities
     * and AndroidX fragments are reported automatically; Compose Navigation
     * with `Traceway.trackNavigation(navController)`
     * ([com.tracewayapp.traceway.navigation.trackNavigation]); call this from
     * another router.
     */
    @JvmStatic
    @JvmOverloads
    fun recordNavigation(path: String?, action: String? = "push", from: String? = null, route: String? = null) {
        guarded("recordNavigation") { recording("recordNavigation")?.recordNavigation(path, action, from, route) }
    }

    /**
     * Names the current screen by its route template (`/orders/:id`): issues
     * and navigations carry it as `app.screen.route`, and metrics group by
     * it. Lasts until the next screen; null removes it.
     */
    @JvmStatic
    fun setRoute(template: String?) {
        guarded("setRoute") { client?.setRoute(template) }
    }

    /**
     * Records a log line at [level] (from a Timber tree, say), shown on the
     * session timeline; the console's minimum severity in the collection
     * settings filters it.
     */
    @JvmStatic
    @JvmOverloads
    fun log(message: String?, level: TracewayLogLevel? = TracewayLogLevel.INFO, attributes: Map<String, String>? = null) {
        guarded("log") { recording("log")?.log(message, level, attributes) }
    }

    /** Records a log line. [severity]: trace, debug, info, warn, error, fatal. */
    @Deprecated(
        "Use log(message, level), with a TracewayLogLevel.",
        ReplaceWith("Traceway.log(message, TracewayLogLevel.INFO)", "com.tracewayapp.traceway.TracewayLogLevel"),
    )
    @JvmStatic
    @JvmOverloads
    fun recordLog(message: String?, severity: String? = "info") {
        guarded("recordLog") { client?.log(message, TracewayLogLevel.of(severity ?: "info")) }
    }

    /**
     * Starts an interaction named [name] (`checkout`): a span that the
     * requests made inside its [TracewayInteraction.run] are children of,
     * and that exceptions and logs recorded there point to. End it with
     * [TracewayInteraction.end]. Before [init] it records nothing.
     */
    @JvmStatic
    @JvmOverloads
    fun startInteraction(name: String?, attributes: Map<String, Any>? = null): TracewayInteraction =
        guarded("startInteraction", null) { client?.startInteraction(name, attributes) } ?: TracewayInteraction(null) { _, _ -> }

    /** The screen on top shows everything it loads: ends its time to full display (`app.screen.ttfd`). */
    @JvmStatic
    fun reportFullyDisplayed() {
        guarded("reportFullyDisplayed") { client?.reportFullyDisplayed() }
    }

    /** Observes [value] of an app-defined distribution, e.g. `checkout.duration`, charted like the built-in metrics. */
    @JvmStatic
    @JvmOverloads
    fun metric(name: String?, value: Double, dimensions: Map<String, Any>? = null) {
        guarded("metric") { client?.recordMetric(name, value, dimensions) }
    }

    /** Adds [n] to an app-defined counter. */
    @JvmStatic
    @JvmOverloads
    fun count(name: String?, n: Double = 1.0, dimensions: Map<String, Any>? = null) {
        guarded("count") { client?.recordCount(name, n, dimensions) }
    }

    /** An app-defined dimension on every metric from now on (`custom.<key>`); null removes it. */
    @JvmStatic
    fun setMetricDimension(key: String?, value: String?) {
        guarded("setMetricDimension") { client?.setMetricDimension(key, value) }
    }

    /**
     * Masks [view] and everything inside it in wireframe recordings and tap
     * records, as [mode] says. The same as setting
     * [android.view.View.tracewayMask]; this form is for Java, where an
     * extension property reads poorly.
     */
    @JvmStatic
    @JvmOverloads
    fun mask(view: View?, mode: TracewayMaskMode? = TracewayMaskMode.blur()) {
        if (view == null) return
        guarded("mask") { TracewayMasks.set(view, mode ?: TracewayMaskMode.blur()) }
    }

    /** Removes the mask [mask] set on [view]; a mask tag from its layout still applies. */
    @JvmStatic
    fun unmask(view: View?) {
        if (view == null) return
        guarded("unmask") { TracewayMasks.set(view, null) }
    }

    /** The id this installation reports (`service.instance.id`), to target it from the dashboard. */
    @JvmStatic
    fun getInstanceId(): String? = guarded("getInstanceId", null) { client?.getInstanceId() }

    /** Asks the server for collection settings now. */
    @JvmStatic
    fun refreshConfig() {
        guarded("refreshConfig") { client?.refreshConfig() }
    }

    /**
     * Sends what is ready now, waiting up to [timeoutMs] (10 s). Blocks the
     * calling thread; on the main thread it waits 2 s at most, whatever
     * [timeoutMs] says (an ANR comes at 5 s), and what is not sent by then
     * goes later.
     */
    @JvmStatic
    @JvmOverloads
    fun flush(timeoutMs: Long = 10_000) {
        guarded("flush") { client?.flush(timeoutMs) }
    }

    /** Flushes (as [flush] does, 2 s at most on the main thread) and stops Traceway. Blocks: call it off the main thread. */
    @JvmStatic
    @JvmOverloads
    fun shutdown(timeoutMs: Long = 10_000) {
        guarded("shutdown") { client?.shutdown(timeoutMs) }
    }
}
