package com.panhub.provider.lanzou

import okhttp3.Headers
import java.util.concurrent.ConcurrentHashMap

/**
 * 蓝奏云的 Cookie 要跨越三种域名：接口域名（登录态）、分享页域名、CDN 直链域名。
 *
 * 为什么不用 OkHttp 自带的 CookieJar：人机校验值 `acw_sc__v2` 不是服务端
 * 用 Set-Cookie 下发的，而是由页面里的 JS 算出来的，必须能手动写进去；
 * 而它又是**按域名生效**的，写进全局桶会被发往接口域名并干扰正常请求。
 *
 * 所以这里分两层：
 * - [globals]：登录态（ylogin / phpdisk_info），发给所有请求；
 * - [buckets]：按域名存放的人机校验值，只发给对应域名。
 */
internal class LanzouCookie(initial: String? = null) {

    private val globals = ConcurrentHashMap<String, String>()
    private val buckets = ConcurrentHashMap<String, MutableMap<String, String>>()

    init {
        if (!initial.isNullOrBlank()) fill(initial)
    }

    /** 清空后重新装载，用于切换到另一个账号的会话。 */
    fun clear() {
        globals.clear()
        buckets.clear()
    }

    /** 把 `a=1; b=2` 形式的字符串装进全局桶。 */
    fun fill(header: String) {
        header.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { pair ->
            val index = pair.indexOf('=')
            if (index > 0) {
                val name = pair.substring(0, index).trim()
                // 校验值是按域名的，混进登录态会污染所有请求
                if (name != LanzouChallenge.MARKER) {
                    globals[name] = pair.substring(index + 1).trim()
                }
            }
        }
    }

    fun put(name: String, value: String) {
        globals[name] = value
    }

    /** 把某个域名专属的 Cookie 存起来（目前只有人机校验值走这条路）。 */
    fun putForHost(host: String, name: String, value: String) {
        buckets.getOrPut(host) { ConcurrentHashMap() }[name] = value
    }

    /** 保存服务端 Set-Cookie 里属于登录态的那些。 */
    fun save(headers: Headers) {
        headers.values("Set-Cookie").forEach { line ->
            val pair = line.substringBefore(';')
            val index = pair.indexOf('=')
            if (index <= 0) return@forEach
            val name = pair.substring(0, index).trim()
            if (name == LanzouChallenge.MARKER) return@forEach
            globals[name] = pair.substring(index + 1).trim()
        }
    }

    /** 导出成可持久化的字符串。人机校验值会过期，不能存。 */
    fun export(): String = globals.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** 生成某次请求的 Cookie 头；空的就返回 null，避免发一个无意义的空头。 */
    fun header(host: String? = null): String? {
        val parts = mutableListOf<String>()
        globals.forEach { (k, v) -> if (k != LanzouChallenge.MARKER) parts += "$k=$v" }
        if (host != null) {
            buckets[host]?.forEach { (k, v) -> parts += "$k=$v" }
        }
        return if (parts.isEmpty()) null else parts.joinToString("; ")
    }

    /** uid（`ylogin`），部分 task 需要它作为 query 参数。 */
    fun uid(): String? = globals["ylogin"]
}
