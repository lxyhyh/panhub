package com.panhub.feature.shareimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.PageRequest
import com.panhub.core.model.RemoteNode
import com.panhub.core.model.ShareEntry
import com.panhub.core.model.ShareSource
import com.panhub.core.session.AccountSummary
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
 * 「转存到哪个目录」对话框的状态。
 *
 * 单独一份栈而不是复用 [ShareImportUiState.stack]：前者在别人的分享里，
 * 后者在自己的网盘里，两边混在一起会让「返回」键的含义随上下文漂移。
 */
data class TargetPickerState(
    val visible: Boolean = false,
    val stack: List<RemoteNode> = emptyList(),
    val folders: List<RemoteNode> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    val current: RemoteNode? get() = stack.lastOrNull()

    /** 当前选中的目标目录；null 表示根目录。 */
    val targetId: NodeId? get() = current?.id

    val canGoUp: Boolean get() = stack.isNotEmpty()

    val title: String get() = stack.joinToString(" / ") { it.name }.ifBlank { "我的网盘" }
}

/**
 * 打开分享页的状态。
 *
 * 把「填链接」「浏览内容」「转存」放在同一个状态里，是因为它们本来就是一次连续动作：
 * 分成三个页面，用户点错返回就会丢掉已经拿到的 stoken，还得重新解析一遍链接。
 */
data class ShareImportUiState(
    val loading: Boolean = true,
    val accounts: List<AccountSummary> = emptyList(),
    /** 当前用来打开分享的账号；空表示还没选。 */
    val accountId: AccountId? = null,
    val accountName: String = "",
    /** 用户粘进来的链接。 */
    val url: String = "",
    val passcode: String = "",
    /** 已经打开的分享；非空表示进入浏览态。 */
    val source: ShareSource? = null,
    val entries: List<ShareEntry> = emptyList(),
    val stack: List<ShareEntry> = emptyList(),
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val selected: Set<NodeId> = emptySet(),
    /** 转存目标目录栈；空表示直接存到自己网盘根目录。 */
    val target: List<RemoteNode> = emptyList(),
    val picker: TargetPickerState = TargetPickerState(),
    val busy: Boolean = false,
    val error: PanError? = null,
    val message: String? = null,
) {
    val hasAccounts: Boolean get() = accounts.isNotEmpty()

    val opened: Boolean get() = source != null

    val currentFolder: NodeId? get() = ShareImportLogic.currentFolder(stack)

    val canGoUp: Boolean get() = stack.isNotEmpty()

    val hasMore: Boolean get() = nextCursor != null

    val selectedCount: Int get() = selected.size

    val allSelected: Boolean get() = ShareImportLogic.allSelected(entries, selected)

    val canOpen: Boolean get() = accountId != null && url.isNotBlank() && !busy

    val canSave: Boolean get() = opened && selected.isNotEmpty() && !busy

    /** 转存目标目录 id；null 表示自己网盘的根目录。 */
    val targetId: NodeId? get() = target.lastOrNull()?.id

    /** 转存目标在界面上的一句话名字。 */
    val targetLabel: String get() = ShareImportLogic.targetLabel(target)

    /** 标题：浏览态显示分享标题，否则显示页面名。 */
    val title: String
        get() {
            val opened = source ?: return "打开分享"
            val shareTitle = opened.title?.takeIf { it.isNotBlank() } ?: "分享内容"
            return if (stack.isEmpty()) shareTitle else stack.joinToString(" / ") { it.name }
        }
}

/**
 * 打开别人分享并把内容转存到自己的网盘。
 *
 * 一个页面里做三件事：选账号、粘链接、挑文件转存。
 * 为什么不用「先选账号再进页面」的老路：转存经常发生在「刚收到链接、
 * 顺手用自己的网盘存一份」的场景里，让用户先回账号页再进来会打断这个动作。
 *
 * 这一层只认识 [ScopedPanProvider] 与 [ShareEntry]，
 * 完全不知道底下是夸克还是别家；支持转存的服务商只要实现 openShare /
 * listShare / saveShare 就能直接用。
 */
class ShareImportViewModel(
    private val session: SessionManager,
    private val presetAccountId: AccountId? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(ShareImportUiState())
    val state: StateFlow<ShareImportUiState> = _state.asStateFlow()

    private var scoped: ScopedPanProvider? = null
    private var started = false
    private var loadJob: Job? = null
    private var pickerJob: Job? = null

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            val all = session.accounts.first()
            val eligible = ShareImportLogic.eligibleAccounts(all)
            if (eligible.isEmpty()) {
                _state.update {
                    it.copy(
                        loading = false,
                        accounts = emptyList(),
                        error = PanError.unknown("还没有支持转存的账号"),
                    )
                }
                return@launch
            }
            // 预置的账号优先（从某个账号的分享页进来时），否则用最近使用的那一个。
            val chosen = eligible.firstOrNull { it.id == presetAccountId } ?: eligible.first()
            _state.update {
                it.copy(loading = false, accounts = eligible, accountId = chosen.id, accountName = chosen.displayName)
            }
            connect(chosen.id)
        }
    }

    /** 换一个账号来接收转存。已打开的分享会作废：stoken 是绑定账号的。 */
    fun selectAccount(accountId: AccountId) {
        val summary = _state.value.accounts.firstOrNull { it.id == accountId } ?: return
        _state.update {
            it.copy(
                accountId = accountId,
                accountName = summary.displayName,
                source = null,
                entries = emptyList(),
                stack = emptyList(),
                selected = emptySet(),
                nextCursor = null,
                target = emptyList(),
                picker = TargetPickerState(),
                error = null,
            )
        }
        connect(accountId)
    }

    private fun connect(accountId: AccountId) {
        viewModelScope.launch {
            when (val result = session.connect(accountId)) {
                is PanResult.Failure -> _state.update { it.copy(error = result.error) }
                is PanResult.Success -> scoped = result.value
            }
        }
    }

    fun setUrl(value: String) {
        _state.update { it.copy(url = value, error = null) }
    }

    fun setPasscode(value: String) {
        _state.update { it.copy(passcode = value) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** 用当前链接打开分享。 */
    fun open() {
        val provider = scoped ?: return
        val snapshot = _state.value
        if (!snapshot.canOpen) return
        val url = snapshot.url.trim()
        val passcode = snapshot.passcode.trim().takeIf { it.isNotEmpty() }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = provider.openShare(url, passcode)) {
                is PanResult.Failure -> _state.update { it.copy(busy = false, error = result.error) }
                is PanResult.Success -> {
                    _state.update {
                        it.copy(
                            busy = false,
                            source = result.value,
                            entries = emptyList(),
                            stack = emptyList(),
                            selected = emptySet(),
                            nextCursor = null,
                        )
                    }
                    load(null)
                }
            }
        }
    }

    /** 重新打开同一条链接（票据过期时用）。 */
    fun reopen() {
        val url = _state.value.url
        if (url.isBlank()) return
        _state.update { it.copy(url = url, source = null) }
        open()
    }

    fun refresh() = load(_state.value.currentFolder, refresh = true)

    private fun load(parentId: NodeId?, refresh: Boolean = false) {
        val provider = scoped ?: return
        val source = _state.value.source ?: return
        loadJob?.cancel()
        _state.update {
            it.copy(
                loadingMore = false,
                error = null,
                // 刷新时保留列表，避免整页闪一下白屏。
                entries = if (refresh) it.entries else emptyList(),
            )
        }
        loadJob = viewModelScope.launch {
            when (val result = provider.listShare(source, parentId, PageRequest(limit = PAGE_SIZE))) {
                is PanResult.Failure -> _state.update { it.copy(error = result.error) }
                is PanResult.Success -> _state.update {
                    it.copy(
                        entries = result.value.items,
                        nextCursor = result.value.nextCursor,
                        selected = emptySet(),
                    )
                }
            }
        }
    }

    /** 进入分享里的下一级目录。 */
    fun enter(folder: ShareEntry) {
        if (!folder.isDir) return
        _state.update { it.copy(stack = it.stack + folder) }
        load(folder.id)
    }

    /** 返回上一级；已经在根目录时什么也不做。 */
    fun goUp() {
        val stack = _state.value.stack
        if (stack.isEmpty()) return
        val next = ShareImportLogic.parentStack(stack)
        _state.update { it.copy(stack = next) }
        load(next.lastOrNull()?.id)
    }

    fun toggle(nodeId: NodeId) {
        _state.update {
            val next = it.selected.toMutableSet()
            if (!next.add(nodeId)) next.remove(nodeId)
            it.copy(selected = next)
        }
    }

    fun toggleAll() {
        _state.update { it.copy(selected = ShareImportLogic.toggleAll(it.entries, it.selected)) }
    }

    // ---- 转存目标目录 ----

    /**
     * 打开「转存到」选择器。
     *
     * 每次都从根目录开始，而不是上次停留的位置：转存是低频的一次性选择，
     * 记住上次目录反而容易让人在手快时把文件丢进一个不相干的文件夹。
     */
    fun openTargetPicker() {
        val provider = scoped ?: return
        _state.update {
            it.copy(picker = TargetPickerState(visible = true, loading = true))
        }
        loadPicker(provider, null)
    }

    fun closeTargetPicker() {
        pickerJob?.cancel()
        _state.update { it.copy(picker = TargetPickerState()) }
    }

    /** 在选择器里进入下一级文件夹。 */
    fun enterTargetFolder(folder: RemoteNode) {
        val provider = scoped ?: return
        if (!folder.isDir) return
        _state.update {
            it.copy(picker = it.picker.copy(stack = it.picker.stack + folder, loading = true, error = null))
        }
        loadPicker(provider, folder.id)
    }

    /** 在选择器里返回上一级。 */
    fun leaveTargetFolder() {
        val provider = scoped ?: return
        val stack = _state.value.picker.stack
        if (stack.isEmpty()) return
        val next = ShareImportLogic.targetStack(stack)
        _state.update {
            it.copy(picker = it.picker.copy(stack = next, loading = true, error = null))
        }
        loadPicker(provider, next.lastOrNull()?.id)
    }

    /** 确认目标目录：把选择器的栈搬到主状态里，之后转存都落在这里。 */
    fun confirmTarget() {
        val stack = _state.value.picker.stack
        pickerJob?.cancel()
        _state.update { it.copy(target = stack, picker = TargetPickerState()) }
    }

    private fun loadPicker(provider: ScopedPanProvider, parentId: NodeId?) {
        pickerJob?.cancel()
        pickerJob = viewModelScope.launch {
            when (val result = provider.list(parentId, PageRequest(limit = PICKER_PAGE_SIZE))) {
                is PanResult.Failure -> _state.update {
                    it.copy(picker = it.picker.copy(loading = false, folders = emptyList(), error = result.error.message))
                }
                is PanResult.Success -> _state.update {
                    it.copy(
                        picker = it.picker.copy(
                            loading = false,
                            folders = ShareImportLogic.folderOnly(result.value.items),
                            error = null,
                        ),
                    )
                }
            }
        }
    }

    /** 把选中的条目转存到当前目标目录（默认是自己的网盘根目录）。 */
    fun save() {
        val provider = scoped ?: return
        val snapshot = _state.value
        val source = snapshot.source ?: return
        val entries = ShareImportLogic.selectedEntries(snapshot.entries, snapshot.selected)
        if (entries.isEmpty()) return
        val folderName = snapshot.targetLabel
        val target = snapshot.targetId
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = provider.saveShare(source, entries, target)) {
                is PanResult.Failure -> _state.update { it.copy(busy = false, error = result.error) }
                is PanResult.Success -> _state.update {
                    it.copy(
                        busy = false,
                        selected = emptySet(),
                        message = ShareImportLogic.savedMessage(result.value, entries.size, folderName),
                    )
                }
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val PICKER_PAGE_SIZE = 200
    }
}
