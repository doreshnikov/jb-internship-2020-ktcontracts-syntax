package org.jetbrains.dummy.lang.check

import org.jetbrains.dummy.lang.DiagnosticReporter
import org.jetbrains.dummy.lang.tree.*

class VariableInitializationChecker(private val reporter: DiagnosticReporter) : AbstractChecker() {
    override fun inspect(file: File) {
        file.functions.forEach { visit(it) }
    }

    private fun visit(function: FunctionDeclaration) {
        val vars = HashMap<String, Boolean>()
        function.parameters.forEach { vars[it] = true }
        function.body.statements.forEach { visit(it, vars) }
    }

    private fun checkFail(access: VariableAccess, scope: HashMap<String, Boolean>): Boolean {
        return !scope.getOrDefault(access.name, true)
    }

    private fun visit(statement: Statement, scope: HashMap<String, Boolean>) {
        when (statement) {
            is Assignment -> {
                visit(statement.rhs, scope)
                scope[statement.variable] = true
            }
            is IfStatement -> visit(statement.condition, scope)
            is VariableDeclaration -> {
                if (statement.initializer == null) {
                    scope.putIfAbsent(statement.name, false)
                } else {
                    visit(statement.initializer, scope)
                    scope.putIfAbsent(statement.name, true)
                }
            }
            is ReturnStatement -> statement.result?.let { visit(it, scope) }
            is Expression -> when (statement) {
                is VariableAccess -> if (checkFail(statement, scope)) {
                    reportAccessBeforeInitialization(statement)
                }
                is FunctionCall -> statement.arguments.forEach { visit(it, scope) }
            }
        }
    }

    // Use this method for reporting errors
    private fun reportAccessBeforeInitialization(access: VariableAccess) {
        reporter.report(access, "Variable '${access.name}' is accessed before initialization")
    }
}