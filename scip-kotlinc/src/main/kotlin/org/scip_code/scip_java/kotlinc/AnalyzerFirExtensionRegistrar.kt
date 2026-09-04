package org.scip_code.scip_java.kotlinc

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.scip_code.scip_java.shared.ScipOptions

class AnalyzerFirExtensionRegistrar(
    private val options: ScipOptions,
    private val graphRoot: java.nio.file.Path?,
    private val graphTarget: String?,
    private val graphMessages: KotlinGraphMessages?,
    private val state: AnalyzerCompilationState,
) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +AnalyzerParamsProvider.getFactory(options, graphRoot, graphTarget, graphMessages)
        +{ session: FirSession -> AnalyzerCheckers(session, state) }
    }
}
