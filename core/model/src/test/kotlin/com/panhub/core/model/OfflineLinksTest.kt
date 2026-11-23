package com.panhub.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineLinksTest {

    @Test
    fun httpLinkIsAcceptedAsIs() {
        val parsed = OfflineLinks.parse("https://example.com/file.zip")
        assertEquals(OfflineKind.LINK, parsed?.kind)
        assertEquals("https://example.com/file.zip", parsed?.value)
    }

    @Test
    fun surroundingWhitespaceIsDropped() {
        val parsed = OfflineLinks.parse("  https://example.com/a.mkv  ")
        assertEquals("https://example.com/a.mkv", parsed?.value)
    }

    @Test
    fun magnetKeepsOnlyTheInfoHash() {
        // 磁力链接里的 trackers 对服务端没用，只传 hash。
        val parsed = OfflineLinks.parse(
            "magnet:?xt=urn:btih:C9E15763F722F23E98A29DECDFAE341B98D53056&dn=ubuntu.iso&tr=udp%3A%2F%2Ftracker",
        )
        assertEquals(OfflineKind.BT, parsed?.kind)
        assertEquals("c9e15763f722f23e98a29decdfae341b98d53056", parsed?.value)
    }

    @Test
    fun infoHashCaseIsNormalised() {
        // 大写十六进制与小写指的是同一个资源，统一成小写省得服务端当成两个。
        val upper = OfflineLinks.parse("magnet:?xt=urn:btih:ABCDEF0123456789ABCDEF0123456789ABCDEF01")
        val lower = OfflineLinks.parse("magnet:?xt=urn:btih:abcdef0123456789abcdef0123456789abcdef01")
        assertEquals(lower?.value, upper?.value)
    }

    @Test
    fun magnetWithoutInfoHashIsRejected() {
        assertNull(OfflineLinks.parse("magnet:?dn=ubuntu.iso"))
    }

    @Test
    fun magnetWithShortHashIsRejected() {
        // 截断的 hash 发过去只会换回一个看不懂的服务端错误，本地先拦掉。
        assertNull(OfflineLinks.parse("magnet:?xt=urn:btih:abc123"))
    }

    @Test
    fun emptyQueryMagnetIsRejected() {
        assertNull(OfflineLinks.parse("magnet:?"))
    }

    @Test
    fun plainTextIsNotAnOfflineLink() {
        assertNull(OfflineLinks.parse("把这部电影下载下来"))
    }

    @Test
    fun ftpIsNotAccepted() {
        // 只有 http/https 能交给服务端；别的协议它也不认。
        assertNull(OfflineLinks.parse("ftp://example.com/a.bin"))
    }

    @Test
    fun blankInputHasNoLink() {
        assertNull(OfflineLinks.parse(null))
        assertNull(OfflineLinks.parse("   "))
    }

    @Test
    fun resourceDefaultsToEverything() {
        val files = listOf(OfflineFile("1", "a.mkv", 100L), OfflineFile("2", "b.srt", 5L))
        val resource = OfflineResource(id = "9", name = "影片", kind = OfflineKind.LINK, files = files)
        assertEquals(setOf("1", "2"), resource.defaultSelection)
        assertEquals(105L, resource.totalBytes)
        assertFalse(resource.isEmpty)
    }

    @Test
    fun emptyResourceIsMarkedEmpty() {
        val resource = OfflineResource(id = "9", name = "空", kind = OfflineKind.BT)
        assertTrue(resource.isEmpty)
        assertEquals(0L, resource.totalBytes)
    }

    @Test
    fun folderSizesAreNotCountedAsBytes() {
        // 目录条目的大小没有意义，算进总量只会让「还剩多少」看起来不对。
        val files = listOf(
            OfflineFile("1", "dir", 999L, isDir = true),
            OfflineFile("2", "a.mkv", 100L),
        )
        val resource = OfflineResource(id = "9", name = "影片", kind = OfflineKind.LINK, files = files)
        assertEquals(100L, resource.totalBytes)
    }
}
