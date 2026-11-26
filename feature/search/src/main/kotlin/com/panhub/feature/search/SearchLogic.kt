package com.panhub.feature.search

import com.panhub.core.model.AccountId
import com.panhub.core.model.FileKind
import com.panhub.core.model.FileKinds
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
     * 全挤到看不见的地方。所以一页只取这么多，服务端说还有下一页时
     * 界面底部会出现「加载更多」，由用户决定要不要继续往下看。
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

    /**
     * 一轮搜索之后还剩哪些账号有下一页。
     *
     * 只认这一轮真问到结果的账号：失败的账号给不出可信的游标，
     * 把它算进「还有更多」会让人点进去只看到一句错误。
     */
    fun cursorsOf(all: List<SearchCursor>, succeeded: Set<String>): List<SearchCursor> =
        all.filter { it.accountId.value in succeeded && it.cursor.isNotBlank() }

    /**
     * 翻一页之后更新游标表。
     *
     * 这一轮没问到的账号保留旧游标：一次超时不该把「还有更多」这个入口抹掉，
     * 否则用户只能重新搜一遍、从头翻。
     */
    fun nextCursors(
        current: List<SearchCursor>,
        refreshed: List<SearchCursor>,
        asked: Set<String>,
    ): List<SearchCursor> {
        val byId = refreshed.associateBy { it.accountId.value }
        return current.mapNotNull { cursor ->
            val id = cursor.accountId.value
            if (id !in asked) return@mapNotNull cursor
            val fresh = byId[id] ?: return@mapNotNull null
            if (fresh.cursor.isBlank()) null else fresh
        }
    }

    /**
     * 按类型筛之后还剩哪些结果。
     *
     * 这里刻意不保留文件夹（与浏览页相反）：浏览页的文件夹是「能进去的下一层」，
     * 搜索结果里的文件夹只是名字撞上了关键字，把它算进「图片」纯属误导。
     */
    fun visibleHits(hits: List<SearchHit>, kind: FileKind?): List<SearchHit> {
        if (kind == null) return hits
        return hits.filter { FileKinds.matches(it.node, kind, keepFolders = false) }
    }

    /**
     * 类型筛空了之后中间那句话。
     *
     * 不复用 [FileKinds.emptyMessage]：那里说的是「这里没有图片」，而这里是搜出来的
     * 一批结果被筛掉，说「搜到的结果里没有图片」才对得上用户刚才做的事。
     */
    fun emptyKindMessage(kind: FileKind?): String =
        if (kind == null) "没有找到匹配的文件" else "搜到的结果里没有" + kind.label

    /** 类型 chip 下面那行小字：只有真筛掉了东西才报数。 */
    fun filterSummary(shown: Int, total: Int, kind: FileKind?): String? {
        if (!FileKinds.active(kind)) return null
        if (shown >= total) return null
        return "筛选中 " + shown + "/" + total + " 项"
    }

    /** 「在 3 个网盘里找到 12 项」这种说法，没有结果时给一句人话。 */
    fun summary(state: SearchUiState): String {
        if (!state.searched) return ""
        if (state.busy) return "正在搜索…"
        val visible = visibleHits(state.hits, state.kind)
        if (visible.isEmpty()) {
            // 全被类型筛掉时要说清是「没有这一类」，不然用户会以为搜索坏了。
            val head = if (FileKinds.active(state.kind) && state.hits.isNotEmpty()) {
                emptyKindMessage(state.kind)
            } else {
                "没有找到匹配的文件"
            }
            return if (state.failures.isEmpty()) head else head + "（有账号没搜成）"
        }
        return "在 " + grouped(visible).size + " 个网盘里找到 " + visible.size + " 项"
    }
}
