package com.panhub.feature.shareimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 从系统分享进来的那段文字里挑链接。
 *
 * 每条用例都对应一个真实的转发格式：分享出来的从来不是干净的 URL，
 * 而是「链接: … 提取码: … 复制这段内容后打开手机网盘」这种整段话。
 */
class SharedTextTest {

    @Test
    fun bareUrlIsKeptAsIs() {
        val link = SharedText.extract("https://pan.quark.cn/s/0123456789ab")
        assertEquals("https://pan.quark.cn/s/0123456789ab", link?.url)
        assertNull(link?.passcode)
    }

    @Test
    fun surroundingChineseIsDropped() {
        val link = SharedText.extract("我用夸克网盘分享了「报告.pdf」，点击链接即可保存。链接：https://pan.quark.cn/s/0123456789ab")
        assertEquals("https://pan.quark.cn/s/0123456789ab", link?.url)
    }

    @Test
    fun passcodeInQueryIsLifted() {
        val link = SharedText.extract("https://www.123pan.cn/s/abcde-fghij?pwd=1234")
        assertEquals("https://www.123pan.cn/s/abcde-fghij?pwd=1234", link?.url)
        assertEquals("1234", link?.passcode)
    }

    @Test
    fun passcodeWrittenAfterTheLinkIsLifted() {
        val link = SharedText.extract("链接: https://pan.baidu.com/s/1abcDEF 提取码: a1b2")
        assertEquals("https://pan.baidu.com/s/1abcDEF", link?.url)
        assertEquals("a1b2", link?.passcode)
    }

    @Test
    fun fullWidthColonDoesNotSwallowTheUrl() {
        val link = SharedText.extract("https://www.123pan.com/s/xyz123：提取码 8877")
        assertEquals("https://www.123pan.com/s/xyz123", link?.url)
        assertEquals("8877", link?.passcode)
    }

    @Test
    fun trailingSentencePunctuationIsTrimmed() {
        val link = SharedText.extract("打开 https://pan.baidu.com/s/1abcDEF.")
        assertEquals("https://pan.baidu.com/s/1abcDEF", link?.url)
    }

    @Test
    fun queryPasscodeWinsOverTheOneWrittenInText() {
        val link = SharedText.extract("https://pan.quark.cn/s/0123abcd?pwd=aaaa 提取码: bbbb")
        assertEquals("aaaa", link?.passcode)
    }

    @Test
    fun httpWithoutSIsAccepted() {
        assertEquals("http://pan.baidu.com/s/1abcDEF", SharedText.extract("http://pan.baidu.com/s/1abcDEF")?.url)
    }

    @Test
    fun plainTextHasNoLink() {
        assertNull(SharedText.extract("今天下午开会，别忘了带电脑"))
    }

    @Test
    fun blankInputHasNoLink() {
        assertNull(SharedText.extract(null))
        assertNull(SharedText.extract("   "))
    }

    @Test
    fun schemeOnlyIsNotALink() {
        assertNull(SharedText.extract("把文件传到 https:// 上面"))
    }

    @Test
    fun firstLinkWinsWhenThereAreTwo() {
        val link = SharedText.extract("https://pan.quark.cn/s/first123 和 https://pan.quark.cn/s/second456")
        assertEquals("https://pan.quark.cn/s/first123", link?.url)
    }
}
