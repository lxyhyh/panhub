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

/**
 * 登录方式。
 *
 * 为什么放进 descriptor 而不是让 UI 猜：各服务商的认证形态完全不同
 * （坚果云是固定的邮箱+应用密码，123 云盘是账号密码或浏览器 token），
 * 让 UI 用 `if (providerId == "pan123")` 判断会把服务商细节泄漏到视图层，
 * 新增一个网盘就得改 UI。声明式的能力开关让新增网盘只改 provider 模块。
 */
enum class AuthMethod {
    /** 只用账号 + 密码（WebDAV 类）。 */
    PASSWORD,

    /** 只用令牌。 */
    TOKEN,

    /** 两者都支持，用户自己选。 */
    BOTH,
}

/**
 * 服务商的静态描述，账号列表 / 新建账号界面靠它渲染。
 *
 * [authHint] 是给用户看的一句话说明，用来说明去哪里拿密码/token；
 * 空表示不需要额外解释。
 */
data class ProviderDescriptor(
    val id: ProviderId,
    val displayName: String,
    val capabilities: ProviderCapabilities,
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    val authHint: String? = null,
)
