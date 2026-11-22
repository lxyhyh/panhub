package com.panhub.core.model

/**
 * 服务商能力声明。UI 用它来决定隐藏哪些按钮，而不是到处写 `if (provider == ...)`。
 * 新增能力时给默认值 false，旧实现不受影响。
 */
data class ProviderCapabilities(
    val list: Boolean = true,
    val quota: Boolean = false,
    val upload: Boolean = false,
    val uploadChunked: Boolean = false,
    val download: Boolean = false,
    val rename: Boolean = false,
    val move: Boolean = false,
    val copy: Boolean = false,
    val trash: Boolean = false,
    val delete: Boolean = false,
    val createFolder: Boolean = false,
    val search: Boolean = false,
    val share: Boolean = false,
    val sharePassword: Boolean = false,
    val shareExpiry: Boolean = false,
    val directLink: Boolean = false,
    val offlineDownload: Boolean = false,
)

/** 服务商的静态描述，账号列表/新建账号界面靠它渲染。 */
data class ProviderDescriptor(
    val id: ProviderId,
    val displayName: String,
    val capabilities: ProviderCapabilities,
)
