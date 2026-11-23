package com.panhub.core.model

/**
 * 用户给账号起的备注名。
 *
 * 为什么要单独一个对象：备注名是**用户输入**，而用户输入什么都会发生——
 * 粘贴一整段带换行的文字、敲一串空格、起一个 200 字的标题。
 * 判断规则只能有一处，否则「界面收下了、存储层又改了一遍」这种分裂迟早出现。
 *
 * 规则只有三条：
 * - 只留第一行（粘贴多行文本时不是想要全都要）；
 * - 去掉首尾空白，中间连续空白压成一个空格；
 * - 超过 [MAX_LENGTH] 截断。
 *
 * 清空 = 没有备注名（返回 null），这样「清空备注」与「从未起过备注」
 * 在数据上是同一件事，界面不用分两种情况处理。
 */
object AccountNames {

    /** 备注名最长多少个字符。够放一句「公司备份盘」，又不会把账号行撑爆。 */
    const val MAX_LENGTH: Int = 24

    /** 把用户输入的备注名归一化成可存储的形式；没有内容时返回 null。 */
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        val firstLine = raw.lineSequence().firstOrNull().orEmpty()
        val squeezed = firstLine.trim().replace(WHITESPACE_RUN, " ")
        if (squeezed.isEmpty()) return null
        return squeezed.take(MAX_LENGTH)
    }

    private val WHITESPACE_RUN = Regex("\\s+")
}
