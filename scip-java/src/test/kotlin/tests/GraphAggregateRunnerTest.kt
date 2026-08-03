package tests

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.scip_code.scip_java.CliEnvironment
import org.scip_code.scip_java.ScipJavaApp
import org.scip_code.scip_java.buildtools.MavenGraphGenerationStore
import org.scip_code.scip_java.commands.GraphAggregateRunner

class GraphAggregateRunnerTest {
    @Test
    fun aggregatesOnlyCommittedGenerationsDeterministically() {
        withWorkspace { workspace, app, _ ->
            val targetroot = workspace.resolve("targetroot")
            write(workspace.resolve("src/A.java"), "class A {}\n")
            write(workspace.resolve("src/B.java"), "class B {}\n")
            write(workspace.resolve("sub/src/C.java"), "class C {}\n")
            val store = MavenGraphGenerationStore(targetroot, workspace, "maven")
            store.prepare()
            write(store.staging.resolve("src/A.java.graph.json"), shard("maven:.", "src/A.java"))
            write(
                store.staging.resolve("sub/src/C.java.graph.json"),
                shard("maven:sub", "sub/src/C.java"),
            )
            store.commit()
            val generation = Files.readString(store.current).trim()

            val direct = targetroot.resolve("META-INF/scip-graph/src/B.java.graph.json")
            write(direct, shard("direct", "src/B.java"))
            write(targetroot.resolve("META-INF/scip-graph/ignored.tmp"), "not a shard")

            val output = workspace.resolve("graph.json")
            assertEquals(0, GraphAggregateRunner.run(output, listOf(targetroot), app))
            val first = Files.readAllBytes(output)
            assertEquals(0, GraphAggregateRunner.run(output, listOf(targetroot), app))
            assertTrue(first.contentEquals(Files.readAllBytes(output)))

            val artifact = Json.parseToJsonElement(Files.readString(output)).jsonObject
            assertEquals(1, artifact["schemaVersion"]!!.jsonPrimitive.content.toInt())
            assertEquals(
                "false",
                artifact["producer"]!!
                    .jsonObject["capabilities"]!!
                    .jsonObject["diagnostics"]!!
                    .jsonPrimitive
                    .content,
            )
            val targets = artifact["targets"]!!.jsonArray
            assertEquals(
                listOf("direct", "maven:.", "maven:sub"),
                targets.map { it.jsonObject["name"]!!.jsonPrimitive.content },
            )
            assertEquals(generation, targets[1].jsonObject["generation"]!!.jsonPrimitive.content)
            assertEquals(generation, targets[2].jsonObject["generation"]!!.jsonPrimitive.content)
            assertEquals(15, targets[1].jsonObject["coverage"]!!.jsonObject.size)
            assertEquals(
                "unsupported",
                targets[1].jsonObject["coverage"]!!.jsonObject["renders"]!!.jsonPrimitive.content,
            )
            assertEquals(
                targets[1].jsonObject["universe"]!!.jsonPrimitive.content,
                targets[2].jsonObject["universe"]!!.jsonPrimitive.content,
            )
            assertEquals(
                "src/B.java",
                targets[0]
                    .jsonObject["shards"]!!
                    .jsonArray
                    .single()
                    .jsonObject["source"]!!
                    .jsonPrimitive
                    .content,
            )
        }
    }

    @Test
    fun malformedCommittedInputCannotReplaceTheLastArtifact() {
        withWorkspace { workspace, app, outputBuffer ->
            val targetroot = workspace.resolve("targetroot")
            write(workspace.resolve("src/A.java"), "class A {}\n")
            val store = MavenGraphGenerationStore(targetroot, workspace, "maven")
            store.prepare()
            write(store.staging.resolve("src/A.java.graph.json"), shard("maven:.", "src/A.java"))
            store.commit()
            val generation = Files.readString(store.current).trim()
            val committed = store.current.parent.resolve("generations").resolve(generation)
            write(committed.resolve("src/A.java.graph.json"), "{broken")
            val output = workspace.resolve("graph.json")
            write(output, "last-known-good\n")

            assertEquals(1, GraphAggregateRunner.run(output, listOf(targetroot), app))
            assertEquals("last-known-good\n", Files.readString(output))
            assertTrue(
                outputBuffer.toString(StandardCharsets.UTF_8).contains("generation digest mismatch")
            )
        }
    }

    @Test
    fun aStoreWithoutAnAtomicManifestCannotReplaceTheLastArtifact() {
        withWorkspace { workspace, app, outputBuffer ->
            val targetroot = workspace.resolve("targetroot")
            write(workspace.resolve("src/A.java"), "class A {}\n")
            val store = MavenGraphGenerationStore(targetroot, workspace, "maven")
            store.prepare()
            write(store.staging.resolve("src/A.java.graph.json"), shard("maven:.", "src/A.java"))
            store.commit()
            Files.delete(targetroot.resolve("META-INF/scip-graph-store/MANIFEST"))
            val output = workspace.resolve("graph.json")
            write(output, "last-known-good\n")

            assertEquals(1, GraphAggregateRunner.run(output, listOf(targetroot), app))
            assertEquals("last-known-good\n", Files.readString(output))
            assertTrue(
                outputBuffer.toString(StandardCharsets.UTF_8).contains("no committed MANIFEST")
            )
        }
    }

    @Test
    fun sourceMovementAfterCompilationCannotReplaceTheLastArtifact() {
        withWorkspace { workspace, app, outputBuffer ->
            val targetroot = workspace.resolve("targetroot")
            val source = workspace.resolve("src/A.java")
            write(source, "class A {}\n")
            val store = MavenGraphGenerationStore(targetroot, workspace, "maven")
            store.prepare()
            write(store.staging.resolve("src/A.java.graph.json"), shard("maven:.", "src/A.java"))
            store.commit()
            write(source, "class A { int moved; }\n")
            val output = workspace.resolve("graph.json")
            write(output, "last-known-good\n")

            assertEquals(1, GraphAggregateRunner.run(output, listOf(targetroot), app))
            assertEquals("last-known-good\n", Files.readString(output))
            assertTrue(outputBuffer.toString(StandardCharsets.UTF_8).contains("source moved"))
        }
    }

    private fun withWorkspace(block: (Path, ScipJavaApp, ByteArrayOutputStream) -> Unit) {
        val workspace = createTempDirectory("graph-aggregate").toRealPath()
        try {
            val buffer = ByteArrayOutputStream()
            val stream = PrintStream(buffer, true, StandardCharsets.UTF_8)
            val app = ScipJavaApp()
            app.env =
                CliEnvironment(
                    workingDirectory = workspace,
                    standardOutput = stream,
                    standardError = stream,
                )
            block(workspace, app, buffer)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, text, StandardCharsets.UTF_8)
    }

    private fun shard(target: String, source: String): String =
        """{"schemaVersion":1,"target":"$target","source":"$source","checkerDigest":"${digest("class ${source.substringAfterLast('/').substringBefore('.')} {}\n")}","diskDigest":"${digest("class ${source.substringAfterLast('/').substringBefore('.')} {}\n")}","nodes":[],"edges":[],"unresolved":[]}"""

    private fun digest(text: String): String =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(text.toByteArray(StandardCharsets.UTF_8))
            )
}
