package com.panhub.feature.trash

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
 * 回收站的按名字筛选。
 *
 * 这里的期望值都写成屏幕上真会看到的字：用户照着卡片上的名字敲字，
 * 敲完必须还在，否则他会以为是回收站把文件弄丢了。
 */
class TrashFilterTest {

    private fun node(name: String, dir: Boolean = false) = TrashNode(
        node = RemoteNode(
            id = NodeId("id-" + name),
            parentId = null,
            name = name,
            kind = if (dir) NodeKind.DIR else NodeKind.FILE,
        ),
        deletedAt = 1_791_000_000_000L,
    )

    private fun state(vararg items: TrashNode, query: String = "") = TrashUiState(
        loading = false,
        items = items.toList(),
        query = query,
    )

    @Test
    fun withoutAKeywordEverythingStays() {
        val ui = state(node("报告.pdf"), node("照片.jpg"))
        assertEquals(2, ui.visible.size)
        assertFalse(ui.filtering)
        assertNull(ui.filterSummary)
    }

    @Test
    fun aKeywordNarrowsTheList() {
        val ui = state(node("报告.pdf"), node("照片.jpg"), query = "报告")
        assertEquals(listOf("报告.pdf"), ui.visible.map { it.name })
        assertTrue(ui.filtering)
    }

    @Test
    fun matchingIgnoresCase() {
        val ui = state(node("Report.PDF"), query = "report")
        assertEquals(1, ui.visible.size)
    }

    @Test
    fun foldersAreSearchableByNameToo() {
        // 回收站里连文件夹一起列，筛的时候不能因为它是文件夹就跳过。
        val ui = state(node("旧照片", dir = true), node("a.txt"), query = "旧")
        assertEquals(listOf("旧照片"), ui.visible.map { it.name })
    }

    @Test
    fun filteringEverythingOutIsNotTheSameAsAnEmptyTrash() {
        val ui = state(node("a.txt"), query = "不存在")
        assertTrue(ui.visible.isEmpty())
        assertEquals("没有名字包含「不存在」的项目", ui.emptyTitle)
        assertEquals("换个关键字，或者点上面的清空", ui.emptyHint)
    }

    @Test
    fun anActuallyEmptyTrashSaysSo() {
        val ui = state(query = "随便")
        assertEquals("回收站是空的", ui.emptyTitle)
        assertEquals("在文件页删除的东西会先放到这里", ui.emptyHint)
    }

    @Test
    fun summaryOnlyAppearsWhenSomethingIsHidden() {
        assertEquals("筛选中 1/3 项", state(node("a.txt"), node("b.md"), node("c.png"), query = "a").filterSummary)
        assertNull(state(node("a.txt"), query = "a").filterSummary)
    }

    @Test
    fun selectAllOnlyCoversWhatIsVisible() {
        // 筛着的时候全选要是把看不见的也选上，「彻底删除」那一刀就没人拦得住了。
        val hidden = node("私密.zip")
        val shown = node("报告.pdf")
        val ui = state(shown, hidden, query = "报告")
        assertTrue(ui.allSelected.not())
    }

    @Test
    fun selectAllIsOnWhenEveryVisibleItemIsPicked() {
        val shown = node("报告.pdf")
        val ui = state(shown, node("私密.zip"), query = "报告")
        val picked = ui.copy(selected = setOf("id-报告.pdf"))
        assertTrue(picked.allSelected)
    }
}
