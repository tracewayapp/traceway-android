import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.tracewayapp.symbols")
}

val localProps = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) localFile.inputStream().use { load(it) }
}

val tracewayDsn: String = localProps.getProperty("traceway.dsn")
    ?: "your-token@https://your-traceway-instance.com"

val tracewayUploadToken: String = localProps.getProperty("traceway.upload_token") ?: ""
val tracewayUrl: String = localProps.getProperty("traceway.upload_url")
    ?: tracewayDsn.substringAfter('@', "").removeSuffix("/api/report").removeSuffix("/")

android {
    namespace = "com.tracewayapp.traceway.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tracewayapp.traceway.example"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "2.0.0"

        buildConfigField("String", "TRACEWAY_DSN", "\"$tracewayDsn\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    uploadToken = tracewayUploadToken
    url = tracewayUrl
    autoUpload = false
}

dependencies {
    implementation(project(":traceway"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
}
