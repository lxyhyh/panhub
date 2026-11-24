package com.panhub.feature.localfiles

import com.panhub.core.transfer.DownloadedEntry
import com.panhub.core.transfer.DownloadsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalFilesLogicTest {

    private fun entry(
        name: String = "a.txt",
        folder: String = "",
        size: Long = 1024L,
        modifiedAt: Long = 0L,
    ): DownloadedEntry {
        val path = if (folder.isEmpty()) name else folder + "/" + name
        return DownloadedEntry(relativePath = path, name = name, size = size, modifiedAt = modifiedAt)
    }

    @Test
    fun summaryCountsFilesAndTotalSize() {
        val snapshot = DownloadsSnapshot(
            entries = listOf(entry(name = "a", size = 1024), entry(name = "b", size = 2048)),
            totalBytes = 3072,
        )
        assertEquals("2 个文件 · 3.0 KB", LocalFilesLogic.summaryOf(snapshot))
    }

    @Test
    fun emptySummaryExplainsRatherThanShowingZero() {
        assertEquals("还没有下载过文件", LocalFilesLogic.summaryOf(DownloadsSnapshot()))
    }

    @Test
    fun subtitleHasSizeFolderAndTime() {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .parse("2026-11-25 10:30")!!.time
        val text = LocalFilesLogic.subtitleOf(
            entry(name = "a.txt", folder = "photos", size = 2048, modifiedAt = stamp),
        )
        assertEquals("2.0 KB · 在 photos 里 · 2026-11-25 10:30", text)
    }

    @Test
    fun rootLevelFileOmitsTheFolderPart() {
        val text = LocalFilesLogic.subtitleOf(entry(size = 512, modifiedAt = 0L))
        assertEquals("512 B", text)
    }

    @Test
    fun unknownTimeIsNotPrinted() {
        // lastModified() 读不到时是 0，显示 1970 年只会让人困惑。
        val text = LocalFilesLogic.subtitleOf(entry(size = 10, modifiedAt = 0L))
        assertFalse(text.contains("1970"))
    }

    @Test
    fun rowsKeepTheEntryOrder() {
        val snapshot = DownloadsSnapshot(
            entries = listOf(entry(name = "new.txt"), entry(name = "old.txt")),
            totalBytes = 2048,
        )
        assertEquals(listOf("new.txt", "old.txt"), LocalFilesLogic.rows(snapshot).map { it.entry.name })
    }

    @Test
    fun deleteQuestionNamesTheFileAndPromisesCloudIsSafe() {
        val text = LocalFilesLogic.deleteQuestion(entry(name = "报告.pdf", size = 2048))
        assertTrue(text.contains("报告.pdf"))
        assertTrue(text.contains("2.0 KB"))
        assertTrue(text.contains("原件不受影响"))
    }

    @Test
    fun clearQuestionCountsEveryFile() {
        val snapshot = DownloadsSnapshot(
            entries = listOf(entry(name = "a", size = 1024), entry(name = "b", size = 1024)),
            totalBytes = 2048,
        )
        val text = LocalFilesLogic.clearQuestion(snapshot)
        assertTrue(text.contains("2 个文件"))
        assertTrue(text.contains("2.0 KB"))
    }

    @Test
    fun deletedMessageSaysWhetherItWorked() {
        assertEquals("已删除 a.txt", LocalFilesLogic.deletedMessage(entry(name = "a.txt"), true))
        assertEquals("没能删掉 a.txt", LocalFilesLogic.deletedMessage(entry(name = "a.txt"), false))
    }

    @Test
    fun clearedMessageReportsHowMuchWasFreed() {
        assertEquals("已清理 3 个文件 · 3.0 KB", LocalFilesLogic.clearedMessage(3, 3072))
    }

    @Test
    fun clearingNothingIsNotAnAchievement() {
        assertEquals("本机没有需要清理的文件", LocalFilesLogic.clearedMessage(0, 0))
    }
}

