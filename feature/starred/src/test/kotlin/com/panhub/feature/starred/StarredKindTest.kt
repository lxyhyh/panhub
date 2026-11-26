package com.panhub.feature.starred

import com.panhub.core.model.FileKind
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.RemoteNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏夹按文件类型筛。
 *
 * 收藏夹是跨目录的，用户来这里通常是为了「把之前的照片找出来」或者
 * 「看看收藏了哪些文档」，类型比名字更常用；筛错的表现是「我明明收藏过」。
 */
class StarredKindTest {

    private fun node(name: String, dir: Boolean = false) = RemoteNode(
        id = NodeId("id-" + name),
        parentId = null,
        name = name,
        kind = if (dir) NodeKind.DIR else NodeKind.FILE,
    )

    private fun state(vararg items: RemoteNode, kind: FileKind? = null, query: String = "") =
        StarredUiState(loading = false, items = items.toList(), query = query, kind = kind)

    @Test
    fun onlyFilesOfThatKindStay() {
        val ui = state(node("a.png"), node("b.txt"), kind = FileKind.IMAGE)
        assertEquals(listOf("a.png"), ui.visible.map { it.name })
    }

    @Test
    fun favoritedFoldersSurviveEveryKind() {
        // 和浏览页同一个理由：文件夹不是文件，硬给它归一类只是猜。
        val ui = state(node("项目资料", dir = true), node("a.png"), kind = FileKind.IMAGE)
        assertEquals(listOf("项目资料", "a.png"), ui.visible.map { it.name })
    }

    @Test
    fun kindAndKeywordNarrowTogether() {
        val ui = state(
            node("报告.png"),
            node("照片.png"),
            node("报告.pdf"),
            kind = FileKind.IMAGE,
            query = "报告",
        )
        assertEquals(listOf("报告.png"), ui.visible.map { it.name })
    }

    @Test
    fun aKindThatHidesEverythingKeepsItsOwnWords() {
        val ui = state(node("a.txt"), kind = FileKind.IMAGE)
        assertTrue(ui.visible.isEmpty())
        assertEquals("这里没有图片", ui.emptyTitle)
        assertEquals("换个类型，或者点上面的清空", ui.emptyHint)
    }

    @Test
    fun anEmptyFavoriteStillSaysItIsEmpty() {
        val ui = state(kind = FileKind.IMAGE)
        assertEquals("这个网盘还没有收藏任何文件", ui.emptyTitle)
        assertEquals("在文件页里点某个文件的「收藏」，它就会出现在这里", ui.emptyHint)
    }

    @Test
    fun filteringCountsBothKindAndKeyword() {
        val ui = state(node("a.png"), node("b.txt"), node("c.md"), kind = FileKind.IMAGE)
        assertEquals("筛选中 1/3 项", ui.filterSummary)
        assertTrue(ui.filtering)
    }

    @Test
    fun withoutAKindNothingIsHidden() {
        val ui = state(node("a.png"), node("b.txt"))
        assertFalse(ui.filtering)
        assertNull(ui.filterSummary)
        assertEquals(2, ui.visible.size)
    }
}
