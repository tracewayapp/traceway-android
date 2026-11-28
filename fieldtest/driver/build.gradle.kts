// The UiAutomator driver (AGENT-4 step 2): a test APK that instruments itself, not the app, so the app process is
// exactly what a user runs. It plays shared/journey.json against either app by package name.
import com.android.build.api.variant.TestAndroidComponentsExtension

plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

abstract class BundleJourney : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile.resolve("fm")
        out.mkdirs()
        for (f in sources.files) if (f.isFile) f.copyTo(out.resolve(f.name), overwrite = true)
    }
}

android {
    namespace = "com.tracewayapp.fieldmart.driver"
    compileSdk = 35
    targetProjectPath = ":app-sdk"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("bench") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// The journey and the id list travel inside the APK (assets/fm/), copied from fieldtest/shared/.
val bundleJourney by tasks.registering(BundleJourney::class) {
    sources.from(rootProject.files("shared/journey.json", "shared/ids.json", "shared/VERSION"))
    outputDir.set(layout.buildDirectory.dir("generated/fm-journey/assets"))
}

extensions.getByType(TestAndroidComponentsExtension::class.java).onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(bundleJourney, BundleJourney::outputDir)
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test:core:1.6.1")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("junit:junit:4.13.2")
}
