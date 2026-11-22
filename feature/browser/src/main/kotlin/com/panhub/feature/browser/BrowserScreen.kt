package com.panhub.feature.browser

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import com.panhub.core.design.LoadingState
import com.panhub.core.design.PanCard
import com.panhub.core.design.QuotaBar
import com.panhub.core.design.Radius
import com.panhub.core.design.Spacing
import com.panhub.core.design.formatBytes
import com.panhub.core.model.RemoteNode
import com.panhub.core.model.ShareLink
import com.panhub.core.model.SortOrder
import com.panhub.core.model.SortSpec

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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker by viewModel.picker.collectAsStateWithLifecycle()
    val share by viewModel.share.collectAsStateWithLifecycle()
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
                if (state.canUpload) {
                    SmallFloatingActionButton(
                        onClick = { pickFile.launch(arrayOf("*/*")) },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = "上传文件")
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
            if (state.selectionMode && (state.canShare || state.canMove || state.canDownload)) {
                SelectionActionBar(
                    canShare = state.canShare,
                    canMove = state.canMove,
                    canDownload = state.canDownload,
                    onShare = { viewModel.withSelection { viewModel.openShareSheet(it) } },
                    onMove = { viewModel.withSelection { viewModel.openFolderPicker(it) } },
                    onDownload = viewModel::downloadSelected,
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
            canDownload = state.canDownload,
            canDirectLink = state.canDirectLink,
            onDismiss = { actionNode = null },
            onRename = {
                actionNode = null
                renameNode = node
            },
            onMove = {
                actionNode = null
                viewModel.openFolderPicker(listOf(node))
            },
            onDelete = {
                actionNode = null
                viewModel.delete(node)
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

    if (picker.visible) {
        FolderPickerDialog(
            picker = picker,
            onDismiss = viewModel::closeFolderPicker,
            onEnter = viewModel::enterPickerFolder,
            onUp = viewModel::leavePickerFolder,
            onConfirm = viewModel::confirmMove,
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
    canDownload: Boolean,
    onShare: () -> Unit,
    onMove: () -> Unit,
    onDownload: () -> Unit,
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
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            if (canShare) {
                SelectionAction("分享", Icons.Default.Share, onShare)
            }
            if (canMove) {
                SelectionAction("移动", Icons.AutoMirrored.Filled.DriveFileMove, onMove)
            }
            if (canDownload) {
                SelectionAction("下载", Icons.Default.Download, onDownload)
            }
        }
    }
}

@Composable
private fun SelectionAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.card))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.xl, vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(Spacing.xs))
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * 排序对话框。
 *
 * 不做成下拉菜单：选项只有三个，加一个方向开关一共四次点击以内就能完成，
 * 而菜单弹出再收起会让用户丢失当前列表的可见上下文。
 */
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
                text = "移动到",
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
                Text("移动到这里")
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
                    text = if (node.isDir) "文件夹" else formatBytes(node.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    canDownload: Boolean,
    canDirectLink: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
    onLink: () -> Unit,
    onShare: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                // 每一项都由 provider 的能力声明决定是否出现：
                // 界面不认识服务商，也就不可能在新增网盘时漏改这里。
                if (canRename) {
                    ActionRow("重命名", Icons.Default.Edit, onRename)
                }
                if (canMove) {
                    ActionRow("移动到", Icons.AutoMirrored.Filled.DriveFileMove, onMove)
                }
                if (!node.isDir && canDownload) {
                    ActionRow("下载", Icons.Default.Download, onDownload)
                }
                if (!node.isDir && canDirectLink) {
                    ActionRow("复制直链", Icons.Default.Link, onLink)
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