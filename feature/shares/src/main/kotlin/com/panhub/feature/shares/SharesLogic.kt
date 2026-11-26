package com.panhub.feature.shares

import com.panhub.core.common.PanError
import com.panhub.core.model.SortOrder
import com.panhub.core.model.SortSpec

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

    /**
     * 排一次序。
     *
     * 「还能不能打开」永远是最外层的分界：失效的链接对谁都没用了，
     * 它按名字排到中间只会让人觉得列表是坏的。所以这里先按失效分层，
     * 层内再按用户选的那个依据排。
     */
    fun List<ShareRow>.sortedFor(spec: SortSpec): List<ShareRow> {
        val validity = compareBy<ShareRow> { it.link.expired }
        val by = when (spec.order) {
            SortOrder.NAME -> compareBy<ShareRow> { shareTitleOf(it.link).lowercase() }
            SortOrder.TIME -> compareBy<ShareRow> { it.link.createdAt ?: 0L }
            // 分享本身没有「多大」这件事：一条链接可能带着几百个文件，
            // 也可能一个都没带。所以 [SORT_ORDERS] 里根本不给这一项，
            // 这里的兜底只是为了 when 写全。
            SortOrder.SIZE -> compareBy<ShareRow> { 0L }
        }
        return sortedWith(validity.then(by.directed(spec.descending)))
    }

    private fun <T> Comparator<T>.directed(descending: Boolean): Comparator<T> =
        if (descending) reversed() else this

    /**
     * 这个页面能提供的排序依据。
     *
     * 只有名称和时间：分享记录里没有大小可用（[ShareLink] 只带节点 id 列表，
     * 列表长度是条目数不是字节数），硬编一个「按大小」只会排出一堆看不出规律的东西。
     */
    val SORT_ORDERS: List<SortOrder> = listOf(SortOrder.NAME, SortOrder.TIME)

    /** 选择条上的计数。 */
    fun selectionSummary(count: Int): String = "已选 " + count + " 条"
}
