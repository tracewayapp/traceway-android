import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.tracewayapp.symbols")
}

val localProps = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) localFile.inputStream().use { load(it) }
}

val tracewayDsn: String = localProps.getProperty("traceway.dsn")
    ?: "your-token@https://your-traceway-instance.com/api/report"

val tracewayUploadToken: String = localProps.getProperty("traceway.upload_token") ?: ""
val tracewayUrl: String = localProps.getProperty("traceway.upload_url")
    ?: tracewayDsn.substringAfter('@', "").removeSuffix("/api/report")

android {
    namespace = "com.tracewayapp.traceway.example"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tracewayapp.traceway.example"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "TRACEWAY_DSN", "\"$tracewayDsn\"")
    }

    buildFeatures {
        buildConfig = true
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
