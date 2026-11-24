package com.panhub.core.transfer

import com.panhub.core.model.SortOrder
import com.panhub.core.model.SortSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSortTest {

    private fun entry(name: String, size: Long = 0L, time: Long = 0L, folder: String = "") =
        DownloadedEntry(
            relativePath = if (folder.isEmpty()) name else folder + "/" + name,
            name = name,
            size = size,
            modifiedAt = time,
        )

    private fun names(entries: List<DownloadedEntry>) = entries.map { it.name }

    @Test
    fun `name order ignores case`() {
        val entries = listOf(entry("Beta"), entry("alpha"), entry("Gamma"))
        assertEquals(listOf("alpha", "Beta", "Gamma"), names(entries.sortedFor(SortSpec(SortOrder.NAME))))
        assertEquals(
            listOf("Gamma", "Beta", "alpha"),
            names(entries.sortedFor(SortSpec(SortOrder.NAME, descending = true))),
        )
    }

    @Test
    fun `time order keeps records without a timestamp at the end`() {
        // lastModified() 读不到时是 0；把它当 1970 年混进真实时间里，列表头会是一堆假的老文件。
        val entries = listOf(entry("old", time = 100L), entry("unknown", time = 0L), entry("new", time = 300L))
        assertEquals(
            listOf("new", "old", "unknown"),
            names(entries.sortedFor(SortSpec(SortOrder.TIME, descending = true))),
        )
        assertEquals(
            listOf("old", "new", "unknown"),
            names(entries.sortedFor(SortSpec(SortOrder.TIME, descending = false))),
        )
    }

    @Test
    fun `size order sorts by bytes`() {
        val entries = listOf(entry("mid", size = 500), entry("small", size = 1), entry("big", size = 9000))
        assertEquals(
            listOf("big", "mid", "small"),
            names(entries.sortedFor(SortSpec(SortOrder.SIZE, descending = true))),
        )
        assertEquals(
            listOf("small", "mid", "big"),
            names(entries.sortedFor(SortSpec(SortOrder.SIZE, descending = false))),
        )
    }

    @Test
    fun `equal keys fall back on the relative path`() {
        // 同名文件在两个子目录里很常见（整目录下载时尤其）。
        val entries = listOf(
            entry("a.txt", size = 10, folder = "photos"),
            entry("a.txt", size = 10, folder = "docs"),
        )
        assertEquals(
            listOf("docs/a.txt", "photos/a.txt"),
            entries.sortedFor(SortSpec(SortOrder.SIZE)).map { it.relativePath },
        )
    }

    @Test
    fun `sorting is stable when nothing distinguishes two records`() {
        val entries = listOf(entry("b", size = 1), entry("a", size = 1))
        assertEquals(
            listOf("a", "b"),
            names(entries.sortedFor(SortSpec(SortOrder.SIZE))),
        )
    }
}
