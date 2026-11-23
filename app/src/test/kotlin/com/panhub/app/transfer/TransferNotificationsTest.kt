package com.panhub.app.transfer

import com.panhub.core.model.AccountId
import com.panhub.core.transfer.TransferDigest
import com.panhub.core.transfer.TransferReport
import com.panhub.core.transfer.TransferDirection
import com.panhub.core.transfer.TransferState
import com.panhub.core.transfer.TransferTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知文案。
 *
 * 这些字符串是用户唯一会看到的传输状态，写错了（比如大小未知时显示 0%、
 * 或者把转存说成下载）比没有通知更糟。这里只测纯函数部分，
 * 不碰 Android 的通知对象。
 */
class TransferNotificationsTest {

    private fun task(
        name: String = "a.txt",
        direction: TransferDirection = TransferDirection.DOWNLOAD,
        state: TransferState = TransferState.RUNNING,
        bytes: Long = 0L,
        totalBytes: Long? = null,
    ) = TransferTask(
        id = "t1",
        accountId = AccountId("acc"),
        name = name,
        direction = direction,
        state = state,
        bytes = bytes,
        totalBytes = totalBytes,
    )

    @Test
    fun titleShowsPercentAndCountLikeTheList() {
        val digest = TransferDigest(
            running = task(bytes = 42, totalBytes = 100),
            queued = 2,
            active = 3,
            doneBytes = 42,
            totalBytes = 100,
        )
        assertEquals("传输 42%（3）", TransferNotifications.titleOf(digest))
    }

    @Test
    fun titleDropsCountWhenOnlyOneTask() {
        val digest = TransferDigest(active = 1, doneBytes = 5, totalBytes = 10)
        assertEquals("传输 50%", TransferNotifications.titleOf(digest))
    }

    @Test
    fun titleOmitsPercentWhenSizeUnknown() {
        // 大小未知时不能显示 0%，那会让用户以为卡住了。
        val digest = TransferDigest(active = 2, queued = 2)
        assertEquals("传输（2）", TransferNotifications.titleOf(digest))
    }

    @Test
    fun titleFallsBackToPlainWord() {
        assertEquals("传输", TransferNotifications.titleOf(TransferDigest()))
    }

    @Test
    fun describeNamesTheDirection() {
        assertTrue(
            TransferNotifications.describe(task(direction = TransferDirection.UPLOAD))
                .startsWith("上传："),
        )
        assertTrue(
            TransferNotifications.describe(task(direction = TransferDirection.CLOUD_COPY))
                .startsWith("转存："),
        )
        // 整目录下载本身不传字节，说成「整理文件」比「下载」准确。
        assertTrue(
            TransferNotifications.describe(task(direction = TransferDirection.DOWNLOAD_FOLDER))
                .startsWith("整理文件："),
        )
    }

    @Test
    fun describeShowsBytesWhenSizeKnown() {
        val line = TransferNotifications.describe(task(bytes = 1024, totalBytes = 4096))
        assertTrue(line.contains("1.0 KB / 4.0 KB"))
    }

    @Test
    fun describeHidesZeroTotal() {
        // 总大小为 0 是「不知道」，显示 "0 B / 0 B" 只会让人困惑。
        val line = TransferNotifications.describe(task(state = TransferState.QUEUED, totalBytes = 0))
        assertFalse(line.contains(" / "))
    }

    @Test
    fun describeTruncatesLongNames() {
        val line = TransferNotifications.describe(task(name = "x".repeat(80)))
        assertTrue("过长的名字要截断", line.contains("…"))
        assertTrue("截断后仍要保留方向", line.startsWith("下载："))
    }

    @Test
    fun emptyDigestHasNoActiveTask() {
        // 服务靠这个判断要不要停掉自己。
        assertFalse(TransferDigest().hasActive)
        assertTrue(TransferDigest.of(listOf(task())).hasActive)
        assertFalse(TransferDigest.of(listOf(task(state = TransferState.COMPLETED))).hasActive)
    }

    @Test
    fun finishedTitleCelebratesACleanRun() {
        assertEquals("传输完成", TransferNotifications.finishedTitle(TransferReport(completed = 3)))
    }

    @Test
    fun finishedTitleSaysFailureWhenNothingWorked() {
        // 三项全失败还说「完成」会让人以为文件已经下好了。
        assertEquals("传输失败", TransferNotifications.finishedTitle(TransferReport(failed = 3)))
    }

    @Test
    fun finishedTitleMentionsHowManyFailedInAMix() {
        assertEquals(
            "传输结束，有 2 项失败",
            TransferNotifications.finishedTitle(TransferReport(completed = 5, failed = 2)),
        )
    }

    @Test
    fun finishedTextCountsBothSidesAndTheBytes() {
        val text = TransferNotifications.finishedText(
            TransferReport(completed = 2, failed = 1, bytes = 3L * 1024L * 1024L),
        )
        assertEquals("2 项完成 · 1 项失败 · 3.0 MB", text)
    }

    @Test
    fun finishedTextOmitsEmptyParts() {
        // 全是成功、字节数未知时不该出现「0 项失败 · 0 B」这种噪声。
        assertEquals("1 项完成", TransferNotifications.finishedText(TransferReport(completed = 1)))
        assertEquals("1 项失败", TransferNotifications.finishedText(TransferReport(failed = 1)))
    }
}
