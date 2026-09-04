package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class KotlinGraphGradleBuildToolTest : BuildToolHarness() {
    @Test
    fun graphModeReusesGradleStateAndPublishesOnlySuccessfulGenerations() {
        val base = newTempBase()
        try {
            val workingDirectory = Files.createDirectories(base.resolve("workingDirectory"))
            val cacheDirectory = Files.createDirectories(base.resolve("cache"))
            val buildScript = workingDirectory.resolve("build.gradle")
            Files.write(buildScript, ByteArray(0))
            val wrapperCommand =
                if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                    listOf("cmd.exe", "/c", "gradle.bat", "wrapper", "--gradle-version", "9.4.1")
                } else {
                    listOf("gradle", "wrapper", "--gradle-version", "9.4.1")
                }
            exec(wrapperCommand, workingDirectory)
            copyFixture("gradle/kotlin-graph", workingDirectory)

            val targetRoot = workingDirectory.resolve("targetroot")
            fun run(output: String): Pair<Int, String> =
                runScipJava(
                    workingDirectory,
                    listOf(
                        "index",
                        "--temporary-directory",
                        cacheDirectory.toString(),
                        "--targetroot",
                        targetRoot.toString(),
                        "--kotlin-graph-output",
                        workingDirectory.resolve(output).toString(),
                        "--build-tool",
                        "gradle",
                        "--",
                        "--build-cache",
                        "--configuration-cache",
                        "samchonCommitKotlinGraph",
                    ),
                )

            val (firstExit, firstLog) = run("first.json")
            assertEquals(0, firstExit, firstLog)
            val first = Files.readAllBytes(workingDirectory.resolve("first.json"))
            assertGraphContract(first)
            val firstUniverse = mainUniverse(first)

            val (secondExit, secondLog) = run("second.json")
            assertEquals(0, secondExit, secondLog)
            assertTrue(secondLog.contains("Reusing configuration cache"), secondLog)
            assertContentEquals(first, Files.readAllBytes(workingDirectory.resolve("second.json")))
            val reports = targetRoot.resolve("META-INF/kotlin-build-reports")
            assertBuildReportRecordedNonIncrementalReason(reports)

            val source = workingDirectory.resolve("src/main/kotlin/example/GraphFixture.kt")
            val original = Files.readString(source, StandardCharsets.UTF_8)
            Files.writeString(source, original.replace("value.uppercase()", "value.lowercase()"))
            val (editedExit, editedLog) = run("edited.json")
            assertEquals(0, editedExit, editedLog)
            assertTrue(editedLog.contains("Reusing configuration cache"), editedLog)
            val edited = Files.readAllBytes(workingDirectory.resolve("edited.json"))
            assertFalse(first.contentEquals(edited))
            assertEquals(
                firstUniverse,
                mainUniverse(edited),
                "a source body edit must not move the target/classpath universe",
            )

            val manifest = targetRoot.resolve("META-INF/kotlin-graph-store/MANIFEST")
            val committed = Files.readAllBytes(manifest)
            Files.writeString(source, "$original\nfun broken(: Unit = Unit\n")
            val failedOutput = workingDirectory.resolve("failed.json")
            val (failedExit, _) = run("failed.json")
            assertNotEquals(0, failedExit)
            assertContentEquals(committed, Files.readAllBytes(manifest))
            assertFalse(Files.exists(failedOutput))

            Files.writeString(source, original)
            val (recoveredExit, recoveredLog) = run("recovered.json")
            assertEquals(0, recoveredExit, recoveredLog)
            assertContentEquals(
                first,
                Files.readAllBytes(workingDirectory.resolve("recovered.json")),
            )

            val residentFirst = workingDirectory.resolve("resident-first.json")
            val residentSecond = workingDirectory.resolve("resident-second.json")
            val requests =
                listOf(residentFirst, residentSecond).mapIndexed { index, output ->
                    """{"id":${index + 1},"protocolVersion":1,"output":${JsonPrimitive(output.toString())}}"""
                }
            val resident =
                runScipJavaProtocol(
                    workingDirectory,
                    listOf("kotlin-graph-server"),
                    requests.joinToString("\n", postfix = "\n"),
                )
            assertEquals(0, resident.exit, resident.error)
            val responses =
                resident.output
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .map(Json::parseToJsonElement)
                    .map { it.jsonObject }
                    .toList()
            assertEquals(
                listOf(1, 2),
                responses.map { it.getValue("id").jsonPrimitive.content.toInt() },
            )
            assertTrue(responses.all { it.getValue("ok").jsonPrimitive.boolean }, resident.error)
            assertContentEquals(
                Files.readAllBytes(residentFirst),
                Files.readAllBytes(residentSecond),
            )

            val created = workingDirectory.resolve("src/main/kotlin/example/Created.kt")
            Files.writeString(created, "package example\nclass Created\n")
            val (createdExit, createdLog) = run("created.json")
            assertEquals(0, createdExit, createdLog)
            assertEquals(
                4,
                shardCount(Files.readAllBytes(workingDirectory.resolve("created.json"))),
            )

            deleteEventually(created)
            val (deletedExit, deletedLog) = run("deleted.json")
            assertEquals(0, deletedExit, deletedLog)
            assertContentEquals(first, Files.readAllBytes(workingDirectory.resolve("deleted.json")))

            val originalBuild = Files.readString(buildScript, StandardCharsets.UTF_8)
            Files.writeString(buildScript, originalBuild.replace("2.3.20", "2.2.21"))
            val (mismatchExit, mismatchLog) = run("mismatch.json")
            assertNotEquals(0, mismatchExit)
            assertTrue(
                mismatchLog.contains(
                    "Kotlin graph exporter supports Kotlin Gradle Plugin 2.3.20 exactly"
                ),
                mismatchLog,
            )

            Files.writeString(
                buildScript,
                """
                plugins {
                    id 'org.jetbrains.kotlin.multiplatform' version '2.3.20'
                }
                repositories { mavenCentral() }
                kotlin { jvm() }
                """
                    .trimIndent(),
            )
            val (multiplatformExit, multiplatformLog) = run("multiplatform.json")
            assertNotEquals(0, multiplatformExit)
            assertTrue(
                multiplatformLog.contains(
                    "Kotlin graph exporter declines multiplatform project ':'; only Kotlin/JVM is supported"
                ),
                multiplatformLog,
            )
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    private fun assertGraphContract(bytes: ByteArray) {
        val graph = Json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)).jsonObject
        val producer = graph.getValue("producer").jsonObject
        assertEquals("scip-kotlinc-k2-graph", producer.getValue("name").jsonPrimitive.content)
        val capabilities = producer.getValue("capabilities").jsonObject
        assertTrue(capabilities.getValue("atomicGenerations").jsonPrimitive.boolean)
        assertTrue(capabilities.getValue("incremental").jsonPrimitive.boolean)
        assertTrue(capabilities.getValue("diagnostics").jsonPrimitive.boolean)

        val targets = graph.getValue("targets").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf(":|jvm|main", ":|jvm|test"),
            targets.map { it.getValue("name").jsonPrimitive.content },
        )
        val target = targets.single { it.getValue("name").jsonPrimitive.content == ":|jvm|main" }
        val shards = target.getValue("shards").jsonArray
        assertEquals(2, shards.size)
        val shard =
            shards
                .map { it.jsonObject }
                .single { it.getValue("source").jsonPrimitive.content.endsWith("GraphFixture.kt") }
        val facts =
            shard
                .getValue("edges")
                .jsonArray
                .map { it.jsonObject.getValue("kind").jsonPrimitive.content }
                .toSet()
        assertTrue(
            facts.containsAll(
                setOf(
                    "contains",
                    "exports",
                    "imports",
                    "calls",
                    "accesses",
                    "instantiates",
                    "type_ref",
                    "extends",
                    "implements",
                    "overrides",
                    "decorates",
                    "tests",
                    "references",
                )
            ),
            "missing compiler facts: $facts",
        )
        val unresolved =
            shard.getValue("unresolved").jsonArray.map {
                it.jsonObject.getValue("family").jsonPrimitive.content
            }
        assertTrue("dispatches" in unresolved)
        assertTrue(shard.getValue("diagnostics").jsonArray.isNotEmpty())
        val nodes = shard.getValue("nodes").jsonArray.map { it.jsonObject }
        assertTrue(nodes.any { it.getValue("name").jsonPrimitive.content == "delegated" })
        assertTrue(nodes.any { it.getValue("name").jsonPrimitive.content == "suspended" })
        assertTrue(nodes.any { it.getValue("name").jsonPrimitive.content == "inlined" })
        assertTrue(nodes.any { it.getValue("name").jsonPrimitive.content == "Outcome" })
        assertTrue(nodes.all { it.getValue("origin").jsonPrimitive.content.isNotEmpty() })
    }

    private fun shardCount(bytes: ByteArray): Int =
        Json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8))
            .jsonObject
            .getValue("targets")
            .jsonArray
            .sumOf { it.jsonObject.getValue("shards").jsonArray.size }

    private fun mainUniverse(bytes: ByteArray): String =
        Json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8))
            .jsonObject
            .getValue("targets")
            .jsonArray
            .map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == ":|jvm|main" }
            .getValue("universe")
            .jsonPrimitive
            .content

    private fun assertBuildReportRecordedNonIncrementalReason(reports: Path) {
        val reportFiles =
            Files.walk(reports).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .filter { it.fileName.toString().endsWith(".json") }
                    .toList()
            }
        assertTrue(reportFiles.isNotEmpty(), "Kotlin build reports were not captured")
        val reasons =
            reportFiles.flatMap { report ->
                Json.parseToJsonElement(Files.readString(report, StandardCharsets.UTF_8))
                    .jsonObject["buildOperationRecord"]
                    ?.jsonArray
                    .orEmpty()
                    .flatMap { operation ->
                        operation.jsonObject["icLogLines"]
                            ?.jsonArray
                            .orEmpty()
                            .map { it.jsonPrimitive.content }
                            .filter {
                                it.startsWith("Non-incremental compilation will be performed:")
                            }
                    }
            }
        assertTrue(reasons.isNotEmpty(), "Kotlin build reports recorded no non-incremental reason")
    }

    private fun deleteEventually(path: Path) {
        var failure: Exception? = null
        repeat(20) {
            try {
                Files.deleteIfExists(path)
                return
            } catch (exception: Exception) {
                failure = exception
                Thread.sleep(100)
            }
        }
        throw failure ?: IllegalStateException("unable to delete $path")
    }
}
