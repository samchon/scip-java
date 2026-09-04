package org.scip_code.scip_java.kotlinc

import java.nio.file.Path
import org.jetbrains.kotlin.*
import org.jetbrains.kotlin.com.intellij.lang.LighterASTNode
import org.jetbrains.kotlin.com.intellij.util.diff.FlyweightCapableTreeStructure
import org.jetbrains.kotlin.diagnostics.*
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.*
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirQualifiedAccessExpressionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirResolvedQualifierChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirTypeOperatorCallChecker
import org.jetbrains.kotlin.fir.analysis.checkers.toClassLikeSymbol
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirTypeOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.calls.FirSyntheticFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.getContainingClassSymbol
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousObjectSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

open class AnalyzerCheckers(session: FirSession, private val state: AnalyzerCompilationState) :
    FirAdditionalCheckersExtension(session) {
    companion object {
        private fun getIdentifier(element: KtSourceElement): KtSourceElement =
            element.treeStructure
                .findChildByType(element.lighterASTNode, KtTokens.IDENTIFIER)
                ?.toKtLightSourceElement(element.treeStructure) ?: element

        context(context: CheckerContext)
        private fun ScipVisitor.emitTypeRef(typeRef: FirTypeRef) {
            val klass = typeRef.toClassLikeSymbol(context.session)
            val source = typeRef.source
            if (klass != null && source != null && source.kind !is KtFakeSourceElementKind) {
                visitClassReference(klass, getIdentifier(source))
            }
        }
    }

    override val declarationCheckers: DeclarationCheckers
        get() =
            AnalyzerDeclarationCheckers(
                session.analyzerParamsProvider.sourceroot,
                session.analyzerParamsProvider.graphRoot,
                session.analyzerParamsProvider.graphTarget,
                session.analyzerParamsProvider.graphMessages,
                state,
            )

    override val expressionCheckers: ExpressionCheckers
        get() =
            object : ExpressionCheckers() {
                override val qualifiedAccessExpressionCheckers:
                    Set<FirQualifiedAccessExpressionChecker> =
                    setOf(SemanticQualifiedAccessExpressionChecker(state))

                override val resolvedQualifierCheckers: Set<FirResolvedQualifierChecker> =
                    setOf(SemanticResolvedQualifierChecker(state))

                override val typeOperatorCallCheckers: Set<FirTypeOperatorCallChecker> =
                    setOf(SemanticClassReferenceExpressionChecker(state))

                override val variableAssignmentCheckers:
                    Set<FirExpressionChecker<FirVariableAssignment>> =
                    setOf(SemanticVariableAssignmentChecker(state))
            }

    open class AnalyzerDeclarationCheckers(
        sourceroot: Path,
        graphRoot: Path? = null,
        graphTarget: String? = null,
        private val graphMessages: KotlinGraphMessages? = null,
        private val state: AnalyzerCompilationState,
    ) : DeclarationCheckers() {
        override val fileCheckers: Set<FirFileChecker> =
            setOf(
                SemanticFileChecker(sourceroot, graphRoot, graphTarget, graphMessages, state),
                SemanticImportsChecker(state),
            )
        override val classLikeCheckers: Set<FirClassLikeChecker> =
            setOf(SemanticClassLikeChecker(state))
        override val constructorCheckers: Set<FirConstructorChecker> =
            setOf(SemanticConstructorChecker(state))
        override val simpleFunctionCheckers: Set<FirSimpleFunctionChecker> =
            setOf(SemanticSimpleFunctionChecker(state))
        override val anonymousFunctionCheckers: Set<FirAnonymousFunctionChecker> =
            setOf(SemanticAnonymousFunctionChecker(state))
        override val propertyCheckers: Set<FirPropertyChecker> =
            setOf(SemanticPropertyChecker(state))
        override val valueParameterCheckers: Set<FirValueParameterChecker> =
            setOf(SemanticValueParameterChecker(state))
        override val typeParameterCheckers: Set<FirTypeParameterChecker> =
            setOf(SemanticTypeParameterChecker(state))
        override val typeAliasCheckers: Set<FirTypeAliasChecker> =
            setOf(SemanticTypeAliasChecker(state))
        override val propertyAccessorCheckers: Set<FirPropertyAccessorChecker> =
            setOf(SemanticPropertyAccessorChecker(state))
        override val enumEntryCheckers: Set<FirEnumEntryChecker> =
            setOf(SemanticEnumEntryChecker(state))
    }

    private class SemanticFileChecker(
        private val sourceroot: Path,
        private val graphRoot: Path?,
        private val graphTarget: String?,
        private val graphMessages: KotlinGraphMessages?,
        private val state: AnalyzerCompilationState,
    ) : FirFileChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirFile) {
            val ktFile = declaration.sourceFile ?: return
            val lineMap = LineMap(declaration)
            val visitor =
                ScipVisitor(
                    sourceroot,
                    ktFile,
                    lineMap,
                    state.globals,
                    graphRoot = graphRoot,
                    graphTarget = graphTarget,
                )
            state.visitors[ktFile] = visitor
            state.diagnosticReporters[ktFile] = reporter
            graphMessages?.register(visitor)
        }
    }

    class SemanticImportsChecker(private val state: AnalyzerCompilationState) :
        FirFileChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirFile) {
            val ktFile = declaration.sourceFile ?: return
            val visitor = state.visitors[ktFile]

            val eachFqNameElement =
                {
                    fqName: FqName,
                    tree: FlyweightCapableTreeStructure<LighterASTNode>,
                    names: LighterASTNode,
                    callback: (FqName, KtLightSourceElement) -> Unit ->
                    val nameList =
                        if (names.tokenType == KtNodeTypes.REFERENCE_EXPRESSION) listOf(names)
                        else tree.collectDescendantsOfType(names, KtNodeTypes.REFERENCE_EXPRESSION)

                    var ancestor = fqName
                    var depth = 0

                    while (ancestor != FqName.ROOT) {
                        val nameNode = nameList[nameList.lastIndex - depth]
                        val nameSource = nameNode.toKtLightSourceElement(tree)

                        callback(ancestor, nameSource)

                        ancestor = ancestor.parent()
                        depth++
                    }
                }

            val packageDirective = declaration.packageDirective
            val fqName = packageDirective.packageFqName
            val source = packageDirective.source
            if (source != null) {
                val names =
                    source.treeStructure.findChildByType(
                        source.lighterASTNode,
                        KtNodeTypes.DOT_QUALIFIED_EXPRESSION,
                    )
                        ?: source.treeStructure.findChildByType(
                            source.lighterASTNode,
                            KtNodeTypes.REFERENCE_EXPRESSION,
                        )

                if (names != null) {
                    eachFqNameElement(fqName, source.treeStructure, names) { fqName, name ->
                        visitor?.visitPackage(fqName, name)
                    }
                }
            }

            declaration.imports.forEach { import ->
                val source = import.source ?: return@forEach
                val fqName = import.importedFqName ?: return@forEach

                val names =
                    source.treeStructure.findDescendantByType(
                        source.lighterASTNode,
                        KtNodeTypes.DOT_QUALIFIED_EXPRESSION,
                    )
                if (names != null) {
                    eachFqNameElement(fqName, source.treeStructure, names) { fqName, name ->
                        val symbolProvider = context.session.symbolProvider

                        val klass =
                            symbolProvider.getClassLikeSymbolByClassId(ClassId.topLevel(fqName))
                        val callables =
                            symbolProvider.getTopLevelCallableSymbols(
                                fqName.parent(),
                                fqName.shortName(),
                            )

                        if (klass != null) {
                            visitor?.visitClassReference(klass, name)
                            visitor?.visitImport(klass, name, import.aliasName?.asString())
                        } else if (callables.isNotEmpty()) {
                            for (callable in callables) {
                                visitor?.visitCallableReference(callable, name)
                                visitor?.visitImport(callable, name, import.aliasName?.asString())
                            }
                        } else {
                            visitor?.visitPackage(fqName, name)
                        }
                    }
                }
            }
        }
    }

    private class SemanticClassLikeChecker(private val state: AnalyzerCompilationState) :
        FirClassLikeChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirClassLikeDeclaration) {
            val source = declaration.source ?: return
            val ktFile = context.containingFileSymbol?.sourceFile ?: return
            val visitor = state.visitors[ktFile]
            val objectKeyword =
                if (declaration is FirAnonymousObject) {
                    source.treeStructure
                        .findChildByType(source.lighterASTNode, KtTokens.OBJECT_KEYWORD)
                        ?.toKtLightSourceElement(source.treeStructure)
                } else {
                    null
                }
            val identifierSource = getIdentifier(source)
            // For unnamed companion objects, getIdentifier() falls back to source (no IDENTIFIER
            // token). Use the 'companion' keyword as the range instead. The COMPANION_KEYWORD is
            // inside a MODIFIER_LIST child, so we use findDescendantByType instead of
            // findChildByType.
            val companionKeyword =
                if (
                    identifierSource === source &&
                        declaration is FirRegularClass &&
                        declaration.isCompanion
                ) {
                    source.treeStructure
                        .findDescendantByType(source.lighterASTNode, KtTokens.COMPANION_KEYWORD)
                        ?.toKtLightSourceElement(source.treeStructure)
                } else {
                    null
                }
            visitor?.visitClassOrObject(
                declaration,
                objectKeyword ?: companionKeyword ?: identifierSource,
                enclosingSource = source,
            )

            if (declaration is FirClass) {
                for (superType in declaration.superTypeRefs) {
                    visitor?.emitTypeRef(superType)
                }
            }
        }
    }

    private class SemanticConstructorChecker(private val state: AnalyzerCompilationState) :
        FirConstructorChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirConstructor) {
            val source = declaration.source ?: return
            val ktFile = context.containingFileSymbol?.sourceFile ?: return
            val visitor = state.visitors[ktFile]

            if (declaration.isPrimary) {
                // if the constructor is not denoted by the 'constructor' keyword, we want to link
                // it to the
                // class identifier
                val klass = declaration.symbol.getContainingClassSymbol()
                val klassSource = klass?.source ?: source
                val constructorKeyboard =
                    source.treeStructure
                        .findChildByType(source.lighterASTNode, KtTokens.CONSTRUCTOR_KEYWORD)
                        ?.toKtLightSourceElement(source.treeStructure)

                val objectKeyword =
                    if (klass is FirAnonymousObjectSymbol) {
                        source.treeStructure
                            .findChildByType(source.lighterASTNode, KtTokens.OBJECT_KEYWORD)
                            ?.toKtLightSourceElement(source.treeStructure)
                    } else {
                        null
                    }

                visitor?.visitPrimaryConstructor(
                    declaration,
                    constructorKeyboard ?: objectKeyword ?: getIdentifier(klassSource),
                    enclosingSource = source,
                )
            } else {
                visitor?.visitSecondaryConstructor(
                    declaration,
                    getIdentifier(source),
                    enclosingSource = source,
                )
            }
        }
    }

    private class SemanticSimpleFunctionChecker(private val state: AnalyzerCompilationState) :
        FirSimpleFunctionChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirNamedFunction) {
            val source = declaration.source ?: return
            val ktFile = context.containingFileSymbol?.sourceFile ?: return
            val visitor = state.visitors[ktFile]
            visitor?.visitNamedFunction(
                declaration,
                getIdentifier(source),
                enclosingSource = source,
            )
            visitor?.emitTypeRef(declaration.returnTypeRef)
            declaration.receiverParameter?.typeRef?.let { visitor?.emitTypeRef(it) }
        }
    }

    private class SemanticAnonymousFunctionChecker(private val state: AnalyzerCompilationState) :
        FirAnonymousFunctionChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirAnonymousFunction) {
            val source = declaration.source ?: return
            val ktFile = context.containingFileSymbol?.sourceFile ?: return
            val visitor = state.visitors[ktFile]
            visitor?.visitNamedFunction(declaration, source, enclosingSource = source)
        }
    }

    private class SemanticPropertyChecker(private val state: AnalyzerCompilationState) :
        FirPropertyChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirProperty) {
            val source = declaration.source ?: return
            val ktFile = context.containingFileSymbol?.sourceFile ?: return
            val visitor = state.visitors[ktFile]
            visitor?.visitProperty(declaration, getIdentifier(source), enclosingSource = source)
            visitor?.emitTypeRef(declaration.returnTypeRef)
            declaration.receiverParameter?.typeRef?.let { visitor?.emitTypeRef(it) }
        }
    }

    private class SemanticValueParameterChecker(private val state: AnalyzerCompilationState) :
        FirValueParameterChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirValueParameter) {
            val source = declaration.source ?: return
            val ktFile = context.containingFileSymbol?.sourceFile ?: return
            val visitor = state.visitors[ktFile]
            visitor?.visitParameter(declaration, getIdentifier(source), enclosingSource = source)
            visitor?.emitTypeRef(declaration.returnTypeRef)
        }
    }

    private class SemanticTypeParameterChecker(private val state: AnalyzerCompilationState) :
        FirTypeParameterChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirTypeParameter) {
            val source = declaration.source ?: return
            val ktFile = context.containingFileSymbol?.sourceFile ?: return
            val visitor = state.visitors[ktFile]
            visitor?.visitTypeParameter(
                declaration,
                getIdentifier(source),
                enclosingSource = source,
            )
        }
    }

    private class SemanticTypeAliasChecker(private val state: AnalyzerCompilationState) :
        FirTypeAliasChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirTypeAlias) {
            val source = declaration.source ?: return
            val ktFile = context.containingFileSymbol?.sourceFile ?: return
            val visitor = state.visitors[ktFile]
            visitor?.visitTypeAlias(declaration, getIdentifier(source), enclosingSource = source)
        }
    }

    private class SemanticPropertyAccessorChecker(private val state: AnalyzerCompilationState) :
        FirPropertyAccessorChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirPropertyAccessor) {
            val source = declaration.source ?: return
            val ktFile = context.containingFileSymbol?.sourceFile ?: return
            val visitor = state.visitors[ktFile]
            val identifierSource =
                if (declaration.isGetter) {
                    source.treeStructure
                        .findChildByType(source.lighterASTNode, KtTokens.GET_KEYWORD)
                        ?.toKtLightSourceElement(source.treeStructure) ?: getIdentifier(source)
                } else if (declaration.isSetter) {
                    source.treeStructure
                        .findChildByType(source.lighterASTNode, KtTokens.SET_KEYWORD)
                        ?.toKtLightSourceElement(source.treeStructure) ?: getIdentifier(source)
                } else {
                    getIdentifier(source)
                }

            visitor?.visitPropertyAccessor(declaration, identifierSource, enclosingSource = source)
        }
    }

    private class SemanticEnumEntryChecker(private val state: AnalyzerCompilationState) :
        FirEnumEntryChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirEnumEntry) {
            val source = declaration.source ?: return
            val ktFile = context.containingFileSymbol?.sourceFile ?: return
            val visitor = state.visitors[ktFile]
            visitor?.visitEnumEntry(declaration, getIdentifier(source), enclosingSource = source)
        }
    }

    private class SemanticResolvedQualifierChecker(private val state: AnalyzerCompilationState) :
        FirResolvedQualifierChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(expression: FirResolvedQualifier) {
            val symbol = expression.symbol ?: return
            val source = expression.source ?: return
            if (source.kind is KtFakeSourceElementKind) return
            val ktFile = context.containingFileSymbol?.sourceFile ?: return
            val visitor = state.visitors[ktFile]
            visitor?.visitClassReference(symbol, getIdentifier(source))
        }
    }

    private class SemanticQualifiedAccessExpressionChecker(
        private val state: AnalyzerCompilationState
    ) : FirQualifiedAccessExpressionChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(expression: FirQualifiedAccessExpression) {
            val source = expression.source ?: return
            val calleeReference = expression.calleeReference
            if ((calleeReference as? FirResolvedNamedReference) == null) {
                return
            }

            val ktFile = context.containingFileSymbol?.sourceFile ?: return
            val visitor = state.visitors[ktFile]
            val identifierSource = getIdentifier(calleeReference.source ?: source)
            visitor?.visitSimpleNameExpression(calleeReference, identifierSource)

            when (expression) {
                is FirFunctionCall ->
                    (calleeReference.resolvedSymbol
                            as? org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol<*>)
                        ?.let { visitor?.visitCall(it, identifierSource) }
                is FirPropertyAccessExpression ->
                    visitor?.visitAccess(calleeReference.resolvedSymbol, identifierSource, "read")
            }

            val resolvedSymbol = calleeReference.resolvedSymbol
            if (
                resolvedSymbol.origin == FirDeclarationOrigin.SamConstructor &&
                    resolvedSymbol is FirSyntheticFunctionSymbol
            ) {
                val referencedKlass = resolvedSymbol.resolvedReturnType.toClassLikeSymbol()
                if (referencedKlass != null) {
                    visitor?.visitClassReference(referencedKlass, identifierSource)
                }
            }

            // When encountering a reference to a property symbol, emit both getter and setter
            // symbols
            if (resolvedSymbol is FirPropertySymbol) {
                resolvedSymbol.getterSymbol?.let {
                    visitor?.visitCallableReference(it, identifierSource)
                }
                resolvedSymbol.setterSymbol?.let {
                    visitor?.visitCallableReference(it, identifierSource)
                }
            }
        }
    }

    private class SemanticClassReferenceExpressionChecker(
        private val state: AnalyzerCompilationState
    ) : FirTypeOperatorCallChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(expression: FirTypeOperatorCall) {
            val typeRef = expression.conversionTypeRef
            val source = typeRef.source ?: return
            val classSymbol = typeRef.toClassLikeSymbol(context.session) ?: return
            val ktFile = context.containingFileSymbol?.sourceFile ?: return
            val visitor = state.visitors[ktFile]

            visitor?.visitClassReference(classSymbol, getIdentifier(source))
        }
    }

    private class SemanticVariableAssignmentChecker(private val state: AnalyzerCompilationState) :
        FirExpressionChecker<FirVariableAssignment>(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(expression: FirVariableAssignment) {
            val access = expression.lValue as? FirQualifiedAccessExpression ?: return
            val reference = access.calleeReference as? FirResolvedNamedReference ?: return
            val source = reference.source ?: access.source ?: return
            if (source.kind is KtFakeSourceElementKind) return
            val ktFile = context.containingFileSymbol?.sourceFile ?: return
            state.visitors[ktFile]?.visitAccess(
                reference.resolvedSymbol,
                getIdentifier(source),
                "write",
            )
        }
    }
}
