// A release build the way customers ship one: the SDK and the com.tracewayapp.symbols plugin, minified with R8 and
// the rules of Android Studio's template (getDefaultProguardFile), nothing kept by hand. CI builds it
// (.github/workflows/minified.yml), checks that what OpenTelemetry reads by reflection survived R8, and runs it on an
// emulator against a stand-in collector until logs, spans and metrics arrive (findings android-001 and android-002).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.tracewayapp.symbols")
}

android {
    namespace = "com.tracewayapp.traceway.minified"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tracewayapp.traceway.minified"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // The stand-in collector of the CI job, on the runner, reached through `adb reverse`.
        buildConfigField("String", "COLLECTOR_URL", "\"http://127.0.0.1:18199\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Installable on the CI emulator.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        // OpenTelemetry Java needs core library desugaring below API 26.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

traceway {
    autoUpload = false
}

dependencies {
    implementation(project(":traceway"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
