package org.jetbrains.dummy.lang.check

import org.jetbrains.dummy.lang.DiagnosticReporter
import org.jetbrains.dummy.lang.tree.*

class VariableOperationOrderChecker(private val reporter: DiagnosticReporter) : AbstractChecker() {

    override fun inspect(file: File) {
        file.functions.forEach { visit(it) }
    }

    private sealed class DeclarationElement {
        abstract fun get(): Element

        class Explicit(val element: VariableDeclaration) : DeclarationElement() {
            override fun get(): VariableDeclaration {
                return element
            }
        }

        class Argument(val element: FunctionDeclaration) : DeclarationElement() {
            override fun get(): FunctionDeclaration {
                return element
            }
        }
    }

    private sealed class Status {
        object Undeclared : Status()
        class Declared(val declaration: DeclarationElement) : Status()
        class Initialized(val declaration: DeclarationElement) : Status() {
            constructor(declared: Declared) : this(declared.declaration)
        }
    }

    private class Scope {
        private var level = 0
        private var varLevels = HashMap<String, ArrayList<Int>>()
        private var levelInfo = ArrayList<HashMap<String, Status>>()

        init {
            levelInfo.add(HashMap())
        }

        private fun allocate() {
            level++
            levelInfo.add(HashMap())
        }

        private fun cleanup() {
            for (key in levelInfo[level].keys) {
                varLevels[key]!!.also {
                    it.removeAt(it.lastIndex)
                    if (it.isEmpty()) {
                        varLevels.remove(key)
                    }
                }
            }
            levelInfo.removeAt(level)
            level--
        }

        fun nextLevel(block: () -> Unit) {
            allocate()
            try {
                block()
            } finally {
                cleanup()
            }
        }

        fun onLastLevel(key: String): Boolean {
            return varLevels[key]?.last() ?: -1 == level
        }

        operator fun get(key: String): Status {
            return varLevels[key]?.let { levelInfo[it.last()][key] } ?: Status.Undeclared
        }

        operator fun set(key: String, value: Status) {
            if ((varLevels[key]?.last() ?: -1) < level) {
                varLevels.getOrPut(key) { ArrayList() }.add(level)
            }
            levelInfo[level][key] = value
        }
    }

    private fun Scope.declare(variable: String, declaration: DeclarationElement): Boolean {
        when (val status = get(variable)) {
            is Status.Undeclared -> set(variable, Status.Declared(declaration)).also { return true }
            else -> {
                val redeclaration = declaration.get() as VariableDeclaration
                val previous = when (status) {
                    is Status.Declared -> status.declaration
                    is Status.Initialized -> status.declaration
                    else -> error("impossible state")
                }
                return if (onLastLevel(variable)) {
                    // report var <name> ... var <name>
                    reportVariableRedeclaration(redeclaration, previous)
                    false
                } else {
                    // warn var <name> ... { ... var <name> ... }
                    warnNameForeshadowing(redeclaration, previous)
                    set(variable, Status.Declared(declaration))
                    true
                }
            }
        }
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
        val scope = Scope()
        val location = DeclarationElement.Argument(function)
        function.parameters.forEach { scope[it] = Status.Initialized(location) }
        visit(function.body, scope)
    }

    private fun visit(block: Block, scope: Scope) {
        scope.nextLevel {
            block.statements.forEach { visit(it, scope) }
        }
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
                if (scope.declare(statement.name, DeclarationElement.Explicit(statement))) {
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

    private fun warnNameForeshadowing(declaration: VariableDeclaration, previous: DeclarationElement) {
        reporter.warn(
            declaration,
            "Declaration of '${declaration.name}' foreshadows the higher-level " +
                    "declaration on line ${previous.get().line}"
        )
    }

    private fun reportVariableRedeclaration(declaration: VariableDeclaration, previous: DeclarationElement) {
        reporter.report(
            declaration,
            "Variable '${declaration.name}' is already declared " +
                    "on line ${previous.get().line} in visible scope"
        )
    }

    private fun reportUndefinedVariableAccess(statement: Statement, variable: String) {
        reporter.report(statement, "Variable '$variable' is accessed but is not declared")
    }

    private fun reportAccessBeforeInitialization(access: VariableAccess) {
        reporter.report(access, "Variable '${access.name}' is accessed before initialization")
    }

}