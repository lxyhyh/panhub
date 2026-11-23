package com.panhub.feature.trash

import com.panhub.core.model.TrashNode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 回收站里的纯逻辑。
 *
 * 抽出来的理由和别的 feature 一样：这些规则错了不会崩，只会安静地
 * 显示一句错话（比如把「还有 3 天过期」写成「已过期」），只有单测盯得住。
 */
object TrashLogic {

    /** 不到一天就提示「今天过期」，比写「0 天后过期」好懂。 */
    const val URGENT_DAYS = 1L

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

    /**
     * 一行的副标题：删除时间，能算出过期时间时再加一句还有多久。
     *
     * 删除时间缺失（老条目没有 TrashedAt）就只说「删除时间未知」，
     * 不编一个时间出来。
     */
    fun subtitle(node: TrashNode, nowMillis: Long): String {
        val line = StringBuilder()
        val deletedAt = node.deletedAt
        line.append(if (deletedAt != null) "删除于 " + formatTime(deletedAt) else "删除时间未知")
        val left = daysLeft(node.expiresAt, nowMillis) ?: return line.toString()
        line.append(" · ")
        line.append(
            when {
                left <= 0L -> "已过期"
                left <= URGENT_DAYS -> "今天过期"
                else -> left.toString() + " 天后过期"
            },
        )
        return line.toString()
    }

    /**
     * 还剩几天过期，向上取整。
     *
     * 向上取整是因为差 3 小时到期时说「还有 1 天」是错的，
     * 说「0 天」更像已经没了。
     */
    fun daysLeft(expiresAt: Long?, nowMillis: Long): Long? {
        val deadline = expiresAt ?: return null
        val diff = deadline - nowMillis
        if (diff <= 0L) return 0L
        val days = diff / MILLIS_PER_DAY
        return if (diff % MILLIS_PER_DAY == 0L) days else days + 1
    }

    fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))

    /** 全选与取消全选共用一个按钮，看当前是不是全都选上了。 */
    fun allSelected(items: List<TrashNode>, selected: Set<String>): Boolean =
        items.isNotEmpty() && items.all { it.id.value in selected }

    /** 返回 N 项里成功 M 项时的说明。失败的把原因带上，别只说「失败」。 */
    fun resultMessage(done: Int, total: Int, action: String, error: String?): String =
        when {
            done == total -> "已" + action + " " + done + " 项"
            done == 0 -> (error ?: "没能" + action + "这 " + total + " 项")
            else -> "已" + action + " " + done + "/" + total + " 项" + (error?.let { " · " + it } ?: "")
        }
}
