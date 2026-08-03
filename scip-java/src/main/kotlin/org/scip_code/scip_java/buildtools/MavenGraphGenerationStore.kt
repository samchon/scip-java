package org.scip_code.scip_java.buildtools

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Maven-owned immutable graph generations with an atomically replaced current pointer. */
internal class MavenGraphGenerationStore(
    targetRoot: Path,
    private val sourceRoot: Path,
    private val target: String,
) {
    private val sha256 = Regex("[0-9a-f]{64}")
    private val targetRoot = targetRoot.toAbsolutePath().normalize()
    private val storeRoot = this.targetRoot.resolve("META-INF/scip-graph-store")
    private val targetKey = digest(target.toByteArray(StandardCharsets.UTF_8))
    private val outputRoot = storeRoot.resolve("targets").resolve(targetKey)
    internal val staging: Path = outputRoot.resolve("staging")
    private val generations = outputRoot.resolve("generations")
    internal val current: Path = outputRoot.resolve("CURRENT")

    /** Start from the last committed generation without publishing any new state. */
    fun prepare() {
        deleteTree(staging)
        Files.createDirectories(staging)
        Files.deleteIfExists(targetRoot.resolve("javacopts.txt"))
        val prior = currentGeneration() ?: return
        copyTree(prior, staging)
        Files.deleteIfExists(staging.resolve("TARGET"))
        Files.deleteIfExists(staging.resolve("TARGETS"))
        Files.deleteIfExists(staging.resolve("SOURCES"))
    }

    /** Reconcile deleted sources and publish only after Maven completed successfully. */
    fun commit() {
        val shards = graphShards(staging)
        for (shard in shards) {
            val source = shardSource(shard)
            val sourcePath = Path.of(source)
            val absolute =
                if (sourcePath.isAbsolute) sourcePath.normalize()
                else sourceRoot.toAbsolutePath().normalize().resolve(sourcePath).normalize()
            if (!Files.isRegularFile(absolute)) Files.deleteIfExists(shard)
        }
        deleteTree(staging.resolve(".seen"))
        deleteEmptyDirectories(staging)
        val metadata = graphShards(staging).map(::shardMetadata)
        metadata.forEach(::validateSource)
        val active = metadata.map(ShardMetadata::source).distinct().sortedWith(::compareUtf8)
        val targets = metadata.map(ShardMetadata::target).distinct().sortedWith(::compareUtf8)
        reconcileCompilerUniverses(targets)
        writeAtomic(staging.resolve("TARGETS"), targets)
        writeAtomic(staging.resolve("SOURCES"), active)
        writeAtomic(staging.resolve("UNIVERSE"), universe())

        val generation = generationDigest(staging)
        Files.createDirectories(generations)
        val committed = generations.resolve(generation)
        if (Files.exists(committed)) deleteTree(staging) else move(staging, committed, false)

        Files.createDirectories(current.parent)
        val temporary = current.resolveSibling("CURRENT.tmp-${ProcessHandle.current().pid()}")
        Files.writeString(temporary, "$generation\n", StandardCharsets.UTF_8)
        move(temporary, current, true)
        writeAtomic(storeRoot.resolve("MANIFEST"), listOf("$targetKey $generation"))
        pruneGenerations(generation)
    }

    private fun currentGeneration(): Path? {
        if (!Files.isRegularFile(current)) return null
        val generation = Files.readString(current, StandardCharsets.UTF_8).trim()
        require(generation.matches(Regex("[0-9a-f]{64}"))) {
            "scip-java: invalid Maven graph CURRENT pointer at $current"
        }
        val committed = generations.resolve(generation).normalize()
        require(committed.startsWith(generations) && Files.isDirectory(committed)) {
            "scip-java: Maven graph CURRENT pointer names no generation at $current"
        }
        return committed
    }

    private fun shardSource(shard: Path): String = shardMetadata(shard).source

    private fun shardMetadata(shard: Path): ShardMetadata {
        val parsed =
            runCatching { Json.parseToJsonElement(Files.readString(shard)).jsonObject }
                .getOrElse {
                    throw IllegalStateException("scip-java: malformed Java graph shard $shard", it)
                }
        val source = parsed["source"]?.jsonPrimitive?.content
        val shardTarget = parsed["target"]?.jsonPrimitive?.content
        val checkerDigest = parsed["checkerDigest"]?.jsonPrimitive?.content
        val diskDigest = parsed["diskDigest"]?.jsonPrimitive?.content
        val schemaVersion = parsed["schemaVersion"]?.jsonPrimitive?.intOrNull
        require(schemaVersion == 1) { "scip-java: unsupported Java graph shard schema at $shard" }
        require(!source.isNullOrEmpty()) { "scip-java: Java graph shard has no source at $shard" }
        require(!shardTarget.isNullOrEmpty()) {
            "scip-java: Java graph shard has no target at $shard"
        }
        require(checkerDigest != null && sha256.matches(checkerDigest)) {
            "scip-java: Java graph shard has invalid checker digest at $shard"
        }
        require(diskDigest != null && (diskDigest.isEmpty() || sha256.matches(diskDigest))) {
            "scip-java: Java graph shard has invalid disk digest at $shard"
        }
        return ShardMetadata(source, shardTarget, diskDigest)
    }

    private fun validateSource(metadata: ShardMetadata) {
        if (metadata.diskDigest.isEmpty()) return
        val source = sourceRoot.toAbsolutePath().normalize().resolve(metadata.source).normalize()
        require(
            source.startsWith(sourceRoot.toAbsolutePath().normalize()) &&
                Files.isRegularFile(source) &&
                digest(Files.readAllBytes(source)) == metadata.diskDigest
        ) {
            "scip-java: Java graph source moved after compilation: ${metadata.source}"
        }
    }

    private fun graphShards(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter { it.fileName.toString().endsWith(".graph.json") }
                .sorted()
                .toList()
        }
    }

    private fun universe(): List<String> {
        val rows = mutableListOf<String>()
        rows += "java.version=${System.getProperty("java.version", "")}"
        rows += "java.home=${normalizedPath(Path.of(System.getProperty("java.home", "")))}"
        if (Files.isDirectory(sourceRoot)) {
            Files.walk(sourceRoot).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .filter { it.fileName.toString() == "pom.xml" }
                    .filter { path ->
                        sourceRoot.relativize(path).none { part ->
                            part.toString() in setOf("target", "build", ".git")
                        }
                    }
                    .sorted()
                    .forEach { pom ->
                        rows += "pom=${normalizedPath(pom)}:${digest(Files.readAllBytes(pom))}"
                    }
            }
        }
        val compilerUniverses = staging.resolve(".universe")
        if (Files.isDirectory(compilerUniverses)) {
            Files.list(compilerUniverses).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .filter { it.fileName.toString().endsWith(".args") }
                    .sorted()
                    .forEach { options ->
                        rows +=
                            "compilerTarget=${options.fileName}:" +
                                digest(Files.readAllBytes(options))
                    }
            }
        }
        return rows.distinct().sortedWith(::compareUtf8)
    }

    private fun reconcileCompilerUniverses(targets: List<String>) {
        val root = staging.resolve(".universe")
        if (!Files.isDirectory(root)) return
        deleteTree(root.resolve(".seen"))
        val active =
            targets.map { digest(it.toByteArray(StandardCharsets.UTF_8)) + ".args" }.toSet()
        Files.list(root).use { paths ->
            for (path in paths.filter(Files::isRegularFile).toList()) {
                if (path.fileName.toString() !in active) Files.deleteIfExists(path)
            }
        }
        deleteEmptyDirectories(root)
    }

    private fun normalizedPath(path: Path): String {
        val normalized = path.toAbsolutePath().normalize()
        val value =
            when {
                normalized.startsWith(sourceRoot) -> sourceRoot.relativize(normalized)
                normalized.startsWith(targetRoot) -> targetRoot.relativize(normalized)
                else -> normalized
            }
        return value.toString().replace('\\', '/')
    }

    private fun currentFiles(root: Path): List<Path> =
        Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).sorted().toList() }

    private fun generationDigest(root: Path): String {
        val hash = MessageDigest.getInstance("SHA-256")
        for (file in currentFiles(root)) {
            val relative = root.relativize(file).toString().replace('\\', '/')
            update(hash, relative.toByteArray(StandardCharsets.UTF_8))
            update(hash, Files.readAllBytes(file))
        }
        return HexFormat.of().formatHex(hash.digest())
    }

    private fun copyTree(source: Path, destination: Path) {
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    Files.createDirectories(destination.resolve(source.relativize(directory)))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    val output = destination.resolve(source.relativize(file))
                    Files.copy(file, output, StandardCopyOption.REPLACE_EXISTING)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun deleteEmptyDirectories(root: Path) {
        if (!Files.isDirectory(root)) return
        Files.walk(root).use { paths ->
            for (directory in
                paths.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList()) {
                if (directory != root) {
                    Files.list(directory).use { children ->
                        if (children.findAny().isEmpty) Files.deleteIfExists(directory)
                    }
                }
            }
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    directory: Path,
                    exception: java.io.IOException?,
                ): FileVisitResult {
                    if (exception != null) throw exception
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun move(source: Path, destination: Path, replace: Boolean) {
        val options = mutableListOf(StandardCopyOption.ATOMIC_MOVE)
        if (replace) options += StandardCopyOption.REPLACE_EXISTING
        try {
            Files.move(source, destination, *options.toTypedArray())
        } catch (_: AtomicMoveNotSupportedException) {
            if (replace) Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
            else Files.move(source, destination)
        }
    }

    private fun pruneGenerations(retained: String) {
        if (!Files.isDirectory(generations)) return
        Files.list(generations).use { paths ->
            for (generation in paths.filter(Files::isDirectory).toList()) {
                if (generation.fileName.toString() != retained) deleteTree(generation)
            }
        }
    }

    private fun writeAtomic(output: Path, lines: List<String>) {
        val temporary =
            output.resolveSibling("${output.fileName}.tmp-${ProcessHandle.current().pid()}")
        val text = if (lines.isEmpty()) "" else lines.joinToString(separator = "\n", postfix = "\n")
        Files.writeString(temporary, text, StandardCharsets.UTF_8)
        move(temporary, output, true)
    }

    private fun update(hash: MessageDigest, value: ByteArray) {
        hash.update(value.size.toString().toByteArray(StandardCharsets.UTF_8))
        hash.update(':'.code.toByte())
        hash.update(value)
    }

    private fun digest(value: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))

    private fun compareUtf8(left: String, right: String): Int =
        java.util.Arrays.compareUnsigned(
            left.toByteArray(StandardCharsets.UTF_8),
            right.toByteArray(StandardCharsets.UTF_8),
        )

    private data class ShardMetadata(val source: String, val target: String, val diskDigest: String)
}
