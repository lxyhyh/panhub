package com.panhub.feature.trash

import com.panhub.core.model.FileKind
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.RemoteNode
import com.panhub.core.model.TrashNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回收站的按类型筛选。
 *
 * 与浏览页、本机文件页共用同一份类型表：同一个文件夹在这三页
 * 都必须被认成同一类，否则用户会觉得分类是随机的。
 */
class TrashKindTest {

    private fun node(name: String, dir: Boolean = false) = TrashNode(
        node = RemoteNode(
            id = NodeId("id-" + name),
            parentId = null,
            name = name,
            kind = if (dir) NodeKind.DIR else NodeKind.FILE,
        ),
        deletedAt = 1_791_000_000_000L,
    )

    private fun state(vararg items: TrashNode, kind: FileKind? = null, query: String = "") =
        TrashUiState(loading = false, items = items.toList(), kind = kind, query = query)

    @Test
    fun withoutAKindEverythingStays() {
        val ui = state(node("报告.pdf"), node("照片.jpg"), node("相册", dir = true))
        assertEquals(3, ui.visible.size)
        assertFalse(ui.filtering)
        assertNull(ui.filterSummary)
    }

    @Test
    fun aKindKeepsOnlyThatKindOfFile() {
        val ui = state(node("报告.pdf"), node("照片.jpg"), kind = FileKind.IMAGE)
        assertEquals(listOf("照片.jpg"), ui.visible.map { it.name })
        assertTrue(ui.filtering)
    }

    @Test
    fun foldersSurviveEveryKind() {
        // 文件夹没有类型。筛图片时把文件夹也藏起来，用户会以为
        // 回收站把那个文件夹清掉了。
        val ui = state(node("相册", dir = true), node("报告.pdf"), kind = FileKind.IMAGE)
        assertEquals(listOf("相册"), ui.visible.map { it.name })
    }

    @Test
    fun kindAndKeywordNarrowTogether() {
        val ui = state(
            node("报告.pdf"),
            node("年会照片.jpg"),
            node("年会报告.png"),
            kind = FileKind.IMAGE,
            query = "年会",
        )
        assertEquals(listOf("年会照片.jpg", "年会报告.png"), ui.visible.map { it.name })
    }

    @Test
    fun everythingFilteredOutIsNotTheSameAsAnEmptyTrash() {
        val ui = state(node("报告.pdf"), kind = FileKind.IMAGE)
        assertTrue(ui.visible.isEmpty())
        assertFalse(ui.emptyTitle == "回收站是空的")
        assertEquals("这里没有图片", ui.emptyTitle)
    }

    @Test
    fun keywordAndKindTogetherSayBothFacts() {
        val ui = state(node("报告.pdf"), kind = FileKind.IMAGE, query = "年会")
        assertEquals("没有名字包含「年会」的图片", ui.emptyTitle)
    }

    @Test
    fun hintPointsAtTheKindWhenOnlyTheKindIsActive() {
        val ui = state(node("报告.pdf"), node("照片.jpg"), kind = FileKind.IMAGE, query = "年会")
        assertTrue(ui.visible.isEmpty())
        assertEquals("换个类型，或者点上面的清空", ui.emptyHint)
    }

    @Test
    fun summaryOnlyShowsUpWhenSomethingWasHidden() {
        val all = state(node("报告.pdf"), node("照片.jpg"))
        assertNull(all.filterSummary)
        val narrowed = state(node("报告.pdf"), node("照片.jpg"), kind = FileKind.IMAGE)
        assertEquals("筛选中 1/2 项", narrowed.filterSummary)
    }
}