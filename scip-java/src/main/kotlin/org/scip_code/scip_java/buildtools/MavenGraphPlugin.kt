package org.scip_code.scip_java.buildtools

import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import org.scip_code.scip_java.Embedded
import org.scip_code.scip_java.ScipJava
import org.scip_code.scip_java.commands.IndexCommand

/** Installs the embedded graph-only Mojo into Maven's selected local repository. */
internal object MavenGraphPlugin {
    private const val group = "org.scip-code"
    private const val artifact = "scip-maven-plugin"
    private const val protocol = "graph-reactor-1"

    data class Installation(val result: ProcessResult, val goal: String?)

    fun install(
        index: IndexCommand,
        mavenScript: String,
        buildCommand: List<String>,
        temporaryDirectory: Path,
    ): Installation {
        val probePom = temporaryDirectory.resolve("maven-local-repository-probe.xml")
        val probeOutput = temporaryDirectory.resolve("maven-local-repository.txt")
        Files.writeString(
            probePom,
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.scip-code.internal</groupId>
              <artifactId>local-repository-probe</artifactId>
              <version>1</version>
            </project>
            """
                .trimIndent(),
            StandardCharsets.UTF_8,
        )
        val probe = mutableListOf(mavenScript)
        probe += projectRepositorySelectionArguments(index.workingDirectory)
        probe += repositorySelectionArguments(buildCommand)
        probe += "--batch-mode"
        probe += listOf("--file", probePom.toString())
        probe += "org.apache.maven.plugins:maven-help-plugin:3.5.2:evaluate"
        probe += "-Dexpression=settings.localRepository"
        probe += "-Doutput=$probeOutput"
        val probeResult =
            index.app.runProcess(
                probe,
                env =
                    mapOf(
                        "MAVEN_BASEDIR" to
                            (System.getenv("MAVEN_BASEDIR") ?: index.workingDirectory.toString())
                    ),
            )
        if (probeResult.exitCode != 0) return Installation(probeResult, null)

        val repository =
            Path.of(Files.readString(probeOutput, StandardCharsets.UTF_8).trim())
                .toAbsolutePath()
                .normalize()
        val version = "${ScipJava.version}-$protocol"
        val artifactDirectory =
            repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version)
        val lockDirectory = artifactDirectory.parent
        Files.createDirectories(lockDirectory)
        FileChannel.open(
                lockDirectory.resolve(".scip-java-graph-plugin.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            .use { channel ->
                channel.lock().use {
                    Files.createDirectories(artifactDirectory)
                    installFile(
                        Embedded.mavenGraphPluginJar(temporaryDirectory),
                        artifactDirectory.resolve("$artifact-$version.jar"),
                    )
                    val pom =
                        """
                        <project xmlns="http://maven.apache.org/POM/4.0.0">
                          <modelVersion>4.0.0</modelVersion>
                          <groupId>$group</groupId>
                          <artifactId>$artifact</artifactId>
                          <version>$version</version>
                          <packaging>maven-plugin</packaging>
                        </project>
                        """
                            .trimIndent()
                    installBytes(
                        pom.toByteArray(StandardCharsets.UTF_8),
                        artifactDirectory.resolve("$artifact-$version.pom"),
                    )
                }
            }
        return Installation(ProcessResult(0), "$group:$artifact:$version:reactor")
    }

    private fun repositorySelectionArguments(arguments: List<String>): List<String> {
        val output = mutableListOf<String>()
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index]
            when {
                argument in setOf("-s", "--settings", "-gs", "--global-settings") -> {
                    output += argument
                    if (index + 1 < arguments.size) output += arguments[++index]
                }
                argument in setOf("-D", "--define") -> {
                    if (
                        index + 1 < arguments.size &&
                            arguments[index + 1].startsWith("maven.repo.local=")
                    ) {
                        output += argument
                        output += arguments[++index]
                    }
                }
                (argument.startsWith("-s") && !argument.startsWith("--") && argument.length > 2) ||
                    (argument.startsWith("-gs") && argument.length > 3) -> output += argument
                argument.startsWith("--settings=") ||
                    argument.startsWith("--global-settings=") ||
                    argument.startsWith("-Dmaven.repo.local=") ||
                    argument.startsWith("--define=maven.repo.local=") -> output += argument
                argument in setOf("-o", "--offline") -> output += argument
            }
            index += 1
        }
        return output
    }

    fun projectRepositorySelectionArguments(root: Path): List<String> {
        val config = root.resolve(".mvn/maven.config")
        if (!Files.isRegularFile(config)) return emptyList()
        val arguments =
            Files.readAllLines(config, StandardCharsets.UTF_8)
            .map(String::trim)
                .filter { value -> value.isNotEmpty() && !value.startsWith("#") }
                .map(::normalizeConfigArgument)
        return repositorySelectionArguments(arguments)
    }

    private fun normalizeConfigArgument(value: String): String {
        val unquoted = value.removeSurrounding("\"")
        for (prefix in listOf(
            "-Dmaven.repo.local=",
            "--define=maven.repo.local=",
            "maven.repo.local=",
            "--settings=",
            "--global-settings=",
        )) {
            if (unquoted.startsWith(prefix)) {
                return prefix + unquoted.removePrefix(prefix).removeSurrounding("\"")
            }
        }
        return unquoted
    }

    private fun installFile(source: Path, destination: Path) {
        val candidate = destination.resolveSibling("${destination.fileName}.tmp")
        Files.copy(source, candidate, StandardCopyOption.REPLACE_EXISTING)
        move(candidate, destination)
    }

    private fun installBytes(bytes: ByteArray, destination: Path) {
        val candidate = destination.resolveSibling("${destination.fileName}.tmp")
        Files.write(candidate, bytes)
        move(candidate, destination)
    }

    private fun move(source: Path, destination: Path) {
        try {
            Files.move(
                source,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
