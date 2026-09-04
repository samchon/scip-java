package org.scip_code.scip_java.buildtools

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import org.scip_code.scip_java.Embedded

/** Persistent, content-addressed files shared by one-shot and resident Kotlin graph builds. */
object KotlinGraphGradleIntegration {
    data class Prepared(val targetRoot: Path, val initScript: Path)

    fun prepare(projectRoot: Path, targetRoot: Path, temporary: Path): Prepared {
        val kotlinc = Files.readAllBytes(Embedded.scipKotlincJar(temporary))
        val javac = Files.readAllBytes(Embedded.scipJar(temporary))
        val gradle = Files.readAllBytes(Embedded.gradlePluginJar(temporary))
        val kotlinGradle = Files.readAllBytes(Embedded.kotlinGradlePluginJar(temporary))
        val bundle = contentDigest(kotlinc, javac, gradle, kotlinGradle)
        val tools =
            targetRoot.resolve("META-INF/kotlin-graph-tools").resolve(bundle).toAbsolutePath()
        val repository = tools.resolve("repository")
        val artifact =
            repository.resolve(
                "org/scip-code/scip-kotlinc-k2-graph/2.3.20-e940c188/scip-kotlinc-k2-graph-2.3.20-e940c188.jar"
            )
        writeIfChanged(artifact, kotlinc)
        val pom =
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.scip-code</groupId>
              <artifactId>scip-kotlinc-k2-graph</artifactId>
              <version>2.3.20-e940c188</version>
            </project>
            """
                .trimIndent() + "\n"
        writeIfChanged(
            artifact.resolveSibling("scip-kotlinc-k2-graph-2.3.20-e940c188.pom"),
            pom.toByteArray(StandardCharsets.UTF_8),
        )
        val persistentJavac = tools.resolve("embedded/scip-plugin.jar")
        val persistentGradle = tools.resolve("embedded/gradle-plugin.jar")
        val persistentKotlinGradle = tools.resolve("embedded/kotlin-gradle-plugin.jar")
        writeIfChanged(persistentJavac, javac)
        writeIfChanged(persistentGradle, gradle)
        writeIfChanged(persistentKotlinGradle, kotlinGradle)

        fun scriptPath(path: Path): String = path.toString().replace('\\', '/')
        val sourceRoot = projectRoot.toAbsolutePath().normalize()
        val script =
            """
            initscript {
                repositories {
                    mavenCentral()
                }
                dependencies{
                    classpath(files("${scriptPath(persistentGradle)}"))
                }
            }

            import org.scip_code.scip_java.gradle.ScipGradlePlugin

            settingsEvaluated { settings ->
              settings.dependencyResolutionManagement.repositories.maven {
               url = new File("${scriptPath(repository)}")
              }
            }

            allprojects {
              buildscript {
                dependencies {
                  classpath(files("${scriptPath(persistentKotlinGradle)}"))
                }
              }
              project.ext["scipTarget"] = "${scriptPath(targetRoot)}"
              project.ext["javacPluginJar"] = "${scriptPath(persistentJavac)}"
              project.ext["dependenciesOut"] = "${scriptPath(targetRoot.resolve("dependencies.txt"))}"
              project.ext["scipKotlincJar"] = "${scriptPath(artifact)}"
              project.ext["scipKotlinGraphEnabled"] = true
              project.ext["scipKotlincGraphJar"] = "${scriptPath(artifact)}"
              project.ext["scipKotlincGraphRepository"] = "${scriptPath(repository)}"
              apply plugin: ScipGradlePlugin
            }
            """
                .trimIndent() + "\n"
        val initScript = tools.resolve("init-script.gradle")
        writeIfChanged(initScript, script.toByteArray(StandardCharsets.UTF_8))
        return Prepared(targetRoot.toAbsolutePath().normalize(), initScript)
    }

    private fun contentDigest(vararg inputs: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (input in inputs) {
            digest.update(input.size.toString().toByteArray(StandardCharsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(input)
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun writeIfChanged(output: Path, bytes: ByteArray) {
        if (Files.isRegularFile(output) && Files.readAllBytes(output).contentEquals(bytes)) return
        Files.createDirectories(output.parent)
        val temporary =
            output.resolveSibling("${output.fileName}.tmp-${ProcessHandle.current().pid()}")
        Files.write(temporary, bytes)
        try {
            Files.move(
                temporary,
                output,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
