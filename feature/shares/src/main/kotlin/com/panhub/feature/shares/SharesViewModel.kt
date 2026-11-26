package com.panhub.feature.shares

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeFilter
import com.panhub.core.model.PageRequest
import com.panhub.core.model.ShareLink
import com.panhub.core.model.SortOrder
import com.panhub.core.model.SortSpec
import com.panhub.core.session.SessionManager
import com.panhub.provider.api.ScopedPanProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 一条分享，带上它属于哪个账号——取消分享时要用账号换会话。 */
data class ShareRow(
    val accountId: AccountId,
    val accountName: String,
    val link: ShareLink,
) {
    val key: String get() = accountId.value + "|" + link.id.value
}

data class SharesUiState(
    val accountName: String = "",
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val rows: List<ShareRow> = emptyList(),
    val nextCursor: String? = null,
    val canCancel: Boolean = false,
    val error: PanError? = null,
    val message: String? = null,
    /** 正在取消的那条，用来禁用它的按钮并显示进度。 */
    val busyKey: String? = null,
    /** 按标题筛选的关键字。纯本地行为，不重新拉列表。 */
    val query: String = "",
    /** 列表排序。默认「创建时间从新到旧」，与之前写死的顺序一致。 */
    val sort: SortSpec = SortSpec(order = SortOrder.TIME, descending = true),
    /** 批量取消时置位，用来禁掉所有按钮并防重入。 */
    val cancelling: Boolean = false,
    /** 勾中的分享（[ShareRow.key]）。取消成功后从里面摘掉。 */
    val selected: Set<String> = emptySet(),
) {
    val hasMore: Boolean get() = nextCursor != null

    /** 失效的排后面：用户更关心还能用的链接。 */
    val sorted: List<ShareRow> get() = rows.sortedFor(sort)

    /** 屏幕上真正显示的那些。规则与另外三页共用 [NodeFilter]。 */
    val visible: List<ShareRow>
        get() = NodeFilter.applyBy(sorted, query) { shareTitleOf(it.link) }

    val filtering: Boolean get() = NodeFilter.active(query)

    val filterSummary: String? get() = NodeFilter.summary(visible.size, sorted.size, query)

    /** 空列表有三种原因，说法要分开，否则用户不知道该改关键字还是去创建分享。 */
    val emptyTitle: String
        get() = when {
            rows.isEmpty() -> "还没有分享"
            else -> NodeFilter.emptyMessage(query)
        }

    val emptyHint: String
        get() = when {
            rows.isEmpty() -> "在文件列表里选中文档，就能生成分享链接"
            else -> "换个关键字，或者点上面的清空"
        }

    val selectionMode: Boolean get() = selected.isNotEmpty()

    val selectedCount: Int get() = selected.size

    val allSelected: Boolean get() = SharesLogic.allSelected(visible, selected)

    /** 勾中的那些，按屏幕顺序取，不按点击顺序。 */
    val selectedRows: List<ShareRow> get() = SharesLogic.selectedRows(rows, selected)
}

/**
 * 我的分享。
 *
 * 一个账号一页：不同网盘的分享 id 形态完全不同（123 是数字 id、百度是 shareId、
 * 蓝奏干脆没有 id 只能删文件），混在一张清单里取消时很容易发错服务商。
 */
class SharesViewModel(
    private val session: SessionManager,
    private val accountId: AccountId,
) : ViewModel() {

    private val _state = MutableStateFlow(SharesUiState())
    val state: StateFlow<SharesUiState> = _state.asStateFlow()

    private var scoped: ScopedPanProvider? = null
    private var started = false
    private var loadJob: Job? = null

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            val summary = session.accounts.first().firstOrNull { it.id == accountId }
            if (summary == null) {
                _state.update { it.copy(loading = false, error = PanError.notFound("账号不存在或已被移除")) }
                return@launch
            }
            _state.update {
                it.copy(
                    accountName = summary.label,
                    canCancel = summary.capabilities.share,
                )
            }
            when (val result = session.connect(accountId)) {
                is PanResult.Failure -> _state.update { it.copy(loading = false, error = result.error) }
                is PanResult.Success -> {
                    scoped = result.value
                    load()
                }
            }
        }
    }

    fun refresh() = load(refresh = true)

    fun retry() = load()

    private fun load(refresh: Boolean = false) {
        val provider = scoped ?: return
        loadJob?.cancel()
        _state.update {
            it.copy(
                loading = !refresh && it.rows.isEmpty(),
                refreshing = refresh,
                error = null,
            )
        }
        loadJob = viewModelScope.launch {
            val request = PageRequest(limit = PAGE_SIZE)
            when (val result = provider.listShares(request)) {
                is PanResult.Failure -> _state.update {
                    it.copy(loading = false, refreshing = false, error = result.error)
                }
                is PanResult.Success -> {
                    val page = result.value
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = null,
                            rows = page.items.map { link ->
                                ShareRow(accountId = accountId, accountName = it.accountName, link = link)
                            },
                            nextCursor = page.nextCursor,
                        )
                    }
                }
            }
        }
    }

    fun loadMore() {
        val provider = scoped ?: return
        val cursor = _state.value.nextCursor ?: return
        if (_state.value.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val request = PageRequest(cursor = cursor, limit = PAGE_SIZE)
            when (val result = provider.listShares(request)) {
                is PanResult.Failure -> _state.update {
                    it.copy(loadingMore = false, message = result.error.message)
                }
                is PanResult.Success -> {
                    val page = result.value
                    _state.update { current ->
                        // 翻页期间可能刚好新建或取消过分享，按 id 去重避免出现两张一样的卡片。
                        val known = current.rows.mapTo(mutableSetOf()) { it.key }
                        val added = page.items
                            .map { link ->
                                ShareRow(accountId = accountId, accountName = current.accountName, link = link)
                            }
                            .filter { known.add(it.key) }
                        current.copy(
                            loadingMore = false,
                            rows = current.rows + added,
                            nextCursor = page.nextCursor,
                        )
                    }
                }
            }
        }
    }

    fun cancel(row: ShareRow) = cancelRows(listOf(row))

    fun cancelSelected() = cancelRows(_state.value.selectedRows)

    /**
     * 一条一条取消。
     *
     * 不并发：同一个账号连着发一串取消请求，服务商很容易把后半截当成异常流量，
     * 结果就是「点了 8 条只成功 3 条」，还看不出来是哪几条。串行慢一点，但每条的结果都清楚。
     */
    fun cancelRows(rows: List<ShareRow>) {
        val provider = scoped ?: return
        if (rows.isEmpty()) return
        if (_state.value.cancelling) return
        _state.update {
            it.copy(
                busyKey = rows.singleOrNull()?.key,
                cancelling = true,
                message = null,
            )
        }
        viewModelScope.launch {
            val cancelled = mutableSetOf<String>()
            var firstError: PanError? = null
            for (row in rows) {
                when (val result = provider.cancelShare(row.link.id.value)) {
                    is PanResult.Failure -> if (firstError == null) firstError = result.error
                    is PanResult.Success -> cancelled.add(row.key)
                }
            }
            _state.update { current ->
                current.copy(
                    busyKey = null,
                    cancelling = false,
                    // 只有真的取消成功的才从列表里摘掉：失败的留在原地，用户还能再点一次。
                    rows = SharesLogic.withoutCancelled(current.rows, cancelled),
                    selected = current.selected - cancelled,
                    message = SharesLogic.cancelledMessage(cancelled.size, rows.size, firstError),
                )
            }
        }
    }

    fun toggleSelect(row: ShareRow) = _state.update { current ->
        current.copy(
            selected = if (row.key in current.selected) {
                current.selected - row.key
            } else {
                current.selected + row.key
            },
        )
    }

    fun toggleSelectAll() = _state.update { current ->
        current.copy(selected = SharesLogic.toggleAll(current.visible, current.selected))
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    /** 同一依据再点一次就反过来；换成别的依据时用它自己的默认方向。 */
    fun setSortOrder(order: SortOrder) = _state.update { current ->
        if (current.sort.order == order) {
            current.copy(sort = current.sort.copy(descending = !current.sort.descending))
        } else {
            current.copy(sort = SortSpec(order = order, descending = order.defaultDescending))
        }
    }

    fun toggleSortDirection() = _state.update {
        it.copy(sort = it.sort.copy(descending = !it.sort.descending))
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun clearQuery() = _state.update { it.copy(query = "") }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private companion object {
        /** 分享列表通常只有几十条，一页给到 50 已经能一次装下大多数账号。 */
        const val PAGE_SIZE = 50
    }
}
