package com.panhub.provider.pan123

/**
 * 把用户粘进来的 123 云盘分享链接拆成「shareKey + 提取码」。
 *
 * 为什么单独一个对象：分享链接的域名有一堆历史写法（123pan.com / 123pan.cn /
 * 123865.com / 123912.com / 123684.com），提取码有时在 query 里、有时要用户手打，
 * 还有人只复制了一串 shareKey。这些形态散落在 ViewModel 里判断，
 * 每加一个入口就要再写一遍，也没法单独验证。
 */
internal object Pan123ShareLink {

    /** [passcode] 可能来自链接的 pwd 参数，也可能根本不存在 —— 后者要用户自己填。 */
    data class Parsed(val shareKey: String, val passcode: String? = null)

    /**
     * 解析一条分享链接；解析不出来返回 null。
     *
     * 认得的形态：
     * - https://www.123pan.cn/s/abcde-fghij?pwd=1234
     * - www.123pan.com/s/abcde-fghij
     * - /s/abcde-fghij
     * - abcde-fghij（用户只复制了 shareKey）
     *
     * 不认得的是「自己网盘里的文件页」这类地址，返回 null，
     * 让上层提示「这不是分享链接」，而不是拿一串垃圾去请求服务端。
     */
    fun parse(raw: String?): Parsed? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        val passcode = queryParam(text, "pwd")?.takeIf { it.isNotBlank() }
            ?: queryParam(text, "password")?.takeIf { it.isNotBlank() }

        val withoutScheme = text
            .removePrefix("https://")
            .removePrefix("http://")

        val path = withoutScheme.substringBefore('?').substringBefore('#')
        val marker = path.indexOf("/s/")
        if (marker >= 0) {
            val key = path.substring(marker + 3).trim('/')
            return key.takeIf { it.isNotEmpty() }?.let { Parsed(it, passcode) }
        }

        // 没有 /s/ 时只剩两种可能：一整条 URL（不是分享页）或者一个裸 shareKey。
        if (path.contains('/') || path.contains('.')) return null
        return path.takeIf { it.isNotEmpty() }?.let { Parsed(it, passcode) }
    }

    /** 从 URL 里取某个 query 参数；没有就返回 null。 */
    private fun queryParam(url: String, name: String): String? {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return null
        return query.split('&')
            .map { it.trim() }
            .firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
    }
}
