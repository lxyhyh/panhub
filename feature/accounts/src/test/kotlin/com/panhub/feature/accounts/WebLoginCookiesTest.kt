package com.panhub.feature.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebView 登录判定「登录成功」的 Cookie 键。
 * 键错了会把半截 Cookie 拿去验证，白白失败一次；
 * 这个表钉住的是官方登录页实际写入的键。
 */
class WebLoginCookiesTest {

    @Test
    fun quarkNeedsKpsAndPpu() {
        assertEquals(listOf("kps", "ppu"), webLoginCookiesOf("quark"))
    }

    @Test
    fun ucSharesQuarkKeys() {
        // UC 与夸克同一套 CAS（client_id 不同），Cookie 键相同。
        assertEquals(webLoginCookiesOf("quark"), webLoginCookiesOf("uc"))
    }

    @Test
    fun baiduNeedsBdussOnly() {
        // STOKEN 是辅助键，官方 App 只认 BDUSS；把它作为硬条件会白挡一次。
        assertEquals(listOf("BDUSS"), webLoginCookiesOf("baidu"))
    }

    @Test
    fun providersWithoutWebLoginGetEmptyList() {
        assertTrue(webLoginCookiesOf("pan123").isEmpty())
        assertTrue(webLoginCookiesOf("nutstore").isEmpty())
        assertTrue(webLoginCookiesOf("lanzou").isEmpty())
        assertTrue(webLoginCookiesOf("cloud189").isEmpty())
    }
}