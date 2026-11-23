package com.panhub.feature.starred

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.PageRequest
import com.panhub.core.model.RemoteNode
import com.panhub.core.session.SessionManager
import com.panhub.provider.api.ScopedPanProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 收藏夹页的状态。
 *
 * 没有多选：收藏夹里最常见的动作是「取消收藏」和「打开看看」，
 * 批量取消的收益抵不上多一套选择状态带来的复杂度和误触风险。
 */
data class StarredUiState(
    val accountName: String = "",
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val items: List<RemoteNode> = emptyList(),
    val nextCursor: String? = null,
    val supported: Boolean = true,
    val busy: Boolean = false,
    val error: PanError? = null,
    val message: String? = null,
) {
    val hasMore: Boolean get() = nextCursor != null

    /** 列表为空时给用户看的话；还在加载就什么都不说。 */
    val emptyText: String?
        get() = if (items.isNotEmpty()) null else StarredLogic.emptyMessage(
            loading = loading,
            failed = error != null,
        )
}

class StarredViewModel(
    private val session: SessionManager,
    private val accountId: AccountId,
) : ViewModel() {

    private val _state = MutableStateFlow(StarredUiState())
    val state: StateFlow<StarredUiState> = _state.asStateFlow()

    private var scoped: ScopedPanProvider? = null
    private var started = false
    private var loadJob: Job? = null

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            val summary = session.accounts.first().firstOrNull { it.id == accountId }
            if (summary == null) {
                _state.update {
                    it.copy(loading = false, error = PanError.notFound("账号不存在或已被移除"))
                }
                return@launch
            }
            val supported = summary.capabilities.star
            _state.update { it.copy(accountName = summary.label, supported = supported) }
            if (!supported) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            when (val result = session.connect(accountId)) {
                is PanResult.Success -> {
                    scoped = result.value
                    load()
                }
                is PanResult.Failure -> _state.update { it.copy(loading = false, error = result.error) }
            }
        }
    }

    fun refresh() {
        if (_state.value.refreshing || _state.value.loadingMore) return
        load(refreshing = true)
    }

    fun retry() {
        _state.update { it.copy(error = null, loading = true) }
        start()
    }

    private fun load(refreshing: Boolean = false) {
        val provider = scoped ?: return
        loadJob?.cancel()
        _state.update {
            it.copy(
                loading = !refreshing && it.items.isEmpty(),
                refreshing = refreshing,
                error = null,
            )
        }
        loadJob = viewModelScope.launch {
            when (val result = provider.listStarred(PageRequest())) {
                is PanResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        items = result.value.items,
                        nextCursor = result.value.nextCursor,
                    )
                }
                is PanResult.Failure -> _state.update {
                    it.copy(loading = false, refreshing = false, error = result.error)
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
            when (val result = provider.listStarred(PageRequest(cursor = cursor))) {
                is PanResult.Success -> _state.update {
                    val seen = it.items.map { node -> node.id.value }.toSet()
                    it.copy(
                        loadingMore = false,
                        items = it.items + result.value.items.filter { node -> node.id.value !in seen },
                        nextCursor = result.value.nextCursor,
                    )
                }
                is PanResult.Failure -> _state.update {
                    it.copy(loadingMore = false, message = result.error.message)
                }
            }
        }
    }

    /**
     * 取消收藏。
     *
     * 成功后直接从本地列表里摘掉这一行，不整页重拉：用户点「取消」就是想让它消失，
     * 重拉一次会让整页闪一下，而服务端那边确实已经删掉了。
     */
    fun unstar(node: RemoteNode) {
        val provider = scoped ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = provider.setStarred(listOf(node.id), false)) {
                is PanResult.Success -> _state.update {
                    it.copy(
                        busy = false,
                        items = it.items.filterNot { item -> item.id == node.id },
                        message = StarredLogic.unstaredMessage(node),
                    )
                }
                is PanResult.Failure -> _state.update {
                    it.copy(busy = false, message = result.error.message)
                }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** 从收藏夹点进一个文件夹后，需要它作为浏览页的起点。 */
    fun nodeOf(id: NodeId): RemoteNode? = _state.value.items.firstOrNull { it.id == id }
}