package com.panhub.feature.browser

import android.content.Intent
import android.net.Uri

import coil3.compose.SubcomposeAsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.panhub.core.design.EmptyState
import com.panhub.core.design.LabeledRow
import com.panhub.core.design.LoadingState
import com.panhub.core.design.PanCard
import com.panhub.core.design.PanColors
import com.panhub.core.design.QuotaBar
import com.panhub.core.design.Radius
import com.panhub.core.design.Spacing
import com.panhub.core.design.formatBytes
import com.panhub.core.model.AccountId
import com.panhub.core.model.RemoteNode
import com.panhub.core.model.ShareLink
import com.panhub.core.model.SortOrder
import com.panhub.core.model.SortSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件浏览页。
 *
 * 蓝云风格的两点体现：顶部是极简标题栏（无阴影），列表用白色卡片分组，
 * 容量条常驻在标题栏下方——用户打开网盘第一眼就该知道还剩多少空间。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onBack: () -> Unit,
    onOpenOffline: (RemoteNode?) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker by viewModel.picker.collectAsStateWithLifecycle()
    val share by viewModel.share.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val copy by viewModel.copy.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val deleting by viewModel.delete.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showNewFolder by remember { mutableStateOf(false) }
    var actionNode by remember { mutableStateOf<RemoteNode?>(null) }
    var renameNode by remember { mutableStateOf<RemoteNode?>(null) }
    var showSort by remember { mutableStateOf(false) }

    // 选完文件先复制到缓存再入队：content:// 授权随进程结束失效，
    // 而上传是异步排队，可能在用户离开本页之后才开始。
    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        // 单个文件走同一条路径：多选一次只选一个时不该有第二套行为。
        val copied = LocalFilePicker.copyAllToCache(context, uris)
        if (copied.isEmpty()) {
            viewModel.showMessage("读取文件失败")
        } else {
            viewModel.uploadAll(copied)
            if (copied.size < uris.size) {
                viewModel.showMessage("有 " + (uris.size - copied.size) + " 个文件读取失败，已跳过")
            }
        }
    }

    // 选整个文件夹：Android 只给一棵 document tree，遍历由 LocalFilePicker 负责。
    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val copied = LocalFilePicker.copyTreeToCache(context, uri)
        if (copied == null) {
            viewModel.showMessage("读取文件夹失败")
        } else {
            viewModel.uploadFolder(copied)
        }
    }

    LaunchedEffect(Unit) { viewModel.start() }

    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        if (state.selectionMode) {
                            IconButton(onClick = viewModel::clearSelection) {
                                Icon(Icons.Default.Close, contentDescription = "退出多选")
                            }
                        } else {
                            IconButton(onClick = { if (!viewModel.goUp()) onBack() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回",
                                )
                            }
                        }
                    },
                    title = {
                        Text(
                            text = if (state.selectionMode) {
                                "已选择 " + state.selectedCount + " 项"
                            } else {
                                state.title
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    actions = {
                        if (state.selectionMode) {
                            IconButton(onClick = viewModel::toggleSelectAll) {
                                Icon(Icons.Default.SelectAll, contentDescription = "全选")
                            }
                        } else {
                            if (state.canSearch) {
                                IconButton(onClick = viewModel::openSearch) {
                                    Icon(Icons.Default.Search, contentDescription = "搜索")
                                }
                            }
                            IconButton(onClick = { showSort = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                            }
                            IconButton(onClick = viewModel::refresh) {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                if (!state.selectionMode) {
                    PathBar(
                        accountName = state.accountName,
                        stack = state.stack,
                        onJump = viewModel::jumpTo,
                    )
                }
                state.quota?.let { quota ->
                    QuotaBar(
                        used = quota.used,
                        total = quota.total,
                        modifier = Modifier.padding(
                            start = Spacing.lg,
                            end = Spacing.lg,
                            bottom = Spacing.md,
                        ),
                    )
                }
                if (state.refreshing) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        floatingActionButton = {
            // 多选时收起新建/上传：此刻唯一该做的事是对已选项目下手，
            // 留着两个会误触的按钮只会让人犹豫。
            if (state.selectionMode) {
                if (state.canDelete) {
                    FloatingActionButton(
                        onClick = viewModel::deleteSelected,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "删除所选")
                    }
                }
            } else Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (state.canOfflineDownload) {
                    // 离线下载放在上传上面：它和上传是同一类动作（往当前目录加东西），
                    // 但用得更少，位置偏外一点不容易误触。
                    SmallFloatingActionButton(
                        onClick = { onOpenOffline(state.currentFolder) },
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "离线下载")
                    }
                }
                if (state.canUpload) {
                    SmallFloatingActionButton(
                        onClick = { pickFile.launch(arrayOf("*/*")) },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = "上传文件")
                    }
                    // 文件夹入口故意和文件入口并排而不是塞进菜单：
                    // 两者是同一次「把本地东西放上来」的两种粒度。
                    SmallFloatingActionButton(
                        onClick = { pickFolder.launch(null) },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Icon(Icons.Default.DriveFolderUpload, contentDescription = "上传文件夹")
                    }
                }
                if (state.canCreateFolder) {
                    FloatingActionButton(
                        onClick = { showNewFolder = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "新建文件夹")
                    }
                }
            }
        },
        bottomBar = {
            if (state.selectionMode && (state.canShare || state.canMove || state.canCopy ||
                state.canDownload || state.canCloudCopy || state.canStar)
            ) {
                SelectionActionBar(
                    canShare = state.canShare,
                    canMove = state.canMove,
                    canCopy = state.canCopy,
                    canDownload = state.canDownload,
                    canCloudCopy = state.canCloudCopy,
                    canStar = state.canStar,
                    onShare = { viewModel.withSelection { viewModel.openShareSheet(it) } },
                    onMove = { viewModel.withSelection { viewModel.openFolderPicker(it) } },
                    onCopy = {
                        viewModel.withSelection {
                            viewModel.openFolderPicker(it, PickerAction.COPY)
                        }
                    },
                    onDownload = viewModel::downloadSelected,
                    onCloudCopy = { viewModel.withSelection { viewModel.openCloudCopy(it) } },
                    onStar = viewModel::starSelected,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            when {
                state.loading -> LoadingState()
                state.error != null -> EmptyState(
                    icon = Icons.Default.Folder,
                    title = "打不开这个账号",
                    message = state.error?.message,
                )
                state.nodes.isEmpty() -> EmptyState(
                    icon = Icons.Default.Folder,
                    title = "这里还没有文件",
                    message = if (state.canCreateFolder) "点右下角新建一个文件夹" else null,
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        top = Spacing.sm,
                        bottom = Spacing.xxl + Spacing.xxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(state.sorted, key = { it.id.value }) { node ->
                        NodeRow(
                            node = node,
                            selectionMode = state.selectionMode,
                            selected = node.id in state.selected,
                            onClick = {
                                if (state.selectionMode) viewModel.toggleSelect(node) else viewModel.open(node)
                            },
                            onLongClick = { viewModel.toggleSelect(node) },
                            onMore = { actionNode = node },
                        )
                    }
                    if (state.hasMore) {
                        item(key = "__more__") {
                            LoadMoreRow(
                                loading = state.loadingMore,
                                onClick = viewModel::loadMore,
                            )
                        }
                    }
                }
            }
        }
    }

    actionNode?.let { node ->
        NodeActionsSheet(
            node = node,
            canShare = state.canShare,
            canRename = state.canRename,
            canDelete = state.canDelete,
            canMove = state.canMove,
            canCopy = state.canCopy,
            canDownload = state.canDownload,
            canDirectLink = state.canDirectLink,
            canCloudCopy = state.canCloudCopy,
            // 预览要拿直链，拿不到直链的服务商连下载都不支持，自然也没法预览。
            canPreview = state.canDownload && viewModel.canPreview(node),
            canStar = state.canStar,
            onDismiss = { actionNode = null },
            onRename = {
                actionNode = null
                renameNode = node
            },
            onMove = {
                actionNode = null
                viewModel.openFolderPicker(listOf(node))
            },
            onCopy = {
                actionNode = null
                viewModel.openFolderPicker(listOf(node), PickerAction.COPY)
            },
            onDelete = {
                actionNode = null
                viewModel.openDelete(listOf(node))
            },
            onDownload = {
                actionNode = null
                viewModel.download(node)
            },
            onLink = {
                actionNode = null
                viewModel.directLink(node) { url -> context.openUrl(url) }
            },
            onShare = {
                actionNode = null
                viewModel.openShareSheet(listOf(node))
            },
            onCloudCopy = {
                actionNode = null
                viewModel.openCloudCopy(listOf(node))
            },
            onDetails = {
                actionNode = null
                viewModel.openDetails(node)
            },
            onPreview = {
                actionNode = null
                viewModel.openPreview(node)
            },
            onStar = {
                actionNode = null
                viewModel.toggleStar(node)
            },
        )
    }

    renameNode?.let { node ->
        NameDialog(
            title = "重命名",
            initial = node.name,
            onDismiss = { renameNode = null },
            onConfirm = { name ->
                renameNode = null
                viewModel.rename(node, name)
            },
        )
    }

    if (showNewFolder) {
        NameDialog(
            title = "新建文件夹",
            initial = "",
            onDismiss = { showNewFolder = false },
            onConfirm = { name ->
                showNewFolder = false
                viewModel.createFolder(name)
            },
        )
    }

    if (deleting.visible) {
        DeleteConfirmDialog(
            state = deleting,
            onDismiss = viewModel::cancelDelete,
            onConfirm = viewModel::confirmDelete,
        )
    }

    if (picker.visible) {
        FolderPickerDialog(
            picker = picker,
            onDismiss = viewModel::closeFolderPicker,
            onEnter = viewModel::enterPickerFolder,
            onUp = viewModel::leavePickerFolder,
            onConfirm = viewModel::confirmPicker,
        )
    }

    if (share.visible) {
        ShareDialog(
            share = share,
            canPassword = state.canSharePassword,
            canExpiry = state.canShareExpiry,
            onPassword = viewModel::setSharePassword,
            onExpiry = viewModel::setShareExpireDays,
            onSubmit = viewModel::submitShare,
            onDismiss = viewModel::closeShareSheet,
            onShare = { url, text -> context.shareText(text ?: url) },
            onCopy = { text -> context.copyText(text) },
        )
    }

    if (search.visible) {
        SearchDialog(
            search = search,
            onQuery = viewModel::setSearchQuery,
            onSubmit = viewModel::submitSearch,
            onLoadMore = viewModel::loadMoreSearch,
            onOpen = viewModel::openFromSearch,
            onDismiss = viewModel::closeSearch,
        )
    }

    if (copy.visible) {
        CloudCopyDialog(
            copy = copy,
            onPick = viewModel::setCloudCopyTarget,
            onEnterFolder = viewModel::enterCloudCopyFolder,
            onUpFolder = { viewModel.leaveCloudCopyFolder() },
            onSubmit = viewModel::submitCloudCopy,
            onDismiss = viewModel::closeCloudCopy,
        )
    }

    if (details.visible) {
        details.node?.let { node ->
            NodeDetailsDialog(
                node = node,
                accountName = state.accountName,
                onDismiss = viewModel::closeDetails,
            )
        }
    }

    if (preview.visible) {
        PreviewDialog(
            state = preview,
            onDismiss = viewModel::closePreview,
            onRetry = viewModel::retryPreview,
            onDownload = {
                preview.node?.let { node ->
                    viewModel.closePreview()
                    viewModel.download(node)
                }
            },
            onPrevious = viewModel::previewPrevious,
            onNext = viewModel::previewNext,
        )
    }

    if (showSort) {
        SortDialog(
            spec = state.sort,
            onOrder = viewModel::setSortOrder,
            onToggleDirection = viewModel::toggleSortDirection,
            onDismiss = { showSort = false },
        )
    }
}

/**
 * 多选时底部的操作条。
 *
 * 删除没有放进来：它是不可撤销的破坏性操作，放在和"分享""移动"并排的位置
 * 太容易被手滑点到，单独留在右下角的 FAB 上更安全。
 */
@Composable
private fun SelectionActionBar(
    canShare: Boolean,
    canMove: Boolean,
    canCopy: Boolean,
    canDownload: Boolean,
    canCloudCopy: Boolean,
    canStar: Boolean,
    onShare: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDownload: () -> Unit,
    onCloudCopy: () -> Unit,
    onStar: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
            // 等分宽度而不是 SpaceEvenly：动作最多能有六个（分享/移动/复制/下载/转存/收藏），
            // 按内容撑开会把最后的按钮挤出屏幕，等分至少保证每个都在。
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canShare) {
                SelectionAction("分享", Icons.Default.Share, onShare, Modifier.weight(1f))
            }
            if (canMove) {
                SelectionAction("移动", Icons.AutoMirrored.Filled.DriveFileMove, onMove, Modifier.weight(1f))
            }
            if (canCopy) {
                SelectionAction("复制", Icons.Default.ContentCopy, onCopy, Modifier.weight(1f))
            }
            if (canDownload) {
                SelectionAction("下载", Icons.Default.Download, onDownload, Modifier.weight(1f))
            }
            if (canCloudCopy) {
                SelectionAction("转存", Icons.Default.SyncAlt, onCloudCopy, Modifier.weight(1f))
            }
            if (canStar) {
                SelectionAction("收藏", Icons.Default.Star, onStar, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SelectionAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.card))
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(Spacing.xs))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 排序对话框。
 *
 * 不做成下拉菜单：选项只有三个，加一个方向开关一共四次点击以内就能完成，
 * 而菜单弹出再收起会让用户丢失当前列表的可见上下文。
 */
/**
 * 搜索对话框。
 *
 * 做成对话框而不是新页面：搜索是「在当前账号里找东西」的辅助动作，
 * 用户查完仍要回到原来的目录继续操作，跳页会让返回路径变长。
 */
@Composable
private fun SearchDialog(
    search: SearchState,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (RemoteNode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索") },
        text = {
            Column {
                OutlinedTextField(
                    value = search.query,
                    onValueChange = onQuery,
                    singleLine = true,
                    label = { Text("文件名关键字") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.md))
                when {
                    search.busy -> Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    search.error != null -> Text(
                        text = search.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    search.results.isEmpty() -> Text(
                        text = if (search.query.isBlank()) "输入关键字后点搜索" else "没有找到匹配的文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(search.results, key = { it.id.value }) { node ->
                            NodeRow(
                                node = node,
                                selectionMode = false,
                                selected = false,
                                onClick = { onOpen(node) },
                                onLongClick = {},
                                onMore = { onOpen(node) },
                                // 搜索是跨目录的，不写清在哪个目录，
                                // 用户看到三个同名文件只能挨个点开试。
                                location = node.providerPath,
                            )
                            Spacer(Modifier.height(Spacing.sm))
                        }
                        if (search.hasMore) {
                            item(key = "__more_search__") {
                                LoadMoreRow(loading = search.loadingMore, onClick = onLoadMore)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = search.canSubmit) { Text("搜索") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * 「复制到其他网盘」对话框。
 *
 * 只让用户选目标账号，目标目录固定为对方的根目录：这里再嵌一层目录浏览，
 * 两个账号的路径会混进同一个返回栈，很容易把文件放进错误的账号。
 */
@Composable
private fun CloudCopyDialog(
    copy: CloudCopyState,
    onPick: (AccountId) -> Unit,
    onEnterFolder: (RemoteNode) -> Unit,
    onUpFolder: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("复制到其他网盘") },
        text = {
            Column {
                Text(
                    text = "共 " + copy.nodes.size + " 个项目，会先下载再上传，期间不要退出应用。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.md))
                copy.targets.forEach { target ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.card))
                            .clickable { onPick(target.id) }
                            .padding(vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (target.id == copy.targetId) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Circle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.size(Spacing.sm))
                        Column {
                            Text(target.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = target.providerName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.md))
                HorizontalDivider()
                Spacer(Modifier.height(Spacing.sm))
                // 目标位置说明行：点「上一级」回退。根目录不可回退。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "存到：" + copy.targetLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (copy.stack.isNotEmpty()) {
                        TextButton(onClick = onUpFolder, enabled = !copy.loadingFolders) {
                            Text("上一级")
                        }
                    }
                }
                when {
                    copy.loadingFolders -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.md),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                    copy.folderError != null -> Text(
                        text = copy.folderError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    copy.folders.isEmpty() -> Text(
                        text = "这里没有子文件夹",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Spacing.sm),
                    )
                    else -> Column {
                        copy.folders.forEach { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Radius.card))
                                    .clickable { onEnterFolder(folder) }
                                    .padding(vertical = Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(Spacing.sm))
                                Text(folder.name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
                copy.error?.let { text ->
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = copy.canSubmit) {
                Text(if (copy.busy) "提交中…" else "开始复制")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SortDialog(
    spec: SortSpec,
    onOrder: (SortOrder) -> Unit,
    onToggleDirection: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("排序方式") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                SortOrder.entries.forEach { order ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.card))
                            .clickable { onOrder(order) }
                            .padding(vertical = Spacing.md, horizontal = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (spec.order == order) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                            contentDescription = null,
                            tint = if (spec.order == order) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(Spacing.md))
                        Text(text = order.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.card))
                        .clickable(onClick = onToggleDirection)
                        .padding(vertical = Spacing.md, horizontal = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(Spacing.md))
                    Text(
                        text = if (spec.descending) "反向：降序" else "反向：升序",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

/**
 * 顶部路径栏。
 *
 * 只显示到当前这一层为止的祖先，不做「点省略号展开全部」：
 * 手机的横向空间本来就窄，多一层交互就把「我现在在哪儿」这件事又藏起来了。
 * 当前层加粗且不可点，其余各层都是可点的祖先。
 */
@Composable
private fun PathBar(
    accountName: String,
    stack: List<RemoteNode>,
    onJump: (Int) -> Unit,
) {
    if (!BrowserPath.isJumpable(accountName, stack)) return
    val crumbs = BrowserPath.crumbs(accountName, stack)
    val current = BrowserPath.currentDepth(stack)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Text(
                    text = "/",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.xs),
                )
            }
            val isCurrent = crumb.depth == current
            Text(
                text = crumb.label.ifEmpty { "未命名" },
                style = MaterialTheme.typography.labelMedium,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.card))
                    .then(
                        if (isCurrent) {
                            Modifier
                        } else {
                            Modifier.clickable { onJump(crumb.depth) }
                        },
                    )
                    .padding(horizontal = Spacing.xs, vertical = 2.dp),
            )
        }
    }
}

/**
 * 目标目录选择器。
 *
 * 用对话框而不是新页面：移动只是浏览过程中的一个短暂动作，
 * 开新页面要处理一次返回栈，还会让人以为已经离开了当前目录。
 */
@Composable
private fun FolderPickerDialog(
    picker: FolderPickerState,
    onDismiss: () -> Unit,
    onEnter: (RemoteNode) -> Unit,
    onUp: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = picker.titleText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (picker.stack.isNotEmpty()) {
                        IconButton(onClick = onUp) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "上一级",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Text(
                        text = picker.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    picker.loading -> Box(
                        modifier = Modifier.fillMaxWidth().height(96.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    picker.error != null -> Text(
                        text = picker.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = Spacing.lg),
                    )
                    picker.folders.isEmpty() -> Text(
                        text = "这里没有子文件夹",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Spacing.lg),
                    )
                    else -> LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        items(picker.folders, key = { it.id.value }) { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Radius.card))
                                    .clickable { onEnter(folder) }
                                    .padding(vertical = Spacing.md, horizontal = Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(Spacing.md))
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = picker.canConfirm) {
                Text(picker.confirmText + "到这里")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun NodeRow(
    node: RemoteNode,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
    /** 搜索结果用：这东西在哪个目录里。普通列表不需要，他自己就在那儿。 */
    location: String? = null,
) {
    PanCard(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Outlined.Circle
                    },
                    contentDescription = null,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(Spacing.md))
            }
            // 有缩略图就直接显示服务商给的图（图片/视频列表一秒认出来），
            // 没有或加载失败都退回类型图标——失败是常态，不值得单独报错。
            if (node.thumbnailUrl != null && !node.isDir) {
                SubcomposeAsyncImage(
                    model = node.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(Radius.card)),
                    loading = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                    error = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(Radius.card))
                        .background(
                            if (node.isDir) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (node.isDir) Icons.Default.Folder else Icons.Default.Description,
                        contentDescription = null,
                        tint = if (node.isDir) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = Spacing.md),
            ) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(if (node.isDir) "文件夹" else formatBytes(node.size))
                        if (location != null) append(" · 在「").append(location).append("」")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 收藏标记只是个小星：它不占操作位置，
            // 但要能一眼看出哪些文件已经在收藏夹里，否则用户没法判断该加还是该减。
            if (node.starred) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "已收藏",
                    tint = PanColors.Orange,
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(onClick = onMore) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "更多操作",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 「加载更多」占位行。做成整行可点的卡片而不是自动触发，
 * 是为了让用户清楚知道还有内容没加载、并且由自己决定要不要花这份流量。
 */
@Composable
private fun LoadMoreRow(loading: Boolean, onClick: () -> Unit) {
    PanCard(
        onClick = if (loading) null else onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(Spacing.sm))
                Text(
                    text = "加载中",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "加载更多",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun NodeActionsSheet(
    node: RemoteNode,
    canShare: Boolean,
    canRename: Boolean,
    canDelete: Boolean,
    canMove: Boolean,
    canCopy: Boolean,
    canDownload: Boolean,
    canDirectLink: Boolean,
    canCloudCopy: Boolean,
    canPreview: Boolean,
    canStar: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
    onLink: () -> Unit,
    onShare: () -> Unit,
    onCloudCopy: () -> Unit,
    onDetails: () -> Unit,
    onPreview: () -> Unit,
    onStar: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                // 每一项都由 provider 的能力声明决定是否出现：
                // 界面不认识服务商，也就不可能在新增网盘时漏改这里。
                // 能预览的文件把「看一眼」放在最前面：它是列表里点开文件时
                // 最想做的事，比改名、移动高频得多。
                if (canPreview) {
                    ActionRow("预览", Icons.Default.Visibility, onPreview)
                }
                ActionRow("查看详情", Icons.Default.Info, onDetails)
                if (canRename) {
                    ActionRow("重命名", Icons.Default.Edit, onRename)
                }
                if (canMove) {
                    ActionRow("移动到", Icons.AutoMirrored.Filled.DriveFileMove, onMove)
                }
                // 复制排在移动之后：两者位置相近，但移动会带走原件，
                // 放在下面一点可以减少"本想复制却点了移动"的手滑。
                if (canCopy) {
                    ActionRow("复制到", Icons.Default.ContentCopy, onCopy)
                }
                // 文件夹也能下载：队列会先把里面的文件列出来再逐个传。
                if (canDownload) {
                    ActionRow(
                        if (node.isDir) "下载文件夹" else "下载",
                        Icons.Default.Download,
                        onDownload,
                    )
                }
                if (!node.isDir && canDirectLink) {
                    ActionRow("复制直链", Icons.Default.Link, onLink)
                }
                if (!node.isDir && canCloudCopy) {
                    ActionRow("复制到其他网盘", Icons.Default.SyncAlt, onCloudCopy)
                }
                if (canStar) {
                    ActionRow(
                        if (node.starred) "取消收藏" else "收藏",
                        if (node.starred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        onStar,
                    )
                }
                if (canShare) {
                    ActionRow("创建分享", Icons.Default.Share, onShare)
                }
                if (canDelete) {
                    ActionRow("删除", Icons.Default.Delete, onDelete, danger = true)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ActionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (danger) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(Spacing.md))
            Text(
                text = label,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 文件详情。
 *
 * 只展示列表里已经带上来的字段：详情面板是「多看一眼」的动作，
 * 为了几行信息再发一次请求，用户等到的是肉眼可见的转圈。
 * 没有值的行直接不画，一排「—」看不出任何信息。
 */
@Composable
private fun NodeDetailsDialog(
    node: RemoteNode,
    accountName: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = node.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column {
                LabeledRow("类型", if (node.isDir) "文件夹" else (node.mimeType ?: "文件"))
                LabeledRow("大小", if (node.isDir) "—" else formatBytes(node.size))
                node.modifiedAt?.let { LabeledRow("修改时间", formatTime(it)) }
                node.createdAt?.let { LabeledRow("创建时间", formatTime(it)) }
                node.providerPath?.takeIf { it.isNotBlank() }?.let { LabeledRow("路径", it) }
                if (accountName.isNotBlank()) LabeledRow("账号", accountName)
                LabeledRow("标识", node.id.value)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/**
 * 删除确认框。
 *
 * 正文随服务商是否回收站而变：只有它真会放进回收站时才写「可以还原」，
 * 否则必须说清「删掉找不回来」——这句话不是免责声明，是用户唯一能依据的信息。
 */
@Composable
private fun DeleteConfirmDialog(
    state: DeleteConfirmState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text(state.title) },
        text = {
            Column {
                Text(
                    text = state.question,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.busy) {
                    Row(
                        modifier = Modifier.padding(top = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = "正在删除…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = Spacing.sm),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !state.busy) {
                Text(
                    text = state.confirmLabel,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.busy) { Text("取消") }
        },
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("名称") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.isNotBlank(),
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun android.content.Context.openUrl(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * 分享对话框。
 *
 * 分成「填参数」和「看结果」两段视图：分享的目的就是拿到一条能发出去的链接，
 * 如果创建成功后还要用户自己关掉再去找，等于把最容易丢的东西丢在了对话框里。
 */
@Composable
private fun ShareDialog(
    share: ShareSheetState,
    canPassword: Boolean,
    canExpiry: Boolean,
    onPassword: (String) -> Unit,
    onExpiry: (Int?) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    onShare: (String, String?) -> Unit,
    onCopy: (String) -> Unit,
) {
    val result = share.result
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (result != null) "分享链接" else "创建分享",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            if (result != null) {
                Column {
                    Text(
                        text = result.url,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    result.password?.takeIf { it.isNotEmpty() }?.let { pwd ->
                        Spacer(Modifier.size(Spacing.sm))
                        Text(
                            text = "提取码：" + pwd,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    result.expiresAt?.let { millis ->
                        Spacer(Modifier.size(Spacing.sm))
                        Text(
                            text = expiryLineOf(millis),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Column {
                    Text(
                        text = share.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (canPassword) {
                        Spacer(Modifier.size(Spacing.md))
                        OutlinedTextField(
                            value = share.password,
                            onValueChange = onPassword,
                            singleLine = true,
                            label = { Text("访问密码（留空则无密码）") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (canExpiry) {
                        Spacer(Modifier.size(Spacing.md))
                        Text(
                            text = "有效期",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(Spacing.xs))
                        Row {
                            EXPIRY_CHOICES.forEach { days ->
                                val selected = share.expireDays == days
                                Text(
                                    text = if (days == null) "永久" else days.toString() + " 天",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(Radius.pill))
                                        .background(
                                            if (selected) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            },
                                        )
                                        .clickable { onExpiry(days) }
                                        .padding(
                                            horizontal = Spacing.md,
                                            vertical = Spacing.sm,
                                        ),
                                )
                                Spacer(Modifier.size(Spacing.sm))
                            }
                        }
                    }
                    if (share.busy) {
                        Spacer(Modifier.size(Spacing.lg))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    share.error?.let { message ->
                        Spacer(Modifier.size(Spacing.md))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (result != null) {
                val text = shareTextOf(result)
                Row {
                    TextButton(onClick = { onCopy(text) }) { Text("复制") }
                    TextButton(onClick = { onShare(result.url, text) }) { Text("发送") }
                }
            } else {
                TextButton(onClick = onSubmit, enabled = !share.busy) { Text("创建") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (result != null) "完成" else "取消") } },
    )
}

/** 分享给别人的一句话：链接和提取码必须一起出去，分开抄很容易漏。 */
private fun shareTextOf(link: ShareLink): String =
    if (link.password.isNullOrEmpty()) {
        link.url
    } else {
        link.url + " 提取码：" + link.password
    }

/**
 * 结果视图里的到期说明。
 *
 * 为什么显示服务商回传的时刻而不是用户选的天数：有的服务商只有几档固定有效期
 * （夸克就是永久/一天/一周/一月），选 3 天实际会落到一周档，照着输入显示会说谎。
 */
private fun expiryLineOf(expiresAt: Long): String {
    val text = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(expiresAt))
    return text + " 到期"
}

/** 有效期候选。null = 永久，与服务商的「0 表示永久」约定对应。 */
private val EXPIRY_CHOICES: List<Int?> = listOf(null, 1, 7, 30)

/** 复制到剪贴板。比弹一个「已复制」的 Toast 更轻，但用户需要知道成功了。 */
private fun android.content.Context.copyText(text: String) {
    val manager = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    manager.setPrimaryClip(android.content.ClipData.newPlainText("PanHub", text))
    android.widget.Toast.makeText(this, "已复制", android.widget.Toast.LENGTH_SHORT).show()
}

private fun android.content.Context.shareText(url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(intent, "分享链接").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
