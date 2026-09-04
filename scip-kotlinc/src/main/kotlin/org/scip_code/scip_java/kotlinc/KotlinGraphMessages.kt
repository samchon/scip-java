package org.scip_code.scip_java.kotlinc

import java.util.concurrent.CopyOnWriteArrayList
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector

/** Keeps serializable compiler messages while preserving the build's collector. */
class KotlinGraphMessages(private val delegate: MessageCollector) : MessageCollector {
    data class Item(
        val severity: CompilerMessageSeverity,
        val message: String,
        val location: CompilerMessageSourceLocation?,
    )

    private val items = CopyOnWriteArrayList<Item>()
    private val visitors = CopyOnWriteArrayList<ScipVisitor>()

    override fun clear() {
        items.clear()
        delegate.clear()
    }

    override fun hasErrors(): Boolean = delegate.hasErrors()

    override fun report(
        severity: CompilerMessageSeverity,
        message: String,
        location: CompilerMessageSourceLocation?,
    ) {
        if (
            severity.isError ||
                severity == CompilerMessageSeverity.WARNING ||
                severity == CompilerMessageSeverity.STRONG_WARNING
        ) {
            val item = Item(severity, message, location)
            items += item
            visitors.forEach { visitor ->
                visitor.graphDiagnostic(item.severity, item.message, item.location)
                visitor.writeGraph()
            }
        }
        delegate.report(severity, message, location)
    }

    fun snapshot(): List<Item> = items.toList()

    fun register(visitor: ScipVisitor) {
        visitors += visitor
    }
}
