package org.jetbrains.dummy.lang

import org.jetbrains.dummy.lang.check.AbstractChecker
import org.jetbrains.dummy.lang.check.FunctionCallsChecker
import org.jetbrains.dummy.lang.check.ReturnCoverageChecker
import org.jetbrains.dummy.lang.check.VariableOperationsOrderChecker
import java.io.OutputStream

class DummyLanguageAnalyzer(outputStream: OutputStream) {
    companion object {
        private val CHECKERS: List<(DiagnosticReporter) -> AbstractChecker> = listOf(
            ::VariableOperationsOrderChecker,
            ::FunctionCallsChecker,
            ::ReturnCoverageChecker
        )
    }

    private val reporter: DiagnosticReporter = DiagnosticReporter(outputStream)

    fun analyze(path: String) {
        val transformer = DummySourceToTreeTransformer()
        val file = transformer.transform(path)

        val checkers = CHECKERS.map { it(reporter) }
        for (checker in checkers) {
            checker.inspect(file)
        }
    }
}

