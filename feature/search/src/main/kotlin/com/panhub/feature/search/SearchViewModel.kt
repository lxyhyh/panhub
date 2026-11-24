package com.panhub.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeFocus
import com.panhub.core.model.PageRequest
import com.panhub.core.session.AccountSummary
import com.panhub.core.session.SessionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 全盘搜索页的状态。
 *
 * 结果按账号分组显示，不做跨网盘统一排序：不同网盘的「相关度」根本不可比，
 * 混排出来的名次是假的。
 */
data class SearchUiState(
    val query: String = "",
    val busy: Boolean = false,
    val searched: Boolean = false,
    val hits: List<SearchHit> = emptyList(),
    val failures: List<SearchFailure> = emptyList(),
    /** 这次没参与搜索的账号名（服务商没有搜索能力）。 */
    val skipped: List<String> = emptyList(),
    /** 服务端说还有下一页的账号（账号 id、账号名、下一页游标）。 */
    val cursors: List<SearchCursor> = emptyList(),
    val loadingMore: Boolean = false,
    /** 最近搜过的关键字，新的在前；不带历史时为空。 */
    val history: List<String> = emptyList(),
) {
    val groups: List<Pair<String, List<SearchHit>>> get() = SearchLogic.grouped(hits)
    val summary: String get() = SearchLogic.summary(this)
    val canSubmit: Boolean get() = query.isNotBlank() && !busy
    val hasBackground: Boolean get() = busy || !searched || resultsEmpty
    // 不看 loadingMore：正在加载时按钮要留在原地显示「正在加载…」，
    // 把整行收掉会让列表在页底跳一下。
    val hasMore: Boolean get() = cursors.isNotEmpty() && !busy
    val moreNames: List<String> get() = cursors.map { it.accountName }
    private val resultsEmpty: Boolean get() = hits.isEmpty() && failures.isEmpty()
}

/**
 * 某个账号还有下一页。
 *
 * 带上账号名是为了界面上能说清「是夸克还有更多」，而不是干巴巴一句「还有更多」。
 */
data class SearchCursor(
    val accountId: AccountId,
    val accountName: String,
    val cursor: String,
)

/**
 * 跨账号搜索。
 *
 * 每个账号各发一次搜索请求，并发执行：串行时六个账号首尾相接，等待时间
 * 是六倍。任何一个账号失败都只记在 [SearchFailure] 里，其它账号的结果照常
 * 显示——一家网盘掉线不该让整次搜索看起来什么都没找到。
 */
class SearchViewModel(
    private val session: SessionManager,
    private val history: SearchHistorySource? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var started = false

    /** 进入页面：先记下哪些账号不能搜，让用户知道结果本来就不全。 */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            val accounts = session.accounts.first()
            _state.update { it.copy(skipped = SearchLogic.skipped(accounts).map { a -> a.label }) }
        }
        val source = history ?: return
        viewModelScope.launch {
            source.entries.collect { remembered ->
                _state.update { it.copy(history = remembered) }
            }
        }
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    /**
     * 点了一条历史：填进输入框并立刻搜。
     *
     * 直接搜而不是只填进去，是因为点历史的人想看的正是那次的结果；
     * 只填不搜等于让他再按一次回车。
     */
    fun useHistory(query: String) {
        setQuery(query)
        submit()
    }

    fun clearHistory() {
        val source = history ?: return
        viewModelScope.launch { runCatching { source.clear() } }
    }

    fun submit() {
        val keyword = _state.value.query.trim()
        if (keyword.isEmpty()) return
        if (_state.value.busy) return
        job?.cancel()
        _state.update {
            it.copy(
                busy = true,
                searched = true,
                hits = emptyList(),
                failures = emptyList(),
                cursors = emptyList(),
                loadingMore = false,
            )
        }
        // 记历史放在发请求之前：这一笔是「用户搜过」，不是「搜到了」。
        // 一家网盘掉线不该让刚敲的词从历史里消失。
        history?.let { source ->
            viewModelScope.launch { runCatching { source.remember(keyword) } }
        }
        job = viewModelScope.launch {
            val accounts = session.accounts.first()
            val targets = SearchLogic.targets(accounts)
            val skipped = SearchLogic.skipped(accounts).map { a -> a.label }
            if (targets.isEmpty()) {
                _state.update {
                    it.copy(busy = false, skipped = skipped, hits = emptyList())
                }
                return@launch
            }
            val outcomes = coroutineScope {
                targets.map { account -> async { searchOne(account, keyword) } }.awaitAll()
            }
            val hits = outcomes.flatMap { it.hits.orEmpty() }
            val failures = outcomes.mapNotNull { outcome ->
                outcome.error?.let { SearchFailure(outcome.account.label, it) }
            }
            _state.update {
                it.copy(
                    busy = false,
                    loadingMore = false,
                    hits = SearchLogic.merge(it.hits, hits),
                    failures = failures,
                    skipped = skipped,
                    cursors = SearchLogic.cursorsOf(outcomes.map { o ->
                        SearchCursor(o.account.id, o.account.label, o.cursor.orEmpty())
                    }, outcomes.filter { it.hits != null }.map { o -> o.account.id.value }.toSet()),
                )
            }
        }
    }

    /** 搜索结果里的一条被点开：交给界面按账号跳转。 */
    fun focusOf(hit: SearchHit): NodeFocus = hit.focus

    fun accountOf(hit: SearchHit): AccountId = hit.accountId

    /**
     * 「还有更多」：对每家还留了游标的账号再要一页。
     *
     * 只追加不重排：用户已经看过的那批结果不该在点一下之后换位置，
     * 否则他会以为自己点错了。
     */
    fun loadMore() {
        val cursors = _state.value.cursors
        if (cursors.isEmpty()) return
        if (_state.value.busy || _state.value.loadingMore) return
        val keyword = _state.value.query.trim()
        if (keyword.isEmpty()) return
        _state.update { it.copy(loadingMore = true) }
        job = viewModelScope.launch {
            val accounts = session.accounts.first().associateBy { it.id }
            val targets = cursors.mapNotNull { cursor -> accounts[cursor.accountId]?.let { it to cursor } }
            // 账号被删掉之后就再没有下一页可要了，留着入口只会让人点了没反应。
            val alive = targets.mapTo(mutableSetOf()) { (_, cursor) -> cursor.accountId.value }
            val outcomes = coroutineScope {
                targets.map { (account, cursor) ->
                    async { searchOne(account, keyword, PageRequest(cursor = cursor.cursor, limit = SearchLogic.PER_ACCOUNT_LIMIT)) }
                }.awaitAll()
            }
            val incoming = outcomes.flatMap { it.hits.orEmpty() }
            val failures = outcomes.mapNotNull { outcome ->
                outcome.error?.let { SearchFailure(outcome.account.label, it) }
            }
            _state.update {
                it.copy(
                    loadingMore = false,
                    hits = SearchLogic.merge(it.hits, incoming),
                    // 只有这一轮真问到了的账号才更新游标：失败的那些保留旧游标，
                    // 让用户还能再点一次，而不是被一次超时抹掉。
                    cursors = SearchLogic.nextCursors(
                        current = it.cursors.filter { cursor -> cursor.accountId.value in alive },
                        refreshed = outcomes.map { o ->
                            SearchCursor(o.account.id, o.account.label, o.cursor.orEmpty())
                        },
                        asked = outcomes.filter { it.hits != null }.map { o -> o.account.id.value }.toSet(),
                    ),
                    failures = (it.failures + failures).distinctBy { f -> f.accountName },
                )
            }
        }
    }

    private suspend fun searchOne(
        account: AccountSummary,
        keyword: String,
        page: PageRequest = PageRequest(limit = SearchLogic.PER_ACCOUNT_LIMIT),
    ): Outcome {
        val connected = session.connect(account.id)
        if (connected is PanResult.Failure) {
            return Outcome(account, null, connected.error.message, null)
        }
        val provider = (connected as PanResult.Success).value
        return when (val result = provider.search(keyword, page)) {
            is PanResult.Failure -> Outcome(account, null, result.error.message, null)
            is PanResult.Success -> Outcome(
                account = account,
                hits = result.value.items.map { node ->
                    SearchHit(accountId = account.id, accountName = account.label, node = node)
                },
                error = null,
                cursor = result.value.nextCursor,
            )
        }
    }

    private data class Outcome(
        val account: AccountSummary,
        val hits: List<SearchHit>?,
        val error: String?,
        val cursor: String?,
    )
}
