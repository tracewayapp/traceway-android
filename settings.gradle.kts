pluginManagement {
    includeBuild("gradle-plugin")
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

rootProject.name = "traceway-android"

include(":traceway")
include(":example")
// A minified release app with the SDK and the plugin, built and run by CI (.github/workflows/minified.yml).
include(":minified-app")
