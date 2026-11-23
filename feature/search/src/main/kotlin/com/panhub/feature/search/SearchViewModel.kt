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
    /** 撞到每账号上限、结果被截断的账号名。 */
    val truncated: List<String> = emptyList(),
) {
    val groups: List<Pair<String, List<SearchHit>>> get() = SearchLogic.grouped(hits)
    val summary: String get() = SearchLogic.summary(this)
    val canSubmit: Boolean get() = query.isNotBlank() && !busy
    val hasBackground: Boolean get() = busy || !searched || resultsEmpty
    private val resultsEmpty: Boolean get() = hits.isEmpty() && failures.isEmpty()
}

/**
 * 跨账号搜索。
 *
 * 每个账号各发一次搜索请求，并发执行：串行时六个账号首尾相接，等待时间
 * 是六倍。任何一个账号失败都只记在 [SearchFailure] 里，其它账号的结果照常
 * 显示——一家网盘掉线不该让整次搜索看起来什么都没找到。
 */
class SearchViewModel(
    private val session: SessionManager,
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
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

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
                truncated = emptyList(),
            )
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
            val truncated = outcomes
                .filter { it.hits != null && it.hits.size >= SearchLogic.PER_ACCOUNT_LIMIT }
                .map { it.account.label }
            _state.update {
                it.copy(
                    busy = false,
                    hits = SearchLogic.merge(it.hits, hits),
                    failures = failures,
                    skipped = skipped,
                    truncated = truncated,
                )
            }
        }
    }

    /** 搜索结果里的一条被点开：交给界面按账号跳转。 */
    fun focusOf(hit: SearchHit): NodeFocus = hit.focus

    fun accountOf(hit: SearchHit): AccountId = hit.accountId

    private suspend fun searchOne(account: AccountSummary, keyword: String): Outcome {
        val connected = session.connect(account.id)
        if (connected is PanResult.Failure) {
            return Outcome(account, null, connected.error.message)
        }
        val provider = (connected as PanResult.Success).value
        return when (
            val result = provider.search(keyword, PageRequest(limit = SearchLogic.PER_ACCOUNT_LIMIT))
        ) {
            is PanResult.Failure -> Outcome(account, null, result.error.message)
            is PanResult.Success -> Outcome(
                account = account,
                hits = result.value.items.map { node ->
                    SearchHit(accountId = account.id, accountName = account.label, node = node)
                },
                error = null,
            )
        }
    }

    private data class Outcome(
        val account: AccountSummary,
        val hits: List<SearchHit>?,
        val error: String?,
    )
}
