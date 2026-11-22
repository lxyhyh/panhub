package com.panhub.core.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieStoreTest {

    @Test
    fun `save then load for same host`() {
        val store = CookieStore()
        val url = "https://api.example.com/a".toHttpUrl()
        val cookie = Cookie.Builder().name("k").value("v").domain("api.example.com").path("/").build()
        store.saveFromResponse(url, listOf(cookie))

        val loaded = store.loadForRequest(url)
        assertEquals(1, loaded.size)
        assertEquals("k", loaded[0].name)
        assertEquals("v", loaded[0].value)
        assertTrue(store.exportHeader().contains("k=v"))
    }

    @Test
    fun `different host does not leak`() {
        val store = CookieStore()
        val a = "https://a.example.com/".toHttpUrl()
        val b = "https://b.example.com/".toHttpUrl()
        store.saveFromResponse(a, listOf(
            Cookie.Builder().name("x").value("1").domain("a.example.com").path("/").build()
        ))
        assertEquals(0, store.loadForRequest(b).size)
    }
}