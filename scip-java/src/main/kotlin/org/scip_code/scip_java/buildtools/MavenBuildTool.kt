package org.scip_code.scip_java.buildtools

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.scip_code.scip_java.Embedded
import org.scip_code.scip_java.commands.IndexCommand

class MavenBuildTool(index: IndexCommand) : BuildTool("Maven", index) {

    override fun usedInCurrentDirectory(): Boolean =
        Files.isRegularFile(index.workingDirectory.resolve("pom.xml"))

    override fun generateScip(): Int {
        val graphStore =
            index.graphOutput?.let {
                MavenGraphGenerationStore(
                    index.finalTargetroot(defaultTargetroot),
                    index.workingDirectory,
                    graphTarget,
                )
            }
        graphStore?.prepare()
        val result = runBuild(graphStore?.staging)
        if (result.exitCode == 0) graphStore?.commit()
        return generateScipFromTargetroot(result, index.finalTargetroot(defaultTargetroot), index)
    }

    private val defaultTargetroot: Path = Paths.get("target", "scip-targetroot")
    private val graphTarget = "maven"

    private fun runBuild(graphRoot: Path?): ProcessResult =
        TemporaryFiles.withDirectory(index) { tmp ->
            val mvnw = index.workingDirectory.resolve("mvnw")
            val windowsMvnw = index.workingDirectory.resolve("mvnw.cmd")
            val mavenScript =
                if (java.io.File.separatorChar == '\\' && Files.isRegularFile(windowsMvnw))
                    windowsMvnw.toString()
                else if (Files.isRegularFile(mvnw) && Files.isExecutable(mvnw)) mvnw.toString()
                else if (java.io.File.separatorChar == '\\') "mvn.cmd" else "mvn"
            val javac =
                Embedded.customJavac(
                    index.workingDirectory,
                    index.finalTargetroot(defaultTargetroot),
                    graphRoot,
                    graphRoot?.let { graphTarget },
                    tmp,
                )
            val command = mutableListOf<String>()
            command += mavenScript
            if (graphRoot == null) command += "-Dmaven.compiler.useIncrementalCompilation=false"
            // NOTE(olafur): the square/javapoet repo sets compilerId to
            // 'javac-with-javac', which appears to override the
            // '-Dmaven.compiler.executable' setting. Forcing the compilerId to
            // 'javac' fixes the issue for this repo.
            command += "-Dmaven.compiler.compilerId=javac"
            command += "-Dmaven.compiler.executable=${javac.executable}"
            command += "-Dmaven.compiler.fork=true"
            command +=
                index.finalBuildCommand(
                    listOf(
                        "--batch-mode",
                        *(if (graphRoot == null) arrayOf("clean") else emptyArray()),
                        // Default to the "verify" command, as recommended by the official docs
                        // https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html#usual-command-line-calls
                        "verify",
                        "-DskipTests",
                    )
                )

            val exit = index.app.runProcess(command, env = javac.environment)
            Embedded.reportUnexpectedJavacErrors(index.app.reporter, tmp) ?: exit
        }
}
