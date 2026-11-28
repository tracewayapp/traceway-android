plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // The shared files (sdk-field-test tools/bin/sync-shared copies them into fieldtest/shared/).
    systemProperty("fm.shared", rootProject.file("shared").absolutePath)
    val shared = rootProject.file("shared")
    if (shared.isDirectory) inputs.dir(shared).withPropertyName("shared")
    maxHeapSize = "256m"
}
