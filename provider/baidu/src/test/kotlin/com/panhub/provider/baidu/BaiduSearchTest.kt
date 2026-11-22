package com.panhub.provider.baidu

import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.PageRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 搜索结果的解析与分页。
 *
 * 百度的搜索是递归全盘的，返回的每一项都带绝对路径但没有父目录字段，
 * 所以这里重点验证「父目录能从路径里正确切出来」。
 */
class BaiduSearchTest {

    private fun item(path: String, isdir: Int = 0, size: Long = 0L) = BaiduSearchItem(
        fsId = 1L,
        path = path,
        serverFilename = path.substringAfterLast("/"),
        size = size,
        isdir = isdir,
        serverMtime = 1_700_000_000L,
    )

    @Test
    fun parentPathIsCutFromFullPath() {
        val node = item("/文档/报告/2026.pdf").toRemoteNode()!!
        assertEquals(NodeId("/文档/报告"), node.parentId)
        assertEquals("2026.pdf", node.name)
        assertEquals(NodeKind.FILE, node.kind)
    }

    @Test
    fun topLevelItemBelongsToRoot() {
        val node = item("/说明.txt").toRemoteNode()!!
        assertEquals(NodeId(BaiduApi.ROOT_PATH), node.parentId)
    }

    @Test
    fun foldersAreKeptAsFolders() {
        val node = item("/备份", isdir = 1).toRemoteNode()!!
        assertEquals(NodeKind.DIR, node.kind)
    }

    @Test
    fun itemWithoutPathIsDropped() {
        assertNull(BaiduSearchItem(path = null).toRemoteNode())
    }

    @Test
    fun modificationTimeComesInSeconds() {
        val node = item("/a.txt").toRemoteNode()!!
        assertEquals(1_700_000_000_000L, node.modifiedAt)
    }

    @Test
    fun onlyServerDecidesWhetherThereIsMore() {
        assertEquals(1, BaiduSearchResp(hasMore = 1).hasMore)
        assertEquals(0, BaiduSearchResp(hasMore = 0).hasMore)
        assertEquals(100, PageRequest().limit)
    }
}
