package org.scip_code.scip_java.kotlinc

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.LinkedHashMap
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.KtSourceFile
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.diagnostics.KtDiagnostic
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.isLocalDeclaredInBlock
import org.jetbrains.kotlin.fir.analysis.checkers.directOverriddenSymbolsSafe
import org.jetbrains.kotlin.fir.analysis.checkers.toClassLikeSymbol
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirEnumEntry
import org.jetbrains.kotlin.fir.declarations.FirField
import org.jetbrains.kotlin.fir.declarations.FirMemberDeclaration
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.FirVariable
import org.jetbrains.kotlin.fir.declarations.utils.isInterface
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.renderer.ConeIdFullRenderer
import org.jetbrains.kotlin.fir.renderer.ConeTypeRenderer
import org.jetbrains.kotlin.fir.renderer.FirAllModifierRenderer
import org.jetbrains.kotlin.fir.renderer.FirCallNoArgumentsRenderer
import org.jetbrains.kotlin.fir.renderer.FirCallableSignatureRendererForReadability
import org.jetbrains.kotlin.fir.renderer.FirDeclarationRenderer
import org.jetbrains.kotlin.fir.renderer.FirNoClassMemberRenderer
import org.jetbrains.kotlin.fir.renderer.FirRenderer
import org.jetbrains.kotlin.fir.resolve.getContainingSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFileSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertyAccessorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.scip_code.scip_java.shared.ScipShardPaths

/** Compiler-owned graph facts for one Kotlin source file. */
@OptIn(SymbolInternals::class)
class KotlinGraphDocumentBuilder(
    private val sourceRoot: Path,
    private val targetRoot: Path,
    private val target: String,
    private val file: KtSourceFile,
    private val lineMap: LineMap,
) {
    private val source = ScipShardPaths.relativePath(sourceRoot, Paths.get(file.path))
    private val bytes = file.getContentsAsStream().use { it.readBytes() }
    private val text = String(bytes, StandardCharsets.UTF_8)
    private val nodes = LinkedHashMap<String, KotlinGraphShard.Node>()
    private val edges = LinkedHashMap<String, KotlinGraphShard.Edge>()
    private val unresolved = LinkedHashMap<String, KotlinGraphShard.Unresolved>()
    private val diagnostics = LinkedHashMap<String, KotlinGraphShard.Diagnostic>()

    context(context: CheckerContext)
    fun declare(symbol: FirBasedSymbol<*>, element: KtSourceElement, enclosing: KtSourceElement?) {
        val key = symbol(symbol)
        if (key.isEmpty()) return
        val kind = kind(symbol.fir)
        val name = name(symbol)
        val qualifiedName = qualifiedName(symbol)
        val evidence = evidence(element, enclosing)
        val declaration = symbol.fir
        val node =
            KotlinGraphShard.Node(
                key,
                kind,
                name,
                qualifiedName,
                source,
                exported(declaration),
                modifiers(declaration),
                signature(symbol.fir),
                origin(symbol),
                evidence,
            )
        val prior = nodes.putIfAbsent(key, node)
        check(prior == null || prior == node) {
            "Kotlin graph symbol changed within one source: $key"
        }
        edge(owner(), key, "contains", null, null, symbol, evidence)
        if (node.exported()) edge(source, key, "exports", null, null, symbol, evidence)
        decorate(symbol, key, declaration, evidence)
        when (symbol) {
            is FirClassLikeSymbol<*> -> inheritance(symbol, key, element)
            is FirFunctionSymbol<*> -> overrides(symbol, key, element)
            is FirPropertySymbol -> overrides(symbol, key, element)
        }
    }

    context(context: CheckerContext)
    fun reference(
        targetSymbol: FirBasedSymbol<*>,
        element: KtSourceElement,
        family: String,
        access: String? = null,
        provenance: String? = null,
    ) {
        val to = symbol(targetSymbol)
        if (to.isEmpty()) {
            unresolved(family, element, "analysis-error")
            return
        }
        edge(owner(), to, family, access, provenance, targetSymbol, evidence(element, null))
    }

    context(context: CheckerContext)
    fun dispatch(targetSymbol: FirBasedSymbol<*>, element: KtSourceElement) {
        val declaration = targetSymbol.fir as? FirCallableDeclaration ?: return
        if (
            declaration.status.modality?.name?.lowercase() !in setOf("open", "abstract", "sealed")
        ) {
            return
        }
        unresolved("dispatches", element, "dynamic", listOf(symbol(targetSymbol)))
    }

    fun diagnostic(
        severity: CompilerMessageSeverity,
        message: String,
        location: CompilerMessageSourceLocation?,
    ) {
        val path = location?.path ?: return
        val normalized = Paths.get(path).toAbsolutePath().normalize()
        if (normalized != Paths.get(file.path).toAbsolutePath().normalize()) return
        val startLine = maxOf(1, location.line)
        val startColumn = maxOf(1, location.column)
        val item =
            KotlinGraphShard.Diagnostic(
                if (severity.isError) "error" else "warning",
                message,
                KotlinGraphShard.Evidence(source, startLine, startColumn, startLine, startColumn),
            )
        diagnostics.putIfAbsent(
            "${item.severity()}\u0000${item.message()}\u0000$startLine\u0000$startColumn",
            item,
        )
    }

    fun diagnostic(diagnostic: KtDiagnostic) {
        val range = diagnostic.firstRange
        val startLine = lineMap.lineNumberForOffset(range.startOffset)
        val startColumn = lineMap.columnForOffset(range.startOffset) + 1
        val endLine = lineMap.lineNumberForOffset(range.endOffset)
        val endColumn = lineMap.columnForOffset(range.endOffset) + 1
        val severity =
            if (diagnostic.severity == org.jetbrains.kotlin.diagnostics.Severity.ERROR) {
                "error"
            } else {
                "warning"
            }
        val item =
            KotlinGraphShard.Diagnostic(
                severity,
                diagnostic.renderMessage(),
                KotlinGraphShard.Evidence(source, startLine, startColumn, endLine, endColumn),
            )
        diagnostics.putIfAbsent(
            "${item.severity()}\u0000${item.message()}\u0000$startLine\u0000$startColumn",
            item,
        )
    }

    fun build(): KotlinGraphShard {
        val disk = Paths.get(file.path).toAbsolutePath().normalize()
        val diskDigest =
            if (Files.isRegularFile(disk)) KotlinGraphShard.digest(Files.readAllBytes(disk)) else ""
        return KotlinGraphShard(
            source,
            KotlinGraphShard.digest(bytes),
            diskDigest,
            target,
            org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION,
            nodes.values.toList(),
            edges.values.toList(),
            unresolved.values.toList(),
            diagnostics.values.toList(),
        )
    }

    context(context: CheckerContext)
    private fun inheritance(symbol: FirClassLikeSymbol<*>, from: String, element: KtSourceElement) {
        val declaration = symbol.fir as? FirClass ?: return
        for (typeRef in declaration.superTypeRefs) {
            val typeSource = typeRef.source ?: continue
            if (typeSource.kind is org.jetbrains.kotlin.KtFakeSourceElementKind) continue
            val parent = typeRef.toClassLikeSymbol(context.session) ?: continue
            val family =
                if ((parent.fir as? FirClass)?.isInterface == true) "implements" else "extends"
            edge(from, symbol(parent), family, null, null, parent, evidence(typeSource, null))
        }
    }

    context(context: CheckerContext)
    private fun overrides(symbol: FirCallableSymbol<*>, from: String, element: KtSourceElement) {
        for (parent in symbol.directOverriddenSymbolsSafe()) {
            edge(from, symbol(parent), "overrides", null, null, parent, evidence(element, null))
        }
    }

    context(context: CheckerContext)
    private fun decorate(
        symbol: FirBasedSymbol<*>,
        from: String,
        declaration: FirDeclaration?,
        evidence: KotlinGraphShard.Evidence,
    ) {
        val annotations = declaration?.annotations.orEmpty()
        for (annotation in annotations) {
            val annotationSymbol =
                annotation.annotationTypeRef.toClassLikeSymbol(context.session) ?: continue
            val annotationEvidence = annotation.source?.let { evidence(it, null) } ?: evidence
            edge(
                from,
                symbol(annotationSymbol),
                "decorates",
                null,
                useSite(annotation.source),
                annotationSymbol,
                annotationEvidence,
            )
            if (isTestAnnotation(annotationSymbol)) {
                edge(
                    from,
                    symbol(annotationSymbol),
                    "tests",
                    null,
                    "annotation",
                    annotationSymbol,
                    annotationEvidence,
                )
            }
        }
    }

    private fun useSite(source: KtSourceElement?): String? {
        val annotation =
            source?.let { text.substring(it.startOffset, it.endOffset.coerceAtMost(text.length)) }
                ?: return null
        val marker = annotation.substringAfter('@', "").substringBefore(':', "")
        return marker.takeIf {
            it in
                setOf(
                    "file",
                    "field",
                    "property",
                    "get",
                    "set",
                    "receiver",
                    "param",
                    "setparam",
                    "delegate",
                )
        }
    }

    private fun isTestAnnotation(symbol: FirClassLikeSymbol<*>): Boolean {
        val name = symbol.classId.asSingleFqName().asString()
        return name == "org.junit.Test" ||
            name == "org.junit.jupiter.api.Test" ||
            name == "kotlin.test.Test" ||
            name.startsWith("io.kotest.")
    }

    context(context: CheckerContext)
    private fun owner(): String =
        context.containingDeclarations
            .lastOrNull { it !is FirFileSymbol }
            ?.let(::symbol)
            ?.takeIf(String::isNotEmpty) ?: source

    private fun edge(
        from: String,
        to: String,
        family: String,
        access: String?,
        provenance: String?,
        targetSymbol: FirBasedSymbol<*>,
        evidence: KotlinGraphShard.Evidence,
    ) {
        if (from.isEmpty() || to.isEmpty()) return
        val edge =
            KotlinGraphShard.Edge(
                from,
                to,
                family,
                access,
                provenance,
                kind(targetSymbol.fir),
                name(targetSymbol),
                qualifiedName(targetSymbol),
                evidence,
            )
        edges.putIfAbsent(
            "$family\u0000$from\u0000$to\u0000${access.orEmpty()}\u0000${provenance.orEmpty()}\u0000${evidence.startLine()}\u0000${evidence.startColumn()}",
            edge,
        )
    }

    private fun unresolved(
        family: String,
        element: KtSourceElement,
        reason: String,
        candidates: List<String> = emptyList(),
    ) {
        val evidence = evidence(element, null)
        val item = KotlinGraphShard.Unresolved(family, reason, evidence, candidates)
        unresolved.putIfAbsent(
            "$family\u0000${evidence.startLine()}\u0000${evidence.startColumn()}\u0000$reason",
            item,
        )
    }

    private fun evidence(
        element: KtSourceElement,
        enclosing: KtSourceElement?,
    ): KotlinGraphShard.Evidence {
        val startLine = lineMap.lineNumber(element)
        val startColumn = lineMap.startCharacter(element) + 1
        val endOffset = enclosing?.endOffset ?: element.endOffset
        val endLine = lineMap.lineNumberForOffset(endOffset)
        val endColumn = lineMap.columnForOffset(endOffset) + 1
        return KotlinGraphShard.Evidence(source, startLine, startColumn, endLine, endColumn)
    }

    @OptIn(SymbolInternals::class)
    private fun symbol(symbol: FirBasedSymbol<*>): String =
        when (symbol) {
            is FirAnonymousFunctionSymbol -> localSymbol(symbol, "lambda")
            is FirClassLikeSymbol<*> -> "class:${symbol.classId.asString()}"
            is FirPropertyAccessorSymbol ->
                callableSymbol(symbol.propertySymbol) +
                    "|accessor=" +
                    if (symbol.isGetter) "get" else "set"
            is FirConstructorSymbol -> callableSymbol(symbol) + "|constructor"
            is FirTypeParameterSymbol ->
                symbol.containingDeclarationSymbol.let(::symbol) +
                    "|type-parameter:${symbol.name.asString()}"
            is FirValueParameterSymbol ->
                symbol.containingDeclarationSymbol.let(::symbol) +
                    "|parameter:${symbol.name.asString()}"
            is FirPropertySymbol -> callableSymbol(symbol)
            is FirVariableSymbol<*> -> localSymbol(symbol, "variable:${symbol.name.asString()}")
            is FirCallableSymbol<*> -> callableSymbol(symbol)
            else -> localSymbol(symbol, symbol.javaClass.simpleName)
        }

    @OptIn(SymbolInternals::class)
    private fun callableSymbol(symbol: FirCallableSymbol<*>): String {
        val callableId =
            symbol.callableId ?: return localSymbol(symbol, "callable:${symbol.name.asString()}")
        if (isGraphLocal(symbol)) return localSymbol(symbol, "callable:${symbol.name.asString()}")
        val declaration = symbol.fir
        val receiver = declaration.receiverParameter?.typeRef?.coneType?.let(::renderType).orEmpty()
        val contexts =
            declaration.contextParameters.joinToString(",") {
                renderType(it.returnTypeRef.coneType)
            }
        val parameters =
            (declaration as? org.jetbrains.kotlin.fir.declarations.FirFunction)
                ?.valueParameters
                ?.joinToString(",") { renderType(it.returnTypeRef.coneType) }
                .orEmpty()
        return "callable:${callableId.asSingleFqName().asString()}|receiver=$receiver|context=$contexts|parameters=$parameters|arity=${declaration.typeParameters.size}"
    }

    private fun localSymbol(symbol: FirBasedSymbol<*>, role: String): String {
        val source = symbol.source
        return "local:${this.source}:${source?.startOffset ?: -1}:$role"
    }

    private fun renderType(type: ConeKotlinType): String {
        val renderer = ConeTypeRenderer()
        val out = StringBuilder()
        val idRenderer = ConeIdFullRenderer()
        renderer.builder = out
        idRenderer.builder = out
        renderer.idRenderer = idRenderer
        renderer.render(type, "")
        return out.toString()
    }

    private fun name(symbol: FirBasedSymbol<*>): String =
        when (symbol) {
            is FirClassLikeSymbol<*> -> symbol.classId.shortClassName.asString()
            is FirPropertyAccessorSymbol -> if (symbol.isGetter) "get" else "set"
            is FirConstructorSymbol -> "<init>"
            is FirCallableSymbol<*> -> symbol.name.asString()
            is FirTypeParameterSymbol -> symbol.name.asString()
            is FirValueParameterSymbol -> symbol.name.asString()
            is FirVariableSymbol<*> -> symbol.name.asString()
            else -> symbol.javaClass.simpleName
        }

    private fun qualifiedName(symbol: FirBasedSymbol<*>): String =
        when (symbol) {
            is FirClassLikeSymbol<*> -> symbol.classId.asSingleFqName().asString()
            is FirTypeParameterSymbol,
            is FirValueParameterSymbol -> ""
            is FirPropertySymbol ->
                symbol.callableId
                    ?.takeUnless { isGraphLocal(symbol) }
                    ?.asSingleFqName()
                    ?.asString()
                    .orEmpty()
            is FirVariableSymbol<*> -> ""
            is FirCallableSymbol<*> ->
                symbol.callableId
                    ?.takeUnless { isGraphLocal(symbol) }
                    ?.asSingleFqName()
                    ?.asString()
                    .orEmpty()
            else -> ""
        }

    @OptIn(SymbolInternals::class)
    private fun isGraphLocal(symbol: FirCallableSymbol<*>): Boolean {
        if (symbol.fir.isLocalDeclaredInBlock) return true
        val owner = symbol.getContainingSymbol(symbol.fir.moduleData.session)
        return owner is FirClassLikeSymbol<*> && owner.isLocal
    }

    private fun kind(element: FirElement): String =
        when (element) {
            is FirClass ->
                when {
                    element.isInterface -> "interface"
                    element.classKind.name == "ENUM_CLASS" -> "enum"
                    else -> "class"
                }
            is FirTypeAlias -> "type"
            is FirConstructor -> "constructor"
            is FirTypeParameter -> "type"
            is FirValueParameter -> "parameter"
            is FirField -> "field"
            is FirPropertyAccessor -> "method"
            is FirProperty -> "property"
            is FirEnumEntry -> "field"
            is FirVariable -> "variable"
            is FirCallableDeclaration -> "function"
            is FirClassLikeDeclaration -> "class"
            else -> "variable"
        }

    private fun exported(declaration: FirDeclaration?): Boolean =
        (declaration as? FirMemberDeclaration)?.status?.visibility?.name == "public"

    private fun modifiers(declaration: FirDeclaration?): List<String> {
        val status = (declaration as? FirMemberDeclaration)?.status ?: return emptyList()
        val out = linkedSetOf<String>()
        status.visibility.name
            .takeIf { it in setOf("public", "private", "protected", "internal") }
            ?.let(out::add)
        if (status.modality?.name == "abstract") out += "abstract"
        if (status.isStatic) out += "static"
        if (status.isConst) out += "const"
        if (status.isSuspend) out += "async"
        if (exported(declaration)) out += "export"
        return out.toList()
    }

    private fun signature(element: FirElement): String {
        val renderer =
            FirRenderer(
                typeRenderer = ConeTypeRenderer(),
                idRenderer = ConeIdFullRenderer(),
                classMemberRenderer = FirNoClassMemberRenderer(),
                bodyRenderer = null,
                propertyAccessorRenderer = null,
                callArgumentsRenderer = FirCallNoArgumentsRenderer(),
                modifierRenderer = FirAllModifierRenderer(),
                callableSignatureRenderer = FirCallableSignatureRendererForReadability(),
                declarationRenderer = FirDeclarationRenderer("local "),
            )
        return renderer.renderElementAsString(element)
    }

    private fun origin(symbol: FirBasedSymbol<*>): String = symbol.fir.origin.toString()

    fun outputPath(): Path = KotlinGraphShard.outputPathAtRoot(targetRoot, Paths.get(source))
}
