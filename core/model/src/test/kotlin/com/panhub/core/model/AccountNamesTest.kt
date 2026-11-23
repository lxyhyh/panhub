package com.panhub.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountNamesTest {

    @Test
    fun plainNameIsKeptAsIs() {
        assertEquals("公司备份盘", AccountNames.normalize("公司备份盘"))
    }

    @Test
    fun surroundingWhitespaceIsDropped() {
        assertEquals("公司备份盘", AccountNames.normalize("  公司备份盘  "))
    }

    @Test
    fun onlyTheFirstLineIsKept() {
        // 从聊天记录里粘过来的备注常常带着后面的上下文。
        assertEquals("公司备份盘", AccountNames.normalize("公司备份盘\n这是我自己的"))
    }

    @Test
    fun innerWhitespaceIsSqueezedToOneSpace() {
        assertEquals("公司 备份盘", AccountNames.normalize("公司\t\t  备份盘"))
    }

    @Test
    fun blankInputMeansNoName() {
        assertNull(AccountNames.normalize(""))
        assertNull(AccountNames.normalize("   "))
        assertNull(AccountNames.normalize("\n\n"))
        assertNull(AccountNames.normalize(null))
    }

    @Test
    fun longNameIsTruncatedToTheLimit() {
        val long = "备".repeat(AccountNames.MAX_LENGTH + 8)
        assertEquals(AccountNames.MAX_LENGTH, AccountNames.normalize(long)?.length)
    }

    @Test
    fun nameExactlyAtTheLimitIsUntouched() {
        val exact = "备".repeat(AccountNames.MAX_LENGTH)
        assertEquals(exact, AccountNames.normalize(exact))
    }

    @Test
    fun leadingLineBreaksDoNotCountAsName() {
        // 第一行是空的，后面有内容：仍是「没起名字」，而不是把第二行顶上来。
        assertNull(AccountNames.normalize("\n公司备份盘"))
    }
}
