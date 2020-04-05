package org.jetbrains.dummy.lang.check

import org.jetbrains.dummy.lang.DiagnosticReporter
import org.jetbrains.dummy.lang.tree.*
import javax.swing.plaf.nimbus.State

class FunctionCallsChecker(private val reporter: DiagnosticReporter) : AbstractChecker() {

    override fun inspect(file: File) {
        val functionInfo = HashMap<String, HashMap<Int, FunctionDeclaration>>()
        collect(file, functionInfo)
        file.functions.forEach { visit(it, functionInfo) }
    }

    private fun collect(file: File, functionInfo: HashMap<String, HashMap<Int, FunctionDeclaration>>) {
        file.functions.forEach { decl ->
            val argCount = decl.parameters.size
            functionInfo.getOrPut(decl.name) { HashMap() }.let { map ->
                map.putIfAbsent(argCount, decl)?.let {
                    // report fun <name, args> ... fun <name, args>
                    reportFunctionRedeclaration(decl, it)
                }
            }
        }
    }

    private fun visit(function: FunctionDeclaration, functionInfo: HashMap<String, HashMap<Int, FunctionDeclaration>>) {
        visit(function.body, functionInfo)
    }

    private fun visit(block: Block, functionInfo: HashMap<String, HashMap<Int, FunctionDeclaration>>) {
        block.statements.forEach { visit(it, functionInfo) }
    }

    private fun visit(statement: Statement, functionInfo: HashMap<String, HashMap<Int, FunctionDeclaration>>) {
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
                    if (fns[statement.arguments.size] == null) {
                        // report fun <name, args1> ... <name>(<args2>)
                        reportMismatchedArgumentsFunctionCall(statement)
                    }
                } else {
                    // report <undef>(...)
                    reportUndeclaredFunctionCall(statement)
                }
            }
        }
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