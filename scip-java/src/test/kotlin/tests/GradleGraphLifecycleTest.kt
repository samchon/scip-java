package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GradleGraphLifecycleTest : BuildToolHarness() {
    @Test
    fun preservesIncrementalStateAndPublishesOnlySuccessfulCompleteGenerations() {
        val base = newTempBase()
        try {
            val workspace = Files.createDirectories(base.resolve("working Directory 한글"))
            val cache = Files.createDirectories(base.resolve("cache"))
            Files.writeString(workspace.resolve("build.gradle"), "")
            val gradle = if (java.io.File.separatorChar == '\\') "gradle.bat" else "gradle"
            exec(listOf(gradle, "wrapper", "--gradle-version", "9.1.0"), workspace)
            copyFixture("gradle/basic", workspace)

            val targetroot = workspace.resolve("targetroot")
            val artifact = workspace.resolve("graph.json")
            val arguments =
                listOf(
                    "index",
                    "--temporary-directory",
                    cache.toString(),
                    "--targetroot",
                    targetroot.toString(),
                    "--graph-output",
                    artifact.toString(),
                    "--",
                    "--configuration-cache",
                    "scipPrintDependencies",
                    "scipCompileAll",
                )

            val first = runScipJava(workspace, arguments)
            assertEquals(0, first.first, first.second)
            assertEquals(
                listOf("src/main/java/App.java", "src/test/java/AppTest.java"),
                artifactSources(artifact),
            )
            val firstArtifact = Files.readAllBytes(artifact)
            val firstPointers = currentPointers(targetroot)

            val second = runScipJava(workspace, arguments)
            assertEquals(0, second.first, second.second)
            assertContains(second.second, "Reusing configuration cache")
            assertContains(second.second, "compileJava UP-TO-DATE")
            assertTrue(firstArtifact.contentEquals(Files.readAllBytes(artifact)))
            assertEquals(firstPointers, currentPointers(targetroot))

            val buildFile = workspace.resolve("build.gradle")
            Files.writeString(
                buildFile,
                Files.readString(buildFile) +
                    "\ntasks.withType(JavaCompile).configureEach { options.compilerArgs += '-parameters' }\n",
            )
            val configured = runScipJava(workspace, arguments)
            assertEquals(0, configured.first, configured.second)
            val configuredPointers = currentPointers(targetroot)
            assertNotEquals(firstPointers, configuredPointers)

            val main = workspace.resolve("src/main/java/App.java")
            val validMain = Files.readString(main)
            Files.writeString(main, validMain.replace("Hello World!", "Hello graph!"))
            val changed = runScipJava(workspace, arguments)
            assertEquals(0, changed.first, changed.second)
            val changedArtifact = Files.readAllBytes(artifact)
            assertTrue(!firstArtifact.contentEquals(changedArtifact))
            val changedPointers = currentPointers(targetroot)
            assertNotEquals(configuredPointers, changedPointers)

            Files.writeString(main, "class Broken {\n")
            val failed = runScipJava(workspace, arguments)
            assertNotEquals(0, failed.first, failed.second)
            assertEquals(changedPointers, currentPointers(targetroot))
            assertTrue(changedArtifact.contentEquals(Files.readAllBytes(artifact)))

            Files.writeString(main, validMain)
            Files.delete(workspace.resolve("src/test/java/AppTest.java"))
            val deleted = runScipJava(workspace, arguments)
            assertEquals(0, deleted.first, deleted.second)
            assertEquals(listOf("src/main/java/App.java"), artifactSources(artifact))

            val created = workspace.resolve("src/main/java/B.java")
            Files.writeString(created, "package gradle.sample.project; class B {}\n")
            assertEquals(0, runScipJava(workspace, arguments).first)
            assertEquals(
                listOf("src/main/java/App.java", "src/main/java/B.java"),
                artifactSources(artifact),
            )

            Files.move(created, created.resolveSibling("C.java"))
            assertEquals(0, runScipJava(workspace, arguments).first)
            assertEquals(
                listOf("src/main/java/App.java", "src/main/java/C.java"),
                artifactSources(artifact),
            )

            val excluded = created.resolveSibling("C.java")
            Files.writeString(
                buildFile,
                Files.readString(buildFile) + "\nsourceSets.main.java.exclude 'C.java'\n",
            )
            assertEquals(0, runScipJava(workspace, arguments).first)
            assertEquals(listOf("src/main/java/App.java"), artifactSources(artifact))
            assertTrue(Files.isRegularFile(excluded))

            fun coldArtifact(temporary: Path): ByteArray {
                targetroot.toFile().deleteRecursively()
                Files.deleteIfExists(artifact)
                val cold = arguments.toMutableList()
                cold[2] = temporary.toString()
                cold.add(8, "--rerun-tasks")
                val result = runScipJava(workspace, cold)
                assertEquals(0, result.first, result.second)
                return Files.readAllBytes(artifact)
            }
            val firstCold = coldArtifact(Files.createDirectories(base.resolve("cold-one")))
            val secondCold = coldArtifact(Files.createDirectories(base.resolve("cold-two")))
            assertTrue(firstCold.contentEquals(secondCold))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    private fun currentPointers(targetroot: Path): Map<String, String> {
        val root = targetroot.resolve("META-INF/scip-graph-store/targets")
        return Files.walk(root).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter { it.name == "CURRENT" }
                .sorted()
                .toList()
                .associate { root.relativize(it).toString() to Files.readString(it).trim() }
        }
    }

    private fun artifactSources(artifact: Path): List<String> {
        val root =
            Json.parseToJsonElement(Files.readString(artifact, StandardCharsets.UTF_8)).jsonObject
        return root["targets"]!!
            .jsonArray
            .flatMap { target -> target.jsonObject["shards"]!!.jsonArray }
            .map { shard -> shard.jsonObject["source"]!!.jsonPrimitive.content }
            .sorted()
    }
}
