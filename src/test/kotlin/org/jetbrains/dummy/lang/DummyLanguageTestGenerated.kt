package org.jetbrains.dummy.lang

import org.junit.Test

class DummyLanguageTestGenerated : AbstractDummyLanguageTest() {
    @Test
    fun testAllFails() {
        doTest("testData\\variables\\allFails.dummy")
    }
    
    @Test
    fun testAllOk() {
        doTest("testData\\variables\\allOk.dummy")
    }
}
