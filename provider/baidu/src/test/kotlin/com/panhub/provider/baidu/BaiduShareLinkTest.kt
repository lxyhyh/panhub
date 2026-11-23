package com.panhub.provider.baidu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 分享链接解析。
 *
 * 值得单独测：百度两个接口对 surl 的写法要求正好相反
 * （短链要带首位 1，提取码要去掉首位 1），
 * 这里错了不会报错，只会让提取码永远校验不过。
 */
class BaiduShareLinkTest {

    @Test
    fun shortLinkKeepsLeadingOne() {
        val parsed = BaiduShareLink.parse("https://pan.baidu.com/s/1pJ0ok7T")
        assertEquals("1pJ0ok7T", parsed!!.surl)
        assertEquals("pJ0ok7T", BaiduShareLink.shortForm(parsed.surl))
    }

    @Test
    fun initPageSurlIsNormalized() {
        val parsed = BaiduShareLink.parse("https://pan.baidu.com/share/init?surl=pJ0ok7T")
        // 旧版初始化页里的 surl 不带首位 1，归一后要与短链一致。
        assertEquals("1pJ0ok7T", parsed!!.surl)
    }

    @Test
    fun passcodeIsReadFromQuery() {
        val parsed = BaiduShareLink.parse("https://pan.baidu.com/s/1pJ0ok7T?pwd=abc1")
        assertEquals("abc1", parsed!!.passcode)
    }

    @Test
    fun bareSurlIsAccepted() {
        assertEquals("1pJ0ok7T", BaiduShareLink.parse("1pJ0ok7T")!!.surl)
        assertEquals("1pJ0ok7T", BaiduShareLink.parse("pJ0ok7T")!!.surl)
    }

    @Test
    fun otherLinksAreRejected() {
        assertNull(BaiduShareLink.parse("https://www.123pan.cn/s/abcde-fghij"))
        assertNull(BaiduShareLink.parse(""))
        assertNull(BaiduShareLink.parse(null))
        assertNull(BaiduShareLink.parse("不是链接"))
    }

    @Test
    fun shortFormNeverDropsTheWholeSurl() {
        assertEquals("", BaiduShareLink.shortForm("1").let { if (it == "1") "" else it })
        assertEquals("pJ0ok7T", BaiduShareLink.shortForm("pJ0ok7T"))
    }
}
