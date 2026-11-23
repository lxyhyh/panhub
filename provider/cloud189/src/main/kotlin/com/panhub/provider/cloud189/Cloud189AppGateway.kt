package com.panhub.provider.cloud189

import com.panhub.core.common.Clock
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.network.HttpEngine
import com.panhub.provider.api.ProviderCredentials
import kotlin.random.Random

/**
 * App 通道的入口。
 *
 * 现在 [Cloud189Provider] 走网页 Cookie 通道，这一层只是把
 * 「凭据里有没有 App token → 换会话 → 拿到业务端点」这条链路封好，
 * 让将来切换成为一次调用，而不是散落在 provider 各处的改造。
 *
 * 用法（将来）：
 *
 *     val api = Cloud189AppGateway.open(engine, session.credentials)
 *     if (api != null) { ...走 App 通道... } else { ...走 Cookie 通道... }
 *
 * 返回 null 表示这个账号没配 App token，属于正常情况，不是错误。
 */
internal object Cloud189AppGateway {

    /**
     * 打开一条 App 通道。凭据里没有 token 返回 null；
     * 有 token 但换会话失败则返回失败（多半是 token 过期，需要重新登录）。
     */
    suspend fun open(
        engine: HttpEngine,
        credentials: ProviderCredentials,
        clock: Clock = Clock.Default,
        random: Random = Random.Default,
    ): PanResult<Cloud189AppApi>? {
        val tokens = Cloud189AppTokens.of(credentials) ?: return null
        val channel = Cloud189AppChannel(engine, clock = clock, random = random)
        // 把凭据里的 refreshToken 一并带进去：换会话的响应不一定回它，
        // 但过期后必须有一个能用来刷新的。
        return when (val established = channel.openSession(tokens.accessToken, tokens.refreshToken)) {
            is PanResult.Failure -> established
            is PanResult.Success -> PanResult.Success(Cloud189AppApi(channel))
        }
    }

    /** 判断一个账号是否配了 App 通道，用来在设置页显示「走哪条通道」。 */
    fun configured(credentials: ProviderCredentials): Boolean =
        Cloud189AppTokens.of(credentials) != null

    /** 把登录拿到的 token 写进凭据的 extra，供 [open] 读取。 */
    fun credentialsWithTokens(
        accessToken: String,
        refreshToken: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): ProviderCredentials = ProviderCredentials.Token(
        accessToken = accessToken,
        refreshToken = refreshToken,
        extra = Cloud189AppTokens.into(
            extra,
            Cloud189AppTokens.Tokens(accessToken, refreshToken),
        ),
    )

    /** token 过期时给用户的提示，与 Cookie 通道的登录失效文案保持一致。 */
    val expiredError: PanError get() = PanError.auth("天翼云盘登录已过期，请重新登录")
}
