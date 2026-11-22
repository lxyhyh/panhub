package com.panhub.core.model

/** 节点类型。目前只区分文件与文件夹，部分服务商还有“快捷方式”等，先归入 FILE。 */
enum class NodeKind { FILE, DIR }

/**
 * 一个远端节点。这是 UI 唯一看到的数据形状，所有服务商都必须归一化到它。
 *
 * [providerPath] 是可选的、给用户看的路径，例如 `/我的文档/报告.pdf`；
 * 有些服务商（蓝奏云）没有路径概念，就是 null。
 */
data class RemoteNode(
    val id: NodeId,
    val parentId: NodeId?,
    val name: String,
    val kind: NodeKind,
    val size: Long = 0L,
    val modifiedAt: Long? = null,
    val createdAt: Long? = null,
    val mimeType: String? = null,
    val thumbnailUrl: String? = null,
    val providerPath: String? = null,
    val isShared: Boolean = false,
) {
    val isDir: Boolean get() = kind == NodeKind.DIR
}

/** 一页结果。游标由各服务商定义，UI 只负责原样回传。 */
data class NodePage(
    val items: List<RemoteNode>,
    val nextCursor: String? = null,
) {
    val hasMore: Boolean get() = nextCursor != null
}

/** 分页请求。 */
data class PageRequest(
    val cursor: String? = null,
    val limit: Int = 100,
)

/** 容量信息。[total] 为 null 表示该服务商不提供配额。 */
data class Quota(
    val used: Long,
    val total: Long? = null,
) {
    val free: Long? get() = total?.minus(used)
}
