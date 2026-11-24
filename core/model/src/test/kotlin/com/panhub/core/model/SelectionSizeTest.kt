package com.panhub.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionSizeTest {

    private fun file(id: String, size: Long) =
        RemoteNode(NodeId(id), null, id + ".bin", NodeKind.FILE, size = size)

    private fun dir(id: String) =
        RemoteNode(NodeId(id), null, id, NodeKind.DIR)

    @Test
    fun nothingSelectedCountsAsNothing() {
        val size = SelectionSizes.of(emptyList())
        assertEquals(0, size.items)
        assertTrue(size.isEmpty)
        assertFalse(size.hasBytes)
    }

    @Test
    fun fileSizesAreAddedUp() {
        val size = SelectionSizes.of(listOf(file("a", 1024L), file("b", 2048L)))
        assertEquals(2, size.items)
        assertEquals(2, size.files)
        assertEquals(0, size.folders)
        assertEquals(3072L, size.bytes)
        assertEquals(0, size.unknownFiles)
    }

    @Test
    fun foldersAreCountedButDoNotContributeBytes() {
        val size = SelectionSizes.of(listOf(dir("photos"), dir("docs"), file("a", 512L)))
        assertEquals(3, size.items)
        assertEquals(1, size.files)
        assertEquals(2, size.folders)
        assertEquals(512L, size.bytes)
    }

    @Test
    fun aFileWithoutASizeIsCountedSeparately() {
        val size = SelectionSizes.of(listOf(file("a", 0L), file("b", 100L)))
        assertEquals(2, size.files)
        assertEquals(1, size.unknownFiles)
        assertEquals(100L, size.bytes)
    }

    @Test
    fun foldersOnlyHaveNoBytesToReport() {
        val size = SelectionSizes.of(listOf(dir("photos")))
        assertFalse(size.hasBytes)
        assertNull(SelectionSizes.line(size) { it.toString() })
    }

    @Test
    fun aPlainSelectionReadsAsJustTheTotal() {
        val size = SelectionSizes.of(listOf(file("a", 1024L), file("b", 1024L)))
        // 没有文件夹也没有缺大小的时候，只写总数一句，不加任何后缀说明。
        assertEquals("2048", SelectionSizes.line(size) { it.toString() })
        assertEquals("2 KB", SelectionSizes.line(size) { (it / 1024).toString() + " KB" })
    }

    @Test
    fun foldersAreMentionedNextToTheTotal() {
        val size = SelectionSizes.of(listOf(file("a", 1024L), dir("photos")))
        assertEquals("1 KB · 含 1 个文件夹", SelectionSizes.line(size) { "1 KB" })
    }

    @Test
    fun filesWithoutASizeAreMentionedToo() {
        val size = SelectionSizes.of(listOf(file("a", 1024L), file("b", 0L), dir("photos")))
        assertEquals(
            "1 KB · 1 个文件未报大小 · 含 1 个文件夹",
            SelectionSizes.line(size) { "1 KB" },
        )
    }

    @Test
    fun aSelectionOfNothingButUnknownSizesStaysSilent() {
        val size = SelectionSizes.of(listOf(file("a", 0L), file("b", 0L)))
        assertFalse(size.hasBytes)
        assertNull(SelectionSizes.line(size) { "0 B" })
    }
}
