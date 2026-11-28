package com.tracewayapp.symbols

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The plugin in a real Android build (Gradle TestKit, the Android Gradle
 * plugin the repo builds with): an app that minifies with R8 and the rules
 * of Android Studio's template, `getDefaultProguardFile(...)`. AGP's
 * `extractProguardFiles` writes that file, and the plugin's
 * `generate<Variant>TracewayLogcatRules` reads it: Gradle refuses the build
 * unless the plugin says it depends on the task that writes it (finding
 * android-001). Needs an Android SDK (`ANDROID_HOME`), as CI has.
 */
class MinifiedBuildTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun androidSdk(): File? =
        listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").firstNotNullOfOrNull { System.getenv(it)?.takeIf { p -> p.isNotBlank() } }
            ?.let(::File)?.takeIf { it.isDirectory }

    private fun write(dir: File, path: String, text: String) {
        File(dir, path).apply { parentFile.mkdirs() }.writeText(text.trimIndent() + "\n")
    }

    /** An app as Android Studio makes it, minified in release, with a rule that strips `Log.d`. */
    private fun app(sdk: File): File {
        val dir = tmp.newFolder("app")
        write(dir, "local.properties", "sdk.dir=${sdk.absolutePath.replace("\\", "\\\\")}")
        write(dir, "gradle.properties", "org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8\nandroid.useAndroidX=true")
        write(
            dir, "settings.gradle.kts",
            """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "minified"
            """,
        )
        write(
            dir, "build.gradle.kts",
            """
            plugins {
                id("com.android.application")
                id("com.tracewayapp.symbols")
            }

            android {
                namespace = "com.example.minified"
                compileSdk = 34
                defaultConfig {
                    applicationId = "com.example.minified"
                    minSdk = 21
                    targetSdk = 34
                    versionCode = 1
                    versionName = "1.0"
                }
                buildTypes {
                    release {
                        isMinifyEnabled = true
                        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                    }
                }
            }
            """,
        )
        write(
            dir, "proguard-rules.pro",
            """
            -keep class com.example.minified.Main { *; }
            -assumenosideeffects class android.util.Log {
                public static int d(...);
            }
            """,
        )
        write(dir, "src/main/AndroidManifest.xml", """<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:label="m" /></manifest>""")
        write(
            dir, "src/main/java/com/example/minified/Main.java",
            """
            package com.example.minified;

            public class Main {
                public static void hello() {
                    android.util.Log.d("minified", "stripped in release");
                    android.util.Log.i("minified", "kept");
                }
            }
            """,
        )
        return dir
    }

    @Test
    fun aMinifiedReleaseWithTheTemplatesDefaultRulesBuilds() {
        val sdk = androidSdk()
        assumeTrue("needs an Android SDK (ANDROID_HOME)", sdk != null)
        val dir = app(sdk!!)
        // R8 needs the default rules AGP extracts and the rules the plugin writes from them: both tasks run.
        val result = GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(":minifyReleaseWithR8", "--stacktrace")
            .forwardOutput()
            .build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateReleaseTracewayLogcatRules")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":minifyReleaseWithR8")?.outcome)
        assertTrue(File(dir, "build/outputs/mapping/release/mapping.txt").isFile)
        // The app's rule that strips Log.d is mirrored for the rewritten calls.
        val mirrored = File(dir, "build/intermediates/traceway/release/traceway-logcat-rules.pro").readText()
        assertTrue(mirrored, mirrored.contains("com.tracewayapp.traceway.TracewayLogcat"))
    }
}
