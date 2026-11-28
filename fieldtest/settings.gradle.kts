// Fieldmart, the SDK field-test app (sdk-field-test/02-APP-SPEC.md), as its own Gradle build so the
// repo's root build is untouched. Build it from the repo root with `./gradlew -p fieldtest …`.
pluginManagement {
    // com.tracewayapp.symbols from the plugin's sources next to it.
    includeBuild("../gradle-plugin")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// com.tracewayapp:traceway 2.1.0 is not published: it resolves to the library in this repo.
includeBuild("..") {
    dependencySubstitution {
        substitute(module("com.tracewayapp:traceway")).using(project(":traceway"))
    }
}

rootProject.name = "fieldmart"

// Plain JVM: the seeded generator, the tick model and the journey model (tested without Android).
include(":core")
// Every screen, the data client, the probe and the Telemetry seam; no Traceway dependency.
include(":ui")
// The `off` build: no SDK, no Gradle plugin (com.tracewayapp.fieldmart.off).
include(":app-off")
// The SDK build: com.tracewayapp:traceway, the OkHttp interceptor, the symbols plugin (com.tracewayapp.fieldmart).
include(":app-sdk")
// The UiAutomator driver: a self-instrumenting test APK that plays the journey.
include(":driver")
