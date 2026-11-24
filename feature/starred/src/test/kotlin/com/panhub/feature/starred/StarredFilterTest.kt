package com.panhub.feature.starred

import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.RemoteNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏夹的按名字筛选。
 *
 * 收藏夹通常不大，但它是跨目录的：同一个名字可能出现在好几个文件夹里，
 * 用户就是靠名字在找。筛错的结果是他以为某个文件已经不在收藏里了。
 */
class StarredFilterTest {

    private fun node(name: String, dir: Boolean = false, size: Long = 1024L) = RemoteNode(
        id = NodeId("id-" + name),
        parentId = null,
        name = name,
        kind = if (dir) NodeKind.DIR else NodeKind.FILE,
        size = size,
    )

    private fun state(vararg items: RemoteNode, query: String = "") = StarredUiState(
        loading = false,
        items = items.toList(),
        query = query,
    )

    @Test
    fun withoutAKeywordEverythingStays() {
        val ui = state(node("合同.pdf"), node("照片.jpg"))
        assertEquals(2, ui.visible.size)
        assertFalse(ui.filtering)
        assertNull(ui.filterSummary)
    }

    @Test
    fun aKeywordNarrowsTheList() {
        val ui = state(node("合同.pdf"), node("照片.jpg"), query = "合同")
        assertEquals(listOf("合同.pdf"), ui.visible.map { it.name })
        assertTrue(ui.filtering)
    }

    @Test
    fun matchingIgnoresCase() {
        val ui = state(node("Budget.XLSX"), query = "budget")
        assertEquals(1, ui.visible.size)
    }

    @Test
    fun favoriteFoldersAreSearchableToo() {
        // 收藏的文件夹也算「收藏」，筛的时候没有理由把它排除。
        val ui = state(node("项目资料", dir = true), node("a.txt"), query = "项目")
        assertEquals(listOf("项目资料"), ui.visible.map { it.name })
    }

    @Test
    fun filteringEverythingOutIsNotTheSameAsAnEmptyFavorite() {
        val ui = state(node("a.txt"), query = "不存在")
        assertTrue(ui.visible.isEmpty())
        assertEquals("没有名字包含「不存在」的项目", ui.emptyTitle)
        assertEquals("换个关键字，或者点上面的清空", ui.emptyHint)
    }

    @Test
    fun anActuallyEmptyFavoriteKeepsItsOwnWords() {
        val ui = state(query = "随便")
        assertEquals("这个网盘还没有收藏任何文件", ui.emptyTitle)
        assertEquals("在文件页里点某个文件的「收藏」，它就会出现在这里", ui.emptyHint)
    }

    @Test
    fun summaryOnlyAppearsWhenSomethingIsHidden() {
        val three = state(node("a.txt"), node("b.md"), node("c.png"), query = "a")
        assertEquals("筛选中 1/3 项", three.filterSummary)
        assertNull(state(node("a.txt"), query = "a").filterSummary)
    }

    @Test
    fun aBlankKeywordHidesNothing() {
        val ui = state(node("a.txt"), node("b.txt"), query = "   ")
        assertEquals(2, ui.visible.size)
        assertFalse(ui.filtering)
    }
}
