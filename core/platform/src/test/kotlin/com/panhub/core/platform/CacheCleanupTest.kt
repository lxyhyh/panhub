package com.panhub.core.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 缓存清理的文件系统部分。
 *
 * 这个类会真的删文件，所以每条规则都要钉住：只删目录里的文件、
 * 目录本身留着（下次上传还要往里写）、读不了的目录跳过而不是崩。
 */
class CacheCleanupTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(dir: File, name: String, bytes: Int): File {
        dir.mkdirs()
        return File(dir, name).apply { writeBytes(ByteArray(bytes)) }
    }

    @Test
    fun aMissingDirectoryWeighsNothing() {
        assertEquals(0L, CacheCleanup.sizeOf(File(temp.root, "nope")))
    }

    @Test
    fun sizeAddsUpEveryFileIncludingSubfolders() {
        val dir = temp.newFolder("upload")
        file(dir, "a.bin", 100)
        file(File(dir, "sub"), "b.bin", 50)

        assertEquals(150L, CacheCleanup.sizeOf(dir))
    }

    @Test
    fun clearingRemovesFilesButKeepsTheDirectory() {
        val dir = temp.newFolder("upload")
        file(dir, "a.bin", 10)

        assertEquals(1, CacheCleanup.clear(dir))

        // 目录本身要留着：下次上传还要往里写，而且 mkdirs 失败会让上传莫名报错。
        assertTrue(dir.isDirectory)
        assertEquals(0L, CacheCleanup.sizeOf(dir))
    }

    @Test
    fun clearingRemovesNestedFilesToo() {
        val dir = temp.newFolder("upload")
        val sub = File(dir, "outer/inner");
        file(sub, "deep.bin", 10)

        assertEquals(1, CacheCleanup.clear(dir))
        assertFalse(File(sub, "deep.bin").exists())
    }

    @Test
    fun clearingAnEmptyDirectoryRemovesNothing() {
        val dir = temp.newFolder("upload")

        assertEquals(0, CacheCleanup.clear(dir))
    }

    @Test
    fun aMissingDirectoryIsNotAnError() {
        assertEquals(0, CacheCleanup.clear(File(temp.root, "nope")))
    }

    @Test
    fun usageSumsBothDirectories() {
        val upload = temp.newFolder("upload")
        val scratch = temp.newFolder("scratch")
        file(upload, "a.bin", 100)
        file(scratch, "b.bin", 200)

        val cleaner = LocalCacheCleaner(upload, scratch, Dispatchers.Unconfined)
        val usage = runBlocking { cleaner.usage() }

        assertEquals(100L, usage.uploadBytes)
        assertEquals(200L, usage.scratchBytes)
        assertEquals(300L, usage.totalBytes)
        assertFalse(usage.isEmpty)
    }

    @Test
    fun clearingReportsTheTotalNumberOfFiles() {
        val upload = temp.newFolder("upload")
        val scratch = temp.newFolder("scratch")
        file(upload, "a.bin", 1)
        file(upload, "b.bin", 1)
        file(scratch, "c.bin", 1)

        val cleaner = LocalCacheCleaner(upload, scratch, Dispatchers.Unconfined)

        assertEquals(3, runBlocking { cleaner.clear() })
        assertEquals(CacheUsage(), runBlocking { cleaner.usage() })
    }
}