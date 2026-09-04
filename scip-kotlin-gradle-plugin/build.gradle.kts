plugins {
    id("scip.java-library")
    id("scip.shadow-producer")
}

description = "Gradle support plugin for the pinned Kotlin K2 graph exporter"

dependencies {
    compileOnly(libs.gradle.api)
    compileOnly(libs.gradle.test.kit)
    compileOnly(libs.kotlin.gradle.plugin.api)
    implementation(libs.kotlinx.serialization.json.jvm)
}
