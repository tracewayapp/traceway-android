import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

val sdkVersion = "2.0.0-beta.1"
val otelVersion = "1.68.0"

android {
    namespace = "com.tracewayapp.traceway"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
        buildConfigField("String", "OTEL_VERSION", "\"$otelVersion\"")
    }

    buildFeatures {
        buildConfig = true
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
            all {
                // Robolectric loads a whole Android framework per SDK.
                it.maxHeapSize = "1g"
                it.jvmArgs("-XX:+UseSerialGC", "-XX:MaxMetaspaceSize=512m")
                it.systemProperty("robolectric.logging.enabled", "false")
                // Its downloads and native libraries go to the build directory, not /tmp.
                it.systemProperty("java.io.tmpdir", it.temporaryDir.absolutePath)
                // The wireframe gallery reads these; a change runs it again.
                for (name in listOf("WIREFRAME_GALLERY_ONLY", "WIREFRAME_GALLERY_OUT")) {
                    it.inputs.property(name, System.getenv(name) ?: "")
                }
            }
        }
    }
}

dependencies {
    // OpenTelemetry Java, the official SDK: logs and spans are records of its
    // SDK, and leave as OTLP protobuf built by its marshalers. Pinned to one
    // line through the BOM so the api/sdk singletons never mix versions.
    implementation(platform("io.opentelemetry:opentelemetry-bom:$otelVersion"))
    implementation("io.opentelemetry:opentelemetry-api")
    implementation("io.opentelemetry:opentelemetry-sdk-common")
    implementation("io.opentelemetry:opentelemetry-sdk-logs")
    implementation("io.opentelemetry:opentelemetry-sdk-trace")
    implementation("io.opentelemetry:opentelemetry-sdk-metrics")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp-common")

    // @VisibleForTesting + nullability annotations.
    compileOnly("androidx.annotation:annotation:1.9.1")

    // Optional integrations, used only when the app has them: the OkHttp
    // interceptor, Jetpack Compose screens in wireframe recordings and taps,
    // and fragment navigation.
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("androidx.compose.ui:ui-android:1.7.8")
    compileOnly("androidx.fragment:fragment:1.8.9")

    // ── JVM unit tests ────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    // org.json on JVM (Android stub returns null without this).
    testImplementation("org.json:json:20231013")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Wireframe capture of real views and Compose screens on the JVM: the
    // Android framework under Robolectric, the widgets apps use on top.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation("com.google.android.material:material:1.12.0")
    testImplementation("androidx.recyclerview:recyclerview:1.3.2")
    // The wireframe gallery's layouts and pagers (the versions Material brings).
    testImplementation("androidx.constraintlayout:constraintlayout:2.0.1")
    testImplementation("androidx.viewpager2:viewpager2:1.0.0")
    testImplementation("androidx.fragment:fragment:1.8.9")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-android:1.7.8")
    testImplementation("androidx.compose.foundation:foundation")
    testImplementation("androidx.compose.material3:material3")
    testImplementation("androidx.compose.material:material-icons-core")
    testImplementation("androidx.activity:activity-compose:1.9.3")
    // The Compose compiler for the tests' composables only. Applied as the
    // Gradle plugin it would also compile the library, which has none, and
    // add Compose's `$stable` fields to its public classes.

    // ── Instrumented tests ────────────────────────────────────────────
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.appcompat:appcompat:1.7.0")
}

// The Compose compiler for the tests' composables only. Applied as the
// Gradle plugin it would also compile the library, which has none, and add
// Compose's `$stable` fields to its public classes.
configurations.configureEach {
    if (name == "kotlinCompilerPluginClasspathDebugUnitTest" || name == "kotlinCompilerPluginClasspathReleaseUnitTest") {
        dependencies.add(project.dependencies.create("org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:2.0.21"))
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    configure(AndroidSingleVariantLibrary(
        variant = "release",
        sourcesJar = true,
        publishJavadocJar = true,
    ))

    coordinates("com.tracewayapp", "traceway", sdkVersion)

    pom {
        name.set("Traceway Android SDK")
        description.set(
            "Error tracking, sessions, wireframe session replay and app performance " +
                "metrics for native Android apps, built on OpenTelemetry. Remote " +
                "collection settings over OpAMP."
        )
        inceptionYear.set("2026")
        url.set("https://github.com/tracewayapp/traceway-android")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("tracewayapp")
                name.set("Traceway")
                url.set("https://github.com/tracewayapp")
            }
        }

        scm {
            url.set("https://github.com/tracewayapp/traceway-android")
            connection.set("scm:git:git://github.com/tracewayapp/traceway-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/tracewayapp/traceway-android.git")
        }
    }
}
