package com.tracewayapp.symbols

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import javax.inject.Inject

abstract class TracewayExtension @Inject constructor(objects: ObjectFactory) {
    abstract val uploadToken: Property<String>

    /** The Traceway server mappings are uploaded to (default `https://cloud.tracewayapp.com`). */
    abstract val url: Property<String>

    abstract val autoUpload: Property<Boolean>

    abstract val proguardUuid: Property<String>

    /**
     * Logcat capture: calls to `android.util.Log` in the app and its
     * dependencies are rewritten to the SDK's `TracewayLogcat`, which logs
     * as before and records the line.
     *
     * ```kotlin
     * traceway { logcat { enabled = true; minLevel = "info" } }
     * ```
     *
     * Needs the SDK 2.1.0 or newer on the variant's runtime classpath; with
     * an older one the plugin leaves `Log` calls alone and the build warns.
     */
    val logcat: LogcatExtension = objects.newInstance(LogcatExtension::class.java)

    fun logcat(action: Action<in LogcatExtension>) = action.execute(logcat)
}

abstract class LogcatExtension {
    /**
     * Rewrite the `Log` calls (default `true`). Nothing is rewritten when
     * the SDK (2.1.0 or newer) is not on the runtime classpath.
     */
    abstract val enabled: Property<Boolean>

    /**
     * Rewrite the calls in the app's dependencies too (default `true`), so
     * Timber, which lives in one, is covered. False rewrites the module's
     * own classes only, which also keeps the first build quicker.
     */
    abstract val includeDependencies: Property<Boolean>

    /** Variants and build types left as they are, set with [disabledFor]. */
    abstract val disabledVariants: SetProperty<String>

    /** Leaves the `Log` calls of these variants or build types alone: `disabledFor("release")`. */
    fun disabledFor(vararg names: String) {
        disabledVariants.addAll(names.toList())
    }

    /**
     * The lowest level rewritten (default `"info"`): `verbose`, `debug`,
     * `info`, `warn`, `error` or `assert`. Calls below it stay plain `Log`
     * calls; the collection settings' minimum severity filters the rest.
     */
    abstract val minLevel: Property<String>

    init {
        enabled.convention(true)
        includeDependencies.convention(true)
        disabledVariants.convention(emptySet())
        minLevel.convention("info")
    }
}
