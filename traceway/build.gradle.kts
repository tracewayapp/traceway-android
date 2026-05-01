plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

android {
    namespace = "com.tracewayapp.traceway"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // @VisibleForTesting + nullability annotations.
    compileOnly("androidx.annotation:annotation:1.7.1")

    // OkHttp interceptor is provided as an opt-in integration; users who do not
    // use OkHttp will not pull it in transitively.
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")

    // ── JVM unit tests ────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    // org.json on JVM (Android stub returns null without this).
    testImplementation("org.json:json:20231013")

    // ── Instrumented tests ────────────────────────────────────────────
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.5.1")

    // Real OkHttp + MockWebServer for end-to-end transport assertions.
    androidTestImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // appcompat is needed for the test Activities' AppCompat themes.
    androidTestImplementation("androidx.appcompat:appcompat:1.6.1")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.tracewayapp"
            artifactId = "traceway"
            version = "1.0.0"

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
