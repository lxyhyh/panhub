package com.panhub.feature.accounts

import com.panhub.core.session.AccountSummary

/**
 * 账号页里可以单独拿出来讲的判断。
 *
 * 放在这里而不是写进 ViewModel，是因为它们只跟输入有关、跟协程无关，
 * 值得被直接测到。
 */
object AccountsLogic {

    /**
     * 这一轮该去查哪些账号的容量。
     *
     * 两条规则：
     * - 只有声明了 quota 能力的服务商才查，否则点进去必然失败；
     * - [attempted] 里有的不再查。查容量是装饰性的网络请求，
     *   失败一次就一直重试只会在弱网下变成一串无用的请求。
     */
    fun quotaTargets(
        accounts: List<AccountSummary>,
        attempted: Set<String>,
    ): List<AccountSummary> = accounts.filter { account ->
        account.capabilities.quota && account.id.value !in attempted
    }
}
