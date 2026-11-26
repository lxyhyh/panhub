package com.panhub.core.model

/**
 * 新建文件夹、重命名时对名字的检查。
 *
 * 各家网盘的非法字符并不完全一样，但只有一个交集是所有人都拒的：斜杠与
 * 半角冒号、星号、问号、双引号、尖括号、竖线。与其逐家维护一张表，不如先挡住
 * 这个交集——剩下的交给服务端，失败时把它的原话显示出来。
 *
 * 检查放在数据层而不是界面层，是因为「能不能用」这个判断和显示哪一行小字
 * 是两件事：前者要在真正发请求之前拦一次，后者只是把理由摆出来。
 */
object NodeNames {

    /** 名字长度上限。255 是主流文件系统的上限，网盘基本照抄。 */
    const val MAX_LENGTH: Int = 255

    /** 各家里最常见的非法字符。斜杠单独处理，因为它的说法不一样。 */
    private const val RESERVED = ":*?\"<>|"

    /**
     * 这个能不能用。能用返回 null，不能用返回一句可以直接显示给用户的话。
     *
     * 为什么返回文案而不是枚举：这句话最终要塞进输入框下面那行小字，
     * 让调用方各自翻译一遍只会得到几种口径不一的说法。
     */
    fun problem(raw: String): String? {
        if (raw.isEmpty()) return "名字不能为空"
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "名字不能只有空格"
        if (trimmed != raw) return "名字前后不能有空格"
        if (trimmed == "." || trimmed == "..") return "「.」和「..」不能作为名字"
        if (trimmed.contains('/') || trimmed.contains('\\')) {
            return "名字里不能有斜杠，那是目录分隔符"
        }
        if (trimmed.any { it in RESERVED }) {
            return "名字里不能有 : * ? \" < > |"
        }
        // 控制字符在界面上看不出来：复制一段带换行的文字进来，输入框里
        // 只显示成一行，发上去却是一堆看不见的东西。
        if (trimmed.any { it < ' ' || it == '\u007f' }) return "名字里有看不见的控制字符"
        if (trimmed.length > MAX_LENGTH) return "名字最多 " + MAX_LENGTH + " 个字符"
        return null
    }

    /** 能用就返回整理好的名字（已去掉首尾空格），不能用返回 null。 */
    fun accept(raw: String): String? = if (problem(raw) == null) raw.trim() else null
}
