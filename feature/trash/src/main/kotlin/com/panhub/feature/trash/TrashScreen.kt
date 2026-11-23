package com.panhub.feature.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.panhub.core.design.EmptyState
import com.panhub.core.design.LoadingState
import com.panhub.core.design.PanCard
import com.panhub.core.design.PanColors
import com.panhub.core.design.Spacing
import com.panhub.core.design.formatBytes
import com.panhub.core.model.RemoteNode
import com.panhub.core.model.TrashNode

/**
 * 回收站。蓝云风格：浅灰底 + 白卡片。
 *
 * 这一页只有一件事要想清楚：删掉的东西还能不能救回来。
 * 所以「还原」是主按钮，「彻底删除」和「清空」都走二次确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    viewModel: TrashViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingPurge by remember { mutableStateOf(false) }
    var pendingEmpty by remember { mutableStateOf(false) }
    val now = viewModel.nowMillis()

    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (state.items.isEmpty()) "回收站" else "回收站（" + state.items.size + "）",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.items.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.toggleSelectAll() },
                            enabled = !state.busy,
                        ) {
                            Icon(
                                Icons.Default.SelectAll,
                                contentDescription = if (state.allSelected) "取消全选" else "全选",
                            )
                        }
                        IconButton(
                            onClick = { pendingEmpty = true },
                            enabled = !state.busy,
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "清空回收站")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.selectedCount > 0) {
                SelectionBar(
                    count = state.selectedCount,
                    busy = state.busy,
                    onRestore = viewModel::restoreSelected,
                    onRestoreTo = viewModel::openRestorePicker,
                    onPurge = { pendingPurge = true },
                    onClear = viewModel::clearSelection,
                )
            }
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            when {
                !state.supported -> EmptyState(
                    icon = Icons.Default.DeleteForever,
                    title = "这个网盘没有回收站",
                    message = "删掉的文件就真的没了，找不到这里来",
                )
                state.loading -> LoadingState()
                state.error != null -> EmptyState(
                    icon = Icons.Default.DeleteForever,
                    title = "打不开回收站",
                    message = state.error!!.message,
                )
                state.items.isEmpty() -> EmptyState(
                    icon = Icons.Default.DeleteForever,
                    title = "回收站是空的",
                    message = "在文件页删除的东西会先放到这里",
                )
                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        top = Spacing.sm,
                        bottom = Spacing.xxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    if (state.refreshing) {
                        item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                    }
                    items(state.items, key = { it.id.value }) { node ->
                        TrashCard(
                            node = node,
                            now = now,
                            selected = node.id.value in state.selected,
                            onClick = { viewModel.toggleSelect(node) },
                        )
                    }
                    if (state.hasMore) {
                        item {
                            TextButton(
                                onClick = { viewModel.loadMore() },
                                enabled = !state.loadingMore,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (state.loadingMore) "正在加载…" else "加载更多")
                            }
                        }
                    }
                }
            }
        }
    }

    if (pendingPurge) {
        ConfirmDialog(
            title = "彻底删除",
            text = "选中的 " + state.selectedCount + " 项会被永久删除，谁都找不回来。",
            confirmLabel = "彻底删除",
            onDismiss = { pendingPurge = false },
            onConfirm = {
                pendingPurge = false
                viewModel.purgeSelected()
            },
        )
    }

    if (pendingEmpty) {
        ConfirmDialog(
            title = "清空回收站",
            text = "回收站里的所有东西都会被永久删除，谁都找不回来。",
            confirmLabel = "清空",
            onDismiss = { pendingEmpty = false },
            onConfirm = {
                pendingEmpty = false
                viewModel.emptyAll()
            },
        )
    }

    if (state.picker.visible) {
        RestorePickerDialog(
            picker = state.picker,
            onDismiss = viewModel::closeRestorePicker,
            onEnter = viewModel::enterPickerFolder,
            onUp = viewModel::leavePickerFolder,
            onConfirm = viewModel::confirmRestoreTo,
        )
    }
}

/**
 * 「还原到…」的目录选择器。
 *
 * 复用转存那套对话框形态：目标目录只在这里挑一次，用页面承载会让
 * 「返回」看起来像是放弃还原，而不是回到上一层目录。
 */
@Composable
private fun RestorePickerDialog(
    picker: RestorePickerState,
    onDismiss: () -> Unit,
    onEnter: (RemoteNode) -> Unit,
    onUp: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("还原到", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (picker.canGoUp) {
                        IconButton(onClick = onUp) {
                            Icon(
                                Icons.Default.ArrowBack,
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
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
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
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onEnter(folder) }
                                    .padding(vertical = Spacing.md, horizontal = Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = Spacing.md),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !picker.loading) { Text("还原到这里") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TrashCard(
    node: TrashNode,
    now: Long,
    selected: Boolean,
    onClick: () -> Unit,
) {
    PanCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = iconOf(node),
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = Spacing.md)) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = TrashLogic.subtitle(node, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (!node.isDir) {
                Text(
                    text = formatBytes(node.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun iconOf(node: TrashNode): ImageVector =
    if (node.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile

@Composable
private fun SelectionBar(
    count: Int,
    busy: Boolean,
    onRestore: () -> Unit,
    onRestoreTo: () -> Unit,
    onPurge: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        TextButton(onClick = onClear, enabled = !busy) { Text("取消") }
        Text(text = "已选 " + count + " 项", style = MaterialTheme.typography.labelMedium)
        Box(modifier = Modifier.weight(1f))
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        TextButton(onClick = onRestore, enabled = !busy) {
            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("还原", modifier = Modifier.padding(start = 4.dp))
        }
        // 原目录被删掉之后「还原」会失败，这里给一个能挑去处的出口。
        IconButton(onClick = onRestoreTo, enabled = !busy) {
            Icon(
                Icons.Default.DriveFileMove,
                contentDescription = "还原到别的目录",
                modifier = Modifier.size(18.dp),
            )
        }
        TextButton(onClick = onPurge, enabled = !busy) {
            Icon(
                Icons.Default.DeleteForever,
                contentDescription = null,
                tint = PanColors.Red,
                modifier = Modifier.size(16.dp),
            )
            Text("删除", color = PanColors.Red, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = PanColors.Red) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
