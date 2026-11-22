package com.panhub.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanResultTest {

    @Test
    fun `map transforms success and passes failure`() {
        val ok = PanResult.Success(2)
        assertEquals(4, ok.map { it * 2 }.getOrNull())

        val bad: PanResult<Int> = PanResult.Failure(PanError.notFound())
        assertTrue(bad.map { it * 2 } is PanResult.Failure)
    }

    @Test
    fun `getOrNull only returns on success`() {
        val failed: PanResult<Int> = PanResult.Failure(PanError.auth())
        assertEquals(null, failed.getOrNull())
        assertEquals(1, PanResult.Success(1).getOrNull())
    }

    @Test
    fun `network error is retryable`() {
        assertTrue(PanError.network().retryable)
        assertTrue(PanError.rateLimited().retryable)
        assertTrue(!PanError.notFound().retryable)
    }
}
