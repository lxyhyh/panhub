package com.panhub.provider.baidu

/**
 * 百度网盘分享链接的解析。
 *
 * 百度有三套链接形态，全都指向同一个 surl：
 * - `https://pan.baidu.com/s/1pJ0ok7T`  （最常见的短链，surl 自带首位 1）
 * - `https://pan.baidu.com/share/init?surl=pJ0ok7T`  （旧版初始化页，surl 不带首位 1）
 * - 用户直接粘 `1pJ0ok7T`
 *
 * 两种接口对 surl 的写法要求**正好相反**，这是实测踩出来的：
 * - `/api/shorturlinfo` 要**带**首位 1（传去掉 1 的版本一律 errno 2）；
 * - `/share/verify` 要**去掉**首位 1（传完整 surl 一律 errno 105）。
 *
 * 所以这里统一把 surl 归一成「带首位 1 的完整形态」保存，
 * 需要短形态时由 [shortForm] 现切，避免两处各自记得不一样。
 */
internal object BaiduShareLink {

    /** [surl] 是带首位 1 的完整形态。 */
    data class Parsed(val surl: String, val passcode: String? = null)

    fun parse(raw: String?): Parsed? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            val afterScheme = text.substringAfter("://")
            val host = afterScheme.substringBefore('/').substringBefore('?').lowercase()
            // 别的网盘的链接也常带 /s/<id>，不校验域名会把 123 的分享当成百度的。
            if (!isBaiduHost(host)) return null
            val pathAndQuery = afterScheme.substringAfter('/', "")
            val path = pathAndQuery.substringBefore('?')
            val query = pathAndQuery.substringAfter('?', "")
            val fromPath = surlFromPath(path)
            val fromQuery = params(query)["surl"]
            val surl = fromPath ?: fromQuery ?: return null
            return Parsed(normalize(surl), params(query).passcode())
        }
        // 不是链接就当成裸 surl。只认 ASCII 字母数字：别的网盘链接、
        // 中文标题、带路径的串都不该被当成百度分享，认错了用户会看到莫名其妙的报错。
        if (!text.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) return null
        return Parsed(normalize(text), null)
    }

    /** 带首位 1 的完整形态。 */
    fun normalize(surl: String): String {
        val trimmed = surl.trim()
        return if (trimmed.startsWith("1")) trimmed else "1" + trimmed
    }

    /** 去掉首位 1 的短形态，`/share/verify` 只认这个。 */
    fun shortForm(surl: String): String {
        val full = normalize(surl)
        return if (full.length > 1) full.substring(1) else full
    }

    private fun isBaiduHost(host: String): Boolean {
        val bare = host.substringBefore(':')
        return bare == "pan.baidu.com" || bare == "yun.baidu.com" ||
            bare == "eyun.baidu.com" || bare.endsWith(".baidu.com")
    }

    /** 路径里 `/s/<surl>` 的那一段。 */
    private fun surlFromPath(path: String): String? {
        val segments = path.split('/').filter { it.isNotEmpty() }
        val index = segments.indexOf("s")
        if (index >= 0 && index + 1 < segments.size) return segments[index + 1]
        return null
    }

    private fun params(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val key = pair.substringBefore('=')
            if (key.isEmpty()) return@mapNotNull null
            key to pair.substringAfter('=', "")
        }.toMap()
    }

    private fun Map<String, String>.passcode(): String? =
        (this["pwd"] ?: this["password"] ?: this["code"])?.trim()?.takeIf { it.isNotEmpty() }
}
