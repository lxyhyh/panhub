package com.panhub.feature.starred

import com.panhub.core.model.FileKind
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.RemoteNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏夹多选。
 *
 * 选择态的全部意义是「批量取消收藏」，所以状态机只考虑一件事：
 * 选中的那些是不是都能被看到、被清掉。
 */
class StarredSelectionTest {

    private fun node(name: String, dir: Boolean = false) = RemoteNode(
        id = NodeId("id-" + name),
        parentId = null,
        name = name,
        kind = if (dir) NodeKind.DIR else NodeKind.FILE,
    )

    private fun state(
        vararg items: RemoteNode,
        selected: Set<String> = emptySet(),
        query: String = "",
        kind: FileKind? = null,
    ) = StarredUiState(
        loading = false,
        items = items.toList(),
        selected = selected,
        query = query,
        kind = kind,
    )

    @Test
    fun nothingSelectedMeansNoSelectionMode() {
        val ui = state(node("a.png"))
        assertFalse(ui.selectionMode)
        assertEquals(0, ui.selectedCount)
    }

    @Test
    fun oneSelectionTurnsTheModeOn() {
        val ui = state(node("a.png"), selected = setOf("id-a.png"))
        assertTrue(ui.selectionMode)
        assertEquals(1, ui.selectedCount)
    }

    @Test
    fun selectAllOnlyCountsWhatIsVisible() {
        // 筛着的时候全选把看不见的条目也选上，取消收藏就会删掉用户没打算动的东西。
        val ui = state(node("a.png"), node("b.txt"), query = ".png")
        assertEquals(listOf("a.png"), ui.visible.map { it.name })
        assertFalse(ui.allSelected)
        val picked = ui.copy(selected = setOf("id-a.png"))
        assertTrue(picked.allSelected)
    }

    @Test
    fun anEmptyListIsNeverAllSelected() {
        // 空列表全选会让顶栏图标显示「取消全选」，点一下什么都不会发生。
        assertFalse(state().allSelected)
    }

    @Test
    fun messageCountsWhatWasUnstarred() {
        assertEquals("已取消收藏 1 项", StarredLogic.unstaredMessage(1))
        assertEquals("已取消收藏 12 项", StarredLogic.unstaredMessage(12))
    }
}