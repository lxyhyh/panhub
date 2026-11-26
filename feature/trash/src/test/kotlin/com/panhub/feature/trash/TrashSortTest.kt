package com.panhub.feature.trash

import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.RemoteNode
import com.panhub.core.model.SortOrder
import com.panhub.core.model.SortSpec
import com.panhub.core.model.TrashNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回收站的排序。
 *
 * 期望值都写成屏幕上真会看到的顺序：回收站最常见的用法是「我刚删错了一个，捞回来」，
 * 刚删的必须排在最上面；没有删除时间的老条目要沉到最后，而不是被当成 1970 混进来。
 */
class TrashSortTest {

    private fun node(
        name: String,
        size: Long = 1024L,
        deletedAt: Long? = 1_791_000_000_000L,
        dir: Boolean = false,
    ) = TrashNode(
        node = RemoteNode(
            id = NodeId("id-" + name),
            parentId = null,
            name = name,
            kind = if (dir) NodeKind.DIR else NodeKind.FILE,
            size = size,
        ),
        deletedAt = deletedAt,
    )

    private fun state(vararg items: TrashNode, sort: SortSpec = SortSpec(SortOrder.TIME, true)) =
        TrashUiState(loading = false, items = items.toList(), sort = sort)

    @Test
    fun theNewestDeletionIsOnTopByDefault() {
        val ui = state(
            node("old.bin", deletedAt = 1_000L),
            node("new.bin", deletedAt = 9_000L),
            node("mid.bin", deletedAt = 5_000L),
        )
        assertEquals(listOf("new.bin", "mid.bin", "old.bin"), ui.visible.map { it.name })
    }

    @Test
    fun flippingTheTimeOrderPutsTheOldestFirst() {
        val ui = state(
            node("old.bin", deletedAt = 1_000L),
            node("new.bin", deletedAt = 9_000L),
            sort = SortSpec(SortOrder.TIME, descending = false),
        )
        assertEquals(listOf("old.bin", "new.bin"), ui.visible.map { it.name })
    }

    @Test
    fun entriesWithoutADeletionTimeSinkToTheBottom() {
        // 服务端偶尔不回删除时间。把它当成 0 会在「从新到旧」时排第一，
        // 那正好是最需要看见的位置，最没有信息的那条占了。
        val ui = state(
            node("unknown.bin", deletedAt = null),
            node("known.bin", deletedAt = 1_000L),
        )
        assertEquals(listOf("known.bin", "unknown.bin"), ui.visible.map { it.name })
    }

    @Test
    fun sortingByNameIgnoresCase() {
        val ui = state(
            node("b.txt"),
            node("A.txt"),
            node("c.txt"),
            sort = SortSpec(SortOrder.NAME, descending = false),
        )
        assertEquals(listOf("A.txt", "b.txt", "c.txt"), ui.visible.map { it.name })
    }

    @Test
    fun sortingBySizePutsTheBiggestFirst() {
        val ui = state(
            node("small.bin", size = 10L),
            node("big.bin", size = 9_000L),
            sort = SortSpec(SortOrder.SIZE, descending = true),
        )
        assertEquals(listOf("big.bin", "small.bin"), ui.visible.map { it.name })
    }

    @Test
    fun foldersAreNotHoistedAboveFiles() {
        // 浏览页文件夹永远在前，回收站里没有这个规矩：
        // 这里是「一件东西」，文件夹和文件同等对待。
        val ui = state(
            node("zzz", dir = true, deletedAt = null),
            node("aaa", deletedAt = 9_000L),
            sort = SortSpec(SortOrder.NAME, descending = false),
        )
        assertEquals(listOf("aaa", "zzz"), ui.visible.map { it.name })
    }

    @Test
    fun sortingAndFilteringWorkTogether() {
        val ui = TrashUiState(
            loading = false,
            items = listOf(
                node("照片1.jpg", deletedAt = 1_000L),
                node("报告.pdf", deletedAt = 9_000L),
                node("照片2.jpg", deletedAt = 5_000L),
            ),
            query = "照片",
            sort = SortSpec(SortOrder.TIME, descending = true),
        )
        // 先按时间排好，再被关键字收窄；筛完仍然是新的在前。
        assertEquals(listOf("照片2.jpg", "照片1.jpg"), ui.visible.map { it.name })
    }

    @Test
    fun tiesFallBackOnTheIdSoTheOrderIsStable() {
        // 同一个时间戳的两条：两次进页面看到的顺序必须一样，否则用户
        // 会以为点错了。这里钉住的是「按 id 兜底」这条规则。
        val ui = state(
            node("b.txt", deletedAt = 1_000L),
            node("a.txt", deletedAt = 1_000L),
        )
        assertEquals(listOf("a.txt", "b.txt"), ui.visible.map { it.name })
        assertTrue(ui.visible.size == 2)
    }
}
