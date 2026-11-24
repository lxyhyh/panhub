package com.panhub.feature.browser

import com.panhub.core.model.RemoteNode

/**
 * 删除前那一问的措辞。
 *
 * 抽成纯函数是为了能被 JVM 单测覆盖：这段话是用户在下手前看到的唯一信息，
 * 说错一个字后果就不一样——「永久删除」和「移入回收站」是两件完全不同的
 * 事，界面上没写清楚，用户就会以为自己随时能捞回来。
 */
object DeleteConfirm {

    /** 一句话说清这次要删什么：一个写名字，多个写数量。 */
    fun subject(nodes: List<RemoteNode>): String = when (nodes.size) {
        0 -> ""
        1 -> "「" + nodes.first().name + "」"
        else -> "这 " + nodes.size + " 项"
    }

    fun title(nodes: List<RemoteNode>): String = "删除" + subject(nodes) + "？"

    /**
     * 正文。
     *
     * [toTrash] 来自服务商声明的回收站能力（capabilities.trash）。
     * 只有它确实会把东西放进回收站时才敢这么承诺：多数网盘没有回收站，
     * 删掉就是删掉，这时候含糊其辞等于骗人。
     */
    fun question(nodes: List<RemoteNode>, toTrash: Boolean): String {
        val folder = nodes.any { it.isDir }
        return buildString {
            if (toTrash) {
                append("会放进网盘的回收站，之后可以在回收站里还原。")
            } else {
                append("这个网盘没有回收站，删掉之后找不回来。")
            }
            if (folder) {
                append("文件夹里的内容会一起处理。")
            }
        }
    }

    /** 确认按钮上的字：做的动作不同，写法也不能一样。 */
    fun confirmLabel(toTrash: Boolean): String = if (toTrash) "移入回收站" else "永久删除"

    /** 删除完成之后给用户看的一句。 */
    fun doneMessage(count: Int, toTrash: Boolean): String = when {
        count <= 0 -> "没有删除任何内容"
        toTrash -> "已移入回收站 " + count + " 项"
        count == 1 -> "已删除 1 项"
        else -> "已删除 " + count + " 项"
    }
}

