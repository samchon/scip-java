package org.scip_code.scip_java.buildtools

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.scip_code.scip_java.Embedded
import org.scip_code.scip_java.commands.IndexCommand

class GradleBuildTool(index: IndexCommand) : BuildTool("Gradle", index) {

    override fun usedInCurrentDirectory(): Boolean {
        val gradleFiles =
            listOf("settings.gradle", "gradlew", "gradlew.bat", "build.gradle", "build.gradle.kts")
        return gradleFiles.any { name -> Files.isRegularFile(index.workingDirectory.resolve(name)) }
    }

    override fun generateScip(): Int {
        val gradleResult = runBuild()
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
        val gradleWrapper = index.workingDirectory.resolve("gradlew")
        val windowsWrapper = index.workingDirectory.resolve("gradlew.bat")
        val gradleCommand =
            if (java.io.File.separatorChar == '\\' && Files.isRegularFile(windowsWrapper))
                windowsWrapper.toString()
            else if (Files.isRegularFile(gradleWrapper) && Files.isExecutable(gradleWrapper))
                gradleWrapper.toString()
            else "gradle"
        return TemporaryFiles.withDirectory(index) { tmp -> runCompileCommand(tmp, gradleCommand) }
    }

    private fun runCompileCommand(tmp: Path, gradleCommand: String): ProcessResult {
        val script = initScript(tmp).toString()
        val cmd = mutableListOf<String>()
        cmd += gradleCommand
        if (index.graphOutput == null) cmd += "--no-daemon"
        cmd += "--init-script"
        cmd += script
        cmd += "-Dscip.targetroot=${targetroot()}"
        if (index.graphOutput == null) cmd += "-Pkotlin.compiler.execution.strategy=in-process"
        val defaults =
            if (index.graphOutput == null)
                listOf("clean", "scipPrintDependencies", "scipCompileAll")
            else listOf("scipPrintDependencies", "scipCompileAll")
        val buildCommand = index.finalBuildCommand(defaults)
        cmd += buildCommand
        if (
            index.graphOutput != null &&
                buildCommand.none { it.substringAfterLast(':') == "scipCommitJavaGraph" }
        ) {
            cmd += "scipCommitJavaGraph"
        }

        if (index.graphOutput == null) targetroot().toFile().deleteRecursively()
        val result = index.app.runProcess(cmd, env = mapOf("TERM" to "dumb"))
        return Embedded.reportUnexpectedJavacErrors(index.app.reporter, tmp) ?: result
    }

    private fun initScript(tmp: Path): Path {
        val pluginpath = Embedded.scipJar(tmp)
        val gradlePluginPath = Embedded.gradlePluginJar(tmp)
        val scipKotlincPath = Embedded.scipKotlincJar(tmp)
        val dependenciesPath = targetroot().resolve("dependencies.txt")
        Files.deleteIfExists(dependenciesPath)

        val script =
            """
             initscript {
                 dependencies{ 
                     classpath(files(${groovyString(gradlePluginPath.toString())}))
                 }
             }

             import org.scip_code.scip_java.gradle.ScipGradlePlugin

             allprojects {
               project.ext["scipTarget"] = ${groovyString(targetroot().toString())}
               project.ext["javacPluginJar"] = ${groovyString(pluginpath.toString())}
               project.ext["dependenciesOut"] = ${groovyString(dependenciesPath.toString())}
               project.ext["scipKotlincJar"] = ${groovyString(scipKotlincPath.toString())}
               project.ext["scipGraphEnabled"] = ${index.graphOutput != null}
               apply plugin: ScipGradlePlugin
             }
            """
                .trimIndent()

        val out = tmp.resolve("init-script.gradle")
        Files.write(out, script.toByteArray(StandardCharsets.UTF_8))
        return out
    }

    private fun groovyString(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
