package com.panhub.feature.starred

import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.RemoteNode
import com.panhub.core.model.SortOrder
import com.panhub.core.model.SortSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 收藏夹的排序。
 *
 * 期望值按屏幕上真会看到的顺序写：这一页最常用来「看看最近收了什么」，
 * 没有修改时间的老条目必须沉底，而不是被当成 1970 顶到最前面。
 */
class StarredSortTest {

    private fun node(
        name: String,
        size: Long = 1024L,
        modifiedAt: Long? = 1_791_000_000_000L,
        dir: Boolean = false,
    ) = RemoteNode(
        id = NodeId("id-" + name),
        parentId = null,
        name = name,
        kind = if (dir) NodeKind.DIR else NodeKind.FILE,
        size = size,
        modifiedAt = modifiedAt,
    )

    private fun state(vararg items: RemoteNode, sort: SortSpec = SortSpec(SortOrder.TIME, true)) =
        StarredUiState(loading = false, items = items.toList(), sort = sort)

    @Test
    fun theNewestIsOnTopByDefault() {
        val ui = state(
            node("old.jpg", modifiedAt = 1_000L),
            node("new.jpg", modifiedAt = 9_000L),
            node("mid.jpg", modifiedAt = 5_000L),
        )
        assertEquals(listOf("new.jpg", "mid.jpg", "old.jpg"), ui.visible.map { it.name })
    }

    @Test
    fun flippingTheTimeOrderPutsTheOldestFirst() {
        val ui = state(
            node("old.jpg", modifiedAt = 1_000L),
            node("new.jpg", modifiedAt = 9_000L),
            sort = SortSpec(SortOrder.TIME, descending = false),
        )
        assertEquals(listOf("old.jpg", "new.jpg"), ui.visible.map { it.name })
    }

    @Test
    fun entriesWithoutATimeSinkToTheBottom() {
        // 服务商没给修改时间时是 null。把它当成 0 会在「从新到旧」时排第一，
        // 那正好是最需要看见的位置，最没有信息的那条占了。
        val ui = state(
            node("unknown.jpg", modifiedAt = null),
            node("known.jpg", modifiedAt = 1_000L),
        )
        assertEquals(listOf("known.jpg", "unknown.jpg"), ui.visible.map { it.name })
    }

    @Test
    fun sortingByNameIgnoresCase() {
        val ui = state(
            node("b.txt"),
            node("A.txt"),
            sort = SortSpec(SortOrder.NAME, descending = false),
        )
        assertEquals(listOf("A.txt", "b.txt"), ui.visible.map { it.name })
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
        // 收藏夹里文件夹和文件是同等的一件东西，没有「文件夹永远在前」。
        val ui = state(
            node("zzz", dir = true, modifiedAt = null),
            node("aaa", modifiedAt = 9_000L),
            sort = SortSpec(SortOrder.NAME, descending = false),
        )
        assertEquals(listOf("aaa", "zzz"), ui.visible.map { it.name })
    }

    @Test
    fun sortingAndFilteringWorkTogether() {
        val ui = StarredUiState(
            loading = false,
            items = listOf(
                node("照片1.jpg", modifiedAt = 1_000L),
                node("报告.pdf", modifiedAt = 9_000L),
                node("照片2.jpg", modifiedAt = 5_000L),
            ),
            query = "照片",
            sort = SortSpec(SortOrder.TIME, descending = true),
        )
        assertEquals(listOf("照片2.jpg", "照片1.jpg"), ui.visible.map { it.name })
    }
}
