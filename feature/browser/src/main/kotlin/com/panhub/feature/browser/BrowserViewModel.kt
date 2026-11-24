package com.panhub.feature.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeFocus
import com.panhub.core.model.NodeKind
import com.panhub.core.model.PageRequest
import com.panhub.core.model.Quota
import com.panhub.core.model.RemoteNode
import com.panhub.core.model.ShareLink
import com.panhub.core.model.ShareOptions
import com.panhub.core.model.SortOrder
import com.panhub.core.model.NodeFilter
import com.panhub.core.model.SortSpec
import com.panhub.core.model.sortedFor
import com.panhub.core.preview.PreviewContent
import com.panhub.core.preview.PreviewKind
import com.panhub.core.preview.PreviewKinds
import com.panhub.core.preview.PreviewCursors
import com.panhub.core.preview.PreviewLoader
import com.panhub.core.preview.PreviewSiblings
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
    /** 删除会进回收站还是永久删掉。决定确认框里敢不敢承诺「能还原」。 */
    val canTrash: Boolean = false,
    val canMove: Boolean = false,
    val canCopy: Boolean = false,
    val canDownload: Boolean = false,
    val canDirectLink: Boolean = false,
    val canSearch: Boolean = false,
    /** 至少还有一个别的账号能接收文件时才有「复制到其他网盘」。 */
    val canCloudCopy: Boolean = false,
    val canOfflineDownload: Boolean = false,
    /** 能否把这个文件加进收藏夹。 */
    val canStar: Boolean = false,
    val error: PanError? = null,
    val message: String? = null,
    val busy: Boolean = false,
    /** 多选模式下被勾选的节点。空集合就代表不在多选模式。 */
    val selected: Set<NodeId> = emptySet(),
    /** 列表排序。纯本地行为，与服务商无关，所以不需要能力门控。 */
    val sort: SortSpec = SortSpec(),
    /**
     * 目录内的名字过滤词。
     *
     * 只筛已经加载的这一批，不联网：目录里有几百项时，靠滚动找文件名是最费劲的一步，
     * 而为了一个「临时找一下」再走一次跨目录搜索既慢又会被限流。
     */
    val filter: String = "",
) {
    val currentFolder: RemoteNode? get() = stack.lastOrNull()

    /** 有勾选项即进入多选模式：不需要额外的开关，少一个状态就少一处不一致。 */
    val selectionMode: Boolean get() = selected.isNotEmpty()

    val selectedCount: Int get() = selected.size

    /** 看得见的项目是否全选。只在多选模式下有意义。 */
    val allSelected: Boolean get() = visible.isNotEmpty() && visible.all { it.id in selected }
    val title: String get() = currentFolder?.name ?: accountName
    val canGoUp: Boolean get() = stack.isNotEmpty()
    val parentId: NodeId? get() = currentFolder?.id

    /** 服务商还有下一页时为 true，界面据此显示「加载更多」。 */
    val hasMore: Boolean get() = nextCursor != null

    /** 服务商返回的顺序各不相同，一律按用户选的规格重排后再显示。 */
    val sorted: List<RemoteNode> get() = nodes.sortedFor(sort)

    /** 真正画到列表上的那一份：先按用户选的规格排，再按过滤词收窄。 */
    val visible: List<RemoteNode> get() = NodeFilter.apply(sorted, filter)

    /** 有没有在过滤。界面据此换掉「这里还没有文件」那句空列表说明。 */
    val filtering: Boolean get() = NodeFilter.active(filter)
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
    /** 目标账号里的目录栈；空表示还在对方根目录。 */
    val stack: List<RemoteNode> = emptyList(),
    val folders: List<RemoteNode> = emptyList(),
    val loadingFolders: Boolean = false,
    val folderError: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = nodes.isNotEmpty() && targetId != null && !busy

    /** 目标账号显示名，提交后拿它写提示。 */
    val targetName: String get() = targets.firstOrNull { it.id == targetId }?.label ?: ""

    /** 提交目标目录：栈顶目录，null 表示对方根目录。 */
    val targetFolderId: NodeId? get() = stack.lastOrNull()?.id

    /** 目标位置的文字：账号名 + 路径，用于对话框里的说明行。 */
    val targetLabel: String
        get() = if (stack.isEmpty()) targetName else targetName + " / " + stack.joinToString(" / ") { it.name }
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
 * 目录选择器要做什么。
 *
 * 移动和复制用的是同一个选择器：两者都只是「挑一个目标目录」，
 * 差别只在最后调哪个接口。做成两个对话框会让用户面对两套几乎一样的导航。
 */
enum class PickerAction { MOVE, COPY }

/**
 * 「移动到」/「复制到」对话框的状态。
 *
 * 目录导航单独放一份栈，而不是复用 [BrowserUiState.stack]：用户在选择目标目录时
 * 不应该把浏览页也一起带跑（取消后要回到原来的位置）。
 */
data class FolderPickerState(
    val visible: Boolean = false,
    /** 这次选目录是为了移动还是复制。 */
    val action: PickerAction = PickerAction.MOVE,
    /** 被移动/复制的节点；多选时会有多个。 */
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

    /** 标题：移动与复制是两个动作，不能共用一句「移动到」。 */
    val titleText: String get() = if (action == PickerAction.MOVE) "移动到" else "复制到"

    val confirmText: String get() = if (action == PickerAction.MOVE) "移动" else "复制"
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
 * 删除确认框的状态。
 *
 * 删除是本页唯一的破坏性操作，所以它有自己的状态而不是「点一下就发请求」：
 * 用户必须先看见这次要删什么、删掉之后还能不能捞回来。
 */
data class DeleteConfirmState(
    val visible: Boolean = false,
    val nodes: List<RemoteNode> = emptyList(),
    /** 服务商声明的回收站能力，决定正文与按钮的措辞。 */
    val toTrash: Boolean = false,
    val busy: Boolean = false,
) {
    val title: String get() = DeleteConfirm.title(nodes)
    val question: String get() = DeleteConfirm.question(nodes, toTrash)
    val confirmLabel: String get() = DeleteConfirm.confirmLabel(toTrash)
}

/**
 * 上传前的同名确认。
 *
 * 只装「这次要传什么」与「哪些名字在目录里已经有了」两件事：
 * 真正入队要等用户点过确认，所以待上传的文件与文件夹必须原样留在这里。
 */
data class UploadConfirmState(
    val visible: Boolean = false,
    /** 待上传的本地文件；上传文件夹任务时为空。 */
    val files: List<Pair<File, String>> = emptyList(),
    /** 待上传的本地文件夹；上传文件任务时为 null。两者只会有一个非空。 */
    val folder: LocalDirectory? = null,
    /** 与当前目录重名的那些名字。 */
    val clashes: List<String> = emptyList(),
) {
    val title: String get() = UploadConflicts.title(clashes)
    val question: String get() = UploadConflicts.question(clashes)
}

/**
 * 文件详情面板的状态。
 *
 * 只用列表里已有的字段，不再单独查一次：详情面板是「多看一眼」的动作，
 * 为了几个字段再发一次请求，用户要等的却是肉眼可见的转圈。
 */
data class NodeDetailsState(
    val visible: Boolean = false,
    val node: RemoteNode? = null,
)

/**
 * 文件预览面板的状态。
 *
 * 和详情面板的区别在于它真的要读内容：所以有 loading 与 error，
 * 而且失败后要能重试——直链是短时的，用户过一会儿再点多半就好了。
 */
data class PreviewState(
    val visible: Boolean = false,
    val node: RemoteNode? = null,
    val kind: PreviewKind = PreviewKind.NONE,
    val loading: Boolean = false,
    val content: PreviewContent? = null,
    val error: PanError? = null,
    /**
     * 同目录里前后还有哪些能看的。
     *
     * 放在预览状态里而不是让界面自己算：界面只有当前节点，拿不到整份列表；
     * 而且「能不能翻」和「翻到哪」本来就是同一件事的两个侧面。
     */
    val cursors: PreviewCursors = PreviewCursors.None,
) {
    val canRetry: Boolean get() = error != null && node != null
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
    private val previewer: PreviewLoader? = null,
    /**
     * 从别处跳进来时要定位到哪。
     *
     * 跨账号搜索给出的结果可能埋在很深的目录里，进页面后直接落在根目录、
     * 让用户自己一层层点回去，等于没搜。打开时先按这个位置摆好栈。
     */
    private val focus: NodeFocus? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(BrowserUiState(accountId = accountId))
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    /** 定位请求只生效一次：重试或换目录时不该又被拉回搜索点中的那一层。 */
    private var pendingFocus: NodeFocus? = focus

    private val _picker = MutableStateFlow(FolderPickerState())
    val picker: StateFlow<FolderPickerState> = _picker.asStateFlow()

    private val _copy = MutableStateFlow(CloudCopyState())

    val copy: StateFlow<CloudCopyState> = _copy.asStateFlow()

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val _share = MutableStateFlow(ShareSheetState())
    val share: StateFlow<ShareSheetState> = _share.asStateFlow()

    private val _details = MutableStateFlow(NodeDetailsState())
    val details: StateFlow<NodeDetailsState> = _details.asStateFlow()

    private val _preview = MutableStateFlow(PreviewState())
    val preview: StateFlow<PreviewState> = _preview.asStateFlow()

    private val _uploadConfirm = MutableStateFlow(UploadConfirmState())
    val uploadConfirm: StateFlow<UploadConfirmState> = _uploadConfirm.asStateFlow()

    private val _delete = MutableStateFlow(DeleteConfirmState())
    val delete: StateFlow<DeleteConfirmState> = _delete.asStateFlow()

    /** 当前打开的预览是否还有协程在跑，关闭时要取消掉。 */
    private var previewJob: Job? = null

    private var scoped: ScopedPanProvider? = null
    private var loadJob: Job? = null

    /**
     * 打开「移动到」选择器。
     *
     * 起始目录固定在根目录而不是上次的位置：移动是做一次性的目录选择，
     * 记住上次位置反而容易让用户在手快时把文件丢进一个不相干的目录。
     */
    fun openFolderPicker(nodes: List<RemoteNode>, action: PickerAction = PickerAction.MOVE) {
        val provider = scoped ?: return
        if (nodes.isEmpty()) return
        _picker.value = FolderPickerState(
            visible = true,
            action = action,
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

    /** 确认在当前目录里移动或复制。 */
    fun confirmPicker() {
        val provider = scoped ?: return
        val snapshot = _picker.value
        val nodes = snapshot.nodes
        if (!snapshot.canConfirm) return
        val target = snapshot.targetId
        val action = snapshot.action
        closeFolderPicker()
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            // 逐个移动/复制而不是找批量接口：只有少数服务商有批量版本，
            // 而逐个的失败语义是明确的——成功几个就是几个，报错也只针对那一个。
            var done = 0
            var firstError: String? = null
            for (node in nodes) {
                val result = when (action) {
                    PickerAction.MOVE -> provider.move(node.id, target)
                    // 复制要整个节点：天翼的批量任务拿文件名给副本命名，光有 id 拼不出来。
                    PickerAction.COPY -> provider.copy(node, target)
                }
                when (result) {
                    is PanResult.Success -> done++
                    is PanResult.Failure -> if (firstError == null) firstError = result.error.message
                }
            }
            _state.update {
                it.copy(
                    busy = false,
                    message = actionMessage(action, done, nodes.size, firstError),
                    selected = emptySet(),
                )
            }
            load(refresh = true)
        }
    }

    /** 移动/复制结果的一句话。部分成功要说清楚，否则用户会以为全成了。 */
    private fun actionMessage(
        action: PickerAction,
        done: Int,
        total: Int,
        error: String?,
    ): String {
        val verb = if (action == PickerAction.MOVE) "移动" else "复制"
        return when {
            done == total -> "已" + verb + " " + total + " 项"
            done == 0 -> error ?: (verb + "失败")
            else -> "已" + verb + " " + done + "/" + total + " 项：" + (error ?: "部分失败")
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
        if (node.isDir) enter(node) else openDetails(node)
    }

    /**
     * 打开详情面板。
     *
     * 点文件和点文件夹的区别就在这里：文件夹要进去，文件没有「进去」这回事，
     * 与其弹一条只有名字的提示，不如把手上已知的信息摊开。
     */
    fun openDetails(node: RemoteNode) {
        _details.value = NodeDetailsState(visible = true, node = node)
    }

    fun closeDetails() {
        _details.value = NodeDetailsState()
    }

    /**
     * 就地预览一个文件。
     *
     * 判断「能不能预览」放在这里而不是界面里：界面上每个入口都要写一遍
     * 同样的判断，迟早会有一处漏改，出现一个点了没反应的按钮。
     * 不能预览时直接返回 false，让界面把入口藏起来。
     */
    fun openPreview(node: RemoteNode): Boolean {
        val loader = previewer ?: return false
        val kind = PreviewKinds.of(node)
        if (kind == PreviewKind.NONE) return false
        previewJob?.cancel()
        _preview.value = PreviewState(
            visible = true,
            node = node,
            kind = kind,
            loading = true,
            // 用界面上看到的那份（visible：已经排序并过滤过），否则用户点
            // 「下一张」跳到的是另一个排序下、甚至已被筛掉的下一张，看起来像是乱跳。
            cursors = PreviewSiblings.of(_state.value.visible, node, kind),
        )
        previewJob = viewModelScope.launch {
            val result = loader.load(accountId, node.id, kind, node.size.takeIf { it > 0 })
            _preview.update { current ->
                // 面板可能在加载期间被关掉或换成了别的文件，只认还在等这份内容的那次。
                if (!current.visible || current.node?.id != node.id) return@update current
                when (result) {
                    is PanResult.Success -> current.copy(
                        loading = false,
                        content = result.value,
                        error = null,
                    )
                    is PanResult.Failure -> current.copy(
                        loading = false,
                        content = null,
                        error = result.error,
                    )
                }
            }
        }
        return true
    }

    /** 重试上一次失败的预览。直链是短时的，多半再点一次就好了。 */
    fun retryPreview() {
        val node = _preview.value.node ?: return
        openPreview(node)
    }

    /**
     * 预览里往后翻一个。
     *
     * 走同一个 [openPreview]，所以加载态、失败重试、防竞态都不用再写一遍；
     * 翻不过去（已经是最后一个或者根本没有邻居）时什么都不做，
     * 界面那边按钮本来就只在有邻居时才画出来。
     */
    fun previewNext() {
        val next = _preview.value.cursors.next ?: return
        openPreview(next)
    }

    /** 预览里往前翻一个。 */
    fun previewPrevious() {
        val previous = _preview.value.cursors.previous ?: return
        openPreview(previous)
    }

    fun closePreview() {
        previewJob?.cancel()
        previewJob = null
        _preview.value = PreviewState()
    }

    /** 这个文件能不能就地预览，界面用它决定要不要显示入口。 */
    fun canPreview(node: RemoteNode): Boolean =
        previewer != null && !node.isDir && PreviewKinds.of(node) != PreviewKind.NONE

    fun enter(folder: RemoteNode) {
        if (!folder.isDir) return
        // 进新目录要清掉过滤词：上一个目录里打的字几乎没有可能也适用于这一层，
        // 留着它只会让人进目录后看到一片空白，还以为目录是空的。
        _state.update { it.copy(stack = it.stack + folder, filter = "") }
        // 换目录必须清空旧列表：否则新目录加载期间会短暂显示上一级的文件，
        // 加载失败时更会一直停在那里，看起来像是进了错的目录。
        reloadFromScratch()
    }

    /** 返回上一级；已在根目录时返回 false，交给系统返回键处理。 */
    fun goUp(): Boolean {
        val stack = _state.value.stack
        if (stack.isEmpty()) return false
        _state.update { it.copy(stack = stack.dropLast(1), filter = "") }
        reloadFromScratch()
        return true
    }

    /**
     * 跳到路径栏上的某一格。根目录是 [BrowserPath.ROOT_DEPTH]。
     *
     * 与连续按返回键的差别在请求数：一次点击只重新加载目标目录一次，
     * 而不是每退一级都拉一遍中间目录的列表。
     */
    fun jumpTo(depth: Int): Boolean {
        val stack = _state.value.stack
        val target = if (depth <= BrowserPath.ROOT_DEPTH) emptyList() else stack.take(depth + 1)
        if (target.size == stack.size) return false
        _state.update { it.copy(stack = target, filter = "") }
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

    /**
     * 收藏 / 取消收藏。
     *
     * 成功后只改本地列表里那一条的 starred 标记，不整页重拉：
     * 收藏夹之外的地方翻页会打断用户正在看的位置，而本地标记已经和服务端一致了。
     */
    fun toggleStar(node: RemoteNode) {
        val provider = scoped ?: return
        if (_state.value.busy) return
        val target = !node.starred
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = provider.setStarred(listOf(node.id), target)) {
                is PanResult.Success -> {
                    _state.update { current ->
                        current.copy(
                            busy = false,
                            nodes = current.nodes.map {
                                if (it.id == node.id) it.copy(starred = target) else it
                            },
                            message = if (target) "已收藏 " + node.name else "已取消收藏 " + node.name,
                        )
                    }
                }
                is PanResult.Failure -> _state.update {
                    it.copy(busy = false, message = result.error.message)
                }
            }
        }
    }

    /**
     * 批量收藏 / 取消收藏。
     *
     * 一次请求带上所有 id（服务端就是收数组的），而不是像删除那样逐个发：
     * 收藏是幂等的标记，中途失败一半也不好回滚，索性一次发完。
     * 目标状态按「是不是全都已经收藏了」来定：全都收藏过就当作取消，
     * 否则一律收藏——混选时让用户得到「都收藏上」，比随第一个的状态更符合预期。
     */
    fun starSelected() {
        val provider = scoped ?: return
        if (_state.value.busy) return
        val nodes = selectedNodes()
        if (nodes.isEmpty()) return
        val target = !nodes.all { it.starred }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = provider.setStarred(nodes.map { it.id }, target)) {
                is PanResult.Success -> {
                    val ids = nodes.map { it.id }.toSet()
                    _state.update { current ->
                        current.copy(
                            busy = false,
                            selected = emptySet(),
                            nodes = current.nodes.map {
                                if (it.id in ids) it.copy(starred = target) else it
                            },
                            message = if (target) {
                                "已收藏 " + nodes.size + " 项"
                            } else {
                                "已取消收藏 " + nodes.size + " 项"
                            },
                        )
                    }
                }
                is PanResult.Failure -> _state.update {
                    it.copy(busy = false, message = result.error.message)
                }
            }
        }
    }

    /**
     * 点删除：先说明会发生什么，不直接动手。
     *
     * 删除是本页唯一不可撤销的动作，而各家网盘对它的语义并不一样：
     * 百度与 123 云盘会放进回收站，其余的直接删掉。让用户在按下按钮之前
     * 就知道能不能捞回来，比事后再解释划算得多。
     */
    fun openDelete(nodes: List<RemoteNode>) {
        if (nodes.isEmpty()) return
        _delete.value = DeleteConfirmState(
            visible = true,
            nodes = nodes,
            toTrash = _state.value.canTrash,
        )
    }

    fun cancelDelete() {
        _delete.value = DeleteConfirmState()
    }

    /** 确认删除：逐项下发并统计结果。没有批量接口的网盘也一样走这条路。 */
    fun confirmDelete() {
        val pending = _delete.value
        if (!pending.visible || pending.busy) return
        val provider = scoped ?: return
        val nodes = pending.nodes
        if (nodes.isEmpty()) return
        _delete.update { it.copy(busy = true) }
        viewModelScope.launch {
            var done = 0
            var firstError: String? = null
            for (node in nodes) {
                when (val result = provider.delete(node.id)) {
                    is PanResult.Success -> done++
                    is PanResult.Failure -> if (firstError == null) firstError = result.error.message
                }
            }
            _delete.value = DeleteConfirmState()
            _state.update {
                it.copy(
                    busy = false,
                    selected = emptySet(),
                    message = when {
                        done == nodes.size -> DeleteConfirm.doneMessage(done, pending.toTrash)
                        done == 0 -> firstError ?: "删除失败"
                        else -> "已删除 " + done + "/" + nodes.size + " 项"
                    },
                )
            }
            load(refresh = true)
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

    /**
     * 全选当前看到的项目；已全选则清空（同一个入口来回切）。
     *
     * 筛选中只选看得见的那几条：把被筛掉的项目一起选中，用户对这批文件
     * 执行删除或移动时会连自己没看见的东西一起处理掉。
     */
    fun toggleSelectAll() {
        _state.update {
            val shown = it.visible
            it.copy(selected = if (it.allSelected) emptySet() else shown.mapTo(mutableSetOf()) { n -> n.id })
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

    /**
     * 改过滤词。
     *
     * 每敲一个字都在本地重筛一遍，不发请求也不需要防抖：几百项的 contains
     * 比一次界面重组便宜得多，加防抖反而会让输入框看起来卡。
     */
    fun setFilter(value: String) = _state.update { it.copy(filter = value) }

    fun clearFilter() = _state.update { it.copy(filter = "") }

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

    /** 批量删除：同样是先问一句，再走 [confirmDelete]。 */
    fun deleteSelected() {
        openDelete(selectedNodes())
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
        requestUpload(listOf(file to name), folder = null)
    }

    /**
     * 一次上传多个文件。
     *
     * 与单个上传的差别只有汇总提示：每个文件仍然是一条独立任务，
     * 由传输队列串行跑。空文件在入队前就筛掉，否则它会变成一条
     * 必然失败的任务，还要用户自己去传输页里找原因。
     */
    fun uploadAll(files: List<Pair<File, String>>) {
        if (files.isEmpty()) return
        requestUpload(files, folder = null)
    }

    /**
     * 上传整个本地文件夹。
     *
     * 目录遍历在 View 层做完了（见 LocalFilePicker），这里只负责入队：
     * 整理型任务会在传输页里自己显示「正在整理本地文件夹」。
     */
    fun uploadFolder(directory: LocalDirectory) {
        requestUpload(emptyList(), folder = directory)
    }

    /**
     * 入队前的最后一道关：看看这些名字在目录里是不是已经有了。
     *
     * 有重名就先把选择停在这里，等用户点过确认再入队。没重名直接入队——
     * 绝大多数上传都不会撞名，每次都弹框只会让人学会无脑点「确定」。
     */
    private fun requestUpload(files: List<Pair<File, String>>, folder: LocalDirectory?) {
        if (uploader == null) {
            _state.update { it.copy(message = "上传功能不可用") }
            return
        }
        val names = if (folder != null) listOf(folder.name) else files.map { it.second }
        val clashes = UploadConflicts.clashes(_state.value.nodes, names)
        if (clashes.isEmpty()) {
            enqueueUpload(files, folder)
            return
        }
        _uploadConfirm.value = UploadConfirmState(
            visible = true,
            files = files,
            folder = folder,
            clashes = clashes,
        )
    }

    /** 关掉同名确认，什么都不传。 */
    fun cancelUpload() {
        _uploadConfirm.value = UploadConfirmState()
    }

    /** 用户确认「仍然上传」：按原样入队。 */
    fun confirmUpload() {
        val pending = _uploadConfirm.value
        if (!pending.visible) return
        _uploadConfirm.value = UploadConfirmState()
        enqueueUpload(pending.files, pending.folder)
    }

    /** 真正入队。空文件在这里筛掉，不落到队列里变成一条必然失败的任务。 */
    private fun enqueueUpload(files: List<Pair<File, String>>, folder: LocalDirectory?) {
        val target = uploader ?: return
        val parentId = _state.value.parentId
        if (folder != null) {
            target.uploadFolder(
                accountId = accountId,
                parentId = parentId,
                localDir = folder.path,
                rootName = folder.name,
            )
            _state.update { it.copy(message = "已加入上传队列，正在整理「" + folder.name + "」") }
            return
        }
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
            loadCloudCopyFolders()
        }
    }

    fun closeCloudCopy() = _copy.update { CloudCopyState() }

    fun setCloudCopyTarget(id: AccountId) {
        _copy.update { it.copy(targetId = id, error = null) }
        // 换账号后旧目录完全失效：直接回根目录重拉一次。
        _copy.update { it.copy(stack = emptyList()) }
        loadCloudCopyFolders()
    }

    /** 进目标账号的某个子目录。 */
    fun enterCloudCopyFolder(folder: RemoteNode) {
        _copy.update { it.copy(stack = it.stack + folder, folderError = null) }
        loadCloudCopyFolders()
    }

    /** 回到目标账号的上一层目录；已在根目录时无事可做。 */
    fun leaveCloudCopyFolder(): Boolean {
        val stack = _copy.value.stack
        if (stack.isEmpty()) return false
        _copy.update { it.copy(stack = stack.dropLast(1)) }
        loadCloudCopyFolders()
        return true
    }

    /**
     * 列出目标账号当前目录里的子文件夹。
     *
     * 与移动/复制用的 loadPicker 不同：那里的账号是本页的（scoped 已就绪），
     * 这里要对另一个账号 connect 再列，所以单独一份实现。
     */
    private fun loadCloudCopyFolders() {
        val snapshot = _copy.value
        val target = snapshot.targetId ?: return
        viewModelScope.launch {
            _copy.update { it.copy(loadingFolders = true, folders = emptyList(), folderError = null) }
            when (val connected = session.connect(target)) {
                is PanResult.Failure -> _copy.update {
                    it.copy(loadingFolders = false, folderError = connected.error.message)
                }
                is PanResult.Success -> {
                    val parent = _copy.value.stack.lastOrNull()?.id
                    var cursor: String? = null
                    var rounds = 0
                    val folders = mutableListOf<RemoteNode>()
                    while (true) {
                        when (val page = connected.value.list(
                            parent,
                            PageRequest(cursor = cursor, limit = PICKER_PAGE_SIZE),
                        )) {
                            is PanResult.Failure -> _copy.update {
                                it.copy(loadingFolders = false, folderError = page.error.message)
                            }
                            is PanResult.Success -> {
                                folders += page.value.items.filter { it.isDir }
                                cursor = page.value.nextCursor
                                rounds++
                                if (folders.isNotEmpty() || cursor == null || rounds >= PICKER_MAX_ROUNDS) {
                                    _copy.update {
                                        it.copy(loadingFolders = false, folders = folders)
                                    }
                                    return@launch
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun submitCloudCopy() {
        val snapshot = _copy.value
        val target = snapshot.targetId ?: return
        val copier = copier ?: return
        if (snapshot.nodes.isEmpty()) return
        _copy.update { it.copy(busy = true, error = null) }
        val toParentId = snapshot.targetFolderId
        val canCreateFolder = snapshot.targets
            .firstOrNull { it.id == target }
            ?.capabilities?.createFolder == true
        var files = 0
        var folders = 0
        var skippedFolders = 0
        for (node in snapshot.nodes) {
            if (node.isDir) {
                if (!canCreateFolder) {
                    skippedFolders++
                    continue
                }
                copier.copyFolderBetween(
                    fromAccountId = accountId,
                    nodeId = node.id,
                    name = node.name,
                    toAccountId = target,
                    toParentId = toParentId,
                )
                folders++
                continue
            }
            copier.copyBetween(
                fromAccountId = accountId,
                nodeId = node.id,
                name = node.name,
                size = node.size.takeIf { it > 0L },
                toAccountId = target,
                toParentId = toParentId,
            )
            files++
        }
        if (files == 0 && folders == 0) {
            _copy.update {
                it.copy(
                    busy = false,
                    error = "「" + snapshot.targetName + "」不支持新建文件夹，无法复制文件夹",
                )
            }
            return
        }
        val name = snapshot.targetName
        _copy.value = CloudCopyState()
        val queued = "正在复制 " + describe(files, folders) + "到「" + name + "」，可在传输列表查看进度"
        val skipped = if (skippedFolders == 0) "" else "；" + skippedFolders + " 个文件夹因对方不支持新建文件夹被跳过"
        _state.update { it.copy(selected = emptySet(), message = queued + skipped) }
    }

    /** 「3 个文件、2 个文件夹」这种说法，只列真正有的那一半。 */
    private fun describe(files: Int, folders: Int): String {
        val parts = ArrayList<String>(2)
        if (files > 0) parts += files.toString() + " 个文件"
        if (folders > 0) parts += folders.toString() + " 个文件夹"
        return parts.joinToString("、")
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
                            canTrash = descriptor.capabilities.trash,
                            canMove = descriptor.capabilities.move,
                            canCopy = descriptor.capabilities.copy,
                            canDownload = descriptor.capabilities.download,
                            canDirectLink = descriptor.capabilities.directLink,
                            canSearch = descriptor.capabilities.search,
                            canOfflineDownload = descriptor.capabilities.offlineDownload,
                            canStar = descriptor.capabilities.star,
                            canCloudCopy = copier != null &&
                                session.accounts.first().any { other ->
                                    other.id != accountId && other.capabilities.upload
                                },
                        )
                    }
                    loadQuota(result.value)
                    val landing = pendingFocus
                    pendingFocus = null
                    applyFocus(landing)
                    load()
                }
            }
        }
    }

    /**
     * 把从别处带来的定位请求摆成导航栈。
     *
     * 只有父目录名字没有 id 的情况（多数网盘不返回父 id）就造一层占位面包屑：
     * 用户按返回能退回上一级，而不是一步跳回根目录。名字也拿不到时留一句中性
     * 说法，不假装知道那个目录叫什么。
     */
    private fun applyFocus(focus: NodeFocus?) {
        if (focus == null) return
        if (!focus.dir) {
            // 结果是文件：直接把它交给详情弹窗，不改变当前目录，
            // 用户关掉弹窗后仍站在根目录，符合「只是看一眼」的预期。
            _details.value = NodeDetailsState(
                visible = true,
                node = RemoteNode(
                    id = focus.id,
                    parentId = focus.parentId,
                    name = focus.name,
                    kind = NodeKind.FILE,
                ),
            )
            return
        }
        val trail = mutableListOf<RemoteNode>()
        // 跨模块属性不能智能转换，先取到局部变量再用。
        val parent = focus.parentId
        if (parent != null) {
            trail += RemoteNode(
                id = parent,
                parentId = null,
                name = focus.parentName?.takeIf { it.isNotBlank() } ?: "上一级目录",
                kind = NodeKind.DIR,
            )
        }
        trail += RemoteNode(
            id = focus.id,
            parentId = parent,
            name = focus.name,
            kind = NodeKind.DIR,
        )
        _state.update { it.copy(stack = trail) }
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

    /**
     * 从搜索结果直接进入某个目录或打开文件。
     *
     * 搜索结果是跨目录的，点进去时目录栈里只有这一层，按返回键会直接跳回
     * 根目录，用户会以为自己站错了地方。所以这里补一层「它在哪个目录」的
     * 占位：返回时先回到那个目录，再往上一级一级退。
     * 父目录 id 与名字都没有（老接口）时不补，宁可直接回根目录。
     */
    fun openFromSearch(node: RemoteNode) {
        closeSearch()
        if (!node.isDir) {
            openDetails(node)
            return
        }
        val parentId = node.parentId
        val stack = if (parentId == null) {
            listOf(node)
        } else {
            val crumb = RemoteNode(
                id = parentId,
                parentId = null,
                name = node.providerPath?.takeIf { it.isNotBlank() } ?: "上一级目录",
                kind = NodeKind.DIR,
            )
            listOf(crumb, node)
        }
        _state.update { it.copy(stack = stack) }
        reloadFromScratch()
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