package org.scip_code.scip_java.buildtools

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import org.scip_code.scip_java.Embedded
import org.scip_code.scip_java.commands.IndexCommand
import org.scip_code.scip_java.commands.KotlinGraphAggregateRunner

class GradleBuildTool(index: IndexCommand) : BuildTool("Gradle", index) {

    override fun usedInCurrentDirectory(): Boolean {
        val gradleFiles = listOf("settings.gradle", "gradlew", "build.gradle", "build.gradle.kts")
        return gradleFiles.any { name -> Files.isRegularFile(index.workingDirectory.resolve(name)) }
    }

    override fun generateScip(): Int {
        val gradleResult = runBuild()
        val graphOutput = index.kotlinGraphOutput
        if (graphOutput != null) {
            if (gradleResult.exitCode != 0) return gradleResult.exitCode
            return KotlinGraphAggregateRunner.run(graphOutput, listOf(targetroot()), index.app)
        }
        if (gradleResult.exitCode == 0) {
            val missing = reportMissingScipOutput()
            if (missing != 0) return missing
        }
        return generateScipFromTargetroot(gradleResult, targetroot(), index)
    }

    /**
     * Diagnose the case where Gradle finished successfully but our SCIP compiler plugin never
     * produced any `.scip` shards, surfacing a clear error pointing at the two known causes.
     * Returns a non-zero exit code when it reports the error so the failure propagates as a return
     * value.
     */
    private fun reportMissingScipOutput(): Int {
        if (containsFileWithSuffix(targetroot(), ".scip")) return 0
        if (!containsFileWithSuffix(index.workingDirectory, ".class")) {
            // Project produced no compiled JVM output — nothing to index, stay quiet.
            return 0
        }
        index.app.reporter.error(
            """scip-java: Gradle finished successfully but produced no SCIP shards in ${targetroot()}.

This means our SCIP compiler plugin was not attached to one or more JavaCompile tasks. Two known causes:

  1. The 'compileOnly' configuration was already resolved before our init script ran.
     Check the Gradle output above for warnings of the form:
       "scip-java: failed to attach SCIP compiler plugin to project '<name>'"
     Workaround: apply the SCIP plugin earlier (e.g. via a settings plugin),
     or restructure the build so that 'compileOnly' is not resolved at evaluation time.

  2. Another Gradle plugin is replacing the compiler arguments we add (rather than appending).
     Verify with:  ./gradlew compileJava --info | grep -- '-Xplugin:scip'
     If '-Xplugin:scip' is missing from the printed javac command, another plugin
     is overwriting JavaCompile.options.compilerArgs.
"""
        )
        return 1
    }

    private fun containsFileWithSuffix(root: Path, suffix: String): Boolean {
        if (!Files.isDirectory(root)) return false
        return try {
            Files.find(
                    root,
                    Integer.MAX_VALUE,
                    { p, attrs -> attrs.isRegularFile && p.fileName.toString().endsWith(suffix) },
                )
                .use { stream -> stream.findFirst().isPresent }
        } catch (_: Exception) {
            false
        }
    }

    fun targetroot(): Path = index.finalTargetroot(defaultTargetroot)

    private val defaultTargetroot: Path = Paths.get("build", "scip-targetroot")

    private fun runBuild(): ProcessResult {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val gradleWrapper =
            index.workingDirectory.resolve(if (windows) "gradlew.bat" else "gradlew")
        val gradleCommand =
            if (
                Files.isRegularFile(gradleWrapper) && (windows || Files.isExecutable(gradleWrapper))
            )
                gradleWrapper.toString()
            else if (windows) "gradle.bat" else "gradle"
        return TemporaryFiles.withDirectory(index) { tmp -> runCompileCommand(tmp, gradleCommand) }
    }

    private fun runCompileCommand(tmp: Path, gradleCommand: String): ProcessResult {
        val script = initScript(tmp).toString()
        val cmd = mutableListOf<String>()
        cmd += gradleCommand
        cmd += "--init-script"
        cmd += script
        cmd += "-Dscip.targetroot=${targetroot()}"
        if (index.kotlinGraphOutput == null) {
            cmd += "--no-daemon"
            cmd += "-Pkotlin.compiler.execution.strategy=in-process"
            cmd +=
                index.finalBuildCommand(listOf("clean", "scipPrintDependencies", "scipCompileAll"))
            targetroot().toFile().deleteRecursively()
        } else {
            cmd += "-Pkotlin.build.report.output=json"
            cmd +=
                "-Pkotlin.build.report.json.directory=${targetroot().resolve("META-INF/kotlin-build-reports")}"
            cmd += index.finalBuildCommand(listOf("samchonCommitKotlinGraph"))
        }
        val result = index.app.runProcess(cmd, env = mapOf("TERM" to "dumb"))
        return Embedded.reportUnexpectedJavacErrors(index.app.reporter, tmp) ?: result
    }

    private fun initScript(tmp: Path): Path {
        val graphArtifact =
            if (index.kotlinGraphOutput == null) null else prepareKotlinGraphArtifact(tmp)
        val pluginpath = graphArtifact?.javacPlugin ?: Embedded.scipJar(tmp)
        val gradlePluginPath = graphArtifact?.gradlePlugin ?: Embedded.gradlePluginJar(tmp)
        val kotlinGradlePluginPath = graphArtifact?.kotlinGradlePlugin
        val scipKotlincPath = graphArtifact?.jar ?: Embedded.scipKotlincJar(tmp)
        val dependenciesPath = targetroot().resolve("dependencies.txt")
        Files.deleteIfExists(dependenciesPath)
        fun scriptPath(path: Path): String = path.toString().replace('\\', '/')

        val script =
            """
             initscript {
                 repositories {
                     mavenCentral()
                 }
                 dependencies{ 
                     classpath(files("${scriptPath(gradlePluginPath)}"))
                 }
             }

             import org.scip_code.scip_java.gradle.ScipGradlePlugin

             ${if (graphArtifact == null) "" else """
             settingsEvaluated { settings ->
               settings.dependencyResolutionManagement.repositories.maven {
                url = new File("${scriptPath(graphArtifact.repository)}")
               }
             }
             """}

             allprojects {
               ${if (kotlinGradlePluginPath == null) "" else """buildscript {
                 dependencies {
                   classpath(files("${scriptPath(kotlinGradlePluginPath)}"))
                 }
               }
               """}
               project.ext["scipTarget"] = "${scriptPath(targetroot())}"
               project.ext["javacPluginJar"] = "${scriptPath(pluginpath)}"
               project.ext["dependenciesOut"] = "${scriptPath(dependenciesPath)}"
               project.ext["scipKotlincJar"] = "${scriptPath(scipKotlincPath)}"
               project.ext["scipKotlinGraphEnabled"] = ${graphArtifact != null}
               ${if (graphArtifact == null) "" else """project.ext["scipKotlincGraphJar"] = "${scriptPath(graphArtifact.jar)}"
               project.ext["scipKotlincGraphRepository"] = "${scriptPath(graphArtifact.repository)}"
               """}
               apply plugin: ScipGradlePlugin
             }
            """
                .trimIndent()

        val out = graphArtifact?.initScript ?: tmp.resolve("init-script.gradle")
        writeIfChanged(out, script.toByteArray(StandardCharsets.UTF_8))
        return out
    }

    private data class KotlinGraphArtifact(
        val repository: Path,
        val jar: Path,
        val javacPlugin: Path,
        val gradlePlugin: Path,
        val kotlinGradlePlugin: Path,
        val initScript: Path,
    )

    private fun prepareKotlinGraphArtifact(tmp: Path): KotlinGraphArtifact {
        val kotlinc = Files.readAllBytes(Embedded.scipKotlincJar(tmp))
        val javac = Files.readAllBytes(Embedded.scipJar(tmp))
        val gradle = Files.readAllBytes(Embedded.gradlePluginJar(tmp))
        val kotlinGradle = Files.readAllBytes(Embedded.kotlinGradlePluginJar(tmp))
        val bundle = contentDigest(kotlinc, javac, gradle, kotlinGradle)
        val tools =
            targetroot().resolve("META-INF/kotlin-graph-tools").resolve(bundle).toAbsolutePath()
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
        return KotlinGraphArtifact(
            repository,
            artifact,
            persistentJavac,
            persistentGradle,
            persistentKotlinGradle,
            tools.resolve("init-script.gradle"),
        )
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
