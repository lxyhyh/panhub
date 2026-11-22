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
import com.panhub.core.model.ShareLink
import com.panhub.core.model.ShareOptions
import com.panhub.core.model.SortOrder
import com.panhub.core.model.SortSpec
import com.panhub.core.model.sortedFor
import com.panhub.core.session.AccountSummary
import com.panhub.core.session.SessionManager
import com.panhub.core.transfer.CloudCopyLauncher
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
    val canSharePassword: Boolean = false,
    val canShareExpiry: Boolean = false,
    val canCreateFolder: Boolean = false,
    val canUpload: Boolean = false,
    val canRename: Boolean = false,
    val canDelete: Boolean = false,
    val canMove: Boolean = false,
    val canDownload: Boolean = false,
    val canDirectLink: Boolean = false,
    val canSearch: Boolean = false,
    /** 至少还有一个别的账号能接收文件时才有「复制到其他网盘」。 */
    val canCloudCopy: Boolean = false,
    val error: PanError? = null,
    val message: String? = null,
    val busy: Boolean = false,
    /** 多选模式下被勾选的节点。空集合就代表不在多选模式。 */
    val selected: Set<NodeId> = emptySet(),
    /** 列表排序。纯本地行为，与服务商无关，所以不需要能力门控。 */
    val sort: SortSpec = SortSpec(),
) {
    val currentFolder: RemoteNode? get() = stack.lastOrNull()

    /** 有勾选项即进入多选模式：不需要额外的开关，少一个状态就少一处不一致。 */
    val selectionMode: Boolean get() = selected.isNotEmpty()

    val selectedCount: Int get() = selected.size

    /** 当前已加载的项目是否全选。只在多选模式下有意义。 */
    val allSelected: Boolean get() = nodes.isNotEmpty() && nodes.all { it.id in selected }
    val title: String get() = currentFolder?.name ?: accountName
    val canGoUp: Boolean get() = stack.isNotEmpty()
    val parentId: NodeId? get() = currentFolder?.id

    /** 服务商还有下一页时为 true，界面据此显示「加载更多」。 */
    val hasMore: Boolean get() = nextCursor != null

    /** 服务商返回的顺序各不相同，一律按用户选的规格重排后再显示。 */
    val sorted: List<RemoteNode> get() = nodes.sortedFor(sort)
}

/**
 * 「复制到其他网盘」对话框的状态。
 *
 * 只需要目标账号与目标目录两项，所以不复用目录选择器：那个选择器是为了
 * 「在同一个账号内移动」，跨账号时还要先选账号，混在一起会让两层导航互相打断。
 */
data class CloudCopyState(
    val visible: Boolean = false,
    val nodes: List<RemoteNode> = emptyList(),
    val targets: List<AccountSummary> = emptyList(),
    val targetId: AccountId? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = nodes.isNotEmpty() && targetId != null && !busy

    /** 目标账号显示名，提交后拿它写提示。 */
    val targetName: String get() = targets.firstOrNull { it.id == targetId }?.displayName ?: ""
}

/**
 * 搜索页的状态。
 *
 * 单独一份而不是复用 [BrowserUiState.nodes]：搜索结果是跨目录的，
 * 塞进当前目录的列表后，用户按返回键回到上一级会看到一堆不属于这个目录的文件。
 */
data class SearchState(
    val visible: Boolean = false,
    val query: String = "",
    val busy: Boolean = false,
    val results: List<RemoteNode> = emptyList(),
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val error: String? = null,
) {
    val hasMore: Boolean get() = nextCursor != null

    /** 关键字为空时不发请求：多数服务商对空关键字返回全盘列表，等于没搜。 */
    val canSubmit: Boolean get() = query.isNotBlank() && !busy
}

/**
 * 「移动到」对话框的状态。
 *
 * 目录导航单独放一份栈，而不是复用 [BrowserUiState.stack]：用户在选择目标目录时
 * 不应该把浏览页也一起带跑（取消后要回到原来的位置）。
 */
data class FolderPickerState(
    val visible: Boolean = false,
    /** 被移动的节点；多选时会有多个。 */
    val nodes: List<RemoteNode> = emptyList(),
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
    val canConfirm: Boolean get() = nodes.isNotEmpty() && !loading && targetId != originParentId
}

/**
 * 分享对话框的状态。
 *
 * 为什么把表单和结果放在同一个状态里：分享是一次「填完 → 拿到链接」的连贯动作，
 * 分成两个对话框会让用户在中途失去上下文（尤其是想确认密码有没有生效时）。
 */
data class ShareSheetState(
    val visible: Boolean = false,
    /** 被分享的节点；多选时会有多个。 */
    val nodes: List<RemoteNode> = emptyList(),
    /** 访问密码；空表示不设。只有服务商支持时才展示这一项。 */
    val password: String = "",
    /** 有效期天数；null 表示永久。只有服务商支持时才展示这一项。 */
    val expireDays: Int? = null,
    val busy: Boolean = false,
    val error: String? = null,
    /** 创建成功后拿到的链接；非空时对话框切换到结果视图。 */
    val result: ShareLink? = null,
) {
    val done: Boolean get() = result != null

    /** 对话框标题里显示的对象：一个就写名字，多个写数量。 */
    val subject: String
        get() = when (nodes.size) {
            0 -> ""
            1 -> nodes.first().name
            else -> nodes.size.toString() + " 个项目"
        }
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
    private val copier: CloudCopyLauncher? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(BrowserUiState(accountId = accountId))
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private val _picker = MutableStateFlow(FolderPickerState())
    val picker: StateFlow<FolderPickerState> = _picker.asStateFlow()

    private val _copy = MutableStateFlow(CloudCopyState())

    val copy: StateFlow<CloudCopyState> = _copy.asStateFlow()

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val _share = MutableStateFlow(ShareSheetState())
    val share: StateFlow<ShareSheetState> = _share.asStateFlow()

    private var scoped: ScopedPanProvider? = null
    private var loadJob: Job? = null

    /**
     * 打开「移动到」选择器。
     *
     * 起始目录固定在根目录而不是上次的位置：移动是做一次性的目录选择，
     * 记住上次位置反而容易让用户在手快时把文件丢进一个不相干的目录。
     */
    fun openFolderPicker(nodes: List<RemoteNode>) {
        val provider = scoped ?: return
        if (nodes.isEmpty()) return
        _picker.value = FolderPickerState(
            visible = true,
            nodes = nodes,
            // 多选时这些节点可能来自不同目录，无法判定"原目录"，
            // 于是传一个不可能等于任何目标目录的哨兵，一律允许确认。
            originParentId = nodes.singleOrNull()?.parentId ?: UNSET_PARENT,
            loading = true,
        )
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
        val nodes = snapshot.nodes
        if (!snapshot.canConfirm) return
        val target = snapshot.targetId
        closeFolderPicker()
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            // 逐个移动而不是找批量接口：只有少数服务商有批量移动，
            // 而逐个移动的失败语义是明确的——成功几个就是几个，报错也只针对那一个。
            var moved = 0
            var firstError: String? = null
            for (node in nodes) {
                when (val result = provider.move(node.id, target)) {
                    is PanResult.Success -> moved++
                    is PanResult.Failure -> if (firstError == null) firstError = result.error.message
                }
            }
            _state.update {
                it.copy(
                    busy = false,
                    message = moveMessage(moved, nodes.size, firstError),
                    selected = emptySet(),
                )
            }
            load(refresh = true)
        }
    }

    /** 移动结果的一句话。部分成功要说清楚，否则用户会以为全成了。 */
    private fun moveMessage(moved: Int, total: Int, error: String?): String = when {
        moved == total -> "已移动 " + total + " 项"
        moved == 0 -> error ?: "移动失败"
        else -> "已移动 " + moved + "/" + total + " 项：" + (error ?: "部分失败")
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

    /**
     * 多选：切换某一项的勾选状态。
     *
     * 勾选即进入多选模式，取消最后一个勾选就退出——不需要一个单独的"进入多选"
     * 开关，少一个状态就少一处会和列表不同步的地方。
     */
    fun toggleSelect(node: RemoteNode) {
        _state.update {
            val next = if (node.id in it.selected) it.selected - node.id else it.selected + node.id
            it.copy(selected = next)
        }
    }

    /** 全选当前已加载的项目；已全选则清空（同一个入口来回切）。 */
    fun toggleSelectAll() {
        _state.update {
            it.copy(selected = if (it.allSelected) emptySet() else it.nodes.mapTo(mutableSetOf()) { n -> n.id })
        }
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    /**
     * 换排序依据。
     *
     * 方向有一个"合理默认"：名称从小到大、时间与大小从新/从大到小——
     * 找文件时想先看最新的，但按名字找时想从 A 开始。
     * 再点一次已经选中的依据就翻转方向，这样用户不必先去按下面的"反向"。
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

    private fun selectedNodes(): List<RemoteNode> {
        val current = _state.value
        val byId = current.nodes.associateBy { it.id }
        return current.selected.mapNotNull { byId[it] }
    }

    /** 把多选的项目交给某个动作；动作结束后统一清空选择。 */
    fun withSelection(action: (List<RemoteNode>) -> Unit) {
        val nodes = selectedNodes()
        if (nodes.isEmpty()) return
        action(nodes)
    }

    /**
     * 批量下载。文件夹会各自展开成一个扫描任务，文件单独排队。
     *
     * 这里不预先统计文件数：目录里有什么要等服务商列完才知道，
     * 先给出一个凭空的数字反而误导。
     */
    fun downloadSelected() {
        val target = launcher ?: run {
            _state.update { it.copy(message = "下载功能不可用") }
            return
        }
        val picked = selectedNodes()
        if (picked.isEmpty()) return
        for (node in picked) {
            if (node.isDir) {
                target.downloadFolder(accountId, node.id, node.name)
            } else {
                target.download(accountId, node.id, node.name, node.size.takeIf { it > 0L })
            }
        }
        val folders = picked.count { it.isDir }
        val files = picked.size - folders
        _state.update {
            it.copy(
                selected = emptySet(),
                message = buildString {
                    append("已加入下载队列：")
                    append(files)
                    append(" 个文件")
                    if (folders > 0) {
                        append("，")
                        append(folders)
                        append(" 个文件夹正在整理")
                    }
                },
            )
        }
    }

    /** 批量删除。没有批量接口，逐个删并统计结果。 */
    fun deleteSelected() {
        val provider = scoped ?: return
        val nodes = selectedNodes()
        if (nodes.isEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            var done = 0
            var firstError: String? = null
            for (node in nodes) {
                when (val result = provider.delete(node.id)) {
                    is PanResult.Success -> done++
                    is PanResult.Failure -> if (firstError == null) firstError = result.error.message
                }
            }
            _state.update {
                it.copy(
                    busy = false,
                    selected = emptySet(),
                    message = when {
                        done == nodes.size -> "已删除 " + done + " 项"
                        done == 0 -> firstError ?: "删除失败"
                        else -> "已删除 " + done + "/" + nodes.size + " 项"
                    },
                )
            }
            load(refresh = true)
        }
    }

    /** 打开分享对话框。 */
    fun openShareSheet(nodes: List<RemoteNode>) {
        if (nodes.isEmpty()) return
        _share.value = ShareSheetState(visible = true, nodes = nodes)
    }

    fun closeShareSheet() {
        _share.value = ShareSheetState()
    }

    fun setSharePassword(value: String) = _share.update { it.copy(password = value, error = null) }

    fun setShareExpireDays(days: Int?) = _share.update { it.copy(expireDays = days, error = null) }

    /**
     * 创建分享。
     *
     * 参数只在服务商声明支持时才转发：123 云盘的 sharePwd 传空串会被当成
     * 「设一个空密码」而不是「不要密码」，所以这里显式给 null 而不是空串。
     */
    fun submitShare() {
        val provider = scoped ?: return
        val snapshot = _share.value
        val nodes = snapshot.nodes
        if (nodes.isEmpty() || snapshot.busy) return
        _share.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val options = ShareOptions(
                password = snapshot.password.trim().takeIf { it.isNotEmpty() },
                expireDays = snapshot.expireDays,
            )
            when (val result = provider.createShare(nodes.map { it.id }, options)) {
                is PanResult.Success -> _share.update {
                    it.copy(busy = false, result = result.value, error = null)
                }
                is PanResult.Failure -> _share.update {
                    it.copy(busy = false, error = result.error.message)
                }
            }
        }
    }

    /** 把文件或文件夹加入下载队列。目录会先扫描再逐个下载。 */
    fun download(node: RemoteNode) {
        val target = launcher ?: run {
            _state.update { it.copy(message = "下载功能不可用") }
            return
        }
        if (node.isDir) {
            target.downloadFolder(accountId, node.id, node.name)
            _state.update { it.copy(message = "正在整理「" + node.name + "」里的文件，稍后开始下载") }
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

    /**
     * 一次上传多个文件。
     *
     * 与单个上传的差别只有汇总提示：每个文件仍然是一条独立任务，
     * 由传输队列串行跑。空文件在入队前就筛掉，否则它会变成一条
     * 必然失败的任务，还要用户自己去传输页里找原因。
     */
    fun uploadAll(files: List<Pair<File, String>>) {
        val target = uploader ?: run {
            _state.update { it.copy(message = "上传功能不可用") }
            return
        }
        if (files.isEmpty()) return
        val parentId = _state.value.parentId
        var queued = 0
        var skipped = 0
        for ((file, name) in files) {
            val size = file.length()
            if (size <= 0L) {
                skipped++
                continue
            }
            target.upload(accountId, parentId, file.absolutePath, name, size)
            queued++
        }
        _state.update {
            it.copy(
                message = when {
                    queued == 0 -> "选中的文件都是空的，无法上传"
                    skipped == 0 -> "已加入上传队列：" + queued + " 个文件"
                    else -> "已加入上传队列：" + queued + " 个文件，跳过 " + skipped + " 个空文件"
                },
            )
        }
    }

    /**
     * 打开「复制到其他网盘」。
     *
     * 候选账号在打开时才取：账号可能刚在别的页面被删掉，
     * 用启动时缓存的列表会给出一个点了才报错的选项。
     */
    fun openCloudCopy(nodes: List<RemoteNode>) {
        if (nodes.isEmpty()) return
        viewModelScope.launch {
            val candidates = session.accounts.first().filter {
                it.id != accountId && it.capabilities.upload
            }
            if (candidates.isEmpty()) {
                _state.update { it.copy(message = "没有可以接收文件的账号") }
                return@launch
            }
            _copy.value = CloudCopyState(
                visible = true,
                nodes = nodes,
                targets = candidates,
                targetId = candidates.first().id,
            )
        }
    }

    fun closeCloudCopy() = _copy.update { CloudCopyState() }

    fun setCloudCopyTarget(id: AccountId) = _copy.update { it.copy(targetId = id, error = null) }

    fun submitCloudCopy() {
        val snapshot = _copy.value
        val target = snapshot.targetId ?: return
        val copier = copier ?: return
        if (snapshot.nodes.isEmpty()) return
        _copy.update { it.copy(busy = true, error = null) }
        // 目标目录直接取对方根目录：让用户在这里再挑一次目录会把两个账号的导航混在一起，
        // 传完之后在目标账号里移动一次更直接。
        var queued = 0
        for (node in snapshot.nodes) {
            if (node.isDir) continue
            copier.copyBetween(
                fromAccountId = accountId,
                nodeId = node.id,
                name = node.name,
                size = node.size.takeIf { it > 0L },
                toAccountId = target,
                toParentId = null,
            )
            queued++
        }
        if (queued == 0) {
            _copy.update { it.copy(busy = false, error = "选中的都是文件夹，暂不支持跨账号复制文件夹") }
            return
        }
        val name = snapshot.targetName
        _copy.value = CloudCopyState()
        _state.update {
            it.copy(
                selected = emptySet(),
                message = "正在复制 " + queued + " 个文件到「" + name + "」，可在传输列表查看进度",
            )
        }
    }

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
                            canSharePassword = descriptor.capabilities.sharePassword,
                            canShareExpiry = descriptor.capabilities.shareExpiry,
                            canCreateFolder = descriptor.capabilities.createFolder,
                            canUpload = descriptor.capabilities.upload,
                            canRename = descriptor.capabilities.rename,
                            canDelete = descriptor.capabilities.delete,
                            canMove = descriptor.capabilities.move,
                            canDownload = descriptor.capabilities.download,
                            canDirectLink = descriptor.capabilities.directLink,
                            canSearch = descriptor.capabilities.search,
                            canCloudCopy = copier != null &&
                                session.accounts.first().any { other ->
                                    other.id != accountId && other.capabilities.upload
                                },
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


    /** 打开搜索页。不改动当前目录的列表，返回时用户还在原地。 */
    fun openSearch() = _search.update { SearchState(visible = true, query = it.query) }

    fun closeSearch() = _search.update { SearchState() }

    fun setSearchQuery(value: String) = _search.update { it.copy(query = value, error = null) }

    /** 提交搜索。关键字没变且已有结果时不重发，避免返回键回到搜索页又白刷一次。 */
    fun submitSearch() {
        val snapshot = _search.value
        val keyword = snapshot.query.trim()
        if (keyword.isEmpty()) return
        val provider = scoped ?: return
        _search.update { it.copy(busy = true, error = null, results = emptyList(), nextCursor = null) }
        viewModelScope.launch {
            when (val result = provider.search(keyword, PageRequest(limit = SEARCH_PAGE_SIZE))) {
                is PanResult.Failure -> _search.update {
                    it.copy(busy = false, error = result.error.message)
                }
                is PanResult.Success -> _search.update {
                    it.copy(
                        busy = false,
                        results = result.value.items,
                        nextCursor = result.value.nextCursor,
                    )
                }
            }
        }
    }

    fun loadMoreSearch() {
        val provider = scoped ?: return
        val snapshot = _search.value
        val cursor = snapshot.nextCursor ?: return
        if (snapshot.loadingMore || snapshot.busy) return
        val keyword = snapshot.query.trim()
        if (keyword.isEmpty()) return
        _search.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val page = PageRequest(cursor = cursor, limit = SEARCH_PAGE_SIZE)
            when (val result = provider.search(keyword, page)) {
                is PanResult.Failure -> _search.update {
                    it.copy(loadingMore = false, error = result.error.message)
                }
                is PanResult.Success -> _search.update { current ->
                    // 搜索期间服务端数据可能变过，按 id 去重，别让同一个文件出现两次。
                    val known = current.results.mapTo(mutableSetOf()) { it.id }
                    val added = result.value.items.filter { known.add(it.id) }
                    current.copy(
                        loadingMore = false,
                        results = current.results + added,
                        nextCursor = result.value.nextCursor,
                    )
                }
            }
        }
    }

    /** 从搜索结果直接进入某个目录或打开文件：关闭搜索并沿用原有导航。 */
    fun openFromSearch(node: RemoteNode) {
        closeSearch()
        open(node)
    }

    private companion object {
        /** 选择目标目录时每页多大。只要文件夹，页面越小越快攒够一屏。 */
        /** 搜索结果每页条数。服务商普遍接受 100，翻页次数少一半。 */
        const val SEARCH_PAGE_SIZE = 100

        const val PICKER_PAGE_SIZE = 50

        /** 连续翻页找文件夹的上限，避免一页全是文件时无限翻下去。 */
        const val PICKER_MAX_ROUNDS = 5

        /**
         * 「没有原目录」的哨兵值。
         *
         * NodeId 不允许为空串，多选跨目录时又确实没有单一的原目录，
         * 所以用一个不可能真实存在的 id 表示它——这样"目标≠原目录"的判断不用改。
         */
        val UNSET_PARENT = NodeId("__unset__")
    }
}