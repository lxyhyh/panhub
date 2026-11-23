package com.panhub.core.session

import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.ProviderDescriptor
import com.panhub.core.model.ProviderId
import com.panhub.provider.api.ProviderCredentials
import com.panhub.provider.api.ScopedPanProvider
import kotlinx.coroutines.flow.Flow

/**
 * 账号与会话的唯一入口。
 *
 * 为什么要有这一层：UI 不该知道 ProviderRegistry 和 AccountStore 的存在，
 * 更不该自己拼 [com.panhub.provider.api.ProviderSession]。所有"登录一次、
 * 长期使用"的复杂度都收在这里：
 *
 * - 登录成功后把凭据加密落盘，并立刻出现在 [accounts] 里；
 * - 每次打开账号时更新 lastUsedAt，让最近用的排前面；
 * - 只把 [AccountSummary] 交给 UI，凭据不出这一层。
 */
interface SessionManager {

    /** 已保存的账号，按最近使用时间倒序。 */
    val accounts: Flow<List<AccountSummary>>

    /** 当前所有可用的服务商（含未登录的），用于"添加账号"列表。 */
    fun descriptors(): List<ProviderDescriptor>

    /** 登录并保存。失败时不会写入任何账号。 */
    suspend fun login(
        providerId: ProviderId,
        credentials: ProviderCredentials,
    ): PanResult<AccountSummary>

    /** 校验已保存的账号是否还有效（网络请求，失败即视为失效）。 */
    suspend fun verify(accountId: AccountId): PanResult<Unit>

    /** 换出可用于发请求的会话对象。找不到账号返回 NOT_FOUND。 */
    suspend fun connect(accountId: AccountId): PanResult<ScopedPanProvider>

    suspend fun logout(accountId: AccountId)

    /**
     * 给账号起个别名（备注名）。传空串或 null 表示清掉备注。
     *
     * 别名纯本地，不会发给服务商，换设备也不会同步。
     */
    suspend fun rename(accountId: AccountId, alias: String?)
}
