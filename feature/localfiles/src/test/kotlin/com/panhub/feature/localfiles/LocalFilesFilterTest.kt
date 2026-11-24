package com.panhub.feature.localfiles

import com.panhub.core.transfer.DownloadedEntry
import com.panhub.core.transfer.DownloadsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本机文件页的筛选。
 *
 * 这一页的筛选规则必须与网盘浏览页一致（忽略大小写、空词全留、不改顺序），
 * 但被筛的对象是磁盘上的下载记录而不是 [com.panhub.core.model.RemoteNode]，
 * 所以接线本身值得单独钉一遍：接线接错时，界面看起来「什么都没发生」。
 */
class LocalFilesFilterTest {

    private fun entry(name: String, folder: String = "", size: Long = 1024L): DownloadedEntry {
        val path = if (folder.isEmpty()) name else folder + "/" + name
        return DownloadedEntry(relativePath = path, name = name, size = size, modifiedAt = 0L)
    }

    private fun state(
        vararg names: String,
        filter: String = "",
    ): LocalFilesUiState {
        val entries = names.map { entry(it) }
        return LocalFilesUiState(
            loading = false,
            snapshot = DownloadsSnapshot(entries = entries, totalBytes = entries.size * 1024L),
            filter = filter,
        )
    }

    @Test
    fun withoutAKeywordEveryRowIsVisible() {
        val s = state("a.txt", "b.txt")
        assertEquals(listOf("a.txt", "b.txt"), s.visibleRows.map { it.entry.name })
        assertFalse(s.filtering)
    }

    @Test
    fun aKeywordNarrowsTheRows() {
        val s = state("报告.pdf", "照片.jpg", "摘要.md", filter = "报")
        assertEquals(listOf("报告.pdf"), s.visibleRows.map { it.entry.name })
        assertTrue(s.filtering)
    }

    @Test
    fun matchingIgnoresCase() {
        val s = state("README.md", "notes.txt", filter = "readme")
        assertEquals(listOf("README.md"), s.visibleRows.map { it.entry.name })
    }

    @Test
    fun nothingMatchingIsNotTheSameAsNoFilesAtAll() {
        // 磁盘上明明有三个文件，只是名字都不匹配：空状态那句话不能是「本机没有文件」，
        // 否则用户会以为文件被清掉了。
        val s = state("a.txt", "b.txt", "c.txt", filter = "zzz")
        assertTrue(s.visibleRows.isEmpty())
        assertFalse(s.empty)
        assertTrue(s.filteredOut)
    }

    @Test
    fun anActuallyEmptyDiskIsNotAFilteringProblem() {
        val s = LocalFilesUiState(loading = false, snapshot = DownloadsSnapshot(), filter = "abc")
        assertTrue(s.empty)
        assertFalse(s.filteredOut)
    }

    @Test
    fun summaryOnlyShowsUpWhenSomethingWasFilteredOut() {
        assertNull(state("a.txt", "b.txt").filterSummary)
        assertEquals("筛选中 1/3 项", state("a1.txt", "a2.txt", "b.txt", filter = "a1").filterSummary)
        // 全都命中时不给「3/3」这种噪音。
        assertNull(state("a1.txt", "a2.txt", filter = "a").filterSummary)
    }

    @Test
    fun theTopSummaryStillCountsEveryFileOnDisk() {
        // 顶部那行讲的是磁盘占用，不该跟着筛选变：筛一半显示一半的容量会让人以为少下了东西。
        val s = state("a.txt", "b.txt", filter = "a.txt")
        assertEquals("2 个文件 · 2.0 KB", s.summary)
    }

    @Test
    fun clearingTheKeywordBringsEverythingBack() {
        val s = state("a.txt", "b.txt", filter = "a.txt")
        assertEquals(1, s.visibleRows.size)
        assertEquals(2, s.copy(filter = "").visibleRows.size)
    }
}
