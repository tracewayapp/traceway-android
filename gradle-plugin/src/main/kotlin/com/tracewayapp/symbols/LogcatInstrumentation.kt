package com.tracewayapp.symbols

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.objectweb.asm.ClassVisitor

/** What the `logcat` rewrite of a variant needs to know. */
interface LogcatParameters : InstrumentationParameters {
    /** `logcat.minLevel` as a `Log` priority. */
    @get:Input
    val minPriority: Property<Int>

    /**
     * Whether the variant's runtime classpath holds the Traceway SDK 2.1.0
     * or newer, and so `TracewayLogcat`: without it a rewritten call would
     * fail (`NoClassDefFoundError`), so nothing is rewritten.
     */
    @get:Input
    val sdkOnClasspath: Property<Boolean>
}

/** AGP's side of [LogcatRewrite]: registered for every variant with `logcat.enabled`. */
abstract class LogcatClassVisitorFactory : AsmClassVisitorFactory<LogcatParameters> {
    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor {
        val params = parameters.get()
        if (!params.sdkOnClasspath.get()) return nextClassVisitor
        return LogcatRewrite.Visitor(instrumentationContext.apiVersion.get(), nextClassVisitor, params.minPriority.get())
    }

    override fun isInstrumentable(classData: ClassData): Boolean =
        parameters.get().sdkOnClasspath.get() && LogcatRewrite.isInstrumentable(classData.className)
}

internal object TracewaySdk {
    /** The first SDK version with `TracewayLogcat`; its prereleases have it too. */
    const val LOGCAT_SINCE = "2.1.0"

    /** The Traceway SDK on a classpath: its version, or null for the SDK as a project of the build. */
    class Found(val version: String?) {
        /** Whether it has `TracewayLogcat`: a project (the in-repo SDK), or 2.1.0 or newer, prereleases of 2.1.0 included. */
        val hasLogcatBridge: Boolean get() = version == null || atLeast(version, LOGCAT_SINCE)
    }

    /** The Traceway SDK in the resolved graph under [root] (`com.tracewayapp:traceway`, or a project of that name), or null. */
    fun find(root: ResolvedComponentResult): Found? {
        val seen = HashSet<Any>()
        val queue = ArrayDeque<ResolvedComponentResult>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c.id)) continue
            if (c !== root) sdk(c)?.let { return it }
            for (d in c.dependencies) if (d is ResolvedDependencyResult) queue.add(d.selected)
        }
        return null
    }

    /** Whether the resolved graph under [root] holds an SDK with `TracewayLogcat`. */
    fun isIn(root: ResolvedComponentResult): Boolean = find(root)?.hasLogcatBridge == true

    private fun sdk(c: ResolvedComponentResult): Found? = when (val id = c.id) {
        is ModuleComponentIdentifier -> if (id.group == "com.tracewayapp" && id.module == "traceway") Found(id.version) else null
        is ProjectComponentIdentifier -> if (c.moduleVersion?.name == "traceway") Found(null) else null
        else -> null
    }

    /**
     * Whether [version] is at least [minimum], comparing the numbers before
     * any `-` (so `2.1.0-beta.1` counts as 2.1.0). A version that does not
     * start with a number is not.
     */
    fun atLeast(version: String, minimum: String): Boolean {
        fun parts(v: String): List<Int>? {
            val numbers = v.trim().substringBefore('-').substringBefore('+').split('.')
            if (numbers.isEmpty() || numbers[0].toIntOrNull() == null) return null
            return numbers.map { it.toIntOrNull() ?: 0 }
        }
        val a = parts(version) ?: return false
        val b = parts(minimum) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return true
    }
}
