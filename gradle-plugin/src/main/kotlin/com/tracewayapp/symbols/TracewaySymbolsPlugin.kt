package com.tracewayapp.symbols

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.BuildConfigField
import com.android.build.api.variant.CanMinifyCode
import com.android.build.api.variant.Variant
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File
import java.util.Collections

class TracewaySymbolsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("traceway", TracewayExtension::class.java)
        ext.autoUpload.convention(false)
        ext.url.convention(TracewaySymbols.DEFAULT_URL)

        // Whichever Android plugin comes, before or after this one.
        var configured = false
        for (id in listOf("com.android.application", "com.android.library")) {
            project.pluginManager.withPlugin(id) {
                if (!configured) {
                    configured = true
                    configure(project, ext)
                }
            }
        }
        project.afterEvaluate {
            if (!configured) {
                throw GradleException("com.tracewayapp.symbols needs an Android app or library module: apply com.android.application or com.android.library too")
            }
        }
    }

    /** What the finalized DSL says, read before the variants are made. */
    private class Dsl {
        var buildConfig = false
        val defaultRules = ArrayList<File>()
        val buildTypeRules = HashMap<String, List<File>>()
        val flavorRules = HashMap<String, List<File>>()
    }

    private fun configure(project: Project, ext: TracewayExtension) {
        val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
        val dsl = Dsl()
        components.finalizeDsl { android ->
            val common = android as? CommonExtension<*, *, *, *, *> ?: return@finalizeDsl
            dsl.buildConfig = common.buildFeatures.buildConfig
                ?: project.findProperty("android.defaults.buildfeatures.buildconfig")?.toString()?.toBoolean()
                ?: false
            dsl.defaultRules.addAll(common.defaultConfig.proguardFiles)
            for (type in common.buildTypes) dsl.buildTypeRules[type.name] = ArrayList(type.proguardFiles)
            for (flavor in common.productFlavors) dsl.flavorRules[flavor.name] = ArrayList(flavor.proguardFiles)
        }

        components.onVariants { variant ->
            val cap = variant.name.replaceFirstChar { it.uppercase() }
            val versionSeed = runCatching {
                (variant as? ApplicationVariant)?.outputs?.firstOrNull()
                    ?.let { "${it.versionName.orNull}:${it.versionCode.orNull}" }
            }.getOrNull() ?: project.version.toString()
            val uuid = ext.proguardUuid.orNull?.takeIf { it.isNotBlank() }
                ?: TracewaySymbols.deriveProguardUuid("${project.path}:${variant.name}:$versionSeed")

            // The SDK reads the UUID from the app's assets, so applying the plugin is enough; BuildConfig has it too when
            // the module builds one (asking for a field of a disabled BuildConfig only makes AGP warn).
            if (variant is ApplicationVariant) {
                val write = project.tasks.register("write${cap}TracewayProguardUuid", WriteProguardUuidTask::class.java) { t ->
                    t.proguardUuid.set(uuid)
                    t.outputDir.set(project.layout.buildDirectory.dir("generated/traceway/${variant.name}/assets"))
                }
                variant.sources.assets?.addGeneratedSourceDirectory(write, WriteProguardUuidTask::outputDir)
            }
            if (dsl.buildConfig) {
                variant.buildConfigFields.put(
                    "TRACEWAY_PROGUARD_UUID",
                    BuildConfigField("String", "\"$uuid\"", "Traceway ProGuard UUID"),
                )
            }

            val task = project.tasks.register("upload${cap}TracewaySymbols", UploadSymbolsTask::class.java) { t ->
                t.uploadToken.set(ext.uploadToken)
                t.url.set(ext.url)
                t.proguardUuid.set(uuid)
                t.mappingFile.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
            }

            if (ext.autoUpload.getOrElse(false)) {
                project.tasks.matching { it.name == "assemble$cap" }.configureEach { it.finalizedBy(task) }
            }

            val logcat = ext.logcat
            val off = logcat.disabledVariants.getOrElse(emptySet())
            if (logcat.enabled.getOrElse(true) && variant.name !in off && variant.buildType !in off) {
                registerLogcat(project, variant, logcat)
                if ((variant as? CanMinifyCode)?.isMinifyEnabled == true) {
                    val rules = ArrayList(dsl.defaultRules)
                    variant.buildType?.let { rules.addAll(dsl.buildTypeRules[it].orEmpty()) }
                    for ((_, flavor) in variant.productFlavors) rules.addAll(dsl.flavorRules[flavor].orEmpty())
                    registerStripMirror(project, variant, cap, rules)
                }
            }
        }
    }

    private companion object {
        /** AGP's task that writes the files `getDefaultProguardFile(...)` names. */
        const val EXTRACT_PROGUARD_FILES = "extractProguardFiles"
    }

    /** The variants warned of an SDK too old for the rewrite, so the warning comes once per variant. */
    private val warned = Collections.synchronizedSet(HashSet<String>())

    /**
     * Rewrites the variant's `android.util.Log` calls to `TracewayLogcat`:
     * the app's classes and, for an app, its dependencies' unless
     * `includeDependencies` is off (a library's own classes only: its
     * dependencies are its consumers' to rewrite). Only when the variant's
     * runtime classpath holds the Traceway SDK 2.1.0 or newer, which has
     * `TracewayLogcat`: with an older one a rewritten call would crash the
     * app (`NoClassDefFoundError`), so nothing is rewritten and the build
     * warns once.
     */
    private fun registerLogcat(project: Project, variant: Variant, logcat: LogcatExtension) {
        val level = logcat.minLevel.getOrElse("info")
        val minPriority = LogcatRewrite.priorityOf(level)
            ?: throw GradleException("traceway.logcat.minLevel must be verbose, debug, info, warn, error or assert, not \"$level\"")
        val name = "${project.path}:${variant.name}"
        val sdk = variant.runtimeConfiguration.incoming.resolutionResult.rootComponent.map { root ->
            val found = TracewaySdk.find(root)
            if (found != null && !found.hasLogcatBridge && warned.add(name)) {
                project.logger.warn(
                    "Traceway logcat capture needs com.tracewayapp:traceway ${TracewaySdk.LOGCAT_SINCE} or newer " +
                        "(found ${found.version}); android.util.Log calls in ${variant.name} are left as they are.",
                )
            }
            found?.hasLogcatBridge == true
        }
        val all = variant is ApplicationVariant && logcat.includeDependencies.getOrElse(true)
        val scope = if (all) InstrumentationScope.ALL else InstrumentationScope.PROJECT
        variant.instrumentation.transformClassesWith(LogcatClassVisitorFactory::class.java, scope) { params ->
            params.minPriority.set(minPriority)
            params.sdkOnClasspath.set(sdk)
        }
    }

    /**
     * When the variant's own R8 rules strip `android.util.Log` calls
     * (`-assumenosideeffects class android.util.Log { … }`), adds the same
     * rule for `TracewayLogcat`: the rewritten calls go too, as the app
     * meant, and their lines are not recorded.
     */
    private fun registerStripMirror(project: Project, variant: Variant, cap: String, rules: List<File>) {
        val mirror = project.tasks.register("generate${cap}TracewayLogcatRules", MirrorLogStripRulesTask::class.java) { t ->
            t.rules.from(rules)
            // getDefaultProguardFile(...), the rules of Android Studio's template, is a file AGP's extractProguardFiles
            // writes: read after it, or Gradle refuses the build (it reads another task's output without a dependency).
            t.dependsOn(project.provider { listOfNotNull(project.tasks.findByName(EXTRACT_PROGUARD_FILES)) })
            t.output.set(project.layout.buildDirectory.file("intermediates/traceway/${variant.name}/traceway-logcat-rules.pro"))
        }
        variant.proguardFiles.add(mirror.flatMap { it.output })
    }
}
