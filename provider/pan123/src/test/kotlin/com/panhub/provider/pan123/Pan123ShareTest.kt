package com.panhub.provider.pan123

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分享相关的纯逻辑：链接归一化、有效期换算、字段大小写兼容、游标终止值。
 *
 * 这些换算在真实请求里出错时只会表现为「分享创建成功但链接打不开」或
 * 「有效期比选的短」，用户无从判断，所以固定在这里。
 */
class Pan123ShareTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Test
    fun bareKeyBecomesShareUrl() {
        assertEquals("https://www.123pan.cn/s/abc", normalizeShareUrl("abc"))
    }

    @Test
    fun relativePathKeepsHost() {
        assertEquals("https://www.123pan.cn/s/abc", normalizeShareUrl("/s/abc"))
    }

    @Test
    fun fullUrlIsLeftAlone() {
        assertEquals("https://example.com/x", normalizeShareUrl("https://example.com/x"))
    }

    @Test
    fun noDaysMeansPermanent() {
        assertEquals(PERMANENT_EXPIRATION, shareExpirationIso(null))
        assertEquals(PERMANENT_EXPIRATION, shareExpirationIso(0))
        assertEquals(4, shareModalityOf(null))
    }

    @Test
    fun dayCountMapsToNearestTier() {
        assertEquals(1, shareModalityOf(1))
        assertEquals(2, shareModalityOf(3))
        assertEquals(2, shareModalityOf(7))
        assertEquals(3, shareModalityOf(30))
    }

    @Test
    fun finiteExpirationIsIsoWithOffset() {
        val iso = shareExpirationIso(7)
        assertTrue("应以 +08:00 结尾: " + iso, iso.endsWith("+08:00"))
        assertTrue("应是 ISO 本地时间: " + iso, iso.contains("T23:59:59+08:00"))
    }

    @Test
    fun unknownFieldsOnShareListDoNotBreakParsing() {
        val raw = """{"code":0,"data":{"Next":"-1","InfoList":[{"shareId":42,"shareKey":"kk","shareName":"n","shareUrl":"","sharePwd":"p","shareStatus":0,"expiration":"2099-12-12T08:00:00+08:00","somethingNew":1}]}}"""
        val data = json.decodeFromString(
            ApiEnvelope.serializer(ShareListData.serializer()),
            raw,
        ).data!!
        val item = data.infoList.single()
        assertEquals(42L, item.shareId)
        assertEquals("kk", item.shareKey)
        assertEquals("p", item.sharePwd)
        assertEquals(0, item.shareStatus)
        assertNull("Next 为 -1 时必须没有下一页", data.next?.takeIf { it != "-1" })
    }

    @Test
    fun upperCaseVariantIsAccepted() {
        val raw = """{"code":0,"data":{"InfoList":[{"ShareId":7,"ShareKey":"kx","ShareName":"nm","Status":1}]}}"""
        val data = json.decodeFromString(
            ApiEnvelope.serializer(ShareListData.serializer()),
            raw,
        ).data!!
        val item = data.infoList.single()
        assertEquals(7L, item.shareId)
        assertEquals("kx", item.shareKey)
        assertEquals("nm", item.shareName)
        assertEquals(1, item.shareStatus)
    }

    @Test
    fun deleteBodyCarriesNumericShareId() {
        val body = json.encodeToString(
            ShareDeleteRequest.serializer(),
            ShareDeleteRequest(shareInfoList = listOf(ShareIdWrapper(shareId = 99))),
        )
        assertTrue("必须把 shareId 包进 shareInfoList: " + body, body.contains("shareInfoList"))
        assertTrue("shareId 必须是数字: " + body, body.contains("99"))
        assertTrue("event 必须是 shareCancel: " + body, body.contains("shareCancel"))
    }

    @Test
    fun createBodySendsFileIdsAsCsvString() {
        val body = json.encodeToString(
            ShareCreateRequest.serializer(),
            ShareCreateRequest(
                expiration = PERMANENT_EXPIRATION,
                fileIdList = "1,2,3",
                fileNum = 3,
            ),
        )
        assertTrue("fileIdList 必须是逗号分隔的字符串: " + body, body.contains("\"fileIdList\":\"1,2,3\""))
        assertTrue("必须带 shareModality: " + body, body.contains("shareModality"))
    }

    @Test
    fun linkListObjectFormIsParsed() {
        val raw = """{"code":0,"data":{"shareLinkList":{"list":["https://www.123pan.cn/s/aa"]},"shareKey":"kk","sharePwd":"8x8x"}}"""
        val data = json.decodeFromString(
            ApiEnvelope.serializer(ShareCreateData.serializer()),
            raw,
        ).data!!
        assertEquals(listOf("https://www.123pan.cn/s/aa"), data.shareLinkList.shareLinks())
        assertEquals("kk", data.shareKey)
        assertEquals("8x8x", data.sharePwd)
    }

    @Test
    fun emptyLinkListGivesNoLinks() {
        assertTrue(emptyList<String>() == (null as kotlinx.serialization.json.JsonElement?).shareLinks())
    }
}
