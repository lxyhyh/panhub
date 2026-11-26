package com.panhub.feature.shares

import com.panhub.core.common.PanError

/**
 * 我的分享页里那些「说给人听」的判断。
 *
 * 抽在这里不是为了复用，是为了能在纯 JVM 单测里钉住：
 * 批量取消时哪几条算失败、失败之后列表该留下什么、消息怎么写。
 */
internal object SharesLogic {

    /**
     * 取消分享的结果汇总。
     *
     * 部分失败是最需要说清楚的情况：用户点了一次「取消」，
     * 屏幕上少了 5 条，剩下的那 2 条为什么还在，必须当场讲明白。
     */
    fun cancelledMessage(done: Int, total: Int, error: PanError?): String = when {
        total <= 0 -> "没有要取消的分享"
        done <= 0 -> error?.message ?: "没能取消分享"
        done < total -> "已取消 " + done + "/" + total + " 项"
        total == 1 -> "已取消分享"
        else -> "已取消 " + total + " 条分享"
    }

    /** 单条取消的确认问句。标题可能为空，退化成链接尾段，和卡片上看到的一致。 */
    fun cancelTitle(row: ShareRow): String = "取消「" + shareTitleOf(row.link) + "」？"

    /** 批量取消的确认标题。 */
    fun cancelTitle(rows: List<ShareRow>): String = when (rows.size) {
        0 -> "取消分享？"
        1 -> cancelTitle(rows.first())
        else -> "取消这 " + rows.size + " 条分享？"
    }

    /** 确认框正文。批量时点明「一起失效」这件事，单条时说明后果。 */
    fun cancelQuestion(count: Int): String = when {
        count <= 1 -> "取消后链接立刻失效，对方将无法再打开。已经保存到手机上的文件不受影响。"
        else -> "这 " + count + " 条链接会一起失效，对方将无法再打开。" +
            "已经保存到手机上的文件不受影响。"
    }

    /** 确认按钮的字。数量多时写清楚要取消几条，让人知道自己按下去会发生什么。 */
    fun cancelConfirmLabel(count: Int): String =
        if (count <= 1) "取消分享" else "取消 " + count + " 条"

    /**
     * 全选是否成立。
     *
     * 只看筛出来的那几条：勾了「全选」之后又补了关键字，
     * 屏幕上明明只显示两条却因为列表里还有第三条而显示未全选，会让人以为操作没生效。
     */
    fun allSelected(visible: List<ShareRow>, selected: Set<String>): Boolean =
        visible.isNotEmpty() && visible.all { row -> row.key in selected }

    /** 全选 / 取消全选。只作用于看得见的那些，不碰被关键字藏起来的。 */
    fun toggleAll(visible: List<ShareRow>, selected: Set<String>): Set<String> =
        if (allSelected(visible, selected)) {
            selected - visible.mapTo(mutableSetOf()) { row -> row.key }
        } else {
            selected + visible.map { row -> row.key }
        }

    /** 从整份列表里挑出被勾中的那些，顺序跟着列表走，不跟着点击顺序走。 */
    fun selectedRows(rows: List<ShareRow>, selected: Set<String>): List<ShareRow> =
        rows.filter { row -> row.key in selected }

    /**
     * 取消之后列表变成什么样。
     *
     * 只摘掉真的取消成功的那几条：失败的留在原地，用户还能再点一次；
     * 全部清空会让人误以为都成功了。
     */
    fun withoutCancelled(rows: List<ShareRow>, cancelled: Set<String>): List<ShareRow> =
        rows.filterNot { row -> row.key in cancelled }

    /** 选择条上的计数。 */
    fun selectionSummary(count: Int): String = "已选 " + count + " 条"
}
