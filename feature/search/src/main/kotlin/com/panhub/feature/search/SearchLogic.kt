package com.panhub.feature.search

import com.panhub.core.model.AccountId
import com.panhub.core.model.RemoteNode
import com.panhub.core.model.toFocus
import com.panhub.core.model.NodeFocus
import com.panhub.core.session.AccountSummary

/** 一条搜索结果，带上它属于哪个账号——点进去要用账号换会话。 */
data class SearchHit(
    val accountId: AccountId,
    val accountName: String,
    val node: RemoteNode,
) {
    val key: String get() = accountId.value + "|" + node.id.value

    /** 点这条结果时该带着什么信息进入浏览页。 */
    val focus: NodeFocus get() = node.toFocus()
}

/** 某个账号这次没搜成。一个账号挂了不该让整次搜索看起来什么都找不到。 */
data class SearchFailure(
    val accountName: String,
    val message: String,
)

/**
 * 跨账号搜索的纯逻辑。
 *
 * 放在独立对象里而不是 ViewModel 私有方法：这些规则（谁能搜、怎么排序、
 * 怎么去重、怎么截断）都是「多一层就少一条一致性问题」的判断，
 * 能在纯 JVM 单测里钉住，不用拖一个 ViewModel 和假网络。
 */
object SearchLogic {

    /**
     * 一次搜索最多从每个账号带回来多少条。
     *
     * 一个大盘可以有几十万文件，关键字稍宽就铺满屏幕，把别的账号的结果
     * 全挤到看不见的地方。截断之后提示里会写明「只显示前 N 条」。
     */
    const val PER_ACCOUNT_LIMIT = 100

    /** 能参与搜索的账号：只有声明了 search 的才发请求。 */
    fun targets(accounts: List<AccountSummary>): List<AccountSummary> =
        accounts.filter { it.capabilities.search }

    /** 不参与搜索的账号，界面上要说清「这几家没搜」，否则用户会以为漏了东西。 */
    fun skipped(accounts: List<AccountSummary>): List<AccountSummary> =
        accounts.filter { !it.capabilities.search }

    /**
     * 按账号分组，组内保持服务商给的顺序。
     *
     * 分组而不是混在一起排：两个网盘里各有一个同名文件时，混排后用户点错
     * 就只能看到另一个网盘的文件，分组至少让「这是哪家的」一眼可见。
     * 组的先后按账号第一次出现的位置定，跟账号列表的顺序一致。
     */
    fun grouped(hits: List<SearchHit>): List<Pair<String, List<SearchHit>>> {
        val order = LinkedHashMap<String, MutableList<SearchHit>>()
        for (hit in hits) {
            order.getOrPut(hit.accountName) { mutableListOf() }.add(hit)
        }
        return order.map { (name, list) -> name to list.toList() }
    }

    /**
     * 追加一页结果并去重。
     *
     * 服务商重搜、翻页重叠都会给出重复项；不去重的话 LazyColumn 的 key
     * 会直接冲突崩掉。
     */
    fun merge(current: List<SearchHit>, incoming: List<SearchHit>): List<SearchHit> {
        val known = current.mapTo(mutableSetOf()) { it.key }
        return current + incoming.filter { known.add(it.key) }
    }

    /** 「在 3 个网盘里找到 12 项」这种说法，没有结果时给一句人话。 */
    fun summary(state: SearchUiState): String {
        if (!state.searched) return ""
        if (state.busy) return "正在搜索…"
        val accounts = grouped(state.hits).size
        if (state.hits.isEmpty()) {
            return if (state.failures.isEmpty()) "没有找到匹配的文件" else "没有找到匹配的文件（有账号没搜成）"
        }
        return "在 " + accounts + " 个网盘里找到 " + state.hits.size + " 项"
    }
}
