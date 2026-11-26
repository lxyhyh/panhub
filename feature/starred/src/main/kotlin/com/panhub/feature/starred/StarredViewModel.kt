package com.panhub.feature.starred

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.FileKind
import com.panhub.core.model.FileKinds
import com.panhub.core.model.NodeFilter
import com.panhub.core.model.SortOrder
import com.panhub.core.model.SortSpec
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
 * 多选只为一件事服务：取消收藏。这一页最大的用途就是把攒下来的东西清理掉，
 * 一次点一颗星去清二十张照片没有意义，所以点一下卡片就进入选择态。
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
    /** 按名字筛选的关键字。纯本地行为：敲字不会重新拉一次收藏夹。 */
    val query: String = "",
    /** 按文件类型筛；null 表示不筛。同样只在已经拉下来的这一批里找。 */
    val kind: FileKind? = null,
    /** 选中的条目 id。空集合表示不在选择态。 */
    val selected: Set<String> = emptySet(),
    /**
     * 排序。默认「收藏时间从新到旧」。
     *
     * 收藏夹多半是「我最近收的那批东西」，服务商返回的顺序不保证有意义，
     * 所以自己排一次；收藏时间服务商没给时，退回修改时间。
     */
    val sort: SortSpec = SortSpec(order = SortOrder.TIME, descending = true),
) {
    val hasMore: Boolean get() = nextCursor != null

    val selectionMode: Boolean get() = selected.isNotEmpty()

    val selectedCount: Int get() = selected.size

    /** 全选只选看得到的那些。 */
    val allSelected: Boolean get() = StarredLogic.allSelected(visible, selected)

    /** 界面上真正看得到的那些：先按用户选的顺序排，再按类型和关键字收窄。 */
    val visible: List<RemoteNode> get() =
        NodeFilter.applyBy(FileKinds.apply(items.sortedStarred(sort), kind), query) { it.name }

    val filtering: Boolean get() = NodeFilter.active(query) || FileKinds.active(kind)

    val filterSummary: String? get() = FileKinds.summary(visible.size, items.size, query, kind)

    /** 筛空了和真的空是两句话，说法必须分开。 */
    val emptyTitle: String
        get() = if (items.isEmpty()) emptyText ?: "收藏夹是空的" else FileKinds.emptyMessage(query, kind)

    val emptyHint: String
        get() = when {
            items.isNotEmpty() && FileKinds.active(kind) -> "换个类型，或者点上面的清空"
            items.isNotEmpty() -> "换个关键字，或者点上面的清空"
            else -> "在文件页里点某个文件的「收藏」，它就会出现在这里"
        }

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
        unstarAll(listOf(node), StarredLogic.unstaredMessage(node))
    }

    /** 批量取消收藏。一次请求带走所有 id，服务端本来就是按 id 列表处理的。 */
    fun unstarSelected() {
        val snapshot = _state.value
        val chosen = snapshot.items.filter { it.id.value in snapshot.selected }
        if (chosen.isEmpty()) return
        unstarAll(chosen, StarredLogic.unstaredMessage(chosen.size))
    }

    /**
     * 真正发请求的地方。
     *
     * 提示语由调用方给：单条取消要说清是哪一个文件，批量取消只能报个数，
     * 两种情况共用一次请求。
     */
    private fun unstarAll(nodes: List<RemoteNode>, message: String) {
        val provider = scoped ?: return
        if (_state.value.busy || nodes.isEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val ids = nodes.map { node -> node.id }
            when (val result = provider.setStarred(ids, false)) {
                is PanResult.Success -> _state.update {
                    val gone = ids.toSet()
                    it.copy(
                        busy = false,
                        items = it.items.filterNot { item -> item.id in gone },
                        selected = it.selected - ids.map { id -> id.value }.toSet(),
                        message = message,
                    )
                }
                is PanResult.Failure -> _state.update {
                    it.copy(busy = false, message = result.error.message)
                }
            }
        }
    }

    /** 点一下卡片就是选中或取消选中：这一页没有别的单击动作会冲突。 */
    fun toggleSelect(node: RemoteNode) {
        _state.update {
            val id = node.id.value
            it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
        }
    }

    fun toggleSelectAll() {
        _state.update {
            val visible = it.visible
            it.copy(
                selected = if (StarredLogic.allSelected(visible, it.selected)) {
                    emptySet()
                } else {
                    visible.map { node -> node.id.value }.toSet()
                },
            )
        }
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** 只改一个字符串，不重新拉收藏夹：每敲一个字都发一次请求纯属浪费。 */
    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun clearQuery() = _state.update { it.copy(query = "") }

    /** 选一个类型再点一次就取消。不重新拉收藏夹，只在本地收窄。 */
    fun toggleKind(value: FileKind) {
        _state.update { it.copy(kind = if (it.kind == value) null else value) }
    }

    fun clearKind() = _state.update { it.copy(kind = null) }

    /**
     * 换排序依据。
     *
     * 与浏览页、本机文件页、回收站同一套手势：再点一次同一个依据就反转方向，
     * 第一次选到某个依据时用它自己的默认方向。
     */
    fun setSortOrder(order: SortOrder) = _state.update {
        val same = it.sort.order == order
        it.copy(
            sort = SortSpec(
                order = order,
                descending = if (same) !it.sort.descending else order.defaultDescending,
            ),
        )
    }

    fun toggleSortDirection() = _state.update {
        it.copy(sort = it.sort.copy(descending = !it.sort.descending))
    }

    /** 从收藏夹点进一个文件夹后，需要它作为浏览页的起点。 */
    fun nodeOf(id: NodeId): RemoteNode? = _state.value.items.firstOrNull { it.id == id }
}