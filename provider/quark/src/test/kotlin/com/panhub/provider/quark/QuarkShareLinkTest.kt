package com.panhub.provider.quark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 分享链接解析的单元测试。
 *
 * 这个函数看着简单，但它决定了「用户粘进来的一串东西能不能用」。
 * 真实的链接形态比文档里写的多得多（分享面板复制、浏览器地址栏、只复制 id），
 * 所以每种形态都单独钉一个用例。
 */
class QuarkShareLinkTest {

    @Test
    fun fullUrlWithPasscodeIsSplit() {
        val parsed = QuarkShareLink.parse("https://pan.quark.cn/s/abcdef1234?pwd=8888")
        assertEquals("abcdef1234", parsed?.linkId)
        assertEquals("8888", parsed?.passcode)
    }

    @Test
    fun urlWithoutPasscodeStillParses() {
        val parsed = QuarkShareLink.parse("https://pan.quark.cn/s/abcdef1234")
        assertEquals("abcdef1234", parsed?.linkId)
        assertNull(parsed?.passcode)
    }

    @Test
    fun trailingSlashIsDropped() {
        assertEquals("abcdef1234", QuarkShareLink.parse("https://pan.quark.cn/s/abcdef1234/")?.linkId)
    }

    @Test
    fun schemeIsOptional() {
        assertEquals("abcdef1234", QuarkShareLink.parse("pan.quark.cn/s/abcdef1234")?.linkId)
    }

    @Test
    fun pathOnlyFormIsAccepted() {
        assertEquals("abcdef1234", QuarkShareLink.parse("/s/abcdef1234")?.linkId)
    }

    @Test
    fun bareIdIsAccepted() {
        val parsed = QuarkShareLink.parse("  abcdef1234  ")
        assertEquals("abcdef1234", parsed?.linkId)
        assertNull(parsed?.passcode)
    }

    @Test
    fun nonShareUrlIsRejected() {
        assertNull(QuarkShareLink.parse("https://pan.quark.cn/list/all"))
    }

    @Test
    fun emptyInputIsRejected() {
        assertNull(QuarkShareLink.parse("   "))
        assertNull(QuarkShareLink.parse(null))
    }

    @Test
    fun passcodeAfterOtherParamsIsFound() {
        val parsed = QuarkShareLink.parse("https://pan.quark.cn/s/abcdef1234?from=wx&pwd=4321&x=1")
        assertEquals("4321", parsed?.passcode)
    }
}
