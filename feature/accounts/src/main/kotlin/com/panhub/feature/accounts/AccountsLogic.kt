package com.panhub.feature.accounts

import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanError
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
     * 一次登录检查的结论。
     *
     * 只有「成功」和「凭据不对」两种。网络超时不进这个表：把一次弱网
     * 当成「登录失效」，用户会照着提示去重新登一遍，然后发现本来就好好的。
     */
    enum class LoginState { OK, EXPIRED }

    /** 把一次会话校验的结果翻成结论；没查成返回 null。 */
    fun stateOf(error: PanError?): LoginState? = when {
        error == null -> LoginState.OK
        error.kind == ErrorKind.AUTH -> LoginState.EXPIRED
        else -> null
    }

    /** 卡片上那行小字。 */
    fun stateLabel(state: LoginState): String = when (state) {
        LoginState.OK -> "登录有效"
        LoginState.EXPIRED -> "登录已失效"
    }

    /**
     * 批量检查之后的一句总结。
     *
     * 三种情况要分开说：全部正常、有账号要重新登录、有账号压根没查成。
     * 把后者混进「失效」里是最坏的选择——用户会去重登一个没问题的账号。
     */
    fun checkMessage(states: Collection<LoginState>, total: Int): String {
        if (total <= 0) return "还没有账号可以检查"
        val expired = states.count { it == LoginState.EXPIRED }
        val ok = states.count { it == LoginState.OK }
        val unknown = total - states.size
        return when {
            expired == 0 && unknown == 0 -> total.toString() + " 个账号都还能用"
            // 一个都没查成时不能说「其余正常」——没有任何一条结论支持这句话。
            expired == 0 -> total.toString() + " 个账号里有 " + unknown + " 个没查成"
            ok == 0 && unknown == 0 -> total.toString() + " 个账号都要重新登录"
            unknown > 0 -> total.toString() + " 个账号里 " + expired + " 个需要重新登录，另有 " +
                unknown + " 个没查成"
            else -> total.toString() + " 个账号里 " + expired + " 个需要重新登录"
        }
    }

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
