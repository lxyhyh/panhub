package com.panhub.core.session

import com.panhub.core.model.AccountId
import com.panhub.core.model.ProviderCapabilities
import com.panhub.core.model.ProviderId

/**
 * 账号列表要展示的一行。
 *
 * 故意不带 secret：视图层拿不到凭据，就不可能出现"日志里打印了 token"。
 * 需要发请求时通过 [com.panhub.core.session.SessionManager.connect] 换取
 * 一个绑定好会话的 [com.panhub.provider.api.ScopedPanProvider]。
 */
data class AccountSummary(
    val id: AccountId,
    val providerId: ProviderId,
    /** 服务商显示名（"坚果云"、"123 云盘"）。 */
    val providerName: String,
    /** 用户在那家服务商上的显示名（昵称/邮箱）。 */
    val displayName: String,
    val lastUsedAt: Long,
    val capabilities: ProviderCapabilities,
)
