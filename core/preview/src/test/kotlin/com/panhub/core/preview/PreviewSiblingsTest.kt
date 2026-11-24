package com.panhub.core.preview

import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.RemoteNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 预览翻页的邻居计算。
 *
 * 这些用例钉住的是「翻页顺序跟着列表走」以及「只有在确实有同类邻居时才出现按钮」，
 * 后者尤其要紧：按钮出现了却点了没反应，比没有按钮更糟。
 */
class PreviewSiblingsTest {

    @Test
    fun imagesFlipAmongThemselves() {
        val nodes = listOf(node("a", "a.png"), node("b", "b.png"), node("c", "c.png"))
        val cursors = PreviewSiblings.of(nodes, nodes[1], PreviewKind.IMAGE)

        assertEquals("a", cursors.previous?.id?.value)
        assertEquals("c", cursors.next?.id?.value)
        assertEquals(2, cursors.index)
        assertEquals(3, cursors.total)
        assertEquals("2 / 3", cursors.position)
    }

    @Test
    fun textFilesFlipAmongThemselves() {
        val nodes = listOf(node("a", "a.md"), node("b", "b.txt"))
        val cursors = PreviewSiblings.of(nodes, nodes[0], PreviewKind.TEXT)

        assertNull(cursors.previous)
        assertEquals("b", cursors.next?.id?.value)
    }

    @Test
    fun mixedFolderOnlyCountsTheSameKind() {
        // 照片堆里夹着一个 README：翻照片不该翻到它，翻文本也不该翻到照片。
        val nodes = listOf(
            node("a", "a.png"),
            node("readme", "README.md"),
            node("b", "b.png"),
        )

        val images = PreviewSiblings.of(nodes, nodes[0], PreviewKind.IMAGE)
        assertEquals("b", images.next?.id?.value)
        assertEquals(2, images.total)

        val texts = PreviewSiblings.of(nodes, nodes[1], PreviewKind.TEXT)
        assertEquals(1, texts.total)
        assertFalse(texts.canBrowse)
    }

    @Test
    fun firstOneHasNoPrevious() {
        val nodes = listOf(node("a", "a.png"), node("b", "b.png"))
        val cursors = PreviewSiblings.of(nodes, nodes[0], PreviewKind.IMAGE)

        assertNull(cursors.previous)
        assertEquals("b", cursors.next?.id?.value)
    }

    @Test
    fun lastOneHasNoNext() {
        val nodes = listOf(node("a", "a.png"), node("b", "b.png"))
        val cursors = PreviewSiblings.of(nodes, nodes[1], PreviewKind.IMAGE)

        assertEquals("a", cursors.previous?.id?.value)
        assertNull(cursors.next)
    }

    @Test
    fun aFileOutsideTheListHasNoCursors() {
        // 从搜索结果打开时同目录文件根本不知道是哪一份，不能拿别的列表去凑。
        val nodes = listOf(node("a", "a.png"))
        val stranger = node("z", "z.png")
        val cursors = PreviewSiblings.of(nodes, stranger, PreviewKind.IMAGE)

        assertEquals(PreviewCursors.None, cursors)
        assertNull(cursors.position)
    }

    @Test
    fun aSingleFileCannotBeBrowsed() {
        val nodes = listOf(node("a", "a.png"))
        val cursors = PreviewSiblings.of(nodes, nodes[0], PreviewKind.IMAGE)

        assertFalse(cursors.canBrowse)
        assertNull(cursors.position)
        assertEquals(1, cursors.index)
        assertEquals(1, cursors.total)
    }

    @Test
    fun positionIsOneBased() {
        val nodes = listOf(node("a", "a.png"), node("b", "b.png"), node("c", "c.png"))
        val cursors = PreviewSiblings.of(nodes, nodes[0], PreviewKind.IMAGE)

        assertEquals("1 / 3", cursors.position)
    }

    @Test
    fun nothingToBrowseForAnUnpreviewableFile() {
        val nodes = listOf(node("a", "a.png"), node("dir", "dir", NodeKind.DIR))
        val cursors = PreviewSiblings.of(nodes, nodes[0], PreviewKind.NONE)

        assertEquals(PreviewCursors.None, cursors)
    }

    @Test
    fun foldersAreNotNeighbours() {
        val nodes = listOf(
            node("a", "a.png"),
            node("dir", "dir", NodeKind.DIR),
            node("b", "b.png"),
        )
        val cursors = PreviewSiblings.of(nodes, nodes[0], PreviewKind.IMAGE)

        assertEquals("b", cursors.next?.id?.value)
        assertEquals(2, cursors.total)
    }

    @Test
    fun imageWithoutExtensionStillCountsByMime() {
        val nodes = listOf(
            node("a", "a", mime = "image/jpeg"),
            node("b", "b.png"),
        )
        val cursors = PreviewSiblings.of(nodes, nodes[0], PreviewKind.IMAGE)

        assertEquals(2, cursors.total)
        assertEquals("b", cursors.next?.id?.value)
    }

    private fun node(
        id: String,
        name: String,
        kind: NodeKind = NodeKind.FILE,
        mime: String? = null,
    ) = RemoteNode(
        id = NodeId(id),
        parentId = null,
        name = name,
        kind = kind,
        mimeType = mime,
    )
}
