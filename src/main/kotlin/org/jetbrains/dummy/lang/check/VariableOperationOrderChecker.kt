package org.jetbrains.dummy.lang.check

import org.jetbrains.dummy.lang.DiagnosticReporter
import org.jetbrains.dummy.lang.tree.*
import javax.swing.plaf.nimbus.State

class VariableOperationOrderChecker(private val reporter: DiagnosticReporter) : AbstractChecker() {
    override fun inspect(file: File) {
        file.functions.forEach { visit(it) }
    }

    private sealed class Status {
        object Undeclared : Status()
        class Declared(val declaration: Element) : Status()
        class Initialized(val declaration: Element) : Status() {
            constructor(declared: Declared) : this(declared.declaration)
        }
    }

    private class Scope : HashMap<String, Status>() {
        override fun get(key: String): Status {
            return super.getOrDefault(key, Status.Undeclared)
        }
    }

    private fun Scope.declare(variable: String, declaration: VariableDeclaration): Boolean {
        when (val status = get(variable)) {
            is Status.Undeclared -> set(variable, Status.Declared(declaration)).also { return true }
            // report var <name> ... var <name>
            is Status.Declared -> reportVariableRedeclaration(declaration, status.declaration)
            is Status.Initialized -> reportVariableRedeclaration(declaration, status.declaration)
        }
        return false
    }

    private fun Scope.initialize(variable: String, statement: Statement): Boolean {
        when (val status = get(variable)) {
            // report <undef> = ...
            is Status.Undeclared -> reportUndefinedVariableAccess(statement, variable).also { return false }
            is Status.Declared -> set(variable, Status.Initialized(status))
        }
        return true
    }

    private fun Scope.access(access: VariableAccess) {
        when (get(access.name)) {
            // report (... <undef> ...)
            is Status.Undeclared -> reportUndefinedVariableAccess(access, access.name)
            // report (... <no-init> ...)
            is Status.Declared -> reportAccessBeforeInitialization(access)
        }
    }

    private fun visit(function: FunctionDeclaration) {
        val vars = Scope()
        function.parameters.forEach { vars[it] = Status.Initialized(function) }
        visit(function.body, vars)
    }

    private fun visit(block: Block, scope: Scope) {
        block.statements.forEach { visit(it, scope) }
    }

    private fun visit(statement: Statement, scope: Scope) {
        when (statement) {
            is Assignment -> {
                scope.initialize(statement.variable, statement)
                visit(statement.rhs, scope)
            }
            is IfStatement -> {
                visit(statement.condition, scope)
                visit(statement.thenBlock, scope)
                statement.elseBlock?.let { visit(it, scope) }
            }
            is VariableDeclaration -> {
                if (scope.declare(statement.name, statement)) {
                    if (statement.initializer != null) {
                        scope.initialize(statement.name, statement)
                        visit(statement.initializer, scope)
                    }
                }
            }
            is ReturnStatement -> statement.result?.let { visit(it, scope) }
            is Expression -> when (statement) {
                is VariableAccess -> scope.access(statement)
                is FunctionCall -> statement.arguments.forEach { visit(it, scope) }
            }
        }
    }

    private fun reportVariableRedeclaration(declaration: VariableDeclaration, previous: Element) {
        reporter.report(
            declaration,
            "Variable '${declaration.name}' is already declared on line ${previous.line}"
        )
    }

    private fun reportUndefinedVariableAccess(statement: Statement, variable: String) {
        reporter.report(statement, "Variable '$variable' is not declared")
    }

    private fun reportAccessBeforeInitialization(access: VariableAccess) {
        reporter.report(access, "Variable '${access.name}' is accessed before initialization")
    }
}