// The `off` build (03-MEASUREMENT.md §1.3): Fieldmart without the SDK, its Gradle plugin or anything of Traceway.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tracewayapp.fieldmart.off"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tracewayapp.fieldmart.off"
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

dependencies {
    implementation(project(":ui"))
}
