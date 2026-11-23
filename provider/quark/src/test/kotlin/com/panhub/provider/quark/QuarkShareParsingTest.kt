package com.panhub.provider.quark

import com.panhub.core.model.NodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarkShareParsingTest {

    @Test
    fun permanentShareHasNoExpiryTypeBoundary() {
        // 不传有效期就是永久（档位 1）。
        assertEquals(1, QuarkShareParsing.expiredTypeOf(null))
        assertEquals(1, QuarkShareParsing.expiredTypeOf(0))
        assertEquals(1, QuarkShareParsing.expiredTypeOf(-5))
    }

    @Test
    fun expiryDaysSnapToNearestBucket() {
        // 服务端只有 1/7/30 三档，3 天只能落到 7 天档。
        assertEquals(2, QuarkShareParsing.expiredTypeOf(1))
        assertEquals(3, QuarkShareParsing.expiredTypeOf(3))
        assertEquals(3, QuarkShareParsing.expiredTypeOf(7))
        assertEquals(4, QuarkShareParsing.expiredTypeOf(30))
        assertEquals(4, QuarkShareParsing.expiredTypeOf(365))
    }

    @Test
    fun daysOfExpiredTypeMirrorsTheBuckets() {
        assertNull(QuarkShareParsing.daysOfExpiredType(1))
        assertEquals(1, QuarkShareParsing.daysOfExpiredType(2))
        assertEquals(7, QuarkShareParsing.daysOfExpiredType(3))
        assertEquals(30, QuarkShareParsing.daysOfExpiredType(4))
        assertNull(QuarkShareParsing.daysOfExpiredType(99))
    }

    @Test
    fun normalizeUrlHandlesFullPathAndBareId() {
        assertEquals(
            "https://pan.quark.cn/s/abc123",
            QuarkShareParsing.normalizeUrl("https://pan.quark.cn/s/abc123"),
        )
        assertEquals(
            "https://pan.quark.cn/s/abc123",
            QuarkShareParsing.normalizeUrl("/s/abc123"),
        )
        assertEquals(
            "https://pan.quark.cn/s/abc123",
            QuarkShareParsing.normalizeUrl("abc123"),
        )
        assertNull(QuarkShareParsing.normalizeUrl(null))
        assertNull(QuarkShareParsing.normalizeUrl("   "))
    }

    @Test
    fun passcodeIsAppendedWithTheRightSeparator() {
        assertEquals(
            "https://pan.quark.cn/s/abc?pwd=1234",
            QuarkShareParsing.withPasscode("https://pan.quark.cn/s/abc", "1234"),
        )
        assertEquals(
            "https://pan.quark.cn/s/abc?from=app&pwd=1234",
            QuarkShareParsing.withPasscode("https://pan.quark.cn/s/abc?from=app", "1234"),
        )
        // 没有提取码时不能拼出一个空 pwd= 让用户手动删。
        assertEquals(
            "https://pan.quark.cn/s/abc",
            QuarkShareParsing.withPasscode("https://pan.quark.cn/s/abc", null),
        )
        assertEquals(
            "https://pan.quark.cn/s/abc",
            QuarkShareParsing.withPasscode("https://pan.quark.cn/s/abc", "  "),
        )
    }

    @Test
    fun permanentSentinelIsTreatedAsNoExpiry() {
        assertNull(QuarkShareParsing.expiresAtOf(null))
        assertNull(QuarkShareParsing.expiresAtOf(0L))
        assertNull(QuarkShareParsing.expiresAtOf(-1L))
        // 服务端的「永久」哨兵是 2100 年，本地要当成没有到期时间。
        assertNull(QuarkShareParsing.expiresAtOf(QuarkShareParsing.PERMANENT_EXPIRED_AT))
        assertEquals(1_700_000_000_000L, QuarkShareParsing.expiresAtOf(1_700_000_000_000L))
    }

    @Test
    fun listItemWithoutShareIdIsDropped() {
        assertNull(
            QuarkShareParsing.toShareLink(
                QuarkShareItem(shareId = null, shareUrl = "https://pan.quark.cn/s/a"),
            ),
        )
        assertNull(
            QuarkShareParsing.toShareLink(
                QuarkShareItem(shareId = "  ", shareUrl = "https://pan.quark.cn/s/a"),
            ),
        )
    }

    @Test
    fun listItemCarriesPasscodeIntoUrl() {
        val link = QuarkShareParsing.toShareLink(
            QuarkShareItem(
                shareId = "abc",
                shareUrl = "https://pan.quark.cn/s/abc",
                passcode = "1234",
                title = "资料",
                createdAt = 1_700_000_000_000L,
                expiredAt = 1_700_100_000_000L,
            ),
        )
        assertTrue(link != null)
        assertEquals("abc", link!!.id.value)
        assertEquals("https://pan.quark.cn/s/abc?pwd=1234", link.url)
        assertEquals("1234", link.password)
        assertEquals("资料", link.title)
        assertEquals(1_700_000_000_000L, link.createdAt)
        assertEquals(1_700_100_000_000L, link.expiresAt)
    }

    @Test
    fun createdShareKeepsNodeIds() {
        val link = QuarkShareParsing.toShareLink(
            QuarkSharePasswordData(
                shareId = "abc",
                shareUrl = "https://pan.quark.cn/s/abc",
                passcode = null,
                title = "t",
                expiredAt = 0L,
            ),
            nodeIds = listOf(NodeId("fid-1")),
        )
        assertTrue(link != null)
        assertEquals(listOf(NodeId("fid-1")), link!!.nodeIds)
        assertNull(link.password)
        assertNull(link.expiresAt)
    }

    @Test
    fun createdShareWithoutShareIdFails() {
        assertNull(
            QuarkShareParsing.toShareLink(
                QuarkSharePasswordData(shareId = null, shareUrl = "https://pan.quark.cn/s/a"),
                nodeIds = emptyList(),
            ),
        )
    }
}
