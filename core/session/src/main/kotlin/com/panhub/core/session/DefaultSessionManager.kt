package com.panhub.core.session

import com.panhub.core.common.Clock
import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.ProviderDescriptor
import com.panhub.core.model.ProviderId
import com.panhub.core.storage.AccountMapper
import com.panhub.core.storage.AccountStore
import com.panhub.provider.api.ProviderCredentials
import com.panhub.provider.api.ProviderRegistry
import com.panhub.provider.api.ScopedPanProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [SessionManager] 的默认实现：ProviderRegistry + AccountStore 的薄组合。
 *
 * 真正的判断只有三处：
 * 1. 登录成功后会话里的 accountId 必须由 provider 给出（不同服务商的
 *    唯一标识规则不同，这一层不猜）；
 * 2. 落盘走 [AccountMapper]，磁盘格式与运行时类型解耦；
 * 3. connect 时顺手更新 lastUsedAt，但不阻塞调用方（失败也不影响使用）。
 */
class DefaultSessionManager(
    private val registry: ProviderRegistry,
    private val store: AccountStore,
    private val clock: Clock = Clock.Default,
) : SessionManager {

    override val accounts: Flow<List<AccountSummary>> =
        store.observeAll().map { records -> records.mapNotNull(::toSummary) }

    override fun descriptors(): List<ProviderDescriptor> = registry.descriptors()

    override suspend fun login(
        providerId: ProviderId,
        credentials: ProviderCredentials,
    ): PanResult<AccountSummary> {
        val provider = registry.get(providerId)
            ?: return PanResult.Failure(
                PanError(
                    kind = ErrorKind.UNSUPPORTED,
                    message = "不支持的网盘：${providerId.value}",
                ),
            )

        return when (val result = provider.login(credentials)) {
            is PanResult.Failure -> PanResult.Failure(result.error)
            is PanResult.Success -> {
                val session = result.value
                val now = clock.nowMillis()
                // 同一个账号重新登录时保留用户起过的备注名：
                // 记录是被整条覆盖的，不带过来的话备注会在下次登录时消失。
                val existing = store.get(session.accountId.value)
                val record = AccountMapper.toRecord(session, now).copy(alias = existing?.alias)
                store.save(record)
                PanResult.Success(
                    AccountSummary(
                        id = session.accountId,
                        providerId = providerId,
                        providerName = provider.descriptor.displayName,
                        displayName = session.displayName
                            ?: record.username
                            ?: provider.descriptor.displayName,
                        lastUsedAt = now,
                        capabilities = provider.descriptor.capabilities,
                        alias = record.alias,
                    ),
                )
            }
        }
    }

    override suspend fun verify(accountId: AccountId): PanResult<Unit> {
        val record = store.get(accountId.value)
            ?: return PanResult.Failure(PanError.notFound("账号不存在：${accountId.value}"))
        val provider = registry.get(ProviderId(record.providerId))
            ?: return PanResult.Failure(
                PanError(ErrorKind.UNSUPPORTED, "不支持的网盘：${record.providerId}"),
            )
        return when (val result = provider.verify(AccountMapper.toSession(record))) {
            is PanResult.Failure -> PanResult.Failure(result.error)
            is PanResult.Success -> {
                runCatching { store.touch(accountId.value, clock.nowMillis()) }
                PanResult.Success(Unit)
            }
        }
    }

    override suspend fun connect(accountId: AccountId): PanResult<ScopedPanProvider> {
        val record = store.get(accountId.value)
            ?: return PanResult.Failure(PanError.notFound("账号不存在：${accountId.value}"))
        val provider = registry.get(ProviderId(record.providerId))
            ?: return PanResult.Failure(
                PanError(ErrorKind.UNSUPPORTED, "不支持的网盘：${record.providerId}"),
            )
        // 记录使用时间只是"锦上添花"，写失败不该让用户打不开网盘。
        runCatching { store.touch(accountId.value, clock.nowMillis()) }
        return PanResult.Success(ScopedPanProvider(provider, AccountMapper.toSession(record)))
    }

    override suspend fun logout(accountId: AccountId) {
        store.delete(accountId.value)
    }

    override suspend fun rename(accountId: AccountId, alias: String?) {
        // 归一化在这一层做：界面提交的是什么都有可能，存储里只留干净的形式。
        store.setAlias(accountId.value, com.panhub.core.model.AccountNames.normalize(alias))
    }

    /**
     * 换凭据：新凭据先走一遍 provider.login 验证（坏 Cookie 进不来），
     * 成功后只把凭据字段写回原账号，身份与备注原样保留。
     *
     * Cookie 型账号的 accountId 由凭据哈希决定，重新登录会算出一个
     * 新 id——直接 save 会把同一份凭据存成第二个账号，所以这里刻意
     * 不用 login 的落盘，而是拿验证过的凭据 in-place 更新。
     * 密码型账号（坚果云）的 id 由用户名决定：新凭据验出的账号若不是
     * 原账号，说明用户要换的是另一份账号，请删号重加。
     */
    override suspend fun reauthenticate(
        accountId: AccountId,
        credentials: ProviderCredentials,
    ): PanResult<Unit> {
        val record = store.get(accountId.value)
            ?: return PanResult.Failure(PanError.notFound("账号不存在：${accountId.value}"))
        val provider = registry.get(ProviderId(record.providerId))
            ?: return PanResult.Failure(
                PanError(ErrorKind.UNSUPPORTED, "不支持的网盘：${record.providerId}"),
            )
        val session = when (val result = provider.login(credentials)) {
            is PanResult.Failure -> return PanResult.Failure(result.error)
            is PanResult.Success -> result.value
        }
        if (session.accountId != accountId) {
            return PanResult.Failure(
                PanError.auth("新凭据属于另一个账号，请移除旧账号后重新添加"),
            )
        }
        // 验证过的凭据写回原记录；展示名跟着服务商更新，其余不动。
        store.updateCredentials(
            record.copy(
                secret = when (val c = session.credentials) {
                    is ProviderCredentials.Password -> c.password
                    is ProviderCredentials.Token -> c.accessToken
                },
                refreshToken = (session.credentials as? ProviderCredentials.Token)?.refreshToken,
                extra = when (val c = session.credentials) {
                    is ProviderCredentials.Password -> emptyMap()
                    is ProviderCredentials.Token -> c.extra
                },
                displayName = session.displayName ?: record.displayName,
            ),
        )
        return PanResult.Success(Unit)
    }

    private fun toSummary(
        record: com.panhub.core.storage.AccountRecord,
    ): AccountSummary? {
        val provider = registry.get(ProviderId(record.providerId)) ?: return null
        return AccountSummary(
            id = AccountId(record.id),
            providerId = provider.descriptor.id,
            providerName = provider.descriptor.displayName,
            displayName = record.displayName
                ?: record.username
                ?: provider.descriptor.displayName,
            lastUsedAt = record.lastUsedAt,
            capabilities = provider.descriptor.capabilities,
            alias = record.alias,
        )
    }
}
