package com.panhub.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/**
 * 扫描器的行为必须与 Rust 内核逐字节一致，否则「有内核」和「没内核」两种
 * 构建算出来的摘要会不一样，秒传判断会时灵时不灵。
 */
class KotlinUploadScannerTest {

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun scan(bytes: ByteArray, sliceSize: Long, prefixLimit: Long) =
        KotlinUploadScanner.scan(ByteArrayInputStream(bytes), sliceSize, prefixLimit)

    @Test
    fun abcMatchesPublishedVectors() {
        val result = scan("abc".toByteArray(), 0L, 0L)
        // MD5("abc") 与 SHA-1("abc") 都是公开测试向量。
        assertEquals("900150983cd24fb0d6963f7d28e17f72", result.md5)
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", result.sha1)
        assertEquals(3L, result.size)
    }

    @Test
    fun emptyInputHasWellKnownDigests() {
        val result = scan(ByteArray(0), 0L, 0L)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", result.md5)
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", result.sha1)
        assertEquals(0L, result.size)
        assertTrue(result.sliceMd5s.isEmpty())
    }

    @Test
    fun slicesSplitOnExactBoundaries() {
        val data = "abcdefghij".toByteArray()
        val result = scan(data, 4L, 0L)
        assertEquals(
            listOf(
                md5("abcd".toByteArray()),
                md5("efgh".toByteArray()),
                md5("ij".toByteArray()),
            ),
            result.sliceMd5s,
        )
    }

    @Test
    fun exactMultipleOfSliceSizeHasNoTrailingSlice() {
        val result = scan(ByteArray(8) { it.toByte() }, 4L, 0L)
        assertEquals(2, result.sliceMd5s.size)
    }

    @Test
    fun prefixLimitOnlyCoversFirstBytes() {
        val data = ByteArray(1000) { (it % 251).toByte() }
        val result = scan(data, 0L, 100L)
        assertEquals(md5(data.copyOfRange(0, 100)), result.prefixMd5)
    }

    @Test
    fun prefixLongerThanFileCoversWholeFile() {
        val data = ByteArray(10) { it.toByte() }
        val result = scan(data, 0L, 4096L)
        assertEquals(md5(data), result.prefixMd5)
    }

    @Test
    fun zeroPrefixLimitLeavesPrefixEmpty() {
        val result = scan(ByteArray(10) { it.toByte() }, 0L, 0L)
        assertEquals("", result.prefixMd5)
    }

    @Test
    fun slicesCrossBufferBoundaries() {
        // 分片比内部缓冲区小得多，一次 read 会跨好几个分片。
        val data = ByteArray(300_000) { (it % 97).toByte() }
        val result = scan(data, 70_000L, 0L)
        assertEquals(5, result.sliceMd5s.size)
        assertEquals(md5(data.copyOfRange(0, 70_000)), result.sliceMd5s[0])
        assertEquals(md5(data.copyOfRange(280_000, 300_000)), result.sliceMd5s[4])
        assertEquals(md5(data), result.md5)
    }

    @Test
    fun fileOverloadMatchesStreamOverload() {
        val file = kotlin.io.path.createTempFile("scan").toFile()
        try {
            val data = ByteArray(200_000) { (it % 13).toByte() }
            file.writeBytes(data)
            val fromFile = KotlinUploadScanner.scan(file, 64L * 1024, 1024L)
            val fromStream = scan(data, 64L * 1024, 1024L)
            assertEquals(fromStream, fromFile)
        } finally {
            file.delete()
        }
    }

    @Test
    fun differentContentProducesDifferentDigest() {
        assertNotEquals(scan("abc".toByteArray(), 0L, 0L).md5, scan("abd".toByteArray(), 0L, 0L).md5)
    }

    @Test
    fun hexToBytesRoundTripsKnownDigest() {
        val bytes = KotlinUploadScanner.hexToBytes("900150983cd24fb0d6963f7d28e17f72")
        assertEquals(16, bytes.size)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", bytes.joinToString("") { "%02x".format(it) })
    }
}
