import org.apache.tools.ant.filters.ReplaceTokens

plugins {
    id("scip.java-library")
    id("scip.maven-publish")
}

description = "A Maven plugin that exports dependency metadata for scip-java"

dependencies {
    implementation(libs.maven.plugin.api)
    implementation(libs.maven.project)
    compileOnly(libs.maven.plugin.annotations)
}

val mavenPluginVersion = project.version.toString()
val graphPluginVersion = "$mavenPluginVersion-graph-reactor-1"

tasks.named<ProcessResources>("processResources") {
    exclude("META-INF/maven/plugin.template.xml")
    from("src/main/resources/META-INF/maven/plugin.template.xml") {
        into("META-INF/maven")
        rename { "plugin.xml" }
        filter(mapOf("tokens" to mapOf("VERSION" to mavenPluginVersion)), ReplaceTokens::class.java)
    }
    inputs.property("version", mavenPluginVersion)
}

tasks.register<Jar>("graphPluginJar") {
    archiveClassifier.set("graph-reactor")
    from(sourceSets.main.get().output) {
        exclude("META-INF/maven/plugin.xml")
    }
    from("src/graphPlugin/plugin.template.xml") {
        into("META-INF/maven")
        rename { "plugin.xml" }
        filter(mapOf("tokens" to mapOf("VERSION" to graphPluginVersion)), ReplaceTokens::class.java)
    }
    inputs.property("version", graphPluginVersion)
}
