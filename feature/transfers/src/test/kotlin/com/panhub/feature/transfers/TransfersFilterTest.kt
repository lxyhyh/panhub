package com.panhub.feature.transfers

import com.panhub.core.model.AccountId
import com.panhub.core.transfer.TransferDirection
import com.panhub.core.transfer.TransferState
import com.panhub.core.transfer.TransferTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传输页按名字找任务。
 *
 * 守两件事：一是不联网、不改队列；二是三种「列表是空的」要说成不同的话——
 * 队列本来就空、这个标签下没有、关键字筛没了，混成一句会让人以为记录丢了。
 */
class TransfersFilterTest {

    private fun task(
        id: String,
        name: String,
        state: TransferState = TransferState.RUNNING,
    ) = TransferTask(
        id = id,
        accountId = AccountId("a1"),
        nodeId = null,
        name = name,
        direction = TransferDirection.DOWNLOAD,
        state = state,
    )

    private fun state(
        vararg tasks: TransferTask,
        filter: TransferFilter = TransferFilter.ALL,
        query: String = "",
    ) = TransfersUiState(filter = filter, tasks = tasks.toList(), query = query)

    @Test
    fun withoutAKeywordEverythingInTheChipIsVisible() {
        val s = state(task("1", "影片.mkv"), task("2", "照片.jpg"))

        assertEquals(2, s.visible.size)
        assertFalse(s.filtering)
        assertNull(s.filterSummary)
    }

    @Test
    fun aKeywordNarrowsTheList() {
        val s = state(
            task("1", "影片.mkv"),
            task("2", "照片.jpg"),
            query = "照片",
        )

        assertEquals(listOf("照片.jpg"), s.visible.map { it.name })
        assertTrue(s.filtering)
    }

    @Test
    fun matchingIgnoresCase() {
        val s = state(task("1", "Report.PDF"), query = "report")

        assertEquals(1, s.visible.size)
    }

    @Test
    fun theChipFilterAndTheKeywordStack() {
        // 标签先说「只看已结束」，关键字再在结果里找名字，两层都是收窄。
        val s = state(
            task("1", "影片.mkv", TransferState.COMPLETED),
            task("2", "影片-花絮.mkv", TransferState.RUNNING),
            task("3", "照片.jpg", TransferState.COMPLETED),
            filter = TransferFilter.FINISHED,
            query = "影片",
        )

        assertEquals(3, s.tasks.size)
        assertEquals(2, s.byChip.size)
        assertEquals(listOf("影片.mkv"), s.visible.map { it.name })
    }

    @Test
    fun summaryCountsAgainstTheChipNotTheWholeQueue() {
        // 分母是当前标签下的条数：写成全部任务数会让人以为筛丢了别的东西。
        val s = state(
            task("1", "影片.mkv", TransferState.COMPLETED),
            task("2", "照片.jpg", TransferState.COMPLETED),
            task("3", "正在下.bin", TransferState.RUNNING),
            filter = TransferFilter.FINISHED,
            query = "影片",
        )

        assertEquals("筛选中 1/2 项", s.filterSummary)
    }

    @Test
    fun anEmptyQueueSaysThereIsNothingAtAll() {
        val s = state(query = "影片")

        assertEquals("还没有传输任务", s.emptyTitle)
        assertEquals("在文件列表里长按文件即可开始下载", s.emptyHint)
    }

    @Test
    fun anEmptyChipSaysToLookElsewhere() {
        val s = state(
            task("1", "影片.mkv", TransferState.COMPLETED),
            filter = TransferFilter.ACTIVE,
        )

        // 队列里有东西，只是这个标签下没有——别让人以为任务丢了。
        assertEquals("进行中没有任务", s.emptyTitle)
        assertEquals("切到别的标签看看", s.emptyHint)
    }

    @Test
    fun aKeywordThatMatchesNothingNamesTheKeyword() {
        val s = state(task("1", "影片.mkv"), query = "照片")

        assertTrue(s.visible.isEmpty())
        assertEquals("没有名字包含「照片」的项目", s.emptyTitle)
        assertEquals("换个关键字，或者点上面的清空", s.emptyHint)
    }

    @Test
    fun filteringDoesNotTouchTheQueueItself() {
        val s = state(task("1", "影片.mkv"), task("2", "照片.jpg"), query = "照片")

        // 筛选只影响画出来的那一份，队列本身还是原样。
        assertEquals(2, s.tasks.size)
        assertEquals(1, s.visible.size)
        assertEquals("筛选中 1/2 项", s.filterSummary)
    }
}
