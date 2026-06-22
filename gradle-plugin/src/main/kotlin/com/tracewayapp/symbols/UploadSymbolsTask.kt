package com.tracewayapp.symbols

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

abstract class UploadSymbolsTask : DefaultTask() {

    @get:Internal
    abstract val uploadToken: Property<String>

    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val proguardUuid: Property<String>

    @get:InputFile
    @get:Optional
    abstract val mappingFile: RegularFileProperty

    init {
        group = "traceway"
        description = "Upload R8 mapping to Traceway"
    }

    @TaskAction
    fun run() {
        val token = uploadToken.orNull?.takeIf { it.isNotBlank() }
            ?: throw GradleException("traceway.uploadToken (the project source-map upload token, not the DSN token) is required to upload symbols")
        val url = url.orNull?.takeIf { it.isNotBlank() }
            ?: throw GradleException("traceway.url is required to upload symbols")
        val endpoint = TracewaySymbols.uploadEndpoint(url)

        var uploaded = 0

        val mapping = mappingFile.orNull?.asFile
        if (mapping != null && mapping.isFile) {
            val result = TracewaySymbols.upload(
                endpoint, token, listOf(
                    TracewaySymbols.textPart("proguard_uuid", proguardUuid.get()),
                    TracewaySymbols.filePart("files", mapping, "text/plain"),
                ),
            )
            if (result.status == 401 || result.status == 403) {
                throw GradleException("mapping upload rejected (${result.status}): check that traceway.uploadToken is the project source-map upload token, not the DSN/client token. ${result.body}")
            }
            if (result.status !in 200..299) {
                throw GradleException("mapping upload failed (${result.status}): ${result.body}")
            }
            logger.lifecycle("Traceway: uploaded mapping ${mapping.name} as ${proguardUuid.get()}.txt")
            uploaded++
        } else {
            logger.warn("Traceway: no mapping.txt found for ${proguardUuid.get()} (is minifyEnabled on for this build type?), skipping symbol upload")
        }

        logger.lifecycle("Traceway: uploaded $uploaded symbol artifact(s)")
    }
}
