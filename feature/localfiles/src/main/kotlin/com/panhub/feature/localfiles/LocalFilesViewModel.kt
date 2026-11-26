package com.panhub.feature.localfiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panhub.core.design.formatBytes
import com.panhub.core.model.FileKind
import com.panhub.core.model.FileKinds
import com.panhub.core.model.NodeFilter
import com.panhub.core.model.SortOrder
import com.panhub.core.model.SortSpec
import com.panhub.core.transfer.DownloadedEntry
import com.panhub.core.transfer.DownloadsSnapshot
import com.panhub.core.transfer.LocalDownloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 本地文件页的状态。 */
data class LocalFilesUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val snapshot: DownloadsSnapshot = DownloadsSnapshot(),
    /** 等待用户确认删除的那一批；空表示没有弹窗。 */
    val confirming: List<DownloadedEntry> = emptyList(),
    /** 是否在问「清空全部」。 */
    val confirmingClear: Boolean = false,
    val message: String? = null,
    /** 多选中的相对路径。空表示不在选择态。 */
    val selected: Set<String> = emptySet(),
    /** 按名字筛选的关键字。纯本地行为，不重新扫盘。 */
    val filter: String = "",
    /** 按类型筛选。null 表示不筛；与浏览页共用同一套 [FileKinds] 判断。 */
    val kind: FileKind? = null,
    /**
     * 排序规格。默认「修改时间从新到旧」，与本机文件一直以来的顺序一致：
     * 用户来找的多半是刚下完的那个，换成按名字排会让刚下载完的文件沉到列表中间。
     */
    val sort: SortSpec = SortSpec(order = SortOrder.TIME, descending = true),
) {
    val rows: List<LocalFileRow> get() = LocalFilesLogic.rows(snapshot)

    /**
     * 排序之后再筛，与浏览页同一个顺序：先按用户选的规格重排，再按类型和关键字收窄。
     *
     * 本机文件列表里没有文件夹，所以 [FileKinds.applyBy] 不会像浏览页那样把
     * 文件夹整批留下——这里筛的就是文件本身。
     */
    val visibleRows: List<LocalFileRow>
        get() = NodeFilter.applyBy(
            FileKinds.applyBy(rows.sortedRows(sort), kind) { it.entry.name },
            filter,
        ) { it.entry.name }

    /** 有没有在筛选。界面据此区分「没有文件」和「筛没了」两种空列表。 */
    val filtering: Boolean get() = NodeFilter.active(filter) || FileKinds.active(kind)

    val summary: String get() = LocalFilesLogic.summaryOf(snapshot)
    val empty: Boolean get() = !loading && snapshot.isEmpty

    /** 筛完一条不剩。这时磁盘上当然还有文件，只是名字都不匹配。 */
    val filteredOut: Boolean get() = !loading && !empty && visibleRows.isEmpty()

    /** 输入框下面那行小字，规则与浏览页相同。 */
    val filterSummary: String?
        get() = FileKinds.summary(visibleRows.size, rows.size, filter, kind)

    /** 筛完一条不剩时的说明。两个条件都在时要分清是被哪个筛掉的。 */
    val emptyFilterMessage: String get() = FileKinds.emptyMessage(filter, kind)

    val canClear: Boolean get() = snapshot.entries.isNotEmpty() && !busy

    val selectionMode: Boolean get() = selected.isNotEmpty()

    val selectedCount: Int get() = selected.size

    val allSelected: Boolean get() = LocalFilesLogic.allSelected(visibleRows, selected)

    /** 底栏那行「已选 N 项 · 多少 MB」。没有选中时不给这段字。 */
    val selectedSummary: String
        get() {
            val bytes = LocalFilesLogic.selectedBytes(snapshot.entries, selected)
            return "已选 " + selected.size + " 项 · " + formatBytes(bytes)
        }
}

/**
 * 本地文件页。
 *
 * 这一页唯一的信息来源是磁盘本身，不是传输队列：用户清空已完成的任务之后，
 * 文件仍然在，而队列里再也找不到它们。所以每次进页面都重新扫一遍，
 * 删除之后也重新扫——磁盘是唯一的事实。
 *
 * 为什么不观察队列变化来自动刷新：文件也可能被系统清理、被别的应用删除，
 * 队列看不到这些；而且扫描很便宜（一次目录遍历），比维护一套失效通知简单得多。
 */
class LocalFilesViewModel(private val root: File) : ViewModel() {

    private val downloads = LocalDownloads(root)

    private val _state = MutableStateFlow(LocalFilesUiState())
    val state: StateFlow<LocalFilesUiState> = _state.asStateFlow()

    fun start() {
        if (!_state.value.loading) return
        refresh()
    }

    /** 重新扫一遍目录。扫描与删除都放 IO 线程：文件多时会明显卡住主线程。 */
    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { downloads.scan() }
            _state.update { it.copy(loading = false, snapshot = snapshot) }
        }
    }

    /**
     * 点删除：先弹确认，不直接动手。删文件是不可撤销的。
     *
     * 只给一个文件时是单个删除；从选择态进来时是整批。
     */
    fun askDelete(entry: DownloadedEntry) {
        if (_state.value.busy) return
        _state.update { it.copy(confirming = listOf(entry)) }
    }

    fun cancelDelete() {
        _state.update { it.copy(confirming = emptyList()) }
    }

    fun confirmDelete() {
        val entries = _state.value.confirming
        if (entries.isEmpty()) return
        _state.update { it.copy(confirming = emptyList(), busy = true, selected = emptySet()) }
        viewModelScope.launch {
            // 逐个删再统计真正删掉的条数：中途某个文件被系统锁住时，
            // 一句「已删除」会让用户以为整个批量都成功了。
            val removed = withContext(Dispatchers.IO) {
                entries.count { downloads.delete(it.relativePath) }
            }
            val snapshot = withContext(Dispatchers.IO) { downloads.scan() }
            _state.update {
                it.copy(
                    busy = false,
                    snapshot = snapshot,
                    message = LocalFilesLogic.deletedMessage(entries, removed),
                )
            }
        }
    }

    // ---- 多选 ----

    /**
     * 勾选/取消一个文件。
     *
     * 点一下就进入选择态，不需要先按「多选」：这一页最常见的手势是「我要删掉这几个」，
     * 多一个开关只是多一次点击。取消到最后一个时自动退出选择态。
     */
    fun toggleSelect(entry: DownloadedEntry) {
        _state.update {
            val next = it.selected.toMutableSet()
            if (!next.add(entry.relativePath)) next.remove(entry.relativePath)
            it.copy(selected = next)
        }
    }

    fun toggleSelectAll() = _state.update {
        it.copy(selected = LocalFilesLogic.toggleAll(it.visibleRows, it.selected))
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    /** 删除选中的那几项；一个都没选时什么也不做。 */
    fun askDeleteSelected() {
        val snapshot = _state.value
        if (snapshot.selected.isEmpty() || snapshot.busy) return
        // 只认还在磁盘上的那些：列表可能刚被刷新过，选择集里还留着已删掉的路径。
        val entries = snapshot.snapshot.entries.filter { it.relativePath in snapshot.selected }
        if (entries.isEmpty()) {
            _state.update { it.copy(selected = emptySet()) }
            return
        }
        _state.update { it.copy(confirming = entries) }
    }

    fun askClear() {
        if (!_state.value.canClear) return
        _state.update { it.copy(confirmingClear = true) }
    }

    fun cancelClear() {
        _state.update { it.copy(confirmingClear = false) }
    }

    /**
     * 清空全部。
     *
     * 逐个删而不是删目录：删掉根目录之后下载器还要自己 mkdirs 回来，
     * 而且中途失败时已经删掉的部分就统计不清了。这里只统计真正删掉的条数。
     */
    fun confirmClear() {
        if (!_state.value.canClear) return
        val before = _state.value.snapshot
        _state.update { it.copy(confirmingClear = false, busy = true) }
        viewModelScope.launch {
            val (count, freed) = withContext(Dispatchers.IO) {
                var removed = 0
                var bytes = 0L
                for (entry in before.entries) {
                    if (downloads.delete(entry.relativePath)) {
                        removed++
                        bytes += entry.size
                    }
                }
                removed to bytes
            }
            val snapshot = withContext(Dispatchers.IO) { downloads.scan() }
            _state.update {
                it.copy(
                    busy = false,
                    snapshot = snapshot,
                    message = LocalFilesLogic.clearedMessage(count, freed),
                )
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /**
     * 改筛选词。
     *
     * 只改一个字符串，不重扫目录：筛的是已经盘点出来的那一份，
     * 每敲一个字都去遍历磁盘会让输入卡顿，而磁盘内容在打字期间不会变。
     */
    fun setFilter(value: String) = _state.update { it.copy(filter = value) }

    fun clearFilter() = _state.update { it.copy(filter = "") }

    /**
     * 切换类型筛选。再点一次同一类是取消。
     *
     * 和关键字不同，这里一次只看一类：本机文件通常只有几十个，同时要两个类型
     * （比如「图片和视频」）的场景还不足以让界面多出一排多选状态的 chip。
     */
    fun toggleKind(value: FileKind) = _state.update {
        it.copy(kind = if (it.kind == value) null else value)
    }

    fun clearKind() = _state.update { it.copy(kind = null) }

    /**
     * 换排序依据。
     *
     * 再次点同一个依据时反转方向：界面上只有一个列表项列表，再加一个「反向」开关
     * 会让这个对话框变成两个小控件，而用户想做的事只有「反过来看看」。
     * 第一次选到某个依据时用它自己的默认方向——按名字是从 A 往下看，
     * 按时间和大小是先看最新最大的。
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

    /** 反转当前排序方向。 */
    fun toggleSortDirection() = _state.update {
        it.copy(sort = it.sort.copy(descending = !it.sort.descending))
    }

    /**
     * 相对路径 → 真实文件。
     *
     * 只在界面要打开/分享/另存时用：这些动作需要一条真实路径，
     * 而状态里存的是相对路径。越界路径返回 null，界面据此提示。
     */
    fun fileOf(entry: DownloadedEntry): File? {
        if (!downloads.contains(entry.relativePath)) return null
        return File(root, entry.relativePath).takeIf { it.isFile }
    }
}

