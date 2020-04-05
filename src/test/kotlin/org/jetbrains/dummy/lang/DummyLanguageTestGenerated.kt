package org.jetbrains.dummy.lang

import org.junit.Test

class DummyLanguageTestGenerated : AbstractDummyLanguageTest() {
    @Test
    fun testAllOk() {
        doTest("testData\\allOk.dummy")
    }
    
    @Test
    fun testFunFails() {
        doTest("testData\\funFails.dummy")
    }
    
    @Test
    fun testRetFails() {
        doTest("testData\\retFails.dummy")
    }
    
    @Test
    fun testVarFails() {
        doTest("testData\\varFails.dummy")
    }
}
