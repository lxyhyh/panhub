package com.panhub.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 名字检查。
 *
 * 这些规则一旦松掉，用户在界面上看到「可以创建」，点了之后收到一句服务端的
 * 英文报错，两边的说法对不上。所以每条拒绝理由都钉在这里。
 */
class NodeNamesTest {

    @Test
    fun `an ordinary name is fine`() {
        assertNull(NodeNames.problem("工作报告 2026.pdf"))
        assertEquals("工作报告 2026.pdf", NodeNames.accept("工作报告 2026.pdf"))
    }

    @Test
    fun `an empty name is rejected`() {
        assertEquals("名字不能为空", NodeNames.problem(""))
    }

    @Test
    fun `a name made of spaces is not the same as empty`() {
        // 两者都建不出来，但用户看到的说法应当不同：一个是忘了填，一个是填错了。
        assertEquals("名字不能只有空格", NodeNames.problem("   "))
    }

    @Test
    fun `spaces around the name are rejected instead of silently trimmed`() {
        assertEquals("名字前后不能有空格", NodeNames.problem(" 相册"))
        assertEquals("名字前后不能有空格", NodeNames.problem("相册 "))
    }

    @Test
    fun `dot and double dot are reserved`() {
        assertEquals("「.」和「..」不能作为名字", NodeNames.problem("."))
        assertEquals("「.」和「..」不能作为名字", NodeNames.problem(".."))
        // 三个点不是保留名，只是难看。
        assertNull(NodeNames.problem("..."))
    }

    @Test
    fun `both slashes are refused`() {
        assertEquals("名字里不能有斜杠，那是目录分隔符", NodeNames.problem("a/b.txt"))
        assertEquals("名字里不能有斜杠，那是目录分隔符", NodeNames.problem("a\\b.txt"))
    }

    @Test
    fun `characters every provider refuses are refused here too`() {
        for (bad in listOf("a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b")) {
            assertEquals("名字里不能有 : * ? \" < > |", NodeNames.problem(bad))
        }
    }

    @Test
    fun `control characters are caught before they reach the server`() {
        assertEquals("名字里有看不见的控制字符", NodeNames.problem("a\nb"))
        assertEquals("名字里有看不见的控制字符", NodeNames.problem("a\u0000b"))
        assertEquals("名字里有看不见的控制字符", NodeNames.problem("a\u007fb"))
    }

    @Test
    fun `names longer than the limit are refused`() {
        val longest = "a".repeat(NodeNames.MAX_LENGTH)
        assertNull(NodeNames.problem(longest))
        assertEquals("名字最多 255 个字符", NodeNames.problem(longest + "a"))
    }

    @Test
    fun `accept only hands back names that pass`() {
        assertEquals("a.txt", NodeNames.accept("a.txt"))
        assertNull(NodeNames.accept("a:b"))
        assertNull(NodeNames.accept(""))
    }
}
