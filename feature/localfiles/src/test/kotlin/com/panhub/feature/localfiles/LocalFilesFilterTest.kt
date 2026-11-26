package com.panhub.feature.localfiles

import com.panhub.core.model.FileKind
import com.panhub.core.model.SortOrder
import com.panhub.core.model.SortSpec
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
        kind: FileKind? = null,
    ): LocalFilesUiState {
        val entries = names.map { entry(it) }
        return LocalFilesUiState(
            loading = false,
            snapshot = DownloadsSnapshot(entries = entries, totalBytes = entries.size * 1024L),
            filter = filter,
            kind = kind,
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
    fun sortingPutsTheNewestFirstByDefault() {
        // 与本机文件一直以来的顺序一致：刚下完的在最上面。
        val older = DownloadedEntry(relativePath = "older.txt", name = "older.txt", size = 1, modifiedAt = 100L)
        val newer = DownloadedEntry(relativePath = "newer.txt", name = "newer.txt", size = 1, modifiedAt = 300L)
        val s = LocalFilesUiState(
            loading = false,
            snapshot = DownloadsSnapshot(entries = listOf(older, newer), totalBytes = 2),
        )
        assertEquals(listOf("newer.txt", "older.txt"), s.visibleRows.map { it.entry.name })
    }

    @Test
    fun sortingThenFilteringUsesTheChosenOrder() {
        val entries = listOf(
            entry("报告-小.pdf", size = 10),
            entry("报告-大.pdf", size = 900),
            entry("别的.txt", size = 500),
        )
        val s = LocalFilesUiState(
            loading = false,
            snapshot = DownloadsSnapshot(entries = entries, totalBytes = 1410),
            filter = "报告",
            sort = SortSpec(SortOrder.SIZE, descending = true),
        )
        assertEquals(listOf("报告-大.pdf", "报告-小.pdf"), s.visibleRows.map { it.entry.name })
    }

    @Test
    fun clearingTheKeywordBringsEverythingBack() {
        val s = state("a.txt", "b.txt", filter = "a.txt")
        assertEquals(1, s.visibleRows.size)
        assertEquals(2, s.copy(filter = "").visibleRows.size)
    }

    // ---- 按类型筛 ----

    @Test
    fun kindNarrowsToTheChosenCategory() {
        val s = state("a.jpg", "b.txt", "c.png", kind = FileKind.IMAGE)
        assertEquals(listOf("a.jpg", "c.png"), s.visibleRows.map { it.entry.name })
        assertTrue(s.filtering)
    }

    @Test
    fun noKindKeepsEverything() {
        val s = state("a.jpg", "b.txt")
        assertEquals(2, s.visibleRows.size)
        assertFalse(s.filtering)
    }

    @Test
    fun choosingAKindThatMatchesNothingStillCountsAsFiltering() {
        // 磁盘上有文件、只是没有这一类：不能落进「本机没有文件」。
        val s = state("a.txt", "b.md", kind = FileKind.VIDEO)
        assertTrue(s.visibleRows.isEmpty())
        assertFalse(s.empty)
        assertTrue(s.filteredOut)
        assertEquals("这里没有视频", s.emptyFilterMessage)
    }

    @Test
    fun kindAndKeywordAreAppliedTogether() {
        val s = state("报告.pdf", "报告.jpg", "照片.jpg", filter = "报", kind = FileKind.DOCUMENT)
        assertEquals(listOf("报告.pdf"), s.visibleRows.map { it.entry.name })
    }

    @Test
    fun theEmptyMessageSaysWhichConditionLetItDown() {
        assertEquals("这里没有图片", state("a.txt", kind = FileKind.IMAGE).emptyFilterMessage)
        assertEquals(
            "没有名字包含「zzz」的图片",
            state("a.txt", filter = "zzz", kind = FileKind.IMAGE).emptyFilterMessage,
        )
        assertEquals("没有名字包含「zzz」的项目", state("a.txt", filter = "zzz").emptyFilterMessage)
    }

    @Test
    fun theSummaryAlsoCountsTypeFiltering() {
        assertEquals("筛选中 1/3 项", state("a.jpg", "b.txt", "c.txt", kind = FileKind.IMAGE).filterSummary)
    }
}
