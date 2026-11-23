package com.panhub.feature.shareimport

/**
 * 从别的应用分享过来的一条链接。
 *
 * [passcode] 为空表示文本里没写提取码；有些链接会把它塞在 query 里（?pwd=1234），
 * 这种情况会先被剥出来，免得打开分享时还要用户自己再填一次。
 */
data class SharedLink(val url: String, val passcode: String? = null)

/**
 * 从系统分享菜单递进来的那段文字里挑出网盘链接。
 *
 * 别的应用分享过来的从来不是一条干净的链接，而是
 * 「链接: https://pan.quark.cn/s/xxxx 提取码: a1b2 复制这段内容后打开手机网盘」。
 * 让用户先粘进输入框再自己删掉前后废话，是很笨的一步。
 *
 * 这里刻意只做文本层面的提取，不认识任何具体服务商：
 * 链接能不能打开由对应的 provider 决定，解析器认不出就原样传给 provider 去判。
 */
object SharedText {

    /**
     * 找不到 http(s) 链接时返回 null。
     *
     * 返回 null 而不是抛异常：用户分享过来的可能是一段纯文字，
     * 那不是错误，只是没有可打开的东西。
     */
    fun extract(text: String?): SharedLink? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val start = indexOfScheme(raw) ?: return null
        val end = urlEnd(raw, start)
        if (end <= start) return null
        val url = trimUrlEnd(raw.substring(start, end))
        // 「http://」之后什么都没剩，说明只是文本里提了一嘴协议名。
        if (url.length <= SHORTEST_URL) return null
        return SharedLink(url = url, passcode = passcodeOf(raw, url))
    }

    /**
     * 找到第一个 http:// 或 https:// 的位置。
     *
     * 两种情况都要比：短链接文本里同时出现两者的可能性极低，
     * 但 appending 顺序不确定，取更靠前的那个才是「第一个链接」。
     */
    private fun indexOfScheme(text: String): Int? {
        val https = text.indexOf(HTTPS)
        val http = text.indexOf(HTTP)
        return when {
            https < 0 -> http.takeIf { it >= 0 }
            http < 0 -> https
            else -> minOf(https, http)
        }
    }

    /** 链接到第一个空白或标点为止；中文分享文本里链接后面通常直接跟空格。 */
    private fun urlEnd(text: String, start: Int): Int {
        var index = start
        while (index < text.length) {
            val ch = text[index]
            if (ch.isWhitespace() || ch in STOP_CHARS) break
            index++
        }
        return index
    }

    /**
     * 去掉结尾的英文标点。
     *
     * 「…打开 https://pan.baidu.com/s/1abc.」里的句号属于句子，不属于链接；
     * 留着它请求会 404，而用户看不出哪里错了。
     */
    private fun trimUrlEnd(url: String): String {
        var end = url.length
        while (end > 0 && url[end - 1] in TRAILING) end--
        return url.substring(0, end)
    }

    /**
     * 先看链接自带的 query，再看正文里的「提取码：xxxx」。
     *
     * 顺序不能反：链接里的提取码是分享者设的那个，正文里的可能是
     * 转发的人手写错的。以服务端生成的那份为准更可靠。
     */
    private fun passcodeOf(text: String, url: String): String? {
        QUERY_CODE.find(url)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        CODE_IN_TEXT.find(text)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return null
    }

    private const val HTTP = "http://"
    private const val HTTPS = "https://"

    /** 比「https://」只多一个字符的链接不可能是真的。 */
    private const val SHORTEST_URL = 8

    /**
     * 链接止于这些字符。
     *
     * 中文字符是全角的，用户复制时外面常带括号书名号；
     * 半角的 < > " ' 一定不是 URL 的一部分（会被转义成 %3C 之类）。
     */
    private const val STOP_CHARS = "\u3000，。、；：！？）（【】《》「」『』“”‘’…—<>\"'"

    private const val TRAILING = ".,;:!?"

    /** 链接自带的提取码：?pwd=a1b2 / &password=a1b2 / ?code=a1b2。 */
    private val QUERY_CODE = Regex("[?&](?:pwd|password|passcode|code)=([A-Za-z0-9]{2,16})")

    /** 正文里的提取码：提取码/密码/访问码，或英文的 pwd/passcode/code。 */
    private val CODE_IN_TEXT =
        Regex("(?:提取码|提取碼|密码|密碼|访问码|passcode|pwd|code)[\\s:：=]*([A-Za-z0-9]{4,8})")
}
