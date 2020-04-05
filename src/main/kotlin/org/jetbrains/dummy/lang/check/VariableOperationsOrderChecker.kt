package org.jetbrains.dummy.lang.check

import org.jetbrains.dummy.lang.DiagnosticReporter
import org.jetbrains.dummy.lang.tree.*

class VariableOperationsOrderChecker(private val reporter: DiagnosticReporter) : AbstractChecker() {

    override fun inspect(file: File) {
        file.functions.forEach { visit(it) }
    }

    private sealed class DeclarationElement {
        abstract fun get(): Element
        abstract fun name1(): String
        fun line1() = get().line

        val name: String get() = name1()
        val line: Int get() = line1()

        class Explicit(val element: VariableDeclaration) : DeclarationElement() {
            override fun get(): VariableDeclaration {
                return element
            }

            override fun name1(): String {
                return element.name
            }
        }

        class Argument(val element: FunctionDeclaration) : DeclarationElement() {
            override fun get(): FunctionDeclaration {
                return element
            }

            override fun name1(): String {
                return element.name
            }
        }
    }

    private sealed class Status {
        var accesses = 0

        open fun getLocation(): DeclarationElement {
            error("This status has no declaration")
        }

        object Undeclared : Status()

        class Declared(val declaration: DeclarationElement) : Status() {
            override fun getLocation(): DeclarationElement {
                return declaration
            }
        }

        class Initialized(val declaration: DeclarationElement) : Status() {
            constructor(declared: Declared) : this(declared.declaration) {
                accesses = declared.accesses
            }

            override fun getLocation(): DeclarationElement {
                return declaration
            }
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

        private fun cleanup(): List<DeclarationElement> {
            val unused = ArrayList<DeclarationElement>()
            for ((key, value) in levelInfo[level]) {
                varLevels[key]!!.also {
                    it.removeAt(it.lastIndex)
                    if (it.isEmpty()) {
                        varLevels.remove(key)
                    }
                }
                if (value.accesses == 0) {
                    unused.add(value.getLocation())
                }
            }
            levelInfo.removeAt(level)
            level--
            return unused
        }

        fun nextLevel(block: () -> Unit): List<DeclarationElement> {
            allocate()
            try {
                block()
            } finally {
                return cleanup()
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
        when (val status = get(access.name)) {
            // report (... <undef> ...)
            is Status.Undeclared -> reportUndefinedVariableAccess(access, access.name)
            // report (... <no-init> ...)
            is Status.Declared -> reportAccessBeforeInitialization(access).also { status.accesses++ }
            is Status.Initialized -> status.accesses++
        }
    }

    private fun Scope.checkedNextLevel(block: () -> Unit) {
        nextLevel(block).forEach(::warnUnusedVariable)
    }

    private fun visit(function: FunctionDeclaration) {
        val scope = Scope()
        val location = DeclarationElement.Argument(function)
        function.parameters.forEach { scope[it] = Status.Initialized(location) }
        visit(function.body, scope)
    }

    private fun visit(block: Block, scope: Scope) {
        scope.checkedNextLevel {
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

    private fun warnUnusedVariable(declaration: DeclarationElement) {
        reporter.warn(declaration.get(), "Variable '${declaration.name}' is declared but is never used")
    }

    private fun warnNameForeshadowing(declaration: VariableDeclaration, previous: DeclarationElement) {
        reporter.warn(
            declaration,
            "Declaration of '${declaration.name}' foreshadows the higher-level " +
                    "declaration on line ${previous.line}"
        )
    }

    private fun reportVariableRedeclaration(declaration: VariableDeclaration, previous: DeclarationElement) {
        reporter.report(
            declaration,
            "Variable '${declaration.name}' is already declared " +
                    "on line ${previous.line} in visible scope"
        )
    }

    private fun reportUndefinedVariableAccess(statement: Statement, variable: String) {
        reporter.report(statement, "Variable '$variable' is accessed but is not declared")
    }

    private fun reportAccessBeforeInitialization(access: VariableAccess) {
        reporter.report(access, "Variable '${access.name}' is accessed before initialization")
    }

}