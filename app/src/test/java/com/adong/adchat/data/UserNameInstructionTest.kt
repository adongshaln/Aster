package com.adong.adchat.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UserNameInstructionTest {

    @Test fun namedUserGetsInstruction() {
        assertEquals("称呼用户为「小明」。", userNameInstruction("小明"))
    }

    @Test fun blankNameYieldsEmptyInstruction() {
        assertEquals("", userNameInstruction(""))
        assertEquals("", userNameInstruction("   "))
    }

    @Test fun surroundingWhitespaceIsTrimmed() {
        assertEquals("称呼用户为「小明」。", userNameInstruction("  小明  "))
    }
}
