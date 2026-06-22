import com.vanniktech.maven.publish.GradlePublishPlugin
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    `java-gradle-plugin`
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("com.gradle.plugin-publish") version "1.2.1"
    id("com.vanniktech.maven.publish") version "0.30.0"
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    compileOnly("com.android.tools.build:gradle:8.2.2")
    compileOnly("com.android.tools.build:gradle-api:8.2.2")
    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions { jvmTarget = "17" }
}

gradlePlugin {
    website.set("https://github.com/tracewayapp/traceway-android")
    vcsUrl.set("https://github.com/tracewayapp/traceway-android")
    plugins {
        create("tracewaySymbols") {
            id = "com.tracewayapp.symbols"
            implementationClass = "com.tracewayapp.symbols.TracewaySymbolsPlugin"
            displayName = "Traceway Symbols"
            description = "Uploads R8/ProGuard mapping.txt to a Traceway instance"
            tags.set(listOf("android", "traceway", "proguard", "r8", "symbolication", "crash-reporting"))
        }
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    configure(GradlePublishPlugin())

    coordinates("com.tracewayapp", "traceway-symbols-plugin", "0.0.1")

    pom {
        name.set("Traceway Symbols Gradle Plugin")
        description.set(
            "Uploads R8/ProGuard mapping.txt to a Traceway instance and injects a " +
                "per-build ProGuard UUID into BuildConfig so obfuscated release crashes " +
                "can be deobfuscated.",
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
