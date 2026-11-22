package com.panhub.feature.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.PageRequest
import com.panhub.core.model.Quota
import com.panhub.core.model.RemoteNode
import com.panhub.core.session.SessionManager
import com.panhub.core.transfer.DownloadLauncher
import com.panhub.core.transfer.UploadLauncher
import com.panhub.provider.api.ScopedPanProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * 文件浏览页的状态。
 *
 * [stack] 是导航栈：网盘大多没有"路径"概念，只有父子 id，
 * 用栈来支持返回上一级比反复查询父节点可靠（有些服务商不返回 parentId）。
 */
data class BrowserUiState(
    val accountId: AccountId? = null,
    val accountName: String = "",
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val nodes: List<RemoteNode> = emptyList(),
    val stack: List<RemoteNode> = emptyList(),
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val quota: Quota? = null,
    val canShare: Boolean = false,
    val canCreateFolder: Boolean = false,
    val canUpload: Boolean = false,
    val canRename: Boolean = false,
    val canDelete: Boolean = false,
    val canMove: Boolean = false,
    val canDownload: Boolean = false,
    val canDirectLink: Boolean = false,
    val error: PanError? = null,
    val message: String? = null,
    val busy: Boolean = false,
) {
    val currentFolder: RemoteNode? get() = stack.lastOrNull()
    val title: String get() = currentFolder?.name ?: accountName
    val canGoUp: Boolean get() = stack.isNotEmpty()
    val parentId: NodeId? get() = currentFolder?.id

    /** 服务商还有下一页时为 true，界面据此显示「加载更多」。 */
    val hasMore: Boolean get() = nextCursor != null

    /** 文件夹在前、同类按名字排。服务商返回的顺序各不相同，这里统一。 */
    val sorted: List<RemoteNode>
        get() = nodes.sortedWith(
            compareByDescending<RemoteNode> { it.isDir }.thenBy { it.name.lowercase() },
        )
}

/**
 * 「移动到」对话框的状态。
 *
 * 目录导航单独放一份栈，而不是复用 [BrowserUiState.stack]：用户在选择目标目录时
 * 不应该把浏览页也一起带跑（取消后要回到原来的位置）。
 */
data class FolderPickerState(
    val visible: Boolean = false,
    /** 被移动的节点。 */
    val node: RemoteNode? = null,
    /** 被移动节点原来所在的目录；null 表示根目录。 */
    val originParentId: NodeId? = null,
    val stack: List<RemoteNode> = emptyList(),
    val folders: List<RemoteNode> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    val current: RemoteNode? get() = stack.lastOrNull()

    /** 当前所在目录；null 表示根目录。 */
    val targetId: NodeId? get() = current?.id

    val title: String get() = if (stack.isEmpty()) "全部文件" else stack.joinToString(" / ") { it.name }

    /**
     * 目标目录等于原目录时不能确认。
     *
     * 这不是防呆而是必要判断：多数服务商的移动接口在「移到原地」上行为不一致，
     * 有的报错「目标已存在」，有的把文件改名加后缀，有的静默成功。
     * 与其让用户看到莫名其妙的失败，不如在这里直接挡住。
     */
    val canConfirm: Boolean get() = node != null && !loading && targetId != originParentId
}

/**
 * 文件浏览。
 *
 * 深模块化的关键点：这一层只认识 [ScopedPanProvider] 和 [RemoteNode]，
 * 完全不知道底下是 WebDAV 还是 123 云盘。所以新增网盘时这个文件不用改。
 */
class BrowserViewModel(
    private val session: SessionManager,
    private val accountId: AccountId,
    private val launcher: DownloadLauncher? = null,
    private val uploader: UploadLauncher? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(BrowserUiState(accountId = accountId))
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private val _picker = MutableStateFlow(FolderPickerState())
    val picker: StateFlow<FolderPickerState> = _picker.asStateFlow()

    private var scoped: ScopedPanProvider? = null
    private var loadJob: Job? = null

    /**
     * 打开「移动到」选择器。
     *
     * 起始目录固定在根目录而不是上次的位置：移动是做一次性的目录选择，
     * 记住上次位置反而容易让用户在手快时把文件丢进一个不相干的目录。
     */
    fun openFolderPicker(node: RemoteNode) {
        val provider = scoped ?: return
        _picker.value = FolderPickerState(visible = true, node = node, loading = true)
        loadPicker(provider, null)
    }

    fun closeFolderPicker() {
        _picker.value = FolderPickerState()
    }

    /** 进入选择器里的下一级文件夹。 */
    fun enterPickerFolder(folder: RemoteNode) {
        val provider = scoped ?: return
        if (!folder.isDir) return
        _picker.update { it.copy(stack = it.stack + folder, loading = true, error = null) }
        loadPicker(provider, folder.id)
    }

    /** 选择器里返回上一级。 */
    fun leavePickerFolder() {
        val provider = scoped ?: return
        val stack = _picker.value.stack
        if (stack.isEmpty()) return
        val next = stack.dropLast(1)
        _picker.update { it.copy(stack = next, loading = true, error = null) }
        loadPicker(provider, next.lastOrNull()?.id)
    }

    /** 确认移动到当前目录。 */
    fun confirmMove() {
        val provider = scoped ?: return
        val snapshot = _picker.value
        val node = snapshot.node ?: return
        if (!snapshot.canConfirm) return
        val target = snapshot.targetId
        closeFolderPicker()
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = provider.move(node.id, target)) {
                is PanResult.Success -> {
                    _state.update { it.copy(busy = false, message = "已移动 " + node.name) }
                    load(refresh = true)
                }
                is PanResult.Failure -> _state.update {
                    it.copy(busy = false, message = it.errorText(result.error))
                }
            }
        }
    }

    /**
     * 只列文件夹，不列文件。
     *
     * 为什么不在这一层过滤：服务商的分页是逐页的，如果这一页全是文件，
     * 过滤后就是空白页，「加载更多」会出现但点了也没东西——用户会以为坏了。
     * 所以这里顺着游标往后翻，直到攒够一页文件夹或翻到底，最多 5 次请求
     * （再多就是在拿用户的流量做无用功）。
     */
    private fun loadPicker(provider: ScopedPanProvider, parentId: NodeId?) {
        viewModelScope.launch {
            var cursor: String? = null
            var rounds = 0
            val folders = mutableListOf<RemoteNode>()
            while (true) {
                when (
                    val result = provider.list(
                        parentId,
                        PageRequest(cursor = cursor, limit = PICKER_PAGE_SIZE),
                    )
                ) {
                    is PanResult.Failure -> {
                        _picker.update { it.copy(loading = false, folders = emptyList(), error = result.error.message) }
                        return@launch
                    }
                    is PanResult.Success -> {
                        val page = result.value
                        folders += page.items.filter { it.isDir }
                        cursor = page.nextCursor
                        rounds++
                        if (folders.isNotEmpty() || cursor == null || rounds >= PICKER_MAX_ROUNDS) {
                            _picker.update {
                                it.copy(loading = false, folders = folders, error = null)
                            }
                            return@launch
                        }
                    }
                }
            }
        }
    }

    /** 打开任意节点（文件夹则进入，文件则展示信息）。 */
    fun open(node: RemoteNode) {
        if (node.isDir) {
            enter(node)
        } else {
            _state.update { it.copy(message = node.name) }
        }
    }

    fun enter(folder: RemoteNode) {
        if (!folder.isDir) return
        _state.update { it.copy(stack = it.stack + folder) }
        // 换目录必须清空旧列表：否则新目录加载期间会短暂显示上一级的文件，
        // 加载失败时更会一直停在那里，看起来像是进了错的目录。
        reloadFromScratch()
    }

    /** 返回上一级；已在根目录时返回 false，交给系统返回键处理。 */
    fun goUp(): Boolean {
        val stack = _state.value.stack
        if (stack.isEmpty()) return false
        _state.update { it.copy(stack = stack.dropLast(1)) }
        reloadFromScratch()
        return true
    }

    fun refresh() = load(refresh = true)

    fun createFolder(name: String) {
        val provider = scoped ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = provider.createFolder(_state.value.parentId, trimmed)) {
                is PanResult.Success -> {
                    _state.update { it.copy(busy = false, message = "已创建 ${result.value.name}") }
                    load(refresh = true)
                }
                is PanResult.Failure -> _state.update {
                    it.copy(busy = false, message = it.errorText(result.error))
                }
            }
        }
    }

    fun rename(node: RemoteNode, newName: String) {
        val provider = scoped ?: return
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == node.name) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = provider.rename(node.id, trimmed)) {
                is PanResult.Success -> {
                    _state.update { it.copy(busy = false, message = "已重命名") }
                    load(refresh = true)
                }
                is PanResult.Failure -> _state.update {
                    it.copy(busy = false, message = it.errorText(result.error))
                }
            }
        }
    }

    fun delete(node: RemoteNode) {
        val provider = scoped ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = provider.delete(node.id)) {
                is PanResult.Success -> {
                    _state.update { it.copy(busy = false, message = "已删除 ${node.name}") }
                    load(refresh = true)
                }
                is PanResult.Failure -> _state.update {
                    it.copy(busy = false, message = it.errorText(result.error))
                }
            }
        }
    }

    /** 拿到直链后交给浏览器/下载器；这里只负责取。 */
    fun directLink(node: RemoteNode, onReady: (String) -> Unit) {
        val provider = scoped ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = provider.downloadUrl(node.id)) {
                is PanResult.Success -> {
                    _state.update { it.copy(busy = false) }
                    onReady(result.value.url)
                }
                is PanResult.Failure -> _state.update {
                    it.copy(busy = false, message = it.errorText(result.error))
                }
            }
        }
    }

    fun createShare(node: RemoteNode, onReady: (String) -> Unit) {
        val provider = scoped ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = provider.createShare(listOf(node.id))) {
                is PanResult.Success -> {
                    _state.update { it.copy(busy = false) }
                    onReady(result.value.url)
                }
                is PanResult.Failure -> _state.update {
                    it.copy(busy = false, message = it.errorText(result.error))
                }
            }
        }
    }

    /** 把文件加入下载队列。文件夹不支持（需要先有递归遍历与打包策略）。 */
    fun download(node: RemoteNode) {
        if (node.isDir) {
            _state.update { it.copy(message = "暂不支持下载整个文件夹") }
            return
        }
        val target = launcher ?: run {
            _state.update { it.copy(message = "下载功能不可用") }
            return
        }
        target.download(accountId, node.id, node.name, node.size.takeIf { it > 0L })
        _state.update { it.copy(message = "已加入下载队列：" + node.name) }
    }

    /**
     * 把已经复制到本地的文件上传到当前目录。
     *
     * 为什么这里收 [File] 而不是 Uri：Uri 的解析在 View 层已经做完了（见 LocalFilePicker），
     * ViewModel 只处理「有这么一个本地文件要传」这个纯粹的事实，
     * 这样它在纯 JVM 下可测，也不需要认识 ContentResolver。
     */
    fun upload(file: File, name: String) {
        val target = uploader ?: run {
            _state.update { it.copy(message = "上传功能不可用") }
            return
        }
        val size = file.length()
        if (size <= 0L) {
            _state.update { it.copy(message = "文件为空，无法上传") }
            return
        }
        target.upload(accountId, _state.value.parentId, file.absolutePath, name, size)
        _state.update { it.copy(message = "已加入上传队列：" + name) }
    }

    /** 只用于把视图层的失败反馈带出去（例如选文件失败）。 */
    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** 首次进入：连接账号 → 取容量 → 列根目录。 */
    fun start() {
        if (scoped != null) return
        viewModelScope.launch {
            // 账号显示名从 accounts 流里取：它是唯一权威来源，
            // 页面不应该自己再拼一份可能过期的名字。
            val summary = session.accounts.first().firstOrNull { it.id == accountId }

            when (val result = session.connect(accountId)) {
                is PanResult.Failure -> _state.update {
                    it.copy(loading = false, error = result.error)
                }
                is PanResult.Success -> {
                    scoped = result.value
                    val descriptor = result.value.descriptor
                    _state.update {
                        it.copy(
                            accountName = summary?.displayName ?: descriptor.displayName,
                            canShare = descriptor.capabilities.share,
                            canCreateFolder = descriptor.capabilities.createFolder,
                            canUpload = descriptor.capabilities.upload,
                            canRename = descriptor.capabilities.rename,
                            canDelete = descriptor.capabilities.delete,
                            canMove = descriptor.capabilities.move,
                            canDownload = descriptor.capabilities.download,
                            canDirectLink = descriptor.capabilities.directLink,
                        )
                    }
                    loadQuota(result.value)
                    load()
                }
            }
        }
    }

    /** 重新连接（错误重试）。 */
    fun retry() {
        scoped = null
        _state.update { it.copy(loading = true, error = null) }
        start()
    }

    private fun loadQuota(provider: ScopedPanProvider) {
        viewModelScope.launch {
            val result = provider.quota()
            if (result is PanResult.Success) {
                _state.update { it.copy(quota = result.value) }
            }
        }
    }

    private fun load(refresh: Boolean = false) {
        val provider = scoped ?: return
        loadJob?.cancel()
        _state.update {
            if (it.nodes.isEmpty()) it.copy(loading = true, error = null)
            else it.copy(refreshing = true, error = null)
        }
        loadJob = viewModelScope.launch {
            when (val result = provider.list(_state.value.parentId)) {
                is PanResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        nodes = result.value.items,
                        nextCursor = result.value.nextCursor,
                        error = null,
                    )
                }
                is PanResult.Failure -> _state.update {
                    // 刷新失败时保留旧内容，只有首次加载失败才显示整页错误
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = if (it.nodes.isEmpty()) result.error else null,
                        message = if (it.nodes.isEmpty()) null else it.errorText(result.error),
                    )
                }
            }
        }
    }

    /**
     * 拉取下一页，追加到当前列表末尾。
     *
     * 为什么不做「滚到底自动加载」：网盘目录动辄上千项，而多数服务商的
     * 分页游标不可重放；用户没打算看完整目录时，多拉的每一页都是真实流量。
     * 明确点一下「加载更多」把主动权交给用户。
     */
    fun loadMore() {
        val provider = scoped ?: return
        val cursor = _state.value.nextCursor ?: return
        if (_state.value.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            when (val result = provider.list(_state.value.parentId, PageRequest(cursor = cursor))) {
                is PanResult.Success -> _state.update {
                    // 服务商在某些边界会重复返回同一项，追加时按 id 去重，
                    // 否则 LazyColumn 的 key 会冲突。
                    val known = it.nodes.mapTo(mutableSetOf()) { node -> node.id.value }
                    val extra = result.value.items.filter { node -> known.add(node.id.value) }
                    it.copy(
                        loadingMore = false,
                        nodes = it.nodes + extra,
                        nextCursor = result.value.nextCursor,
                    )
                }
                is PanResult.Failure -> _state.update {
                    it.copy(loadingMore = false, message = it.errorText(result.error))
                }
            }
        }
    }

    /** 清空分页并重新加载（切换目录时用，避免把上一页的数据带进来）。 */
    private fun reloadFromScratch() {
        _state.update { it.copy(nodes = emptyList(), nextCursor = null) }
        load()
    }

    private fun BrowserUiState.errorText(error: PanError): String = error.message

    private companion object {
        /** 选择目标目录时每页多大。只要文件夹，页面越小越快攒够一屏。 */
        const val PICKER_PAGE_SIZE = 50

        /** 连续翻页找文件夹的上限，避免一页全是文件时无限翻下去。 */
        const val PICKER_MAX_ROUNDS = 5
    }
}
