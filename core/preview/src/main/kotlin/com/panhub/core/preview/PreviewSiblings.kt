package com.panhub.core.preview

import com.panhub.core.model.RemoteNode

/**
 * 同目录里可以往前/往后翻的邻居。
 *
 * 看照片时最常做的事是「看下一张」。每看一张都要关掉预览、回到列表、
 * 再点开下一张，等于把浏览拆成几十次重复操作。这里把「谁在前后」算清楚，
 * 界面只负责画出两个按钮。
 *
 * [index] 从 1 开始（给人看的「第 3 张」），为 0 表示当前文件不在这个列表里——
 * 比如从搜索结果或文件详情里打开时，同目录的其他文件根本不知道是哪一份。
 */
data class PreviewCursors(
    val previous: RemoteNode? = null,
    val next: RemoteNode? = null,
    /** 当前文件在这批可预览文件里的序号，从 1 开始；0 表示不知道。 */
    val index: Int = 0,
    /** 这批可预览文件的总数；0 表示不知道。 */
    val total: Int = 0,
) {
    /** 能不能翻。界面据此决定要不要画那两个按钮。 */
    val canBrowse: Boolean get() = total > 1

    /** 标题旁边那行「3 / 8」；只有一个或不知道时返回 null。 */
    val position: String?
        get() = if (canBrowse && index in 1..total) index.toString() + " / " + total else null

    companion object {
        val None = PreviewCursors()
    }
}

/**
 * 从当前目录的节点里挑出同类可预览的邻居。
 *
 * 只跟同一类文件互相翻：在十几张照片里夹着一个 README.md，
 * 翻到它再翻下一张又要跳回去，反而更难用。所以按 [kind] 过滤，
 * 图片就和图片一串，文本和文本一串。
 */
object PreviewSiblings {

    fun of(
        nodes: List<RemoteNode>,
        current: RemoteNode,
        kind: PreviewKind,
    ): PreviewCursors {
        if (kind == PreviewKind.NONE) return PreviewCursors.None
        val peers = nodes.filter { member -> PreviewKinds.of(member) == kind }
        val index = peers.indexOfFirst { it.id == current.id }
        // 找不到说明当前文件不在这个列表里（搜索结果、文件详情、刚上传的）。
        // 此时「上一个」无从谈起，宁可整块不显示，也不猜一个。
        if (index < 0) return PreviewCursors.None
        return PreviewCursors(
            previous = peers.getOrNull(index - 1),
            next = peers.getOrNull(index + 1),
            index = index + 1,
            total = peers.size,
        )
    }
}
