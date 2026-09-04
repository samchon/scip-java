package org.scip_code.scip_java.kotlinc

import java.nio.file.Path
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent.Factory
import org.scip_code.scip_java.shared.ScipOptions

open class AnalyzerParamsProvider(
    session: FirSession,
    val options: ScipOptions,
    val graphRoot: Path? = null,
    val graphTarget: String? = null,
    val graphMessages: KotlinGraphMessages? = null,
) : FirExtensionSessionComponent(session) {
    val sourceroot: Path
        get() = options.sourceroot

    companion object {
        fun getFactory(
            options: ScipOptions,
            graphRoot: Path?,
            graphTarget: String?,
            graphMessages: KotlinGraphMessages?,
        ): Factory {
            return Factory {
                AnalyzerParamsProvider(it, options, graphRoot, graphTarget, graphMessages)
            }
        }
    }
}

val FirSession.analyzerParamsProvider: AnalyzerParamsProvider by
    FirSession.sessionComponentAccessor()
