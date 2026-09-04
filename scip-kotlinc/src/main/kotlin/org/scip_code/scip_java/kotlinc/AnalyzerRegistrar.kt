package org.scip_code.scip_java.kotlinc

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.scip_code.scip.Document
import org.scip_code.scip_java.shared.ScipOptions

@OptIn(ExperimentalCompilerApi::class)
class AnalyzerRegistrar(private val callback: (Document) -> Unit = {}) : CompilerPluginRegistrar() {
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val options =
            ScipOptions().apply {
                sourceroot = configuration[KEY_SOURCES]!!
                targetroot = configuration[KEY_TARGET]!!
            }
        val graphRoot = configuration[KEY_GRAPH_ROOT]
        val graphTarget = configuration[KEY_GRAPH_TARGET]
        require((graphRoot == null) == (graphTarget == null)) {
            "scip-kotlinc graphroot and graphtarget must be provided together"
        }
        val messages =
            if (graphRoot == null) null
            else {
                val delegate =
                    configuration[CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY]
                        ?: org.jetbrains.kotlin.cli.common.messages.MessageCollector.NONE
                KotlinGraphMessages(delegate).also {
                    configuration.put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, it)
                }
            }
        val state = AnalyzerCompilationState()
        FirExtensionRegistrarAdapter.registerExtension(
            AnalyzerFirExtensionRegistrar(options, graphRoot, graphTarget, messages, state)
        )
        IrGenerationExtension.registerExtension(
            PostAnalysisExtension(
                configuration = configuration,
                sourceRoot = options.sourceroot,
                targetRoot = options.targetroot,
                callback = callback,
                graphMessages = messages,
                state = state,
            )
        )
    }

    override val pluginId = PLUGIN_ID

    override val supportsK2: Boolean
        get() = true
}
