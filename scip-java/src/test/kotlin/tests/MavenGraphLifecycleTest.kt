package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.scip_code.scip_java.ScipJava

class MavenGraphLifecycleTest : BuildToolHarness() {
    @Test
    fun publishesAtomicMultiModuleGenerationsOnWindowsAndPosix() {
        val base = newTempBase()
        try {
            val workspace = Files.createDirectories(base.resolve("working Directory 한글"))
            val cache = Files.createDirectories(base.resolve("cache"))
            val mavenRepository = cache.resolve("maven repository")
            writeProject(workspace, mavenRepository)
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
                    "compile",
                )

            val first = runScipJava(workspace, arguments)
            assertEquals(0, first.first, first.second)
            val graphPluginVersion = "${ScipJava.version}-graph-reactor-1"
            assertTrue(
                Files.isRegularFile(
                    mavenRepository.resolve(
                        "org/scip-code/scip-maven-plugin/$graphPluginVersion/scip-maven-plugin-$graphPluginVersion.jar"
                    )
                )
            )
            assertEquals(listOf("maven:module-a", "maven:module-b"), artifactTargets(artifact))
            assertEquals(
                listOf(
                    "module-a/src/main/java/example/A.java",
                    "module-b/src/main/java/example/B.java",
                ),
                artifactSources(artifact),
            )
            val firstArtifact = Files.readAllBytes(artifact)
            val firstPointer = currentPointer(targetroot)
            assertEquals(2, compilerUniverseCount(targetroot, firstPointer))

            val second = runScipJava(workspace, arguments)
            assertEquals(0, second.first, second.second)
            assertTrue(firstArtifact.contentEquals(Files.readAllBytes(artifact)))
            assertEquals(firstPointer, currentPointer(targetroot))

            val pom = workspace.resolve("pom.xml")
            Files.writeString(
                pom,
                Files.readString(pom)
                    .replace(
                        "<maven.compiler.release>17</maven.compiler.release>",
                        "<maven.compiler.release>17</maven.compiler.release><maven.compiler.parameters>true</maven.compiler.parameters>",
                    ),
            )
            val configured = runScipJava(workspace, arguments)
            assertEquals(0, configured.first, configured.second)
            val configuredPointer = currentPointer(targetroot)
            assertNotEquals(firstPointer, configuredPointer)

            val sourceA = workspace.resolve("module-a/src/main/java/example/A.java")
            Files.writeString(
                sourceA,
                "package example; public class A { int value() { return 2; } }\n",
            )
            val changed = runScipJava(workspace, arguments)
            assertEquals(0, changed.first, changed.second)
            val changedArtifact = Files.readAllBytes(artifact)
            val changedPointer = currentPointer(targetroot)
            assertNotEquals(configuredPointer, changedPointer)
            assertEquals(2, compilerUniverseCount(targetroot, changedPointer))

            Files.writeString(sourceA, "package example; public class A {\n")
            val failed = runScipJava(workspace, arguments)
            assertNotEquals(0, failed.first, failed.second)
            assertEquals(changedPointer, currentPointer(targetroot))
            assertTrue(changedArtifact.contentEquals(Files.readAllBytes(artifact)))

            Files.writeString(sourceA, "package example; public class A {}\n")
            val sourceB = workspace.resolve("module-b/src/main/java/example/B.java")
            Files.delete(sourceB)
            val deleted = runScipJava(workspace, arguments)
            assertEquals(0, deleted.first, deleted.second)
            assertEquals(listOf("module-a/src/main/java/example/A.java"), artifactSources(artifact))

            val created = workspace.resolve("module-b/src/main/java/example/C.java")
            write(created, "package example; public class C {}\n")
            assertEquals(0, runScipJava(workspace, arguments).first)
            assertEquals(
                listOf(
                    "module-a/src/main/java/example/A.java",
                    "module-b/src/main/java/example/C.java",
                ),
                artifactSources(artifact),
            )

            val renamed = created.resolveSibling("D.java")
            Files.move(created, renamed)
            Files.writeString(renamed, "package example; public class D {}\n")
            assertEquals(0, runScipJava(workspace, arguments).first)
            assertEquals(
                listOf(
                    "module-a/src/main/java/example/A.java",
                    "module-b/src/main/java/example/D.java",
                ),
                artifactSources(artifact),
            )

            val rootPom = workspace.resolve("pom.xml")
            Files.writeString(
                rootPom,
                Files.readString(rootPom).replace("<module>module-b</module>", ""),
            )
            assertEquals(0, runScipJava(workspace, arguments).first)
            assertEquals(listOf("maven:module-a"), artifactTargets(artifact))
            assertEquals(listOf("module-a/src/main/java/example/A.java"), artifactSources(artifact))
            assertTrue(Files.isRegularFile(renamed))

            val withUserOutput = arguments.dropLast(1) + listOf("compile", "-Doutput=user-value")
            val userOutput = runScipJava(workspace, withUserOutput)
            assertEquals(0, userOutput.first, userOutput.second)
            assertEquals(listOf("maven:module-a", "maven:output-poison"), artifactTargets(artifact))

            val buildOutputs =
                listOf("target", "module-a/target", "module-b/target", "output-poison/target")
            fun coldArtifact(temporary: Path): ByteArray {
                for (output in buildOutputs) {
                    assertTrue(workspace.resolve(output).toFile().deleteRecursively())
                }
                assertTrue(targetroot.toFile().deleteRecursively())
                assertTrue(Files.notExists(targetroot))
                Files.deleteIfExists(artifact)
                val cold = arguments.toMutableList()
                cold[2] = temporary.toString()
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

    private fun writeProject(root: Path, repository: Path) {
        write(
            root.resolve(".mvn/maven.config"),
            "-q\n-Dmaven.repo.local=${repository.toAbsolutePath().normalize()}\n",
        )
        write(
            root.resolve("pom.xml"),
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>example</groupId><artifactId>root</artifactId><version>1</version>
              <packaging>pom</packaging>
              <modules><module>module-a</module><module>module-b</module></modules>
              <profiles><profile><id>output-property-must-remain-unset</id>
                <activation><property><name>output</name></property></activation>
                <modules><module>output-poison</module></modules>
              </profile></profiles>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
              <build><pluginManagement><plugins><plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version>
              </plugin></plugins></pluginManagement></build>
            </project>
            """
                .trimIndent(),
        )
        for (module in listOf("module-a", "module-b", "output-poison")) {
            write(
                root.resolve("$module/pom.xml"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>example</groupId><artifactId>root</artifactId><version>1</version></parent>
                  <artifactId>$module</artifactId>
                </project>
                """
                    .trimIndent(),
            )
        }
        write(
            root.resolve("module-a/src/main/java/example/A.java"),
            "package example; public class A {}\n",
        )
        write(
            root.resolve("module-b/src/main/java/example/B.java"),
            "package example; public class B {}\n",
        )
        write(
            root.resolve("output-poison/src/main/java/example/Output.java"),
            "package example; public class Output {}\n",
        )
    }

    private fun artifactTargets(artifact: Path): List<String> =
        artifactRoot(artifact)["targets"]!!
            .jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
            .sorted()

    private fun artifactSources(artifact: Path): List<String> =
        artifactRoot(artifact)["targets"]!!
            .jsonArray
            .flatMap { it.jsonObject["shards"]!!.jsonArray }
            .map { it.jsonObject["source"]!!.jsonPrimitive.content }
            .sorted()

    private fun artifactRoot(artifact: Path) =
        Json.parseToJsonElement(Files.readString(artifact, StandardCharsets.UTF_8)).jsonObject

    private fun currentPointer(targetroot: Path): String {
        val targets = targetroot.resolve("META-INF/scip-graph-store/targets")
        return Files.walk(targets).use { paths ->
            val pointers =
                paths
                    .filter(Files::isRegularFile)
                    .filter { it.fileName.toString() == "CURRENT" }
                    .toList()
            assertEquals(1, pointers.size)
            Files.readString(pointers.single()).trim()
        }
    }

    private fun compilerUniverseCount(targetroot: Path, generation: String): Int {
        val targets = targetroot.resolve("META-INF/scip-graph-store/targets")
        return Files.walk(targets).use { paths ->
            val universes =
                paths
                    .filter(Files::isRegularFile)
                    .filter { it.fileName.toString().endsWith(".args") }
                    .filter { path ->
                        val universe =
                            if (path.parent.fileName.toString().endsWith(".args.d"))
                                path.parent.parent
                            else path.parent
                        universe.fileName.toString() == ".universe" &&
                            universe.parent.fileName.toString() == generation
                    }
                    .map { path ->
                        if (path.parent.fileName.toString().endsWith(".args.d")) path.parent
                        else path
                    }
                    .distinct()
                    .toList()
            universes.size
        }
    }

    private fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, text, StandardCharsets.UTF_8)
    }
}
