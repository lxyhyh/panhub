package com.panhub.feature.shareimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.PageRequest
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

    /** 把选中的条目转存到自己的网盘根目录。 */
    fun save() {
        val provider = scoped ?: return
        val snapshot = _state.value
        val source = snapshot.source ?: return
        val entries = ShareImportLogic.selectedEntries(snapshot.entries, snapshot.selected)
        if (entries.isEmpty()) return
        val folderName = snapshot.accountName.ifBlank { "我的网盘" }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = provider.saveShare(source, entries, null)) {
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
    }
}
