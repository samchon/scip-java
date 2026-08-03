plugins {
    id("scip.java-library")
    id("scip.shadow-producer")
}

dependencies {
    implementation(libs.kotlinx.serialization.json.jvm)
    compileOnly(libs.gradle.api)
    compileOnly(libs.gradle.test.kit)
}
