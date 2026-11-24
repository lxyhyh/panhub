package com.panhub.feature.browser

import com.panhub.core.model.RemoteNode

/**
 * 上传前的同名检查。
 *
 * 各网盘对同名的处理并不一致：有的直接覆盖，有的自动改名，用户无从预期，
 * 而覆盖意味着原来那份文件消失。这一层不改服务商的行为，只负责在入队前
 * 把眼前的事实说清楚，让人自己决定要不要继续。
 *
 * 只比对当前目录已经加载出来的那一批。翻页尚未读到的部分看不见，
 * 所以界面上的话术只承诺「这个目录里能看到的部分」，不假装知道全部。
 */
internal object UploadConflicts {

    /**
     * 传进来的名字里，哪些在当前列表里已经有了。
     *
     * 严格按字符串比对，不忽略大小写：多数网盘里 `a.txt` 与 `A.txt` 是两个
     * 不同的文件，按忽略大小写比会把这种情况误判成冲突，于是每次上传都弹一次
     * 没必要的框，弹几次之后用户就学会闭着眼睛点「继续」了。
     */
    fun clashes(nodes: List<RemoteNode>, names: List<String>): List<String> {
        if (nodes.isEmpty() || names.isEmpty()) return emptyList()
        val existing = nodes.mapTo(HashSet()) { it.name }
        return names.filter { it in existing }
    }

    /** 确认框标题。带上前面的名字，用户一眼就知道是哪一批文件。 */
    fun title(clashes: List<String>): String = when (clashes.size) {
        0 -> "上传"
        1 -> "「" + clashes.first() + "」已经存在"
        else -> "有 " + clashes.size + " 个文件重名"
    }

    /**
     * 确认框正文。
     *
     * 名字列到三个为止：再长也只是重复「它们都重名」这一件事，
     * 而列表一长就会把下面那句真正重要的说明挤出视线。
     */
    fun question(clashes: List<String>): String {
        if (clashes.isEmpty()) return ""
        val listed = clashes.take(LISTED).joinToString("、")
        val head = if (clashes.size <= LISTED) {
            "当前目录里已经有 " + listed + "。"
        } else {
            "当前目录里已经有 " + listed + " 等 " + clashes.size + " 个同名文件。"
        }
        return head + "继续上传后，有的网盘会覆盖旧文件，有的会自动改名，由网盘自己决定。"
    }

    /** 确认按钮。写「仍然上传」而不是「确定」：这是个会丢数据的动作。 */
    const val CONFIRM_LABEL: String = "仍然上传"

    /** 取消按钮。 */
    const val DISMISS_LABEL: String = "先不上传"

    private const val LISTED = 3
}
