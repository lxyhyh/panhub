package com.panhub.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SortOrderTest {

    private fun dir(name: String, time: Long? = null) = RemoteNode(
        id = NodeId("d-" + name),
        parentId = null,
        name = name,
        kind = NodeKind.DIR,
        modifiedAt = time,
    )

    private fun file(name: String, size: Long = 0L, time: Long? = null) = RemoteNode(
        id = NodeId("f-" + name),
        parentId = null,
        name = name,
        kind = NodeKind.FILE,
        size = size,
        modifiedAt = time,
    )

    private fun names(nodes: List<RemoteNode>) = nodes.map { it.name }

    @Test
    fun `folders come first whatever the order`() {
        val nodes = listOf(file("b", size = 900), dir("z"), file("a", size = 10), dir("y"))
        assertEquals(listOf("y", "z", "a", "b"), names(nodes.sortedFor(SortSpec(SortOrder.NAME))))
        assertEquals(listOf("z", "y", "b", "a"), names(nodes.sortedFor(SortSpec(SortOrder.SIZE, descending = true))))
    }

    @Test
    fun `name order ignores case`() {
        val nodes = listOf(file("Beta"), file("alpha"), file("Gamma"))
        assertEquals(
            listOf("alpha", "Beta", "Gamma"),
            names(nodes.sortedFor(SortSpec(SortOrder.NAME))),
        )
        assertEquals(
            listOf("Gamma", "Beta", "alpha"),
            names(nodes.sortedFor(SortSpec(SortOrder.NAME, descending = true))),
        )
    }

    @Test
    fun `time order keeps nodes without time at the end`() {
        val nodes = listOf(file("old", time = 100L), file("unknown", time = null), file("new", time = 300L))
        assertEquals(
            listOf("new", "old", "unknown"),
            names(nodes.sortedFor(SortSpec(SortOrder.TIME, descending = true))),
        )
        assertEquals(
            listOf("old", "new", "unknown"),
            names(nodes.sortedFor(SortSpec(SortOrder.TIME, descending = false))),
        )
    }

    @Test
    fun `size order sorts by bytes`() {
        val nodes = listOf(file("mid", size = 500), file("small", size = 1), file("big", size = 9000))
        assertEquals(
            listOf("big", "mid", "small"),
            names(nodes.sortedFor(SortSpec(SortOrder.SIZE, descending = true))),
        )
        assertEquals(
            listOf("small", "mid", "big"),
            names(nodes.sortedFor(SortSpec(SortOrder.SIZE, descending = false))),
        )
    }

    @Test
    fun `default spec keeps the old name ascending behaviour`() {
        val nodes = listOf(file("b"), file("a"))
        assertEquals(listOf("a", "b"), names(nodes.sortedFor(SortSpec())))
    }
}
