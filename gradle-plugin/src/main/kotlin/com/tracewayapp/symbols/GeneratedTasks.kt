package com.tracewayapp.symbols

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Writes the build's ProGuard UUID to `traceway/proguard-uuid.txt` in a
 * generated assets directory of the variant, where the SDK reads it when
 * the app passes none: applying the plugin is enough.
 */
@CacheableTask
abstract class WriteProguardUuidTask : DefaultTask() {
    @get:Input
    abstract val proguardUuid: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val file = File(outputDir.get().asFile, ASSET)
        file.parentFile.mkdirs()
        file.writeText(proguardUuid.get() + "\n", Charsets.UTF_8)
    }

    companion object {
        /** Where the SDK looks (`TracewayClient.PROGUARD_UUID_ASSET`). */
        const val ASSET = "traceway/proguard-uuid.txt"
    }
}

/**
 * Writes the R8 rule that strips `TracewayLogcat` calls when the variant's
 * own rules strip `android.util.Log` calls ([LogcatRewrite.mirrorStripRules]),
 * else a file with a comment only.
 */
@CacheableTask
abstract class MirrorLogStripRulesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rules: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun run() {
        val texts = rules.files.filter { it.isFile }.map { it.readText(Charsets.UTF_8) }
        val mirrored = LogcatRewrite.mirrorStripRules(texts)
        if (mirrored != null) logger.info("Traceway: mirrored your Log stripping rule for TracewayLogcat")
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText(mirrored ?: "# Traceway: your rules do not strip android.util.Log calls; nothing to mirror.\n", Charsets.UTF_8)
    }
}
