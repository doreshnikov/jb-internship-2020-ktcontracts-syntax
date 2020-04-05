package org.jetbrains.dummy.lang.check

import org.jetbrains.dummy.lang.DiagnosticReporter
import org.jetbrains.dummy.lang.tree.*

class ReturnCoverageChecker(private val reporter: DiagnosticReporter) : AbstractChecker() {

    override fun inspect(file: File) {
        file.functions.forEach { visit(it) }
    }

    private enum class ReturnKind {
        NONE,
        EMPTY,
        VALUE
    }

    private class ReturnStatus(
        private var covered: Boolean, val kind: ReturnKind, val returnStatement: ReturnStatement?
    ) {
        constructor() : this(false, ReturnKind.NONE, null)

        fun isCovered(): Boolean {
            return covered
        }

        fun cover(): ReturnStatus {
            if (kind != ReturnKind.NONE) {
                covered = true
            }
            return this
        }

        fun nocover(): ReturnStatus {
            covered = false
            return this
        }
    }

    private fun ReturnStatus.acceptOne(next: ReturnStatus): ReturnStatus {
        if (kind == ReturnKind.NONE) return next
        if (next.kind == ReturnKind.NONE) return this
        if (kind != next.kind) {
            reportReturnKindsCollision(next.returnStatement!!, returnStatement!!)
        }
        return this.let { if (next.isCovered()) it.cover() else it }
    }

    private fun ReturnStatus.acceptCases(thenCase: ReturnStatus, elseCase: ReturnStatus?): ReturnStatus {
        if (elseCase == null) return acceptOne(thenCase.nocover())
        val covered = thenCase.isCovered() && elseCase.isCovered()
        return acceptOne(thenCase).acceptOne(elseCase).let { if (covered) it.cover() else it.nocover() }
    }

    private fun visit(function: FunctionDeclaration) {
        val returnStatus = visit(function.body)
        if (!returnStatus.isCovered() && returnStatus.kind == ReturnKind.VALUE) {
            reportExplicitReturnNeeded(function)
        }
    }

    private fun visit(block: Block): ReturnStatus {
        var returnStatus = ReturnStatus()
        block.statements.forEach {
            if (returnStatus.isCovered()) {
                warnUnreachableCode(it)
                return returnStatus
            } else {
                returnStatus = visit(it, returnStatus)
            }
        }
        return returnStatus
    }

    private fun visit(statement: Statement, returnStatus: ReturnStatus): ReturnStatus {
        return when (statement) {
            is IfStatement -> {
                val firstCase = visit(statement.thenBlock)
                returnStatus.acceptCases(firstCase, statement.elseBlock?.let { visit(it) })
            }
            is ReturnStatement -> {
                returnStatus.acceptOne(
                    if (statement.result == null) {
                        ReturnStatus(true, ReturnKind.EMPTY, statement)
                    } else {
                        ReturnStatus(true, ReturnKind.VALUE, statement)
                    }
                )
            }
            else -> ReturnStatus()
        }
    }

    private fun warnUnreachableCode(statement: Statement) {
        reporter.warn(statement, "Unreachable code after full coverage of 'return' statements")
    }

    private fun reportReturnKindsCollision(returnStatement: ReturnStatement, first: ReturnStatement) {
        reporter.report(
            returnStatement, "Different kinds of 'return' statements: diverges from line ${first.line}"
        )
    }

    private fun reportExplicitReturnNeeded(function: FunctionDeclaration) {
        reporter.report(
            function,
            "Some of the 'if' cases are not covered with 'return' statement " +
                    "but '${function.name}' should have a value-return"
        )
    }

}