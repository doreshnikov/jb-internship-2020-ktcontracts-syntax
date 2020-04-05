package org.jetbrains.dummy.lang.check

import org.jetbrains.dummy.lang.DiagnosticReporter
import org.jetbrains.dummy.lang.tree.*
import javax.swing.plaf.nimbus.State

class FunctionCallsChecker(private val reporter: DiagnosticReporter) : AbstractChecker() {

    companion object {
        private const val MAIN_NAME = "main"
        private const val MAIN_ARG_COUNT = 0

        fun isMain(function: FunctionDeclaration): Boolean {
            return function.name == MAIN_NAME && function.parameters.size == MAIN_ARG_COUNT
        }
    }

    private class FunctionInfo(val declaration: FunctionDeclaration) {
        var calls = 0
    }

    override fun inspect(file: File) {
        val functionInfo = HashMap<String, HashMap<Int, FunctionInfo>>()
        collect(file, functionInfo)
        file.functions.forEach { visit(it, functionInfo) }

        functionInfo.values.forEach { fns ->
            fns.values.forEach { fn ->
                val declaration = fn.declaration
                if (fn.calls == 0 && !isMain(declaration)) {
                    warnUnusedFunction(declaration)
                }
            }
        }
    }

    private fun collect(file: File, functionInfo: HashMap<String, HashMap<Int, FunctionInfo>>) {
        file.functions.forEach { decl ->
            val argCount = decl.parameters.size
            functionInfo.getOrPut(decl.name) { HashMap() }.let { map ->
                map.putIfAbsent(argCount, FunctionInfo(decl))?.let {
                    // report fun <name, args> ... fun <name, args>
                    reportFunctionRedeclaration(decl, it.declaration)
                }
            }
        }
    }

    private fun visit(function: FunctionDeclaration, functionInfo: HashMap<String, HashMap<Int, FunctionInfo>>) {
        visit(function.body, functionInfo)
    }

    private fun visit(block: Block, functionInfo: HashMap<String, HashMap<Int, FunctionInfo>>) {
        block.statements.forEach { visit(it, functionInfo) }
    }

    private fun visit(statement: Statement, functionInfo: HashMap<String, HashMap<Int, FunctionInfo>>) {
        when (statement) {
            is Assignment -> visit(statement.rhs, functionInfo)
            is IfStatement -> {
                visit(statement.condition, functionInfo)
                visit(statement.thenBlock, functionInfo)
                statement.elseBlock?.let { visit(it, functionInfo) }
            }
            is VariableDeclaration -> statement.initializer?.let { visit(it, functionInfo) }
            is ReturnStatement -> statement.result?.let { visit(it, functionInfo) }
            is Expression -> if (statement is FunctionCall) {
                val fns = functionInfo[statement.function]
                if (fns != null) {
                    // report fun <name, args1> ... <name>(<args2>)
                    fns[statement.arguments.size]?.let { it.calls++ }
                        ?: reportMismatchedArgumentsFunctionCall(statement)
                } else {
                    // report <undef>(...)
                    reportUndeclaredFunctionCall(statement)
                }
            }
        }
    }

    private fun warnUnusedFunction(declaration: FunctionDeclaration) {
        reporter.warn(declaration, "Function '${declaration.name}' is declared but is never used")
    }

    private fun reportFunctionRedeclaration(declaration: FunctionDeclaration, old: FunctionDeclaration) {
        reporter.report(
            declaration,
            "Function '${declaration.name}' with exactly ${declaration.parameters.size} argument(s) " +
                    "is already declared on line ${old.line}"
        )
    }

    private fun reportUndeclaredFunctionCall(call: FunctionCall) {
        reporter.report(call, "Function '${call.function}' is called but is not declared")
    }

    private fun reportMismatchedArgumentsFunctionCall(call: FunctionCall) {
        reporter.report(call, "No function '${call.function}' with exactly ${call.arguments.size} arguments")
    }

}