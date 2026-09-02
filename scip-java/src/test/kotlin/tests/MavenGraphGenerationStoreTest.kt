package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.scip_code.scip_java.buildtools.MavenGraphGenerationStore

class MavenGraphGenerationStoreTest {
    @Test
    fun compilerUniverseIgnoresDuplicateAndReorderedInvocations(@TempDir root: Path) {
        val store = MavenGraphGenerationStore(root.resolve("targetroot"), root, "maven:.")
        val first = root.resolve("first.args")
        val second = root.resolve("second.args")
        val changed = root.resolve("changed.args")
        Files.writeString(
            first,
            "@invocation\n@plugin\nplugin\narg-a\n@invocation\n@plugin\nplugin\narg-b\n" +
                "@invocation\n@plugin\nplugin\narg-a\n",
        )
        Files.writeString(
            second,
            "@invocation\n@plugin\nplugin\narg-b\n@invocation\n@plugin\nplugin\narg-a\n",
        )
        Files.writeString(
            changed,
            "@invocation\n@plugin\nplugin\narg-c\n@invocation\n@plugin\nplugin\narg-a\n",
        )

        assertEquals(store.compilerUniverseDigest(first), store.compilerUniverseDigest(second))
        assertNotEquals(store.compilerUniverseDigest(first), store.compilerUniverseDigest(changed))
    }

    @Test
    fun publishesOnlySuccessfulCompleteGenerations() {
        withWorkspace { workspace ->
            val source = workspace.resolve("src/A.java")
            write(source, "class A {}\n")
            val store =
                MavenGraphGenerationStore(workspace.resolve("targetroot"), workspace, "maven")
            store.prepare()
            write(store.staging.resolve("src/A.java.graph.json"), shard("maven:.", "src/A.java"))
            store.commit()
            val first = Files.readString(store.current)
            val firstCommitted = store.current.parent.resolve("generations").resolve(first.trim())
            assertEquals(listOf("maven:."), Files.readAllLines(firstCommitted.resolve("TARGETS")))

            store.prepare()
            write(store.staging.resolve("src/A.java.graph.json"), "{broken")
            assertFailsWith<IllegalStateException> { store.commit() }
            assertEquals(first, Files.readString(store.current))

            store.prepare()
            write(store.staging.resolve("src/A.java.graph.json"), shard("maven:.", "src/A.java"))
            write(source, "class A { int moved; }\n")
            assertFailsWith<IllegalArgumentException> { store.commit() }
            assertEquals(first, Files.readString(store.current))
            write(source, "class A {}\n")

            store.prepare()
            store.commit()
            assertEquals(first, Files.readString(store.current))

            Files.delete(source)
            val replacement = workspace.resolve("src/B.java")
            write(replacement, "class B {}\n")
            store.prepare()
            write(store.staging.resolve("src/B.java.graph.json"), shard("maven:.", "src/B.java"))
            store.commit()
            val second = Files.readString(store.current)
            assertNotEquals(first, second)
            val committed = store.current.parent.resolve("generations").resolve(second.trim())
            assertFalse(Files.exists(committed.resolve("src/A.java.graph.json")))
            assertTrue(Files.isRegularFile(committed.resolve("src/B.java.graph.json")))
            assertEquals(listOf("src/B.java"), Files.readAllLines(committed.resolve("SOURCES")))
            assertEquals(listOf("maven:."), Files.readAllLines(committed.resolve("TARGETS")))
            assertEquals(1, Files.list(committed.parent).use { it.count() })
        }
    }

    @Test
    fun removesAReactorModuleWithoutDeletingItsSources() {
        withWorkspace { workspace ->
            val rootPom = workspace.resolve("pom.xml")
            write(rootPom, "<project><modules><module>sub</module></modules></project>\n")
            write(workspace.resolve("sub/pom.xml"), "<project/>\n")
            val source = workspace.resolve("sub/src/main/java/B.java")
            write(source, "class B {}\n")
            val store =
                MavenGraphGenerationStore(workspace.resolve("targetroot"), workspace, "maven")
            store.prepare()
            write(
                store.staging.resolve("sub/src/main/java/B.java.graph.json"),
                shard("maven:sub", "sub/src/main/java/B.java"),
            )
            store.commit()

            write(rootPom, "<project/>\n")
            store.prepare()
            store.commit()
            val generation = Files.readString(store.current).trim()
            val committed = store.current.parent.resolve("generations").resolve(generation)
            assertTrue(Files.isRegularFile(source))
            assertEquals("", Files.readString(committed.resolve("SOURCES")))
            assertFalse(Files.exists(committed.resolve("sub/src/main/java/B.java.graph.json")))
        }
    }

    @Test
    fun removesAnInactiveProfileModuleUsingTheEffectiveReactor() {
        withWorkspace { workspace ->
            write(
                workspace.resolve("pom.xml"),
                "<project><profiles><profile><id>extra</id><modules><module>sub</module></modules></profile></profiles></project>\n",
            )
            write(workspace.resolve("sub/pom.xml"), "<project/>\n")
            val source = workspace.resolve("sub/inherited/java/B.java")
            write(source, "class B {}\n")
            val store =
                MavenGraphGenerationStore(workspace.resolve("targetroot"), workspace, "maven")

            store.prepare()
            write(
                store.staging.resolve("sub/inherited/java/B.java.graph.json"),
                shard("maven:sub", "sub/inherited/java/B.java"),
            )
            write(
                store.reactorManifest,
                reactorManifest(
                    workspace to workspace.resolve("src/main/java"),
                    workspace.resolve("sub") to workspace.resolve("sub/inherited/java"),
                ),
            )
            store.commit()

            store.prepare()
            write(
                store.reactorManifest,
                reactorManifest(workspace to workspace.resolve("src/main/java")),
            )
            store.commit()

            val generation = Files.readString(store.current).trim()
            val committed = store.current.parent.resolve("generations").resolve(generation)
            assertTrue(Files.isRegularFile(source))
            assertEquals("", Files.readString(committed.resolve("SOURCES")))
            assertFalse(Files.exists(committed.resolve("sub/inherited/java/B.java.graph.json")))
        }
    }

    private fun withWorkspace(block: (Path) -> Unit) {
        val workspace = createTempDirectory("maven-graph-store").toRealPath()
        try {
            block(workspace)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, text, StandardCharsets.UTF_8)
    }

    private fun shard(target: String, source: String): String =
        """{"schemaVersion":1,"target":"$target","source":"$source","checkerDigest":"${digest(source)}","diskDigest":"${digest(source)}","nodes":[],"edges":[],"unresolved":[]}"""

    private fun digest(source: String): String {
        val className = source.substringAfterLast('/').substringBefore('.')
        val text = "class $className {}\n"
        return HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(text.toByteArray(StandardCharsets.UTF_8))
            )
    }

    private fun reactorManifest(vararg projects: Pair<Path, Path>): String = buildString {
        append("schema\t1\n")
        for (module in projects.map { it.first }.distinct()) {
            append("project\t").append(encoded(module)).append('\n')
        }
        for ((module, source) in projects) {
            append("source\t")
                .append(encoded(module))
                .append('\t')
                .append(encoded(source))
                .append('\n')
        }
    }

    private fun encoded(path: Path): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                path.toAbsolutePath().normalize().toString().toByteArray(StandardCharsets.UTF_8)
            )
}
