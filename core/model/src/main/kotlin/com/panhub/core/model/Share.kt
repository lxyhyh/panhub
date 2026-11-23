package com.panhub.core.model

/** 创建分享时的可选参数，各服务商支持的子集不同，见 [ProviderCapabilities]。 */
data class ShareOptions(
    val password: String? = null,
    val expireDays: Int? = null,
    val description: String? = null,
)

/** 一条已创建的分享链接。 */
data class ShareLink(
    val id: ShareId,
    val url: String,
    val password: String? = null,
    val createdAt: Long? = null,
    val expiresAt: Long? = null,
    val nodeIds: List<NodeId> = emptyList(),
    val title: String? = null,
    /**
     * 服务商已经判定这条分享失效（过期、被取消、违规下架）。
     *
     * 为什么不由 [expiresAt] 推算：服务商还会因为别的原因让分享失效，
     * 本地算出来的「还没到期」并不代表链接还能打开。
     */
    val expired: Boolean = false,
)

/** 一页分享记录。游标语义与文件列表一致，由各服务商定义。 */
data class SharePage(
    val items: List<ShareLink>,
    val nextCursor: String? = null,
) {
    val hasMore: Boolean get() = nextCursor != null
}
/**
 * 别人发来的一条分享，打开之后才知道里面有什么。
 *
 * [linkId] 是分享链接里的那串标识（夸克叫 pwd_id），[token] 是打开时服务端下发的会话票据。
 * 两者都要：列目录与转存时服务端会同时校验「哪条分享」和「你有没有被授权看它」，
 * 而且它们跟账号自己的登录态是两回事，所以必须单独建模，不能塞进 [RemoteNode]。
 */
data class ShareSource(
    val linkId: String,
    val token: String,
    /** 分享方起的标题。 */
    val title: String? = null,
    /** 分享方昵称。 */
    val ownerName: String? = null,
    /** 这条分享自己的到期时间；null 表示永久或未知。 */
    val expiresAt: Long? = null,
)

/**
 * 分享里的一个条目。
 *
 * [ticket] 是分享内票据：转存时必须原样回传，否则服务端认为「你没有权限取这一项」。
 * 它只在分享内有效，与账号自己网盘里的节点 id 完全不同，所以这里是独立类型，
 * 不是 [RemoteNode] 加一个字段 —— 混用迟早会把分享内的 id 当成自己的 id 去删文件。
 */
data class ShareEntry(
    val id: NodeId,
    /** 条目所在的分享目录；null 表示分享根目录。转存时服务端要知道从哪儿取。 */
    val parentId: NodeId? = null,
    val name: String,
    val kind: NodeKind,
    val ticket: String? = null,
    val size: Long = 0L,
    val modifiedAt: Long? = null,
) {
    val isDir: Boolean get() = kind == NodeKind.DIR
}

/** 分享里的一页条目。游标语义与文件列表一致，由各服务商定义。 */
data class ShareEntryPage(
    val items: List<ShareEntry>,
    val nextCursor: String? = null,
) {
    val hasMore: Boolean get() = nextCursor != null
}
