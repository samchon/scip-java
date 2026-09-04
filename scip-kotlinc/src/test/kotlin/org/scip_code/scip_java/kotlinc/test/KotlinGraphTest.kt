package org.scip_code.scip_java.kotlinc.test

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.PluginOption
import com.tschuchort.compiletesting.SourceFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.io.TempDir
import org.scip_code.scip_java.kotlinc.AnalyzerCommandLineProcessor
import org.scip_code.scip_java.kotlinc.AnalyzerRegistrar

@OptIn(ExperimentalCompilerApi::class)
class KotlinGraphTest {
    @Test
    fun graphIdentitiesAreStructuralAndTopLevelDeclarationsBelongToTheSource(@TempDir root: Path) {
        val graphRoot = root.resolve("graph")
        val result =
            KotlinCompilation()
                .apply {
                    sources =
                        listOf(
                            SourceFile.kotlin(
                                "Graph.kt",
                                """
                                package example

                                import kotlin.collections.List as KList

                                @Deprecated("fixture")
                                fun old(): Unit = Unit

                                fun String.describe(): String = this
                                fun Int.describe(): String = toString()

                                fun first(value: String): String = value
                                fun second(value: Int): Int = value

                                fun overloaded(value: String): String = value
                                fun overloaded(value: Int): Int = value

                                @get:JvmName("getXProperty")
                                val x: String
                                    get() = "x"

                                fun getX(): String = x

                                class Many {
                                    constructor()
                                    constructor(value: Int)
                                }

                                fun <T> identity(value: T): T = value

                                fun sameName(): Unit = Unit

                                fun use(values: KList<String>): String {
                                    old()
                                    return values.first().describe() + 1.describe()
                                }
                                """
                                    .trimIndent(),
                            ),
                            SourceFile.kotlin(
                                "Other.kt",
                                "package other\nfun sameName(): Unit = Unit",
                            ),
                        )
                    compilerPluginRegistrars = listOf(AnalyzerRegistrar())
                    commandLineProcessors = listOf(AnalyzerCommandLineProcessor())
                    pluginOptions =
                        listOf(
                            PluginOption("scip-kotlinc", "sourceroot", root.toString()),
                            PluginOption(
                                "scip-kotlinc",
                                "targetroot",
                                root.resolve("scip").toString(),
                            ),
                            PluginOption("scip-kotlinc", "graphroot", graphRoot.toString()),
                            PluginOption("scip-kotlinc", "graphtarget", ":|jvm|main"),
                        )
                    workingDir = root.toFile()
                    verbose = false
                }
                .compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val shard =
            Files.walk(graphRoot).use { paths ->
                val output =
                    paths
                        .filter(Files::isRegularFile)
                        .filter { it.fileName.toString() == "Graph.kt.graph.json" }
                        .findFirst()
                        .orElseThrow()
                Files.readString(output, StandardCharsets.UTF_8)
            }
        assertTrue(shard.contains("receiver=kotlin/String"))
        assertTrue(shard.contains("receiver=kotlin/Int"))
        assertTrue(
            shard.contains(
                "callable:example.first|receiver=|context=|parameters=kotlin/String|arity=0|parameter:value"
            )
        )
        assertTrue(
            shard.contains(
                "callable:example.second|receiver=|context=|parameters=kotlin/Int|arity=0|parameter:value"
            )
        )
        assertTrue(
            shard.contains(
                "callable:example.overloaded|receiver=|context=|parameters=kotlin/String|arity=0"
            )
        )
        assertTrue(
            shard.contains(
                "callable:example.overloaded|receiver=|context=|parameters=kotlin/Int|arity=0"
            )
        )
        assertTrue(
            shard.contains("callable:example.x|receiver=|context=|parameters=|arity=0|accessor=get")
        )
        assertTrue(shard.contains("callable:example.getX|receiver=|context=|parameters=|arity=0"))
        assertTrue(
            shard.contains(
                "callable:example.Many.Many|receiver=|context=|parameters=|arity=0|constructor"
            )
        )
        assertTrue(
            shard.contains(
                "callable:example.Many.Many|receiver=|context=|parameters=kotlin/Int|arity=0|constructor"
            )
        )
        assertTrue(
            shard.contains("callable:example.identity|receiver=|context=|parameters=T|arity=1")
        )
        assertTrue(
            shard.contains("callable:example.sameName|receiver=|context=|parameters=|arity=0")
        )
        assertTrue(shard.contains("\"from\":\"sources/Graph.kt\""))
        assertFalse(shard.contains("FirFileSymbol"))
        assertTrue(shard.contains("\"kind\":\"imports\""))
        assertTrue(shard.contains("\"provenance\":\"KList\""))
        assertTrue(shard.contains("\"severity\":\"warning\""))
    }
}
