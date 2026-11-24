package com.panhub.core.transfer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalDownloadsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun root(): File = temp.newFolder(LocalDownloads.ROOT_DIR_NAME)

    private fun file(dir: File, name: String, bytes: Int): File {
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(bytes))
        return f
    }

    @Test
    fun missingRootScansAsEmpty() {
        // 还没下过任何东西时目录根本不存在，这不该是错误。
        val snapshot = LocalDownloads(File(temp.root, "nope")).scan()
        assertTrue(snapshot.isEmpty)
        assertEquals(0L, snapshot.totalBytes)
    }

    @Test
    fun flatFilesAreListedWithTheirSizes() {
        val root = root()
        file(root, "a.txt", 10)
        file(root, "b.bin", 32)
        val snapshot = LocalDownloads(root).scan()
        assertEquals(2, snapshot.entries.size)
        assertEquals(42L, snapshot.totalBytes)
        assertEquals(setOf("a.txt", "b.bin"), snapshot.entries.map { it.relativePath }.toSet())
    }

    @Test
    fun foldersAreNotEntriesTheFilesInsideAre() {
        val root = root()
        file(File(root, "photos"), "a.jpg", 5)
        val snapshot = LocalDownloads(root).scan()
        assertEquals(1, snapshot.entries.size)
        assertEquals("photos/a.jpg", snapshot.entries[0].relativePath)
        assertEquals("photos", snapshot.entries[0].folder)
    }

    @Test
    fun rootLevelFileHasEmptyFolder() {
        val root = root()
        file(root, "top.txt", 1)
        assertEquals("", LocalDownloads(root).scan().entries[0].folder)
    }

    @Test
    fun newestFileComesFirst() {
        val root = root()
        val old = file(root, "old.txt", 1)
        val recent = file(root, "new.txt", 1)
        old.setLastModified(1_000L)
        recent.setLastModified(9_000L)
        val entries = LocalDownloads(root).scan().entries
        assertEquals("new.txt", entries[0].name)
        assertEquals("old.txt", entries[1].name)
    }

    @Test
    fun deleteRemovesTheFile() {
        val root = root()
        val f = file(File(root, "sub"), "a.bin", 4)
        val downloads = LocalDownloads(root)
        assertTrue(downloads.delete("sub/a.bin"))
        assertFalse(f.exists())
    }

    @Test
    fun deletingAMissingFileCountsAsDone() {
        // 目的已经达到：这份文件不再占地方了。
        val downloads = LocalDownloads(root())
        assertTrue(downloads.delete("gone.bin"))
    }

    @Test
    fun escapingTheRootIsRefused() {
        val outside = temp.newFile("outside.txt")
        outside.writeBytes(ByteArray(3))
        val downloads = LocalDownloads(root())
        assertFalse(downloads.contains("../outside.txt"))
        assertFalse(downloads.delete("../outside.txt"))
        assertTrue(outside.exists())
    }

    @Test
    fun absolutePathIsNeverARelativeEntry() {
        // File(parent, "/a/b") 在 Unix 上会把斜杠当分隔符拼进 parent，
        // 结果看着仍「在根目录内」，所以绝对路径要单独拦一道。
        val outside = temp.newFile("abs.txt")
        outside.writeBytes(ByteArray(3))
        val downloads = LocalDownloads(root())
        assertFalse(downloads.contains(outside.absolutePath))
        assertFalse(downloads.delete(outside.absolutePath))
        assertTrue(outside.exists())
    }

    @Test
    fun blankPathIsRefused() {
        val downloads = LocalDownloads(root())
        assertFalse(downloads.contains("   "))
        assertFalse(downloads.delete(""))
    }

    @Test
    fun deletingAFolderIsRefused() {
        // 目录必须靠里面的文件一个个删，留空目录在这里没有意义，
        // 但也不能把整棵目录当成一个文件删掉。
        val root = root()
        file(File(root, "dir"), "a.bin", 1)
        assertFalse(LocalDownloads(root).delete("dir"))
        assertTrue(File(root, "dir").isDirectory)
    }

    @Test
    fun entriesAreOrderedStableWhenTimestampsTie() {
        val root = root()
        val a = file(root, "b.txt", 1)
        val b = file(root, "a.txt", 1)
        a.setLastModified(5_000L)
        b.setLastModified(5_000L)
        val names = LocalDownloads(root).scan().entries.map { it.name }
        assertEquals(listOf("a.txt", "b.txt"), names)
    }
}

