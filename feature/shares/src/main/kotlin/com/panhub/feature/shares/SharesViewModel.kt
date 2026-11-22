package com.panhub.feature.shares

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.PageRequest
import com.panhub.core.model.ShareLink
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
) {
    val hasMore: Boolean get() = nextCursor != null

    /** 失效的排后面：用户更关心还能用的链接。 */
    val sorted: List<ShareRow>
        get() = rows.sortedWith(
            compareBy<ShareRow> { it.link.expired }
                .thenByDescending { it.link.createdAt ?: 0L },
        )
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
                    accountName = summary.displayName,
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

    fun cancel(row: ShareRow) {
        val provider = scoped ?: return
        if (_state.value.busyKey != null) return
        _state.update { it.copy(busyKey = row.key, message = null) }
        viewModelScope.launch {
            when (val result = provider.cancelShare(row.link.id.value)) {
                is PanResult.Failure -> _state.update {
                    it.copy(busyKey = null, message = result.error.message)
                }
                is PanResult.Success -> _state.update { current ->
                    current.copy(
                        busyKey = null,
                        rows = current.rows.filterNot { it.key == row.key },
                        message = "已取消分享",
                    )
                }
            }
        }
    }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private companion object {
        /** 分享列表通常只有几十条，一页给到 50 已经能一次装下大多数账号。 */
        const val PAGE_SIZE = 50
    }
}
