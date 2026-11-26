package com.panhub.feature.trash

import com.panhub.core.model.RemoteNode
import com.panhub.core.model.SortOrder
import com.panhub.core.model.SortSpec
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

    /** 根目录没有名字，给它一个能读的说法。 */
    private const val ROOT_LABEL = "我的网盘"

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

    /**
     * 选择器里只留文件夹。
     *
     * 还原的目标只能是一个目录；把文件也列出来，用户点一下就会得到
     * 一句「这不是文件夹」，不如一开始就不给点。
     */
    fun folderOnly(nodes: List<RemoteNode>): List<RemoteNode> = nodes.filter { it.isDir }

    /** 选择器栈的一句话名字；空栈就是网盘根目录。 */
    fun restoreTargetLabel(stack: List<RemoteNode>): String {
        // 名字为空的那一层直接跳过：拼成「photos / 」比只显示 photos 更让人怀疑自己选错了。
        val name = stack.map { it.name.trim() }.filter { it.isNotEmpty() }.joinToString(" / ")
        return name.ifBlank { ROOT_LABEL }
    }

    /** 返回 N 项里成功 M 项时的说明。失败的把原因带上，别只说「失败」。 */
    fun resultMessage(done: Int, total: Int, action: String, error: String?): String =
        when {
            done == total -> "已" + action + " " + done + " 项"
            done == 0 -> (error ?: "没能" + action + "这 " + total + " 项")
            else -> "已" + action + " " + done + "/" + total + " 项" + (error?.let { " · " + it } ?: "")
        }
}


/**
 * 回收站的排序。
 *
 * 与浏览页、本机文件页共用同一套 SortSpec，但比较的东西不同：
 * 这里有「什么时候删的」和「什么时候过期」，没有文件夹在前的规则
 * （回收站里文件夹和文件是同等的一件东西）。名字比较忽略大小写，
 * 同值时按 id 兜底，保证同一个列表两次排出来顺序一致。
 */
fun List<TrashNode>.sortedTrash(spec: SortSpec): List<TrashNode> {
    if (size < 2) return this
    val byOrder = when (spec.order) {
        SortOrder.NAME ->
            compareBy<TrashNode> { it.name.lowercase() }.directed(spec.descending)
        SortOrder.SIZE ->
            compareBy<TrashNode> { it.size }.directed(spec.descending)
        // 没有删除时间的（老条目）永远排在最后，所以「有没有时间」这一段
        // 不能跟着方向一起反过来，否则从新到旧时这些条目会跑到最前面。
        SortOrder.TIME -> {
            val missing = compareBy<TrashNode> { it.deletedAt == null }
            val byTime = compareBy<TrashNode> { it.deletedAt ?: 0L }.directed(spec.descending)
            missing.then(byTime)
        }
    }
    // 同值不另设兜底键：sortedWith 是稳定排序，相等的两条保持服务端给的顺序，
    // 同一个列表两次排出来就是同一个顺序。
    return sortedWith(byOrder)
}

/** 按需反转。写成一个扩展是为了上面那张分支表能保持只描述「比什么」。 */
private fun <T> Comparator<T>.directed(descending: Boolean): Comparator<T> =
    if (descending) reversed() else this
