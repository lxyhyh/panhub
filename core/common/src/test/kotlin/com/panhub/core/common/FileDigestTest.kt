package com.panhub.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileDigestTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun emptyBytesHasWellKnownMd5() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", FileDigest.md5(ByteArray(0)))
    }

    @Test
    fun abcBytesMatchPublishedVector() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", FileDigest.md5("abc".toByteArray()))
    }

    @Test
    fun md5IsLowerCaseHexWithoutDashes() {
        val hex = FileDigest.md5("hello".toByteArray())
        assertEquals(32, hex.length)
        assertEquals(hex.lowercase(), hex)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun fileAndBytesAgree() {
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        val file = folder.newFile("payload.bin").apply { writeBytes(bytes) }
        assertEquals(FileDigest.md5(bytes), FileDigest.md5(file))
    }

    @Test
    fun progressReportsGrowingByteCount() {
        val bytes = ByteArray(700_000) { (it % 97).toByte() }
        val file = folder.newFile("big.bin").apply { writeBytes(bytes) }
        val reported = mutableListOf<Long>()
        FileDigest.md5(file) { reported.add(it) }
        assertTrue("至少要回调一次", reported.isNotEmpty())
        assertEquals(bytes.size.toLong(), reported.last())
        assertEquals(reported.sorted(), reported)
    }

    @Test
    fun emptyFileDoesNotCallProgress() {
        val file: File = folder.newFile("empty.bin")
        val reported = mutableListOf<Long>()
        FileDigest.md5(file) { reported.add(it) }
        assertTrue(reported.isEmpty())
    }
}
