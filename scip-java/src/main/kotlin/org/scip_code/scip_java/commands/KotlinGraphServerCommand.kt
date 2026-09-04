package org.scip_code.scip_java.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.scip_code.scip_java.ScipJavaApp
import org.scip_code.scip_java.buildtools.KotlinGraphGradleIntegration

/** Resident NDJSON endpoint that reuses one Gradle Tooling API connection and its daemon. */
class KotlinGraphServerCommand : CliktCommand(name = "kotlin-graph-server") {
    private val app by requireObject<ScipJavaApp>()

    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Serve compiler-owned Kotlin graph generations over NDJSON."

    override fun run() {
        // Gradle's Tooling API writes distribution-download progress directly
        // to System.out before a BuildLauncher can redirect its output. Keep
        // stdout reserved for protocol frames even on the first cold launch.
        val systemOutput = System.out
        System.setOut(app.env.standardError)
        try {
            serve()
        } finally {
            System.setOut(systemOutput)
        }
    }

    private fun serve() {
        val project = app.env.workingDirectory.toAbsolutePath().normalize()
        val targetRoot = project.resolve("build/scip-targetroot")
        val temporary = Files.createTempDirectory("scip-java-kotlin-graph")
        val prepared =
            try {
                KotlinGraphGradleIntegration.prepare(project, targetRoot, temporary)
            } finally {
                temporary.toFile().deleteRecursively()
            }
        val connector =
            GradleConnector.newConnector()
                .forProjectDirectory(project.toFile())
                .useDistribution(gradleDistribution(project))
        val connection: ProjectConnection = connector.connect()
        try {
            app.env.standardInput.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val request = parseRequest(line, project)
                    val failure =
                        runCatching {
                                connection
                                    .newBuild()
                                    .forTasks("samchonCommitKotlinGraph")
                                    .withArguments(
                                        "--init-script",
                                        prepared.initScript.toString(),
                                        "-Dscip.targetroot=${prepared.targetRoot}",
                                        "-Pkotlin.build.report.output=json",
                                        "-Pkotlin.build.report.json.directory=${prepared.targetRoot.resolve("META-INF/kotlin-build-reports")}",
                                    )
                                    .setStandardOutput(app.env.standardError)
                                    .setStandardError(app.env.standardError)
                                    .run()
                                check(
                                    KotlinGraphAggregateRunner.run(
                                        request.output,
                                        listOf(prepared.targetRoot),
                                        app,
                                    ) == 0
                                ) {
                                    "Kotlin graph aggregation failed"
                                }
                            }
                            .exceptionOrNull()
                    respond(request.id, failure)
                }
            }
        } finally {
            connection.close()
        }
    }

    private fun parseRequest(line: String, project: Path): Request {
        val value =
            Json.parseToJsonElement(line) as? JsonObject
                ?: error("Kotlin graph server request must be a JSON object")
        val id =
            value["id"]?.jsonPrimitive?.intOrNull
                ?: error("Kotlin graph server request has no integer id")
        check(value["protocolVersion"]?.jsonPrimitive?.intOrNull == PROTOCOL_VERSION) {
            "Kotlin graph server protocol mismatch"
        }
        val text =
            value["output"]?.jsonPrimitive?.content
                ?: error("Kotlin graph server request has no output")
        val output = project.resolve(text).normalize()
        check(output.isAbsolute) { "Kotlin graph server output must be absolute" }
        return Request(id, output)
    }

    private fun respond(id: Int, failure: Throwable?) {
        val response =
            linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
                "id" to JsonPrimitive(id),
                "protocolVersion" to JsonPrimitive(PROTOCOL_VERSION),
                "ok" to JsonPrimitive(failure == null),
            )
        if (failure != null) {
            response["error"] = JsonPrimitive(message(failure))
        }
        app.env.standardOutput.println(JsonObject(response))
        app.env.standardOutput.flush()
    }

    private fun message(failure: Throwable): String {
        var current = failure
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return (current.message ?: current::class.java.name).take(MAX_ERROR_CHARS)
    }

    private fun gradleDistribution(project: Path): URI {
        val wrapper = project.resolve("gradle/wrapper/gradle-wrapper.properties")
        if (!Files.isRegularFile(wrapper)) return URI(DEFAULT_GRADLE_DISTRIBUTION)
        val properties = Properties()
        Files.newInputStream(wrapper).use(properties::load)
        return URI(
            properties.getProperty("distributionUrl")
                ?: error("Gradle wrapper properties have no distributionUrl")
        )
    }

    private data class Request(val id: Int, val output: Path)

    private companion object {
        const val PROTOCOL_VERSION = 1
        const val MAX_ERROR_CHARS = 16 * 1024
        const val DEFAULT_GRADLE_DISTRIBUTION =
            "https://services.gradle.org/distributions/gradle-9.4.1-bin.zip"
    }
}
