package com.panhub.core.transfer

import com.panhub.core.model.SortOrder
import com.panhub.core.model.SortSpec

/**
 * 按 [spec] 排列本机下载记录。
 *
 * 与网盘列表用的是同一套 [SortSpec]，但规则不能照搬：
 * 网盘列表里「文件夹永远在前」，本机下载记录全是文件，分层只体现在相对路径里；
 * 网盘里没有修改时间的节点要排到最后，本机则是 [DownloadedEntry.modifiedAt] 为 0
 * （系统读不到时间），同样不该当成 1970 年混在真实时间中间。
 *
 * 方向只作用于主键；同值时按相对路径兜底，保证同一份磁盘内容每次排出同样的顺序——
 * 顺序会变的话，删除文件之后列表里剩下的条目会莫名其妙地跳位置。
 */
fun List<DownloadedEntry>.sortedFor(spec: SortSpec): List<DownloadedEntry> {
    val tieBreak = compareBy<DownloadedEntry> { it.relativePath }
    return when (spec.order) {
        SortOrder.NAME -> {
            val byName = compareBy<DownloadedEntry> { it.name.lowercase() }
            sortedWith(byName.directed(spec.descending).then(tieBreak))
        }

        SortOrder.TIME -> {
            val missing = compareBy<DownloadedEntry> { it.modifiedAt <= 0L }
            val byTime = compareBy<DownloadedEntry> { it.modifiedAt }
            sortedWith(missing.then(byTime.directed(spec.descending)).then(tieBreak))
        }

        SortOrder.SIZE -> {
            val bySize = compareBy<DownloadedEntry> { it.size }
            sortedWith(bySize.directed(spec.descending).then(tieBreak))
        }
    }
}

private fun <T> Comparator<T>.directed(descending: Boolean): Comparator<T> =
    if (descending) reversed() else this
