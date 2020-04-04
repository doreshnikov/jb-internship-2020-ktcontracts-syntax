package org.jetbrains.dummy.lang.check

import org.jetbrains.dummy.lang.tree.File

abstract class AbstractChecker {
    abstract fun inspect(file: File)
}