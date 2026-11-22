package com.panhub.provider.baidu

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 百度分享记录的解析。
 *
 * 两个容易踩的点：list 为空时服务端给的是对象而不是数组；
 * 提取码要单独签名另取，记录里那个 passwd 字段早就不返回了。
 */
class BaiduShareParsingTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }

    @Test
    fun emptyListAsObjectIsTreatedAsNoShares() {
        val raw = """{"count":0,"errno":-6,"list":{},"nextpage":0,"show_msg":"x"}"""
        val resp = json.decodeFromString(BaiduShareRecordResp.serializer(), raw)
        assertTrue("空对象必须解析成空列表", resp.list.shareRecords().isEmpty())
    }

    @Test
    fun arrayOfRecordsIsParsed() {
        val raw = """{"count":1,"errno":0,"nextpage":0,"list":[{"shareId":123,"shortlink":"https://pan.baidu.com/s/1abc","status":0,"expiredTime":4123456789}]}"""
        val resp = json.decodeFromString(BaiduShareRecordResp.serializer(), raw)
        val record = resp.list.shareRecords().single()
        assertEquals(123L, record.shareId)
        assertEquals("https://pan.baidu.com/s/1abc", record.shortlink)
    }

    @Test
    fun surlSignMatchesKnownVector() {
        // md5("123456_sharesurlinfo!@#")
        assertEquals("0d282c7b1804a5d519f4d69c34a6f487", shareSurlSign("123456"))
    }

    @Test
    fun zeroPasswordMeansNoPassword() {
        assertNull(normalizeSharePwd("0"))
        assertNull(normalizeSharePwd(""))
        assertNull(normalizeSharePwd(null))
        assertEquals("abcd", normalizeSharePwd("abcd"))
    }

    @Test
    fun cancelledShareIsNotReportedAsLive() {
        val record = json.decodeFromString(
            BaiduShareRecord.serializer(),
            """{"shareId":9,"status":3}""",
        )
        assertTrue("status 非 0 就是失效", record.status != 0)
    }
}
