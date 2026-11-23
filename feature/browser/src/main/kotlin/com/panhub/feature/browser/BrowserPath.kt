package com.panhub.feature.browser

import com.panhub.core.model.RemoteNode

/** 路径栏上的一格。depth 是导航栈里的下标，[ROOT_DEPTH] 表示网盘根目录。 */
data class PathCrumb(val label: String, val depth: Int)

/**
 * 浏览页顶部的路径栏。
 *
 * 网盘大多没有真正的路径，只有父子 id；能用来做路径栏的只有导航栈本身。
 * 这里只负责把栈翻成人能点的几格，不碰网络，所以能单测。
 */
object BrowserPath {

    /** 根目录用 -1 而不是 0：0 是栈里第一个文件夹的下标，两者不能混。 */
    const val ROOT_DEPTH: Int = -1

    private const val FALLBACK_ROOT = "我的网盘"

    /**
     * 顶层永远是账号名（空则退成「我的网盘」），之后是栈里的每一级文件夹。
     *
     * 名字为空白的文件夹照原样留一格，但不写字：去掉这一格会让路径断掉，
     * 用户点上一格时也会跳错层级。
     */
    fun crumbs(accountName: String, stack: List<RemoteNode>): List<PathCrumb> {
        val root = accountName.trim().ifEmpty { FALLBACK_ROOT }
        val items = ArrayList<PathCrumb>(stack.size + 1)
        items += PathCrumb(root, ROOT_DEPTH)
        stack.forEachIndexed { index, node ->
            items += PathCrumb(node.name.trim(), index)
        }
        return items
    }

    /** 当前所在的那一格：栈为空时是根目录。 */
    fun currentDepth(stack: List<RemoteNode>): Int = stack.size - 1

    /** 路径只有一层时没有可跳的地方，界面据此不显示这一栏。 */
    fun isJumpable(accountName: String, stack: List<RemoteNode>): Boolean = stack.isNotEmpty()
}
