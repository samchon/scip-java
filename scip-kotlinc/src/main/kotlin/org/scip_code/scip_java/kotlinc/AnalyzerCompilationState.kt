package org.scip_code.scip_java.kotlinc

import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlin.KtSourceFile
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter

/** Compiler-invocation state that must never leak across parallel Kotlin compilations. */
class AnalyzerCompilationState(val globals: GlobalSymbolsCache = GlobalSymbolsCache()) {
    val visitors: MutableMap<KtSourceFile, ScipVisitor> = ConcurrentHashMap()
    val diagnosticReporters: MutableMap<KtSourceFile, DiagnosticReporter> = ConcurrentHashMap()
}
