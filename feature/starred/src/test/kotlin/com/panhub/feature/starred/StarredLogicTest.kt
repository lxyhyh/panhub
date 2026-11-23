package com.panhub.feature.starred

import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.RemoteNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StarredLogicTest {

    private fun file(size: Long = 1024L) = RemoteNode(
        id = NodeId("1"),
        parentId = null,
        name = "report.pdf",
        kind = NodeKind.FILE,
        size = size,
        starred = true,
    )

    private fun folder() = RemoteNode(
        id = NodeId("2"),
        parentId = null,
        name = "docs",
        kind = NodeKind.DIR,
        starred = true,
    )

    @Test
    fun fileSubtitleShowsItsSize() {
        // 格式化交给 core:design 的 formatBytes，这里只验证「文件说大小」这一件事。
        assertEquals("1024 B", StarredLogic.subtitle(file(1024L)) { it.toString() + " B" })
    }

    @Test
    fun folderSubtitleSaysFolderNotFound() {
        assertEquals("文件夹", StarredLogic.subtitle(folder()) { it.toString() + " B" })
    }

    @Test
    fun unstaredMessageNamesTheFile() {
        assertEquals("已取消收藏 report.pdf", StarredLogic.unstaredMessage(file()))
    }

    @Test
    fun stillLoadingSaysNothing() {
        assertNull(StarredLogic.emptyMessage(loading = true, failed = false))
    }

    @Test
    fun failureIsNotTheSameAsAnEmptyFolder() {
        // 加载失败和「真的没有收藏」是两件事：后者再点多少次都是空的。
        assertEquals("收藏夹没能加载出来", StarredLogic.emptyMessage(loading = false, failed = true))
        assertEquals(
            "这个网盘还没有收藏任何文件",
            StarredLogic.emptyMessage(loading = false, failed = false),
        )
    }

    @Test
    fun failureWinsOverLoading() {
        // 重试时 error 会被清掉，所以 error 还在就说明这一轮确实没成功。
        assertEquals(
            "收藏夹没能加载出来",
            StarredLogic.emptyMessage(loading = true, failed = true),
        )
    }
}