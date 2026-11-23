package com.panhub.provider.quark

/**
 * 把用户粘进来的分享链接拆成「分享 id + 提取码」。
 *
 * 为什么要单独一个对象：用户在微信里复制到的链接长什么样都有 ——
 * 带不带 https、末尾有没有斜杠、提取码在 query 里还是手打、甚至只有一串 id。
 * 这些形态如果散落在 ViewModel 里判断，新增一个入口就要再写一遍，
 * 而且每种形态都能单独验证，没有理由不做成纯函数。
 */
internal object QuarkShareLink {

    /**
     * 解析结果。
     *
     * [passcode] 可能来自链接自带的 pwd 参数，也可能根本不存在 ——
     * 后者需要用户自己填，不能当成错误。
     */
    data class Parsed(val linkId: String, val passcode: String? = null)

    /**
     * 解析一条分享链接；解析不出来返回 null。
     *
     * 认得的形态：
     * - https://pan.quark.cn/s/abcdef?pwd=1234
     * - pan.quark.cn/s/abcdef
     * - /s/abcdef
     * - abcdef（用户只复制了 id）
     *
     * 不认得的形态（比如别人分享的是自己的文件页 /1/clouddrive/...）返回 null，
     * 让上层提示「这不是分享链接」，而不是拿一串垃圾去请求服务端。
     */
    fun parse(raw: String?): Parsed? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        val passcode = queryParam(text, "pwd")?.takeIf { it.isNotBlank() }

        val withoutScheme = text
            .removePrefix("https://")
            .removePrefix("http://")

        val path = withoutScheme.substringBefore('?').substringBefore('#')
        val marker = path.indexOf("/s/")
        if (marker >= 0) {
            val id = path.substring(marker + 3).trim('/')
            return id.takeIf { it.isNotEmpty() }?.let { Parsed(it, passcode) }
        }

        // 没有 /s/ 时只剩两种可能：一整条 URL（不是分享页）或者一个裸 id。
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
