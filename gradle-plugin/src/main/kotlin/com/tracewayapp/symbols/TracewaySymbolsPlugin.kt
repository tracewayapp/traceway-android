package com.tracewayapp.symbols

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.BuildConfigField
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

class TracewaySymbolsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("traceway", TracewayExtension::class.java)
        ext.autoUpload.convention(false)

        val components = project.extensions.findByType(AndroidComponentsExtension::class.java)
            ?: throw GradleException("com.tracewayapp.symbols must be applied after an Android application/library plugin")

        components.onVariants { variant ->
            val cap = variant.name.replaceFirstChar { it.uppercase() }
            val versionSeed = runCatching {
                (variant as? ApplicationVariant)?.outputs?.firstOrNull()
                    ?.let { "${it.versionName.orNull}:${it.versionCode.orNull}" }
            }.getOrNull() ?: project.version.toString()
            val uuid = ext.proguardUuid.orNull?.takeIf { it.isNotBlank() }
                ?: TracewaySymbols.deriveProguardUuid("${project.path}:${variant.name}:$versionSeed")

            variant.buildConfigFields.put(
                "TRACEWAY_PROGUARD_UUID",
                BuildConfigField("String", "\"$uuid\"", "Traceway ProGuard UUID"),
            )

            val task = project.tasks.register("upload${cap}TracewaySymbols", UploadSymbolsTask::class.java) { t ->
                t.uploadToken.set(ext.uploadToken)
                t.url.set(ext.url)
                t.proguardUuid.set(uuid)
                t.mappingFile.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
            }

            if (ext.autoUpload.getOrElse(false)) {
                project.tasks.matching { it.name == "assemble$cap" }.configureEach { it.finalizedBy(task) }
            }
        }
    }
}
