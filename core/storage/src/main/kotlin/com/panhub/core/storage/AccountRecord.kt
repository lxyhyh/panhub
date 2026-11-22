package com.panhub.core.storage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 落盘的账号记录。
 *
 * 为什么不直接序列化 [com.panhub.provider.api.ProviderSession]：
 * 会话是"运行时"概念，将来可能加内存里才有的字段；落盘格式必须稳定，
 * 所以单独定义一个 DTO，并在 [com.panhub.core.storage.AccountMapper] 里转换。
 * 这样以后改 ProviderSession 不会把用户已登录的账号读崩。
 */
@Serializable
data class AccountRecord(
    /** 格式 `<providerId>:<服务商侧的唯一标识>`，同时作为文件名与主键。 */
    val id: String,
    @SerialName("provider") val providerId: String,
    @SerialName("name") val displayName: String? = null,
    @SerialName("user") val username: String? = null,
    @SerialName("secret") val secret: String? = null,
    @SerialName("refresh") val refreshToken: String? = null,
    @SerialName("extra") val extra: Map<String, String> = emptyMap(),
    @SerialName("created") val createdAt: Long = 0L,
    @SerialName("lastUsed") val lastUsedAt: Long = 0L,
) {
    /** 令牌型凭据（多数网盘）与账号密码型（WebDAV）用同一个字段存主密钥。 */
    val isTokenBased: Boolean get() = refreshToken != null || username == null
}
