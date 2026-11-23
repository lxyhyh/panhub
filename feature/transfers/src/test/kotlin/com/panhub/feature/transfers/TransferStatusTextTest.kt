package com.panhub.feature.transfers

import com.panhub.core.model.AccountId
import com.panhub.core.transfer.TransferDirection
import com.panhub.core.transfer.TransferState
import com.panhub.core.transfer.TransferTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferStatusTextTest {

    private fun task(
        state: TransferState,
        direction: TransferDirection = TransferDirection.DOWNLOAD,
        bytes: Long = 1_000_000L,
        totalBytes: Long? = 4_000_000L,
    ) = TransferTask(
        id = "t1",
        accountId = AccountId("a1"),
        nodeId = null,
        name = "a.bin",
        direction = direction,
        state = state,
        bytes = bytes,
        totalBytes = totalBytes,
    )

    @Test
    fun runningTaskShowsSpeedAndRemainingTime() {
        // 已传 1MB、共 4MB，速度 1MiB/s：剩 3MB 需要 2.86 秒，
        // 估算向下取整报「2 秒」（宁可说少了，也别让人以为还要等更久）。
        val text = TransferStatusText.speedNote(
            task(TransferState.RUNNING),
            1024L * 1024L,
        )
        assertEquals(" · 1.0 MB/s · 还剩 2 秒", text)
    }

    @Test
    fun unknownTotalShowsSpeedOnly() {
        // 拿不到总大小时不该瞎猜剩余时间，只报速度。
        val text = TransferStatusText.speedNote(
            task(TransferState.RUNNING, totalBytes = null),
            512L * 1024L,
        )
        assertEquals(" · 512 KB/s", text)
    }

    @Test
    fun queuedTaskShowsNothing() {
        // 排队中的任务字节数不动，标个速率会让人以为它已经在传了。
        assertEquals("", TransferStatusText.speedNote(task(TransferState.QUEUED), 1024L))
    }

    @Test
    fun finishedTaskShowsNothing() {
        assertEquals("", TransferStatusText.speedNote(task(TransferState.COMPLETED), 1024L))
        assertEquals("", TransferStatusText.speedNote(task(TransferState.FAILED), 1024L))
        assertEquals("", TransferStatusText.speedNote(task(TransferState.CANCELED), 1024L))
    }

    @Test
    fun unmeasuredSpeedShowsNothing() {
        // 刚起步只有一次采样，测不出速度；写 0 B/s 会让人以为卡住了。
        assertEquals("", TransferStatusText.speedNote(task(TransferState.RUNNING), null))
    }

    @Test
    fun folderTasksShowNothing() {
        // 整理型任务不传字节，它们的速率是列目录的速度，说出来没意义。
        for (direction in listOf(
            TransferDirection.DOWNLOAD_FOLDER,
            TransferDirection.CLOUD_COPY_FOLDER,
            TransferDirection.UPLOAD_FOLDER,
        )) {
            assertEquals(
                direction.name,
                "",
                TransferStatusText.speedNote(task(TransferState.RUNNING, direction), 1024L),
            )
        }
    }

    @Test
    fun uploadTaskIsMeasuredLikeDownload() {
        val text = TransferStatusText.speedNote(
            task(TransferState.RUNNING, TransferDirection.UPLOAD),
            2L * 1024L * 1024L,
        )
        assertTrue(text, text.startsWith(" · 2.0 MB/s"))
        assertTrue(text, text.contains("还剩"))
    }

    @Test
    fun finishedBytesYieldNoRemainingTime() {
        // 速度不为零但已经传完：剩余时间就是 0，文案是「不到 1 秒」。
        val text = TransferStatusText.speedNote(
            task(TransferState.RUNNING, bytes = 4_000_000L, totalBytes = 4_000_000L),
            1024L * 1024L,
        )
        assertEquals(" · 1.0 MB/s · 还剩 不到 1 秒", text)
    }

    @Test
    fun theFirstQueuedTaskHasNobodyAhead() {
        // 队列里只有它一个，下一个就是它。
        val only = task(TransferState.QUEUED).copy(id = "q1")
        assertEquals(0, TransferStatusText.aheadOf(listOf(only), only))
    }

    @Test
    fun theRunningTaskCountsAsOneAhead() {
        // 正在跑的那个跑完才轮到排队的人，所以它算一个。
        val running = task(TransferState.RUNNING).copy(id = "r1")
        val queued = task(TransferState.QUEUED).copy(id = "q1")
        assertEquals(1, TransferStatusText.aheadOf(listOf(running, queued), queued))
    }

    @Test
    fun pausedTasksAreNotCounted() {
        // 暂停的任务不会自己跑起来，算进去会让人以为还要等它。
        val paused = task(TransferState.PAUSED).copy(id = "p1")
        val queued = task(TransferState.QUEUED).copy(id = "q1")
        assertEquals(0, TransferStatusText.aheadOf(listOf(paused, queued), queued))
    }

    @Test
    fun finishedTasksBehindDoNotCount() {
        // 排在后面的任务与我的位置无关。
        val queued = task(TransferState.QUEUED).copy(id = "q1")
        val done = task(TransferState.COMPLETED).copy(id = "d1")
        assertEquals(0, TransferStatusText.aheadOf(listOf(queued, done), queued))
    }

    @Test
    fun tasksThatAreNotQueuedHaveNoPosition() {
        val running = task(TransferState.RUNNING).copy(id = "r1")
        val running2 = task(TransferState.RUNNING).copy(id = "r2")
        assertEquals(0, TransferStatusText.aheadOf(listOf(running, running2), running2))
    }

    @Test
    fun queueNoteSaysHowManyAreLeft() {
        assertEquals("", TransferStatusText.queueNote(0))
        assertEquals(" · 前面还有 1 个", TransferStatusText.queueNote(1))
        assertEquals(" · 前面还有 3 个", TransferStatusText.queueNote(3))
    }
}
