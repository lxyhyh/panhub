package com.panhub.core.model

/**
 * 回收站里的一项。
 *
 * 为什么不让 [RemoteNode] 直接带上删除时间与过期时间：回收站是个独立场景，
 * 浏览目录时的节点永远没有这两个字段，塞进去会让每个 provider 的映射函数
 * 都要多想一次「这个字段该填什么」。分开一个类型，回收站之外的地方不需要知道它。
 */
data class TrashNode(
    val node: RemoteNode,
    /** 删进回收站的时间；服务端没给就是 null。 */
    val deletedAt: Long? = null,
    /** 服务端说的自动清理时间；null 表示不会自动清理。 */
    val expiresAt: Long? = null,
) {
    val id: NodeId get() = node.id
    val name: String get() = node.name
    val kind: NodeKind get() = node.kind
    val isDir: Boolean get() = node.isDir
    val size: Long get() = node.size
}

/** 回收站的一页。游标语义与 [NodePage] 一致，由服务商定义。 */
data class TrashPage(
    val items: List<TrashNode>,
    val nextCursor: String? = null,
) {
    val hasMore: Boolean get() = nextCursor != null
}
