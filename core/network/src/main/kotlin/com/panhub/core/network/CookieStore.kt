package com.panhub.core.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * 内存版 CookieJar，支持把 Cookie 导出成字符串持久化。
 * 123 云盘、夸克这类靠 Cookie 鉴权的服务商依赖它。
 */
class CookieStore(
    private val initial: String? = null,
) : CookieJar {

    private val buckets = ConcurrentHashMap<String, MutableList<Cookie>>()

    init {
        if (!initial.isNullOrBlank()) loadFromHeader(initial)
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val list = buckets.getOrPut(url.host) { mutableListOf() }
        synchronized(list) {
            cookies.forEach { cookie ->
                list.removeAll { it.name == cookie.name }
                list.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val list = buckets[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        return synchronized(list) {
            list.removeAll { it.expiresAt < now }
            list.filter { it.matches(url) }
        }
    }

    /** 导出成 `a=1; b=2` 形式，便于存进加密存储。 */
    fun exportHeader(): String =
        buckets.values.flatMap { it.toList() }.joinToString("; ") { "${it.name}=${it.value}" }

    private fun loadFromHeader(header: String) {
        header.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@forEach
            val cookie = Cookie.Builder()
                .name(pair.substring(0, idx).trim())
                .value(pair.substring(idx + 1).trim())
                .domain("localhost")
                .path("/")
                .build()
            buckets.getOrPut("localhost") { mutableListOf() }.add(cookie)
        }
    }
}
