package com.panhub.core.transfer

import com.panhub.core.model.AccountId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一轮传输结束时的成绩单。
 *
 * 这里钉的是「什么时候该发通知、通知里数什么」。数错了的后果不是数字难看，
 * 而是用户会在什么都没做的时候收到「传输完成」，然后把这个通知关掉。
 */
class TransferReportTest {

    private fun task(
        id: String,
        state: TransferState,
        totalBytes: Long? = 1_000L,
        bytes: Long = 0L,
    ) = TransferTask(
        id = id,
        accountId = AccountId("acc"),
        name = id + ".bin",
        direction = TransferDirection.DOWNLOAD,
        state = state,
        bytes = bytes,
        totalBytes = totalBytes,
    )

    @Test
    fun nothingIsReportedWhileTheQueueIsStillBusy() {
        val tracker = TransferRunTracker()
        assertNull(tracker.sample(listOf(task("a", TransferState.RUNNING))))
        assertNull(tracker.sample(listOf(task("a", TransferState.QUEUED))))
    }

    @Test
    fun anEmptyQueueNeverReports() {
        val tracker = TransferRunTracker()
        // 冷启动时队列本来就是空的，这里报一次就会在每次打开 App 时弹「传输完成」。
        assertNull(tracker.sample(emptyList()))
        assertNull(tracker.sample(emptyList()))
    }

    @Test
    fun theReportArrivesWhenTheLastTaskEnds() {
        val tracker = TransferRunTracker()
        assertNull(tracker.sample(listOf(task("a", TransferState.RUNNING))))
        val report = tracker.sample(listOf(task("a", TransferState.COMPLETED, totalBytes = 2_048L)))
        assertEquals(1, report?.completed)
        assertEquals(0, report?.failed)
        assertEquals(2_048L, report?.bytes)
    }

    @Test
    fun failuresAreCountedSeparately() {
        val tracker = TransferRunTracker()
        tracker.sample(
            listOf(
                task("a", TransferState.RUNNING),
                task("b", TransferState.QUEUED),
            ),
        )
        val report = tracker.sample(
            listOf(
                task("a", TransferState.COMPLETED, totalBytes = 100L),
                task("b", TransferState.FAILED),
            ),
        )
        assertEquals(1, report?.completed)
        assertEquals(1, report?.failed)
        assertEquals(2, report?.total)
    }

    @Test
    fun canceledTasksAreNotCountedAsFailures() {
        // 取消是用户自己不要的，把它算成失败会让人以为出了问题。
        val tracker = TransferRunTracker()
        tracker.sample(listOf(task("a", TransferState.RUNNING)))
        val report = tracker.sample(listOf(task("a", TransferState.CANCELED)))
        // 没有成功也没有失败：这一轮等于什么都没发生，不该发通知。
        assertNull(report)
    }

    @Test
    fun historyFromDiskIsNotPartOfThisRound() {
        // 冷启动恢复出来的历史任务是 COMPLETED，但这一轮没跑过它。
        val tracker = TransferRunTracker()
        tracker.sample(listOf(task("old", TransferState.COMPLETED)))
        val report = tracker.sample(listOf(task("old", TransferState.COMPLETED)))
        assertNull(report)
    }

    @Test
    fun aSecondRoundGetsItsOwnReport() {
        val tracker = TransferRunTracker()
        tracker.sample(listOf(task("a", TransferState.RUNNING)))
        assertEquals(1, tracker.sample(listOf(task("a", TransferState.COMPLETED)))?.completed)

        tracker.sample(listOf(task("b", TransferState.RUNNING)))
        val second = tracker.sample(
            listOf(
                task("a", TransferState.COMPLETED),
                task("b", TransferState.COMPLETED),
            ),
        )
        // 第二轮的 a 是上一轮的成绩，不能重复计入。
        assertEquals(1, second?.completed)
    }

    @Test
    fun pausingEverythingDoesNotReportAFinishedRound() {
        // 暂停不是结束：用户还要继续，这时发「传输完成」是假话。
        val tracker = TransferRunTracker()
        tracker.sample(listOf(task("a", TransferState.RUNNING)))
        assertNull(tracker.sample(listOf(task("a", TransferState.PAUSED))))
    }

    @Test
    fun emptyReportIsNotWorthANotification() {
        assertTrue(TransferReport.None.isEmpty)
        assertTrue(TransferReport(completed = 1).isEmpty.not())
        assertTrue(TransferReport(failed = 1).isEmpty.not())
    }
}
