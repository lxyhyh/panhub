package com.panhub.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panhub.core.common.Clock
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.PageRequest
import com.panhub.core.model.TrashNode
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
 * 回收站页的状态。
 *
 * 选中集用 NodeId 的字符串形式存：翻页时列表会换一批，
 * 但选中的东西不该跟着列表一起消失。
 */
data class TrashUiState(
    val accountName: String = "",
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val items: List<TrashNode> = emptyList(),
    val nextCursor: String? = null,
    val selected: Set<String> = emptySet(),
    val supported: Boolean = true,
    val busy: Boolean = false,
    val error: PanError? = null,
    val message: String? = null,
) {
    val hasMore: Boolean get() = nextCursor != null
    val selectedCount: Int get() = selected.size
    val allSelected: Boolean get() = TrashLogic.allSelected(items, selected)
    val canAct: Boolean get() = selected.isNotEmpty() && !busy
}

class TrashViewModel(
    private val session: SessionManager,
    private val accountId: AccountId,
    private val clock: Clock = Clock.Default,
) : ViewModel() {

    private val _state = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = _state.asStateFlow()

    private var scoped: ScopedPanProvider? = null
    private var started = false
    private var loadJob: Job? = null

    fun nowMillis(): Long = clock.nowMillis()

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
            val supported = summary.capabilities.trash
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
            when (val result = provider.listTrash(PageRequest())) {
                is PanResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        items = result.value.items,
                        nextCursor = result.value.nextCursor,
                        // 刷新后原来选中的东西可能已经不在了，跟着剔掉。
                        selected = it.selected.intersect(result.value.items.map { n -> n.id.value }),
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
            when (val result = provider.listTrash(PageRequest(cursor = cursor))) {
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

    fun toggleSelect(node: TrashNode) {
        _state.update {
            val id = node.id.value
            it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
        }
    }

    fun toggleSelectAll() {
        _state.update {
            it.copy(selected = if (TrashLogic.allSelected(it.items, it.selected)) emptySet() else it.items.map { n -> n.id.value }.toSet())
        }
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    /** 还原。回原目录；原目录没了服务端会单独报出来，见 provider 里的处理。 */
    fun restoreSelected() {
        val provider = scoped ?: return
        val ids = _state.value.selected.toList()
        if (ids.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = provider.restoreTrash(ids.map { NodeId(it) })
            val message = when (result) {
                is PanResult.Success -> TrashLogic.resultMessage(ids.size, ids.size, "还原", null)
                is PanResult.Failure -> result.error.message
            }
            _state.update { it.copy(busy = false, message = message, selected = emptySet()) }
            if (result is PanResult.Success) load(refreshing = true)
        }
    }

    fun purgeSelected() {
        val provider = scoped ?: return
        val ids = _state.value.selected.toList()
        if (ids.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = provider.purgeTrash(ids.map { NodeId(it) })
            val message = when (result) {
                is PanResult.Success -> TrashLogic.resultMessage(ids.size, ids.size, "彻底删除", null)
                is PanResult.Failure -> result.error.message
            }
            _state.update { it.copy(busy = false, message = message, selected = emptySet()) }
            if (result is PanResult.Success) load(refreshing = true)
        }
    }

    fun emptyAll() {
        val provider = scoped ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = provider.emptyTrash()
            val message = when (result) {
                is PanResult.Success -> "回收站已清空"
                is PanResult.Failure -> result.error.message
            }
            _state.update { it.copy(busy = false, message = message, selected = emptySet()) }
            if (result is PanResult.Success) load(refreshing = true)
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
