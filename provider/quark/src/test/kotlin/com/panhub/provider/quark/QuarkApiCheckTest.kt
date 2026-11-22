package com.panhub.provider.quark

import com.panhub.core.network.HttpEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarkApiCheckTest {

    private val api = QuarkApi(HttpEngine())

    @Test
    fun successfulResponsePassesThrough() {
        val raw = """{"status":200,"code":0,"data":{"list":[]}}"""
        val result = api.check(raw)
        assertTrue(result is com.panhub.core.common.PanResult.Success)
    }

    @Test
    fun nonZeroCodeBecomesFailureWithMessage() {
        val raw = """{"status":400,"code":23001,"message":"file is doloading[同名冲突]"}"""
        val result = api.check(raw)
        assertTrue(result is com.panhub.core.common.PanResult.Failure)
        val error = (result as com.panhub.core.common.PanResult.Failure).error
        assertEquals("file is doloading[同名冲突]", error.message)
    }

    @Test
    fun unauthorizedMapsToAuth() {
        val raw = """{"status":401,"code":31001,"message":"require login"}"""
        val result = api.check(raw)
        assertTrue(result is com.panhub.core.common.PanResult.Failure)
        val error = (result as com.panhub.core.common.PanResult.Failure).error
        assertEquals(com.panhub.core.common.ErrorKind.AUTH, error.kind)
    }

    @Test
    fun blankBodyIsNotParsed() {
        val result = api.check("")
        assertTrue(result is com.panhub.core.common.PanResult.Failure)
    }

    @Test
    fun renewedCookieIsCarried() {
        val raw = """{"status":200,"code":0}"""
        val result = api.check(raw, mapOf("__puus" to "new-value"))
        assertTrue(result is com.panhub.core.common.PanResult.Success)
        val value = (result as com.panhub.core.common.PanResult.Success).value
        assertEquals("new-value", value.renewed["__puus"])
    }
}
