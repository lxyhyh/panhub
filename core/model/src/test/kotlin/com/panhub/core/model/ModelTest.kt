package com.panhub.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelTest {

    @Test
    fun `blank provider id is rejected`() {
        val error = runCatching { ProviderId("") }.exceptionOrNull()
        requireNotNull(error)
    }

    @Test
    fun `page without cursor has no more`() {
        val page = NodePage(items = emptyList(), nextCursor = null)
        assertEquals(false, page.hasMore)
        val more = NodePage(items = emptyList(), nextCursor = "abc")
        assertEquals(true, more.hasMore)
    }

    @Test
    fun `quota free is null when total unknown`() {
        assertNull(Quota(used = 1024L, total = null).free)
        assertEquals(924L, Quota(used = 100L, total = 1024L).free)
    }

    @Test
    fun `dir node is dir`() {
        val node = RemoteNode(
            id = NodeId("1"),
            parentId = null,
            name = "docs",
            kind = NodeKind.DIR,
        )
        assertEquals(true, node.isDir)
    }
}
