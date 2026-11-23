package com.panhub.provider.pan123

/**
 * 123 云盘的「网线上的约定」：query 编码与翻页游标。
 *
 * 这两件事在列目录、分享转存、搜索三处都要用，而且都踩过只在真机上暴露的坑，
 * 所以收在一处、单独测住，而不是各自复制一份。
 */
internal object Pan123Wire {

    /** 列表接口的终止游标。 */
    const val NO_MORE = "-1"

    /** 搜索接口的另一个终止游标，网页端原文 next:"-1"!==s&&"0"!==s&&s。 */
    const val EMPTY_CURSOR = "0"

    private val HEX = "0123456789ABCDEF".toCharArray()

    /**
     * 手写 URL 编码：只放行 ASCII 未保留字符，其余按 UTF-8 百分号编码。
     *
     * 不用 URLEncoder：它把空格编成加号，而 123 的服务端把这个加号当成
     * 字面量，于是「我的 报告.txt」这种带空格的关键字永远搜不到东西。
     */
    fun encode(value: String): String {
        val out = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val ch = byte.toInt() and 0xFF
            val c = ch.toChar()
            if (c.isLetterOrDigit() && ch < 0x80 || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append(c)
            } else {
                out.append('%')
                out.append(HEX[ch shr 4])
                out.append(HEX[ch and 0x0F])
            }
        }
        return out.toString()
    }

    /**
     * 把响应的 Next 转成游标，没有下一页时返回 null。
     *
     * 终止值有两个：列表接口用 "-1"，搜索接口网页端同时判 "-1" 与 "0"。
     * 把这两个值当成游标传回去，界面会一直显示「加载更多」，点一次拿回
     * 同一批数据，看起来像是翻页坏了。
     */
    fun nextCursor(raw: String?): String? =
        raw?.takeIf { it.isNotBlank() && it != NO_MORE && it != EMPTY_CURSOR }
}
