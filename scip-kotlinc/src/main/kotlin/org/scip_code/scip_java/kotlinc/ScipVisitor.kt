package org.scip_code.scip_java.kotlinc

import java.nio.file.Path
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.KtSourceFile
import org.jetbrains.kotlin.diagnostics.KtDiagnostic
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.name.FqName
import org.scip_code.scip.Document

/**
 * Per-file accumulator of SCIP occurrences and symbols. The FIR checkers in [AnalyzerCheckers] call
 * into this and the resulting [Document] is written as a `.scip` shard at the end of compilation.
 */
class ScipVisitor(
    sourceroot: Path,
    file: KtSourceFile,
    lineMap: LineMap,
    globals: GlobalSymbolsCache,
    locals: LocalSymbolsCache = LocalSymbolsCache(),
    graphRoot: Path? = null,
    graphTarget: String? = null,
) {
    private val cache = SymbolsCache(globals, locals)
    private val documentBuilder = ScipTextDocumentBuilder(sourceroot, file, lineMap, cache)
    private val graphBuilder =
        if (graphRoot == null || graphTarget == null) null
        else KotlinGraphDocumentBuilder(sourceroot, graphRoot, graphTarget, file, lineMap)

    private data class SymbolDescriptorPair(
        val firBasedSymbol: FirBasedSymbol<*>?,
        val symbol: Symbol,
    )

    fun build(): Document = documentBuilder.build()

    fun buildGraph(): KotlinGraphShard? = graphBuilder?.build()

    fun graphOutputPath(): Path? = graphBuilder?.outputPath()

    fun writeGraph() {
        val builder = graphBuilder ?: return
        builder.build().write(builder.outputPath())
    }

    fun graphDiagnostic(
        severity: org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity,
        message: String,
        location: org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation?,
    ) = graphBuilder?.diagnostic(severity, message, location)

    fun graphDiagnostic(diagnostic: KtDiagnostic) = graphBuilder?.diagnostic(diagnostic)

    context(context: CheckerContext)
    private fun Sequence<SymbolDescriptorPair>?.emitAll(
        element: KtSourceElement,
        isDefinition: Boolean,
        enclosingSource: KtSourceElement? = null,
    ): List<Symbol>? =
        this?.onEach { (firBasedSymbol, symbol) ->
                documentBuilder.emitScipData(
                    firBasedSymbol,
                    symbol,
                    element,
                    isDefinition,
                    enclosingSource,
                )
            }
            ?.map { it.symbol }
            ?.toList()

    private fun Sequence<Symbol>.with(firBasedSymbol: FirBasedSymbol<*>?) =
        this.map { SymbolDescriptorPair(firBasedSymbol, it) }

    context(context: CheckerContext)
    fun visitPackage(pkg: FqName, element: KtSourceElement) {
        cache[pkg].with(null).emitAll(element, isDefinition = false)
    }

    context(context: CheckerContext)
    fun visitClassReference(firClassSymbol: FirClassLikeSymbol<*>, element: KtSourceElement) {
        cache[firClassSymbol].with(firClassSymbol).emitAll(element, isDefinition = false)
        graphBuilder?.reference(firClassSymbol, element, "type_ref")
        graphBuilder?.reference(firClassSymbol, element, "references")
    }

    context(context: CheckerContext)
    fun visitCallableReference(firClassSymbol: FirCallableSymbol<*>, element: KtSourceElement) {
        cache[firClassSymbol].with(firClassSymbol).emitAll(element, isDefinition = false)
        graphBuilder?.reference(firClassSymbol, element, "references")
    }

    context(context: CheckerContext)
    fun visitImport(firSymbol: FirBasedSymbol<*>, element: KtSourceElement, alias: String? = null) {
        graphBuilder?.reference(firSymbol, element, "imports", provenance = alias)
    }

    context(context: CheckerContext)
    fun visitCall(firSymbol: FirCallableSymbol<*>, element: KtSourceElement) {
        graphBuilder?.reference(firSymbol, element, "calls")
        if (firSymbol is org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol) {
            graphBuilder?.reference(firSymbol, element, "instantiates")
        }
        graphBuilder?.dispatch(firSymbol, element)
    }

    context(context: CheckerContext)
    fun visitAccess(firSymbol: FirBasedSymbol<*>, element: KtSourceElement, access: String) {
        graphBuilder?.reference(firSymbol, element, "accesses", access)
    }

    context(context: CheckerContext)
    fun visitClassOrObject(
        firClass: FirClassLikeDeclaration,
        element: KtSourceElement,
        enclosingSource: KtSourceElement? = null,
    ) {
        cache[firClass.symbol]
            .with(firClass.symbol)
            .emitAll(element, isDefinition = true, enclosingSource)
        graphBuilder?.declare(firClass.symbol, element, enclosingSource)
    }

    context(context: CheckerContext)
    fun visitPrimaryConstructor(
        firConstructor: FirConstructor,
        source: KtSourceElement,
        enclosingSource: KtSourceElement? = null,
    ) {
        cache[firConstructor.symbol]
            .with(firConstructor.symbol)
            .emitAll(source, isDefinition = true, enclosingSource)
        graphBuilder?.declare(firConstructor.symbol, source, enclosingSource)
    }

    context(context: CheckerContext)
    fun visitSecondaryConstructor(
        firConstructor: FirConstructor,
        source: KtSourceElement,
        enclosingSource: KtSourceElement? = null,
    ) {
        cache[firConstructor.symbol]
            .with(firConstructor.symbol)
            .emitAll(source, isDefinition = true, enclosingSource)
        graphBuilder?.declare(firConstructor.symbol, source, enclosingSource)
    }

    context(context: CheckerContext)
    fun visitNamedFunction(
        firFunction: FirFunction,
        source: KtSourceElement,
        enclosingSource: KtSourceElement? = null,
    ) {
        cache[firFunction.symbol]
            .with(firFunction.symbol)
            .emitAll(source, isDefinition = true, enclosingSource)
        graphBuilder?.declare(firFunction.symbol, source, enclosingSource)
    }

    context(context: CheckerContext)
    fun visitProperty(
        firProperty: FirProperty,
        source: KtSourceElement,
        enclosingSource: KtSourceElement? = null,
    ) {
        cache[firProperty.symbol]
            .with(firProperty.symbol)
            .emitAll(source, isDefinition = true, enclosingSource)
        graphBuilder?.declare(firProperty.symbol, source, enclosingSource)
    }

    context(context: CheckerContext)
    fun visitParameter(
        firParameter: FirValueParameter,
        source: KtSourceElement,
        enclosingSource: KtSourceElement? = null,
    ) {
        cache[firParameter.symbol]
            .with(firParameter.symbol)
            .emitAll(source, isDefinition = true, enclosingSource)
        graphBuilder?.declare(firParameter.symbol, source, enclosingSource)
    }

    context(context: CheckerContext)
    fun visitTypeParameter(
        firTypeParameter: FirTypeParameter,
        source: KtSourceElement,
        enclosingSource: KtSourceElement? = null,
    ) {
        cache[firTypeParameter.symbol]
            .with(firTypeParameter.symbol)
            .emitAll(source, isDefinition = true, enclosingSource)
        graphBuilder?.declare(firTypeParameter.symbol, source, enclosingSource)
    }

    context(context: CheckerContext)
    fun visitTypeAlias(
        firTypeAlias: FirTypeAlias,
        source: KtSourceElement,
        enclosingSource: KtSourceElement? = null,
    ) {
        cache[firTypeAlias.symbol]
            .with(firTypeAlias.symbol)
            .emitAll(source, isDefinition = true, enclosingSource)
        graphBuilder?.declare(firTypeAlias.symbol, source, enclosingSource)
    }

    context(context: CheckerContext)
    fun visitPropertyAccessor(
        firPropertyAccessor: FirPropertyAccessor,
        source: KtSourceElement,
        enclosingSource: KtSourceElement? = null,
    ) {
        cache[firPropertyAccessor.symbol]
            .with(firPropertyAccessor.symbol)
            .emitAll(source, isDefinition = true, enclosingSource)
        graphBuilder?.declare(firPropertyAccessor.symbol, source, enclosingSource)
    }

    context(context: CheckerContext)
    fun visitEnumEntry(
        firEnumEntry: FirEnumEntry,
        source: KtSourceElement,
        enclosingSource: KtSourceElement? = null,
    ) {
        cache[firEnumEntry.symbol]
            .with(firEnumEntry.symbol)
            .emitAll(source, isDefinition = true, enclosingSource)
        graphBuilder?.declare(firEnumEntry.symbol, source, enclosingSource)
    }

    context(context: CheckerContext)
    fun visitSimpleNameExpression(
        firResolvedNamedReference: FirResolvedNamedReference,
        source: KtSourceElement,
    ) {
        cache[firResolvedNamedReference.resolvedSymbol]
            .with(firResolvedNamedReference.resolvedSymbol)
            .emitAll(source, isDefinition = false)
        graphBuilder?.reference(firResolvedNamedReference.resolvedSymbol, source, "references")
    }
}
