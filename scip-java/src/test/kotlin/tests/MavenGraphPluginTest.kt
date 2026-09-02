package tests

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir
import org.scip_code.scip_java.buildtools.MavenGraphPlugin

class MavenGraphPluginTest {
    @Test
    fun preservesProjectRepositorySelectionAndCliOverrideOrder(@TempDir root: Path) {
        val config = root.resolve(".mvn/maven.config")
        Files.createDirectories(config.parent)
        Files.writeString(
            config,
            """
            # repository selection for the fixture
            --settings
            "settings with spaces.xml"
            -D
            "maven.repo.local=repository with spaces"
            --offline
            """.trimIndent(),
        )

        val project = MavenGraphPlugin.projectRepositorySelectionArguments(root)
        assertEquals(
            listOf(
                "--settings",
                "settings with spaces.xml",
                "-D",
                "maven.repo.local=repository with spaces",
                "--offline",
            ),
            project,
        )
        assertEquals(
            "-Dmaven.repo.local=cli-override",
            (project + "-Dmaven.repo.local=cli-override").last(),
        )
    }
}
