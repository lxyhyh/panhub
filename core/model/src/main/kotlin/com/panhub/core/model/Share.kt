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
)
