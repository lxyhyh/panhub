package com.panhub.provider.cloud189

import com.panhub.provider.api.ProviderCredentials

/**
 * 官方客户端通道的一次会话。
 *
 * 与网页 Cookie 通道不同：这条通道不需要 Cookie，而是用一对 accessToken /
 * refreshToken 换出 sessionKey 与 sessionSecret，之后每个请求都用
 * sessionSecret 做 HMAC-SHA1 签名。sessionSecret 只用于签名，不落盘。
 */
internal data class Cloud189AppSession(
    val sessionKey: String,
    val sessionSecret: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val loginName: String? = null,
)

/**
 * 从账号凭据里取 App 通道的两个 token。
 *
 * 现在 provider 走的是网页 Cookie 通道，App 通道只是预留：将来要把
 * [Cloud189Provider] 切过来时，登录流程把这两个值写进
 * ProviderCredentials.Token.extra 即可，其它代码不用动。
 */
internal object Cloud189AppTokens {

    const val KEY_ACCESS_TOKEN = "appAccessToken"
    const val KEY_REFRESH_TOKEN = "appRefreshToken"

    data class Tokens(val accessToken: String, val refreshToken: String? = null)

    /** 凭据里没有 App token 就返回 null，表示这条通道没被配置。 */
    fun of(credentials: ProviderCredentials): Tokens? {
        val token = credentials as? ProviderCredentials.Token ?: return null
        val access = token.extra[KEY_ACCESS_TOKEN]?.trim().orEmpty()
        if (access.isEmpty()) return null
        val refresh = token.extra[KEY_REFRESH_TOKEN]?.trim()?.takeIf { it.isNotEmpty() }
        return Tokens(access, refresh)
    }

    /** 反过来把 token 写进 extra，登录成功时用。 */
    fun into(extra: Map<String, String>, tokens: Tokens): Map<String, String> =
        extra + mapOf(
            KEY_ACCESS_TOKEN to tokens.accessToken,
            KEY_REFRESH_TOKEN to tokens.refreshToken.orEmpty(),
        )
}
