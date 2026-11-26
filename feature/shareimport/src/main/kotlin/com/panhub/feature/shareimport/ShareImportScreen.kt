package com.panhub.feature.shareimport

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.panhub.core.design.EmptyState
import com.panhub.core.design.FilterBar
import com.panhub.core.design.LoadingState
import com.panhub.core.design.PanCard
import com.panhub.core.design.Radius
import com.panhub.core.design.Spacing
import com.panhub.core.design.formatBytes
import com.panhub.core.model.RemoteNode
import com.panhub.core.model.ShareEntry

/**
 * 打开别人的分享并转存。
 *
 * 一个页面完成「选账号 → 粘链接 → 挑文件 → 转存」，不分步。
 * 蓝云风格的取舍：输入区是一张白卡片而不是独立页面，
 * 因为用户十次里有九次只做一件事——把链接粘进来点确定。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareImportScreen(
    viewModel: ShareImportViewModel,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.start() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showFilter by remember { mutableStateOf(false) }

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
                    title = {
                        Column {
                            Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (state.opened && state.accountName.isNotBlank()) {
                                Text(
                                    text = "转存到 " + state.accountName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { if (state.canGoUp) viewModel.goUp() else onBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (state.opened) {
                            IconButton(
                                onClick = {
                                    if (showFilter) viewModel.clearQuery()
                                    showFilter = !showFilter
                                },
                            ) {
                                Icon(Icons.Default.FilterList, contentDescription = "筛选")
                            }
                            TextButton(onClick = viewModel::refresh, enabled = !state.busy) {
                                Text("刷新")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                if (state.busy) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().size(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        floatingActionButton = {
            if (state.canSave) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::save,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null)
                    Text(
                        text = "转存 " + state.selectedCount + " 项",
                        modifier = Modifier.padding(start = Spacing.sm),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            when {
                state.loading -> LoadingState()
                !state.hasAccounts -> EmptyState(
                    icon = Icons.Default.Link,
                    title = "没有可用的账号",
                    message = "先添加一个支持转存的账号（123 云盘、夸克网盘、百度网盘），再回来打开分享",
                )
                !state.opened -> LinkInputCard(
                    url = state.url,
                    passcode = state.passcode,
                    busy = state.busy,
                    canOpen = state.canOpen,
                    accountName = state.accountName,
                    onUrl = viewModel::setUrl,
                    onPasscode = viewModel::setPasscode,
                    onOpen = viewModel::open,
                )
                state.entries.isEmpty() -> EmptyState(
                    icon = Icons.Default.Folder,
                    title = if (state.error != null) "没能读到内容" else "这个文件夹是空的",
                    message = state.error?.message,
                )
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    // 筛选条放在列表外面：塞进 LazyColumn 就成了可以滚走的一项，
                    // 而且会和外层的水平内边距叠成两倍。
                    if (showFilter) {
                        FilterBar(
                            query = state.query,
                            summary = state.filterSummary,
                            onQuery = viewModel::setQuery,
                            onClear = viewModel::clearQuery,
                            placeholder = "分享里的文件名",
                        )
                    }
                    if (state.filteredOut) {
                        EmptyState(
                            icon = Icons.Default.FilterList,
                            title = state.emptyFilterMessage,
                            message = "换个关键字，或者点上面的清空",
                        )
                        return@Column
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = Spacing.lg,
                            end = Spacing.lg,
                            top = Spacing.sm,
                            bottom = Spacing.xxl + Spacing.xxl,
                        ),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        item(key = "__target__") {
                            TargetRow(
                                label = state.targetLabel,
                                accountName = state.accountName,
                                enabled = !state.busy,
                                onChange = viewModel::openTargetPicker,
                            )
                        }
                        item(key = "__toolbar__") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = state.visibleEntries.size.toString() + " 项",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Box(modifier = Modifier.weight(1f))
                                TextButton(onClick = viewModel::toggleAll) {
                                    Text(if (state.allSelected) "取消全选" else "全选")
                                }
                            }
                        }
                        items(state.visibleEntries, key = { it.id.value }) { entry ->
                            EntryCard(
                                entry = entry,
                                selected = entry.id in state.selected,
                                onOpen = { viewModel.enter(entry) },
                                onToggle = { viewModel.toggle(entry.id) },
                            )
                        }
                        if (state.hasMore) {
                            item(key = "__more__") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (state.loadingMore) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        TextButton(onClick = viewModel::loadMore) {
                                            Text("加载更多")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val error = state.error
    if (error != null && state.opened && state.entries.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text("操作没有成功") },
            text = { Text(error.message) },
            confirmButton = {
                TextButton(onClick = viewModel::clearError) { Text("知道了") }
            },
        )
    }

    if (state.picker.visible) {
        TargetPickerDialog(
            picker = state.picker,
            onDismiss = viewModel::closeTargetPicker,
            onEnter = viewModel::enterTargetFolder,
            onUp = viewModel::leaveTargetFolder,
            onConfirm = viewModel::confirmTarget,
        )
    }
}

/**
 * 列表顶部的一行：转存会落到哪里。
 *
 * 默认是根目录，用户点一下才换成别的文件夹。把目标放在列表顶部而不是塞进转存按钮里，
 * 是因为「转存到哪」是一次决定、多次可见的选择，藏在确认对话框里每次都要重新确认。
 */
@Composable
private fun TargetRow(
    label: String,
    accountName: String,
    enabled: Boolean,
    onChange: () -> Unit,
) {
    PanCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(start = Spacing.md)) {
                Text(
                    text = "转存到 " + label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = accountName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onChange, enabled = enabled) { Text("更改") }
        }
    }
}

/**
 * 目标目录选择器。
 *
 * 用对话框而不是新页面：换目标只是浏览过程中的一个短暂动作，
 * 开新页面要处理一次返回栈，还会让人以为已经离开了当前分享。
 */
@Composable
private fun TargetPickerDialog(
    picker: TargetPickerState,
    onDismiss: () -> Unit,
    onEnter: (RemoteNode) -> Unit,
    onUp: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("转存到", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (picker.canGoUp) {
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
            TextButton(onClick = onConfirm) { Text("存到这里") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 粘链接的那张卡片。 */
@Composable
private fun LinkInputCard(
    url: String,
    passcode: String,
    busy: Boolean,
    canOpen: Boolean,
    accountName: String,
    onUrl: (String) -> Unit,
    onPasscode: (String) -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        PanCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    text = "把分享链接粘进来",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("分享链接") },
                    placeholder = { Text("https://pan.quark.cn/s/…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                OutlinedTextField(
                    value = passcode,
                    onValueChange = onPasscode,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("提取码（链接里带了就不用填）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                OutlinedButton(
                    onClick = onOpen,
                    enabled = canOpen,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("打开")
                    }
                }
            }
        }
        Text(
            text = "转存到 " + accountName + "。打开之后可以挑文件，只有选中转存的内容才会进你的网盘。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 分享里的一个条目：点一下进目录或勾选文件。 */
@Composable
private fun EntryCard(
    entry: ShareEntry,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    PanCard(
        onClick = { if (entry.isDir) onOpen() else onToggle() },
        onLongClick = if (entry.isDir) null else onToggle,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (entry.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = if (entry.isDir) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
            Column(
                modifier = Modifier.weight(1f).padding(start = Spacing.md),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!entry.isDir) {
                    Text(
                        text = formatBytes(entry.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!entry.isDir && selected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "已选中",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
