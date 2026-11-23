package com.panhub.provider.baidu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class BaiduUploadDigestTest {

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).toLowerHex()

    private fun compute(data: ByteArray, sliceSize: Long = BaiduUploadDigest.SLICE_SIZE) =
        BaiduUploadDigest.compute(ByteArrayInputStream(data), data.size.toLong(), sliceSize)

    @Test
    fun smallFileHasSingleBlock() {
        val data = "abc".toByteArray()
        val digests = compute(data)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", digests.contentMd5)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", digests.sliceMd5)
        assertEquals(listOf("900150983cd24fb0d6963f7d28e17f72"), digests.blockList)
    }

    @Test
    fun sliceMd5OnlyCoversFirst256Kib() {
        // 文件比 256 KiB 大，sliceMd5 必须只取开头那一段，
        // 而不是整文件的 MD5，也不是所有分片 MD5 的再哈希。
        val data = ByteArray(300 * 1024) { (it % 251).toByte() }
        val digests = compute(data)
        assertEquals(md5(data.copyOf(256 * 1024)), digests.sliceMd5)
        assertEquals(md5(data), digests.contentMd5)
    }

    @Test
    fun blocksSplitBySliceSize() {
        val slice = 1024L
        val data = ByteArray(2500) { (it % 97).toByte() }
        val digests = BaiduUploadDigest.compute(ByteArrayInputStream(data), 2500L, slice)
        assertEquals(3, digests.blockList.size)
        assertEquals(md5(data.copyOfRange(0, 1024)), digests.blockList[0])
        assertEquals(md5(data.copyOfRange(1024, 2048)), digests.blockList[1])
        assertEquals(md5(data.copyOfRange(2048, 2500)), digests.blockList[2])
    }

    @Test
    fun exactMultipleOfSliceSizeHasNoEmptyTailBlock() {
        // 2000 正好是 1000 的两倍：不能多切出一个空片。
        val data = ByteArray(2000) { (it % 53).toByte() }
        val digests = BaiduUploadDigest.compute(ByteArrayInputStream(data), 2000L, 1000L)
        assertEquals(2, digests.blockList.size)
        assertEquals(md5(data.copyOfRange(1000, 2000)), digests.blockList[1])
    }

    @Test
    fun sliceCountMatchesBlocks() {
        assertEquals(0, BaiduUploadDigest.sliceCount(0))
        assertEquals(1, BaiduUploadDigest.sliceCount(1))
        assertEquals(1, BaiduUploadDigest.sliceCount(BaiduUploadDigest.SLICE_SIZE))
        assertEquals(2, BaiduUploadDigest.sliceCount(BaiduUploadDigest.SLICE_SIZE + 1))
        assertEquals(3, BaiduUploadDigest.sliceCount(BaiduUploadDigest.SLICE_SIZE * 3))
    }

    @Test
    fun emptyInputReadsNothingButIsRejected() {
        // 百度不接受空文件，摘要层直接拒绝，避免上游白跑一遍上传流程。
        val failed = runCatching { compute(ByteArray(0)) }.isFailure
        assertEquals(true, failed)
    }

    @Test
    fun shortReadsArePaddedAcrossBufferBoundaries() {
        // 用比分片还小的 buffer 也要算出一样的 block：说明循环按剩余字节续读。
        val data = ByteArray(200 * 1024) { (it % 31).toByte() }
        val digests = BaiduUploadDigest.compute(ByteArrayInputStream(data), data.size.toLong(), 64 * 1024)
        assertEquals(4, digests.blockList.size)
        assertEquals(md5(data.copyOfRange(0, 64 * 1024)), digests.blockList[0])
        assertEquals(md5(data.copyOfRange(192 * 1024, 200 * 1024)), digests.blockList[3])
    }
}

class BaiduUploadHostTest {

    private val uploader = BaiduUploader(
        engine = com.panhub.core.network.HttpEngine(),
        api = BaiduApi(com.panhub.core.network.HttpEngine()),
    )

    @Test
    fun bareHostGetsHttpsScheme() {
        assertEquals("https://c.pcs.baidu.com", uploader.normalizeHost("c.pcs.baidu.com"))
    }

    @Test
    fun existingSchemeIsKept() {
        assertEquals("https://d.pcs.baidu.com", uploader.normalizeHost("https://d.pcs.baidu.com/"))
        assertEquals("http://d.pcs.baidu.com", uploader.normalizeHost("http://d.pcs.baidu.com"))
    }

    @Test
    fun blankHostIsNull() {
        assertNull(uploader.normalizeHost(null))
        assertNull(uploader.normalizeHost("   "))
    }
}
