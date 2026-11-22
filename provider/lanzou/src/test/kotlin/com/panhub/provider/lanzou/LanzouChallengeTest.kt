package com.panhub.provider.lanzou

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanzouChallengeTest {

    @Test fun markerIsDetected() {
        assertTrue(LanzouChallenge.contains("<script>var arg1='abc'</script>acw_sc__v2"))
        assertFalse(LanzouChallenge.contains("<html>正常页面</html>"))
    }

    @Test fun challengeWithoutArgIsIgnored() {
        assertNull(LanzouChallenge.compute("<html>没有 arg1</html>"))
    }

    /**
     * 固定向量：用 40 个十六进制字符做输入，算出的 cookie 必须是同一串。
     * 这些数字不是随机取的，是把置换表与密钥手工展开后得到的期望值，
     * 只要置换表或密钥被改动，这个断言就会失败。
     */
    @Test fun permutationMatchesReferenceVector() {
        val arg = "0123456789ABCDEF0123456789ABCDEF01234567"
        val computed = LanzouChallenge.compute("arg1='$arg'")
        assertEquals("d2c7186598ab1a508a4f6064e4fa746323ab17c6", computed ?: "null")
    }
}