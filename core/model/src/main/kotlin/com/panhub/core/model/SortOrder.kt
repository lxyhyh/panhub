package com.panhub.core.model

/**
 * 列表排序依据。
 *
 * 只给出用户真正用来找文件的三件事：名字、修改时间、大小。
 * 类型、完整路径这类字段在网盘里几乎没用——目录层级本身已经把路径说清楚了。
 *
 * [defaultDescending] 是「刚选到这个依据时用哪个方向」，不是「必须用哪个方向」。
 * 按名字找文件是从 A 开始往下看，按时间和大小找是先看最新或最大的，
 * 所以方向不该对所有依据都是同一个固定值。
 */
enum class SortOrder(val label: String, val defaultDescending: Boolean) {
    NAME("名称", false),
    TIME("修改时间", true),
    SIZE("大小", true),
}

/**
 * 排序规格：依据 + 方向。
 *
 * 拆成两个字段而不是穷举成六个枚举值：界面上就是一个「按什么排」加一个「反过来」，
 * 新增依据时不用再补两个方向。
 */
data class SortSpec(
    val order: SortOrder = SortOrder.NAME,
    val descending: Boolean = false,
)

/**
 * 按 [spec] 排列节点。
 *
 * 文件夹永远在前：网盘里最常见的动作是「进目录」，把文件夹混在文件里
 * 按大小或时间排，每次都要先扫一遍列表去找目录。
 *
 * 没有修改时间的节点排在最后，而不是当成 0 参与比较——有些服务商不返回
 * 这个字段，按时间排序时让它们冒出一堆「1970 年修改」比排不上更糟。
 */
fun List<RemoteNode>.sortedFor(spec: SortSpec): List<RemoteNode> {
    val foldersFirst = compareByDescending<RemoteNode> { it.isDir }
    return when (spec.order) {
        SortOrder.NAME -> {
            val byName = compareBy<RemoteNode> { it.name.lowercase() }
            sortedWith(foldersFirst.then(byName.directed(spec.descending)))
        }

        SortOrder.TIME -> {
            val missing = compareBy<RemoteNode> { it.modifiedAt == null }
            val byTime = compareBy<RemoteNode> { it.modifiedAt ?: 0L }
            sortedWith(foldersFirst.then(missing).then(byTime.directed(spec.descending)))
        }

        SortOrder.SIZE -> {
            val bySize = compareBy<RemoteNode> { it.size }
            sortedWith(foldersFirst.then(bySize.directed(spec.descending)))
        }
    }
}

private fun <T> Comparator<T>.directed(descending: Boolean): Comparator<T> =
    if (descending) reversed() else this
