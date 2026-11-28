// The SDK build (03-MEASUREMENT.md §1.3): Fieldmart with com.tracewayapp:traceway 2.1.0 from this repo, its OkHttp
// interceptor, and the com.tracewayapp.symbols plugin with its defaults (so android.util.Log calls are rewritten, as
// for a customer who follows the docs). The variant (core, buffer, stream-img, stream-noimg) is a launch setting.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.tracewayapp.symbols")
}

android {
    namespace = "com.tracewayapp.fieldmart"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tracewayapp.fieldmart"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "fieldtest-2.1.0"
    }

    buildTypes {
        // Release-like (03-MEASUREMENT.md §3.3): R8, shrunk resources, not debuggable, profileable from the shell,
        // signed with the debug key. Every number comes from it.
        create("bench") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // WORKAROUND for finding android-002: see the file.
            if (providers.gradleProperty("fm.workaround.otelResourceEntities").getOrElse("true") != "false") {
                proguardFile("workaround-otel-resource-entities.pro")
            }
        }
        // The bench build without the cleartext rule for 127.0.0.1 (hypothesis H-A4): what the SDK does when every
        // plain-HTTP post is refused by the platform.
        create("benchNoCleartext") {
            initWith(getByName("bench"))
            matchingFallbacks += listOf("bench", "release")
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

traceway {
    // Mappings are uploaded by hand to the local instance after a run; the plugin's defaults otherwise.
    autoUpload = false
}

// WORKAROUND for finding android-001 (blocks-the-test): com.tracewayapp.symbols 2.1.0 registers
// generate<Variant>TracewayLogcatRules, which reads the default ProGuard file extracted by AGP's extractProguardFiles
// without depending on it, and Gradle 8.9 then refuses to build any minified variant (the repo's own
// `:example:assembleRelease` fails the same way). Declaring the dependency here is the smallest change; turn it off
// with -Pfm.workaround.symbolsTaskDependency=false to see the failure.
if (providers.gradleProperty("fm.workaround.symbolsTaskDependency").getOrElse("true") != "false") {
    tasks.matching { it.name.startsWith("generate") && it.name.endsWith("TracewayLogcatRules") }.configureEach {
        dependsOn("extractProguardFiles")
    }
}

dependencies {
    implementation(project(":ui"))
    implementation("com.tracewayapp:traceway:2.1.0")
}
