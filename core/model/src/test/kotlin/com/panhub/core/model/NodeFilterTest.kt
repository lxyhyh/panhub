package com.panhub.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录内过滤的规则。
 *
 * 这些用例钉的是「输入即生效、绝不联网」这件事的边界：空关键字必须全放行，
 * 大小写不能影响结果，过滤也不能顺手把用户选好的排序打乱。
 */
class NodeFilterTest {

    private fun node(name: String) = RemoteNode(
        id = NodeId(name),
        parentId = null,
        name = name,
        kind = NodeKind.FILE,
    )

    private fun names(nodes: List<RemoteNode>) = nodes.map { it.name }

    @Test
    fun `blank keyword keeps everything`() {
        val nodes = listOf(node("a.txt"), node("b.txt"))
        assertEquals(nodes, NodeFilter.apply(nodes, ""))
        assertEquals(nodes, NodeFilter.apply(nodes, "   "))
    }

    @Test
    fun `matching is a plain substring test`() {
        val nodes = listOf(node("报告-2024.pdf"), node("照片.jpg"), node("报告-2023.pdf"))
        assertEquals(listOf("报告-2024.pdf", "报告-2023.pdf"), names(NodeFilter.apply(nodes, "报告")))
    }

    @Test
    fun `case does not matter`() {
        val nodes = listOf(node("README.md"), node("notes.txt"))
        assertEquals(listOf("README.md"), names(NodeFilter.apply(nodes, "readme")))
        assertEquals(listOf("notes.txt"), names(NodeFilter.apply(nodes, "TXT")))
    }

    @Test
    fun `surrounding spaces in the keyword are ignored`() {
        val nodes = listOf(node("账单.csv"), node("日志.log"))
        assertEquals(listOf("账单.csv"), names(NodeFilter.apply(nodes, "  账单  ")))
    }

    @Test
    fun `filtering never reorders`() {
        val nodes = listOf(node("c"), node("a"), node("b"), node("ab"))
        assertEquals(listOf("a", "ab"), names(NodeFilter.apply(nodes, "a")))
    }

    @Test
    fun `an empty keyword is not active filtering`() {
        assertFalse(NodeFilter.active(""))
        assertFalse(NodeFilter.active("  "))
        assertTrue(NodeFilter.active("x"))
    }

    @Test
    fun `no match is an empty list not an error`() {
        val nodes = listOf(node("a.txt"))
        assertTrue(NodeFilter.apply(nodes, "zzz").isEmpty())
    }

    @Test
    fun `one node matches itself`() {
        val target = node("x.txt")
        assertTrue(NodeFilter.matches(target, "x."))
        assertTrue(NodeFilter.matches(target, ""))
        assertFalse(NodeFilter.matches(target, "y"))
    }

    @Test
    fun `summary only appears while filtering`() {
        assertNull(NodeFilter.summary(2, 10, ""))
        assertEquals("筛选中 2/10 项", NodeFilter.summary(2, 10, "report"))
    }

    @Test
    fun `summary stays quiet when nothing was filtered out`() {
        assertNull(NodeFilter.summary(10, 10, "report"))
    }

    @Test
    fun `empty message names the keyword`() {
        assertEquals("这里还没有文件", NodeFilter.emptyMessage(""))
        assertEquals("没有名字包含「报表」的项目", NodeFilter.emptyMessage(" 报表 "))
    }

    @Test
    fun `any list can be filtered by a name accessor`() {
        // 本机下载记录不是 RemoteNode，但筛选规则必须和网盘列表完全一致。
        val names = listOf("报告.pdf", "照片.jpg", "汇报.md")
        assertEquals(listOf("报告.pdf", "汇报.md"), NodeFilter.applyBy(names, "报", { it }))
    }

    @Test
    fun `name accessor filtering keeps the original order and shape`() {
        val rows = listOf(1 to "b.txt", 2 to "a.txt", 3 to "c.txt")
        val kept = NodeFilter.applyBy(rows, "A", { it.second })
        assertEquals(listOf(2 to "a.txt"), kept)
    }

    @Test
    fun `a blank keyword returns the very same list instance`() {
        // 空词时不复制列表：界面每次重组都会走一遍这个函数，能省就省。
        val rows = listOf("a", "b")
        assertTrue(NodeFilter.applyBy(rows, "  ", { it }) === rows)
    }
}