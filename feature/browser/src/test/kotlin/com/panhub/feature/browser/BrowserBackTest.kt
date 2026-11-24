package com.panhub.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserBackTest {

    @Test
    fun `selection goes away before the folder does`() {
        val action = BrowserBack.action(selectionMode = true, filterVisible = false, canGoUp = true)
        assertEquals(BrowserBackAction.EXIT_SELECTION, action)
    }

    @Test
    fun `selection wins over an open filter bar`() {
        val action = BrowserBack.action(selectionMode = true, filterVisible = true, canGoUp = true)
        assertEquals(BrowserBackAction.EXIT_SELECTION, action)
    }

    @Test
    fun `an open filter bar closes before going up`() {
        val action = BrowserBack.action(selectionMode = false, filterVisible = true, canGoUp = true)
        assertEquals(BrowserBackAction.CLOSE_FILTER, action)
    }

    @Test
    fun `an open filter bar in the root still closes first`() {
        // 根目录时可退的只剩筛选条：这时按返回收条，而不是直接离开浏览页。
        val action = BrowserBack.action(selectionMode = false, filterVisible = true, canGoUp = false)
        assertEquals(BrowserBackAction.CLOSE_FILTER, action)
    }

    @Test
    fun `a subfolder is left before the page`() {
        val action = BrowserBack.action(selectionMode = false, filterVisible = false, canGoUp = true)
        assertEquals(BrowserBackAction.GO_UP, action)
    }

    @Test
    fun `an untouched root page lets the system leave`() {
        val action = BrowserBack.action(selectionMode = false, filterVisible = false, canGoUp = false)
        assertEquals(BrowserBackAction.LEAVE, action)
    }

    @Test
    fun `only a plain root page is left alone`() {
        assertFalse(BrowserBack.handles(selectionMode = false, filterVisible = false, canGoUp = false))
        assertTrue(BrowserBack.handles(selectionMode = true, filterVisible = false, canGoUp = false))
        assertTrue(BrowserBack.handles(selectionMode = false, filterVisible = true, canGoUp = false))
        assertTrue(BrowserBack.handles(selectionMode = false, filterVisible = false, canGoUp = true))
    }
}
