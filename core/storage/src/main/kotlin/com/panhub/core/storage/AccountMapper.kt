package com.panhub.core.storage

import com.panhub.core.model.AccountId
import com.panhub.provider.api.ProviderCredentials
import com.panhub.provider.api.ProviderSession

/**
 * 落盘记录 <-> 运行时会话的双向转换。
 *
 * 单独拎出来是因为它是"格式兼容"的唯一责任点：只有这里知道
 * 老版本存下来的字段怎么映射到新的运行时类型。
 */
object AccountMapper {

    fun toRecord(session: ProviderSession, now: Long): AccountRecord = AccountRecord(
        id = session.accountId.value,
        providerId = session.accountId.value.substringBefore(':'),
        displayName = session.displayName,
        username = when (val c = session.credentials) {
            is ProviderCredentials.Password -> c.username
            is ProviderCredentials.Token -> null
        },
        secret = when (val c = session.credentials) {
            is ProviderCredentials.Password -> c.password
            is ProviderCredentials.Token -> c.accessToken
        },
        refreshToken = (session.credentials as? ProviderCredentials.Token)?.refreshToken,
        extra = when (val c = session.credentials) {
            is ProviderCredentials.Password -> emptyMap()
            is ProviderCredentials.Token -> c.extra
        },
        createdAt = now,
        lastUsedAt = now,
    )

    fun toSession(record: AccountRecord): ProviderSession {
        val credentials: ProviderCredentials = if (record.username != null) {
            ProviderCredentials.Password(
                username = record.username,
                password = record.secret.orEmpty(),
            )
        } else {
            ProviderCredentials.Token(
                accessToken = record.secret.orEmpty(),
                refreshToken = record.refreshToken,
                extra = record.extra,
            )
        }
        return ProviderSession(
            accountId = AccountId(record.id),
            credentials = credentials,
            displayName = record.displayName,
        )
    }
}
