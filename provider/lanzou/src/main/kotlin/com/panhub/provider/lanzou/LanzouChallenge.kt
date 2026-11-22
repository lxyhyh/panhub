package com.panhub.provider.lanzou

/**
 * 蓝奏云分享页的 `acw_sc__v2` 人机校验。
 *
 * 分享页第一次请求往往不返回正文，而是一段 JS 挑战：页面里有 `arg1='...'`，
 * 需要用固定置换表打乱、再和一个固定密钥异或，得到的结果作为 `acw_sc__v2`
 * Cookie 重新请求才能拿到真正的内容。CDN 直链域名也会做同样的校验，
 * 所以下载前还要对直链做一次「预热」。
 *
 * 这段逻辑是纯函数，与网络无关，单独放一个文件便于用固定向量测试。
 */
internal object LanzouChallenge {

    /** 页面里出现它就说明撞上了挑战。 */
    const val MARKER = "acw_sc__v2"

    private val ARG = Regex("""arg1='([0-9A-Za-z]+)'""")

    private const val KEY = "3000176000856006061501533003690027800375"

    /** 置换表：第 j 个输出位置取自输入的哪个序号（从 1 开始）。 */
    private val ORDER = intArrayOf(
        15, 35, 29, 24, 33, 16, 1, 38, 10, 9,
        19, 31, 40, 27, 22, 23, 25, 13, 6, 11,
        39, 18, 20, 8, 14, 21, 32, 26, 2, 30,
        7, 4, 17, 5, 3, 28, 34, 37, 12, 36,
    )

    fun contains(html: String): Boolean = html.contains(MARKER)

    /** 从挑战页算出 cookie 值；页面上没有 `arg1` 或输入非法时返回 null。 */
    fun compute(html: String): String? {
        val arg = ARG.find(html)?.groupValues?.get(1) ?: return null
        if (arg.isEmpty()) return null
        return hexXor(unsbox(arg), KEY)
    }

    /**
     * 按置换表重排字符。长度不足 40 时尾部留空——必须保留空位，
     * 直接丢弃会让后续字符整体前移，算出来的 cookie 就是错的。
     */
    private fun unsbox(input: String): String {
        val slots = Array(ORDER.size) { "" }
        for (index in input.indices) {
            val wanted = index + 1
            for (j in ORDER.indices) {
                if (ORDER[j] == wanted) slots[j] = input[index].toString()
            }
        }
        return slots.joinToString("")
    }

    /** 两个十六进制串按字节异或，取较短的长度。 */
    private fun hexXor(a: String, b: String): String {
        val length = minOf(a.length, b.length)
        val out = StringBuilder(length / 2)
        var i = 0
        while (i + 1 < length) {
            val x = a.substring(i, i + 2).toIntOrNull(16) ?: return out.toString()
            val y = b.substring(i, i + 2).toIntOrNull(16) ?: return out.toString()
            out.append(((x xor y) and 0xFF).toString(16).padStart(2, '0'))
            i += 2
        }
        return out.toString()
    }
}
