package com.panhub.core.model

/**
 * 多选时已选项目的数量与大小。
 *
 * 文件夹在列表里不带内容大小（要递归下去才知道），所以这里的 [bytes] 只覆盖文件，
 * 选了几个文件夹单独计数——把文件夹当 0 混进总和，会让人以为「这些加起来就这点」。
 */
data class SelectionSize(
    val items: Int = 0,
    val files: Int = 0,
    val folders: Int = 0,
    /** 已知大小的文件加起来的总字节数。 */
    val bytes: Long = 0L,
    /** 服务商没给大小（报 0）的文件个数。 */
    val unknownFiles: Int = 0,
) {
    val isEmpty: Boolean get() = items <= 0

    /** 有没有值得说一句的大小。一个文件都没报大小时说「0 B」是错的。 */
    val hasBytes: Boolean get() = bytes > 0L
}

/**
 * 多选的计数与体积。
 *
 * 与界面分开：这是纯算术，算得对不对和用哪个字号显示是两件事。
 */
object SelectionSizes {

    /** 数一遍这批选中项。空列表得到空结果，调用方不必先判空。 */
    fun of(nodes: List<RemoteNode>): SelectionSize {
        if (nodes.isEmpty()) return SelectionSize()
        var files = 0
        var folders = 0
        var bytes = 0L
        var unknown = 0
        for (node in nodes) {
            if (node.isDir) {
                folders++
                continue
            }
            files++
            if (node.size > 0L) bytes += node.size else unknown++
        }
        return SelectionSize(
            items = nodes.size,
            files = files,
            folders = folders,
            bytes = bytes,
            unknownFiles = unknown,
        )
    }

    /**
     * 顶栏第二行：已知大小加上必要的说明。
     *
     * 没有任何大小可说时返回 null，调用方据此不画这一行——只选了文件夹，或者
     * 服务商一个大小都没报的时候，写「0 B」会让人以为选中的东西是空的。
     *
     * 格式化函数由调用方传进来：core:model 不认识界面层，而 1024 还是 1000、
     * 保留几位小数这些规则必须整个 App 只有一份（[com.panhub.core.design.formatBytes]）。
     */
    fun line(size: SelectionSize, formatBytes: (Long) -> String): String? {
        if (!size.hasBytes) return null
        val notes = ArrayList<String>(2)
        if (size.unknownFiles > 0) notes.add(size.unknownFiles.toString() + " 个文件未报大小")
        if (size.folders > 0) notes.add("含 " + size.folders + " 个文件夹")
        val known = formatBytes(size.bytes)
        return if (notes.isEmpty()) known else known + " · " + notes.joinToString(" · ")
    }
}
