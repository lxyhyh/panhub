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
