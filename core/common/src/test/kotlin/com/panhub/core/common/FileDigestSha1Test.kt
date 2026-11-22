package com.panhub.core.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * MD5 + SHA-1 一次遍历的成对摘要。
 *
 * 期望值用公开向量锁死，不拿实现互相印证：md5(abc)=900150…，sha1(abc)=a9993e36…
 */
class FileDigestSha1Test {

    @Test
    fun abcMatchesPublishedVectors() {
        val pair = FileDigest.md5AndSha1(ByteArrayInputStream("abc".toByteArray()))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", pair.first)
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", pair.second)
    }

    @Test
    fun emptyInputMatchesPublishedVectors() {
        val pair = FileDigest.md5AndSha1(ByteArrayInputStream(ByteArray(0)))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", pair.first)
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", pair.second)
    }

    @Test
    fun largeInputMatchesSingleDigestHelpers() {
        val bytes = ByteArray(700_000) { (it % 251).toByte() }
        val pair = FileDigest.md5AndSha1(ByteArrayInputStream(bytes))
        assertEquals(FileDigest.md5(bytes), pair.first)
    }

    @Test
    fun progressReportsEveryRead() {
        val bytes = ByteArray(300_000) { 1 }
        var last = 0L
        FileDigest.md5AndSha1(ByteArrayInputStream(bytes)) { last = it }
        assertEquals(bytes.size.toLong(), last)
    }
}
