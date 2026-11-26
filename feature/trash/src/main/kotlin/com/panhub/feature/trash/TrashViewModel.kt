package com.panhub.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panhub.core.common.Clock
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
 * 「还原到哪个目录」选择器的状态。
 *
 * 单独一份栈而不是复用别处的：这里选的是自己网盘里的目录，
 * 与回收站列表没有关系，混在一起会让「返回」键的含义随上下文漂移。
 */
data class RestorePickerState(
    val visible: Boolean = false,
    val stack: List<RemoteNode> = emptyList(),
    val folders: List<RemoteNode> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    val canGoUp: Boolean get() = stack.isNotEmpty()

    /** 当前选中的目录；null 表示网盘根目录。 */
    val targetId: NodeId? get() = stack.lastOrNull()?.id

    val title: String get() = TrashLogic.restoreTargetLabel(stack)
}

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
    val picker: RestorePickerState = RestorePickerState(),
    val error: PanError? = null,
    val message: String? = null,
    /** 按名字筛选的关键字。纯本地行为：敲字不会重新拉一次回收站。 */
    val query: String = "",
    /** 按文件类型筛；null 表示不筛。同样只在已经拉下来的这一批里找。 */
    val kind: FileKind? = null,
    /**
     * 排序。默认「删除时间从新到旧」。
     *
     * 回收站最常见的用法是「我刚删错了一个，捞回来」，所以刚删的要在最上面；
     * 服务商返回的顺序不保证有意义（123 和百度都没在这个接口上写排序）。
     */
    val sort: SortSpec = SortSpec(order = SortOrder.TIME, descending = true),
) {
    val hasMore: Boolean get() = nextCursor != null
    val selectedCount: Int get() = selected.size

    /** 界面上真正看得到的那些：先按用户选的顺序排，再按类型和关键字收窄。 */
    val visible: List<TrashNode>
        get() {
            // 按类型筛时文件夹一律留着：回收站里「一件东西」和「一个文件夹」
            // 是两回事，把文件夹跟着图片一起藏起来会让人以为它被清掉了。
            val byKind =
                if (kind == null) items.sortedTrash(sort) else items.sortedTrash(sort).filter { FileKinds.matches(it.node, kind) }
            return NodeFilter.applyBy(byKind, query) { it.name }
        }

    val filtering: Boolean get() = NodeFilter.active(query) || FileKinds.active(kind)

    val filterSummary: String? get() = FileKinds.summary(visible.size, items.size, query, kind)

    /**
     * 全选只选看得到的那些。
     *
     * 筛着的时候把看不见的东西一起选上，「彻底删除」那一刀就没人拦得住了。
     */
    val allSelected: Boolean get() = TrashLogic.allSelected(visible, selected)

    val canAct: Boolean get() = selected.isNotEmpty() && !busy

    /** 「一件都没有」和「被关键字筛没了」是两件事，说法必须分开。 */
    val emptyTitle: String
        get() = if (items.isEmpty()) "回收站是空的" else FileKinds.emptyMessage(query, kind)

    val emptyHint: String
        get() = when {
            items.isNotEmpty() && FileKinds.active(kind) -> "换个类型，或者点上面的清空"
            items.isNotEmpty() -> "换个关键字，或者点上面的清空"
            else -> "在文件页删除的东西会先放到这里"
        }
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
    private var pickerJob: Job? = null

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
            val visible = it.visible
            it.copy(
                selected = if (TrashLogic.allSelected(visible, it.selected)) {
                    emptySet()
                } else {
                    visible.map { n -> n.id.value }.toSet()
                },
            )
        }
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    /** 只改一个字符串，不重新拉回收站：每敲一个字都发一次请求纯属浪费。 */
    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun clearQuery() = _state.update { it.copy(query = "") }

    /** 选一个类型再点一次就取消。不重新拉回收站，只在本地收窄。 */
    fun toggleKind(value: FileKind) {
        _state.update { it.copy(kind = if (it.kind == value) null else value) }
    }

    fun clearKind() = _state.update { it.copy(kind = null) }

    /**
     * 换排序依据。
     *
     * 与浏览页、本机文件页同一套手势：再点一次同一个依据就反转方向，
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

    /** 还原到原位置；原目录没了服务端会单独报出来，见 provider 里的处理。 */
    fun restoreSelected() = restoreTo(null)

    /** 打开「还原到…」的目录选择器。 */
    fun openRestorePicker() {
        val provider = scoped ?: return
        if (_state.value.selected.isEmpty() || _state.value.busy) return
        _state.update { it.copy(picker = RestorePickerState(visible = true, loading = true)) }
        loadPicker(provider, null)
    }

    fun closeRestorePicker() {
        pickerJob?.cancel()
        _state.update { it.copy(picker = RestorePickerState()) }
    }

    fun enterPickerFolder(folder: RemoteNode) {
        val provider = scoped ?: return
        if (!folder.isDir) return
        _state.update {
            it.copy(picker = it.picker.copy(stack = it.picker.stack + folder, loading = true, error = null))
        }
        loadPicker(provider, folder.id)
    }

    fun leavePickerFolder() {
        val provider = scoped ?: return
        val stack = _state.value.picker.stack
        if (stack.isEmpty()) return
        val next = stack.dropLast(1)
        _state.update { it.copy(picker = it.picker.copy(stack = next, loading = true, error = null)) }
        loadPicker(provider, next.lastOrNull()?.id)
    }

    /** 确认目标目录并把选中的项还原过去。 */
    fun confirmRestoreTo() {
        val target = _state.value.picker.targetId
        pickerJob?.cancel()
        _state.update { it.copy(picker = RestorePickerState()) }
        restoreTo(target)
    }

    private fun loadPicker(provider: ScopedPanProvider, parentId: NodeId?) {
        pickerJob?.cancel()
        pickerJob = viewModelScope.launch {
            when (val result = provider.list(parentId, PageRequest(limit = PICKER_PAGE_SIZE))) {
                is PanResult.Failure -> _state.update {
                    it.copy(
                        picker = it.picker.copy(
                            loading = false,
                            folders = emptyList(),
                            error = result.error.message,
                        ),
                    )
                }
                is PanResult.Success -> _state.update {
                    it.copy(
                        picker = it.picker.copy(
                            loading = false,
                            folders = TrashLogic.folderOnly(result.value.items),
                            error = null,
                        ),
                    )
                }
            }
        }
    }

    /**
     * 还原。parentId 为 null 表示回原位置。
     *
     * 走到这里说明用户已经确定要还原哪些、还原到哪，所以先把选中集清掉：
     * 还原成功的那几项会从列表里消失，留着选中只会让下一次操作看起来像是
     * 作用在已经不在的东西上。
     */
    private fun restoreTo(parentId: NodeId?) {
        val provider = scoped ?: return
        val ids = _state.value.selected.toList()
        if (ids.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = provider.restoreTrash(ids.map { NodeId(it) }, parentId)
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

    private companion object {
        /** 一次取满，选择器里翻页的收益小于多点一次的成本。 */
        const val PICKER_PAGE_SIZE = 200
    }
}
