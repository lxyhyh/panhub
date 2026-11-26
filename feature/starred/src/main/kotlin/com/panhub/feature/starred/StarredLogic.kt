package com.panhub.feature.starred

import com.panhub.core.model.RemoteNode

/**
 * 收藏夹页里不值得放进界面、但值得单独测的规则。
 *
 * 这些判断写错的表现都很像「功能坏了」：空列表说出了「没有收藏」，
 * 或者取消收藏之后行还在，用户以为自己点错了。
 */
object StarredLogic {

    /** 条目副标题：文件夹说文件夹，文件说大小。 */
    fun subtitle(node: RemoteNode, formatBytes: (Long) -> String): String =
        if (node.isDir) "文件夹" else formatBytes(node.size)

    /** 取消收藏之后的提示。用文件名而不是「已取消」，否则同时取消好几个时分不清。 */
    fun unstaredMessage(node: RemoteNode): String = "已取消收藏 " + node.name

    /** 一次取消好几个时的提示：逐个报名字在批量操作下只会糊成一片。 */
    fun unstaredMessage(count: Int): String = "已取消收藏 " + count + " 项"

    /**
     * 全选只选看得到的那些。
     *
     * 筛选着的时候把看不见的也一起取消掉，用户会以为收藏夹出了 bug——
     * 他并没有看见那些东西被选中。
     */
    fun allSelected(visible: List<RemoteNode>, selected: Set<String>): Boolean =
        visible.isNotEmpty() && visible.all { node -> node.id.value in selected }

    /**
     * 列表为空时说什么。
     *
     * 加载失败和「真的没有收藏」是两件事：前者重试可能就有了，
     * 后者再点多少次都是空的，所以文案必须分开。
     */
    fun emptyMessage(loading: Boolean, failed: Boolean): String? = when {
        // 失败排在加载之前：重试时 error 会被清掉，所以只要还留着 error
        // 就说明这一轮确实没成功，此时说「正在加载」会让用户以为还在等。
        failed -> "收藏夹没能加载出来"
        loading -> null
        else -> "这个网盘还没有收藏任何文件"
    }
}