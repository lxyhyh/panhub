package com.panhub.feature.accounts

import com.panhub.core.design.formatBytes
import com.panhub.core.model.Quota
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

    /**
     * 几个账号的容量加起来。
     *
     * [total] 只加那些真的报了总量的服务商：把「不知道总量」当成 0
     * 会让总和凭空变小，看起来像空间被谁吃掉了一块。所以总量未知时
     * 干脆不报百分比，只报已用，并在 [withoutTotal] 里说清有几家没给。
     */
    data class QuotaSummary(
        val used: Long,
        val total: Long?,
        /** 参与统计的账号数。 */
        val accounts: Int,
        /** 其中没给出总容量的账号数。 */
        val withoutTotal: Int,
    ) {
        val free: Long? get() = total?.let { (it - used).coerceAtLeast(0L) }

        val percent: Int?
            get() = total?.takeIf { it > 0L }
                ?.let { (used * 100L / it).toInt().coerceIn(0, 100) }
    }

    /**
     * 把若干个容量合成一个总账；一个都没有就返回 null。
     *
     * 传进来的是已经查到的那些，查失败或不支持的账号本来就不在里面——
     * 它们对总和没有贡献，也不该被算作「已用 0」。
     */
    fun quotaSummary(quotas: Collection<Quota>): QuotaSummary? {
        if (quotas.isEmpty()) return null
        val used = quotas.sumOf { it.used.coerceAtLeast(0L) }
        val known = quotas.mapNotNull { it.total }.filter { it > 0L }
        val total = if (known.isEmpty()) null else known.sum()
        return QuotaSummary(
            used = used,
            total = total,
            accounts = quotas.size,
            withoutTotal = quotas.size - known.size,
        )
    }

    /**
     * 总账那一行字。
     *
     * 百分比只在总量已知时出现；有几家没给总量时补一句，
     * 否则「已用 / 总量」看起来就是全部家当了。
     */
    fun summaryLine(summary: QuotaSummary): String = buildString {
        append(summary.accounts)
        append(" 个网盘 · 已用 ")
        append(formatBytes(summary.used))
        val total = summary.total
        if (total != null) {
            append(" / ")
            append(formatBytes(total))
            val percent = summary.percent
            if (percent != null) {
                append("（")
                append(percent)
                append("%）")
            }
        }
        if (summary.withoutTotal > 0) {
            append("，另有 ")
            append(summary.withoutTotal)
            append(" 家没给总容量")
        }
    }
}
