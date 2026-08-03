package org.scip_code.scip_java.commands

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.scip_code.scip_java.ScipJava
import org.scip_code.scip_java.ScipJavaApp

/** Aggregates only committed javac graph generations into one atomic producer artifact. */
object GraphAggregateRunner {
    private const val shardSuffix = ".graph.json"
    private val sha256 = Regex("[0-9a-f]{64}")

    fun run(output: Path, targetroots: List<Path>, app: ScipJavaApp): Int {
        val project = app.env.workingDirectory.toAbsolutePath().normalize()
        val roots = targetroots.map { project.resolve(it).normalize() }
        val targets = mutableListOf<JsonObject>()
        val names = mutableSetOf<String>()
        try {
            for (root in roots) {
                for (target in committedTargets(root, app)) {
                    val name = target["name"]!!.jsonPrimitive.content
                    if (!names.add(name)) {
                        return fail(app, "scip-java: duplicate committed graph target '$name'")
                    }
                    targets += target
                }
                for (target in directTargets(root, app)) {
                    val name = target["name"]!!.jsonPrimitive.content
                    if (!names.add(name)) {
                        return fail(app, "scip-java: duplicate direct graph target '$name'")
                    }
                    targets += target
                }
            }
        } catch (_: GraphAggregationException) {
            return 1
        }
        if (targets.isEmpty()) {
            app.error("scip-java: no committed Java graph shards were produced")
            return 1
        }
        targets.sortBy { it["name"]!!.jsonPrimitive.content }
        val artifact =
            JsonObject(
                linkedMapOf(
                    "schemaVersion" to JsonPrimitive(1),
                    "projectRoot" to JsonPrimitive(project.toString()),
                    "producer" to
                        JsonObject(
                            linkedMapOf(
                                "name" to JsonPrimitive("scip-java-javac-graph"),
                                "version" to JsonPrimitive(ScipJava.version),
                                "protocolVersion" to JsonPrimitive(1),
                                "capabilities" to
                                    JsonObject(
                                        linkedMapOf(
                                            "atomicGenerations" to JsonPrimitive(true),
                                            "incremental" to JsonPrimitive(true),
                                            "diagnostics" to JsonPrimitive(false),
                                        )
                                    ),
                            )
                        ),
                    "targets" to JsonArray(targets),
                )
            )
        writeAtomic(project.resolve(output), artifact.toString() + "\n")
        return 0
    }

    private fun committedTargets(root: Path, app: ScipJavaApp): List<JsonObject> {
        val storeRoot = root.resolve("META-INF/scip-graph-store")
        val targetsRoot = storeRoot.resolve("targets")
        if (!Files.isDirectory(targetsRoot)) return emptyList()
        val manifest = storeRoot.resolve("MANIFEST")
        if (!Files.isRegularFile(manifest)) {
            fail(app, "scip-java: Java graph store has no committed MANIFEST at $manifest")
        }
        val seen = mutableSetOf<String>()
        return Files.readAllLines(manifest, StandardCharsets.UTF_8)
            .filter { it.isNotEmpty() }
            .flatMap { line ->
                val fields = line.split(' ')
                if (fields.size != 2 || !sha256.matches(fields[0]) || !sha256.matches(fields[1])) {
                    fail(app, "scip-java: invalid Java graph MANIFEST entry at $manifest")
                }
                if (!seen.add(fields[0])) {
                    fail(app, "scip-java: duplicate Java graph MANIFEST target at $manifest")
                }
                committedGeneration(targetsRoot.resolve(fields[0]), fields[1], app)
            }
    }

    private fun committedGeneration(
        targetRoot: Path,
        manifestGeneration: String?,
        app: ScipJavaApp,
    ): List<JsonObject> {
        val current = targetRoot.resolve("CURRENT")
        val generation =
            manifestGeneration ?: Files.readString(current, StandardCharsets.UTF_8).trim()
        if (!sha256.matches(generation)) {
            fail(app, "scip-java: invalid Java graph CURRENT pointer at $current")
        }
        val committed = targetRoot.resolve("generations").resolve(generation).normalize()
        if (!committed.startsWith(targetRoot) || !Files.isDirectory(committed)) {
            fail(app, "scip-java: Java graph CURRENT pointer names no generation at $current")
        }
        if (directoryDigest(committed) != generation) {
            fail(app, "scip-java: committed Java graph generation digest mismatch at $committed")
        }
        val shards = readShards(committed, app)
        val sourcesFile = committed.resolve("SOURCES")
        if (!Files.isRegularFile(sourcesFile)) {
            fail(app, "scip-java: committed Java graph generation has no SOURCES: $committed")
        }
        val sources =
            Files.readAllLines(sourcesFile, StandardCharsets.UTF_8).filter { it.isNotEmpty() }
        val shardSources =
            shards.map { it["source"]!!.jsonPrimitive.content }.distinct().sortedWith(::compareUtf8)
        if (sources.sortedWith(::compareUtf8) != shardSources) {
            fail(app, "scip-java: committed Java graph generation SOURCES mismatch at $committed")
        }
        val universeFile = committed.resolve("UNIVERSE")
        if (!Files.isRegularFile(universeFile)) {
            fail(app, "scip-java: committed Java graph generation has no UNIVERSE: $committed")
        }
        val universeBytes = Files.readAllBytes(universeFile)
        if (universeBytes.isEmpty()) {
            fail(
                app,
                "scip-java: committed Java graph generation has an empty UNIVERSE: $committed",
            )
        }
        val universe = byteDigest(universeBytes)

        val targetFile = committed.resolve("TARGET")
        val targetsFile = committed.resolve("TARGETS")
        if (Files.isRegularFile(targetsFile)) {
            if (Files.isRegularFile(targetFile)) {
                fail(
                    app,
                    "scip-java: committed Java graph generation has TARGET and TARGETS: $committed",
                )
            }
            val names =
                Files.readAllLines(targetsFile, StandardCharsets.UTF_8)
                    .filter { it.isNotEmpty() }
                    .sortedWith(::compareUtf8)
            if (names.any(String::isEmpty) || names.distinct().size != names.size) {
                fail(app, "scip-java: invalid committed Java graph TARGETS at $committed")
            }
            val grouped = shards.groupBy { it["target"]!!.jsonPrimitive.content }
            if (names != grouped.keys.sortedWith(::compareUtf8)) {
                fail(
                    app,
                    "scip-java: committed Java graph generation TARGETS mismatch at $committed",
                )
            }
            return names.map { name -> target(name, generation, universe, grouped.getValue(name)) }
        }
        if (!Files.isRegularFile(targetFile)) {
            fail(
                app,
                "scip-java: committed Java graph generation has no TARGET or TARGETS: $committed",
            )
        }
        val name = Files.readString(targetFile, StandardCharsets.UTF_8).trim()
        if (name.isEmpty()) {
            fail(app, "scip-java: committed Java graph generation has an empty TARGET: $committed")
        }
        if (shards.isEmpty() && sources.isEmpty()) return emptyList()
        if (shards.any { it["target"]!!.jsonPrimitive.content != name }) {
            fail(app, "scip-java: committed Java graph generation target mismatch at $committed")
        }
        return listOf(target(name, generation, universe, shards))
    }

    private fun directTargets(root: Path, app: ScipJavaApp): List<JsonObject> {
        val direct = root.resolve("META-INF/scip-graph")
        if (!Files.isDirectory(direct)) return emptyList()
        val grouped = readShards(direct, app).groupBy { it["target"]!!.jsonPrimitive.content }
        return grouped.entries
            .sortedBy { it.key }
            .map { (name, shards) ->
                val generation = digest(shards)
                target(name, generation, generation, shards)
            }
    }

    private fun readShards(root: Path, app: ScipJavaApp): List<JsonObject> =
        Files.walk(root).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter { it.fileName.toString().endsWith(shardSuffix) }
                .sorted()
                .map { path -> parseShard(path, app) }
                .filter { it != null }
                .map { it!! }
                .toList()
        }

    private fun parseShard(path: Path, app: ScipJavaApp): JsonObject? {
        val parsed =
            runCatching { Json.parseToJsonElement(Files.readString(path, StandardCharsets.UTF_8)) }
                .getOrElse {
                    fail(app, "scip-java: malformed Java graph shard $path: ${it.message}")
                }
        if (parsed !is JsonObject || parsed["schemaVersion"]?.jsonPrimitive?.intOrNull != 1) {
            fail(app, "scip-java: unsupported Java graph shard schema at $path")
        }
        val target = parsed["target"]?.jsonPrimitive?.content
        val source = parsed["source"]?.jsonPrimitive?.content
        val checkerDigest = parsed["checkerDigest"]?.jsonPrimitive?.content
        val diskDigest = parsed["diskDigest"]?.jsonPrimitive?.content
        if (
            target.isNullOrEmpty() ||
                source.isNullOrEmpty() ||
                checkerDigest == null ||
                diskDigest == null ||
                !sha256.matches(checkerDigest) ||
                (diskDigest.isNotEmpty() && !sha256.matches(diskDigest))
        ) {
            fail(app, "scip-java: Java graph shard has invalid metadata at $path")
        }
        if (diskDigest.isNotEmpty()) {
            val project = app.env.workingDirectory.toAbsolutePath().normalize()
            val sourcePath = project.resolve(source).normalize()
            if (
                !sourcePath.startsWith(project) ||
                    !Files.isRegularFile(sourcePath) ||
                    byteDigest(Files.readAllBytes(sourcePath)) != diskDigest
            ) {
                fail(app, "scip-java: Java graph source moved after compilation: $source")
            }
        }
        return parsed
    }

    private fun target(
        name: String,
        generation: String,
        universe: String,
        shards: List<JsonObject>,
    ): JsonObject =
        JsonObject(
            linkedMapOf(
                "name" to JsonPrimitive(name),
                "generation" to JsonPrimitive(generation),
                "universe" to JsonPrimitive(universe),
                "coverage" to coverage(shards),
                "shards" to JsonArray(shards),
            )
        )

    private fun coverage(shards: List<JsonObject>): JsonObject {
        val unresolved =
            shards
                .flatMap { shard ->
                    shard["unresolved"]?.let { (it as? JsonArray)?.toList() }.orEmpty()
                }
                .mapNotNull { row -> (row as? JsonObject)?.get("family")?.jsonPrimitive?.content }
                .toSet()
        val states =
            linkedMapOf(
                "contains" to "partial",
                "exports" to "partial",
                "imports" to "complete",
                "calls" to "complete",
                "accesses" to "complete",
                "instantiates" to "complete",
                "type_ref" to "partial",
                "extends" to "complete",
                "implements" to "complete",
                "overrides" to "complete",
                "dispatches" to "partial",
                "decorates" to "complete",
                "renders" to "unsupported",
                "tests" to "partial",
                "references" to "partial",
            )
        for (family in unresolved) {
            if (states[family] == "complete") states[family] = "partial"
        }
        return JsonObject(states.mapValues { JsonPrimitive(it.value) })
    }

    private fun digest(values: List<JsonObject>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (value in values) {
            val bytes = value.toString().toByteArray(StandardCharsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(StandardCharsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun byteDigest(value: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))

    private fun directoryDigest(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.walk(root).use { paths ->
            for (file in paths.filter(Files::isRegularFile).sorted().toList()) {
                val relative = root.relativize(file).toString().replace('\\', '/')
                update(digest, relative.toByteArray(StandardCharsets.UTF_8))
                update(digest, Files.readAllBytes(file))
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun update(digest: MessageDigest, value: ByteArray) {
        digest.update(value.size.toString().toByteArray(StandardCharsets.UTF_8))
        digest.update(':'.code.toByte())
        digest.update(value)
    }

    private fun compareUtf8(left: String, right: String): Int =
        java.util.Arrays.compareUnsigned(
            left.toByteArray(StandardCharsets.UTF_8),
            right.toByteArray(StandardCharsets.UTF_8),
        )

    private fun writeAtomic(output: Path, text: String) {
        Files.createDirectories(output.parent)
        val temporary =
            output.resolveSibling("${output.fileName}.tmp-${ProcessHandle.current().pid()}")
        Files.writeString(temporary, text, StandardCharsets.UTF_8)
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

    private fun fail(app: ScipJavaApp, message: String): Nothing {
        app.error(message)
        throw GraphAggregationException()
    }

    private class GraphAggregationException : RuntimeException()
}
