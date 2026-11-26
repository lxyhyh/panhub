package com.panhub.feature.localfiles

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.panhub.core.design.ChipRow
import com.panhub.core.design.EmptyState
import com.panhub.core.design.FilterBar
import com.panhub.core.design.LoadingState
import com.panhub.core.design.PanCard
import com.panhub.core.design.PanColors
import com.panhub.core.design.SortDialog
import com.panhub.core.design.Spacing
import com.panhub.core.model.FileKinds
import com.panhub.core.model.SortOrder
import com.panhub.core.platform.LocalFileActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地文件页：看看下载下来的东西都在哪、有多大、删掉。
 *
 * 与其他页面同一个骨架：浅灰底、白卡片、顶部极简标题栏。
 * 这一页没有 FAB——这里不产出任何东西，只做盘点与清理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalFilesScreen(
    viewModel: LocalFilesViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.start() }

    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    // 另存到：系统对话框的回调只给 uri，所以「保存哪一个」要先记下来。
    var pendingExport by remember { mutableStateOf<File?>(null) }
    // 筛选条默认收起：这一页通常只有几个到几十个文件，多数时候是来直接点开的。
    var showFilter by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val source = pendingExport
        pendingExport = null
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        LocalFileActions.copyTo(source, out)
                    }
                }.getOrNull()
            }
            snackbar.showSnackbar(
                if (written == null) "没能打开那个位置，换个地方试试"
                else "已保存 · " + com.panhub.core.design.formatBytes(written),
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.selectionMode) {
                SelectionBar(
                    summary = state.selectedSummary,
                    busy = state.busy,
                    onDelete = viewModel::askDeleteSelected,
                    onClear = viewModel::clearSelection,
                )
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("本机文件") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 选择态下只留「全选 / 删除」：这时候再露出筛选和排序，
                    // 会让底下那排按钮挤成一团，也容易误触。
                    if (state.selectionMode) {
                        IconButton(onClick = viewModel::toggleSelectAll) {
                            Icon(
                                Icons.Default.SelectAll,
                                contentDescription = if (state.allSelected) "取消全选" else "全选",
                            )
                        }
                        TextButton(onClick = viewModel::askDeleteSelected, enabled = !state.busy) {
                            Text("删除")
                        }
                    } else {
                        IconButton(
                            onClick = {
                                // 收起时顺手清掉关键字：只藏输入框却留着过滤，
                                // 会让列表莫名其妙少东西，而用户找不到原因。
                                if (showFilter) {
                                    viewModel.clearFilter()
                                    viewModel.clearKind()
                                }
                                showFilter = !showFilter
                            },
                        ) {
                            Icon(Icons.Default.FilterList, contentDescription = "筛选")
                        }
                        IconButton(onClick = { showSort = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                        }
                        if (state.canClear) {
                            TextButton(onClick = viewModel::askClear) { Text("清空") }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.loading) {
                LoadingState()
                return@Column
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.summary,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showFilter && !state.empty) {
                // 本机文件全是文件、没有目录项，所以这里筛的每一类都可能真的
                // 把列表筛空——文案由 FileKinds 决定，和浏览页同一份。
                ChipRow(
                    options = FileKinds.filterable.map { it.label },
                    selectedIndex = FileKinds.indexOf(state.kind),
                    onSelect = { viewModel.toggleKind(FileKinds.filterable[it]) },
                )
                FilterBar(
                    query = state.filter,
                    placeholder = "本机已下载的文件",
                    summary = state.filterSummary,
                    onQuery = viewModel::setFilter,
                    onClear = viewModel::clearFilter,
                )
            }
            if (state.empty) {
                EmptyState(
                    icon = Icons.Default.DownloadDone,
                    title = "本机没有文件",
                    message = LocalFilesLogic.emptyMessage(),
                )
                return@Column
            }
            if (state.filteredOut) {
                EmptyState(
                    icon = Icons.Default.FilterList,
                    title = state.emptyFilterMessage,
                    message = "换个条件，或者点上面的清空",
                )
                return@Column
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.lg,
                    end = Spacing.lg,
                    top = Spacing.xs,
                    bottom = Spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(state.visibleRows, key = { it.entry.relativePath }) { row ->
                    LocalFileCard(
                        row = row,
                        busy = state.busy,
                        selecting = state.selectionMode,
                        selected = row.entry.relativePath in state.selected,
                        onToggleSelect = { viewModel.toggleSelect(row.entry) },
                        onOpen = {
                            val file = viewModel.fileOf(row.entry)
                            if (file == null || !LocalFileActions.open(context, file)) {
                                scope.launch { snackbar.showSnackbar("没有能打开这个文件的应用") }
                            }
                        },
                        onShare = {
                            val file = viewModel.fileOf(row.entry)
                            if (file != null) LocalFileActions.share(context, file)
                        },
                        onSaveAs = {
                            val file = viewModel.fileOf(row.entry) ?: return@LocalFileCard
                            pendingExport = file
                            exportLauncher.launch(LocalFileActions.suggestName(file.absolutePath))
                        },
                        onDelete = { viewModel.askDelete(row.entry) },
                    )
                }
            }
        }
    }

    if (state.confirming.isNotEmpty()) {
        val entries = state.confirming
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(LocalFilesLogic.deleteTitle(entries.size)) },
            text = { Text(LocalFilesLogic.deleteQuestion(entries)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) { Text("取消") }
            },
        )
    }

    if (state.confirmingClear) {
        AlertDialog(
            onDismissRequest = viewModel::cancelClear,
            title = { Text("清空本机文件？") },
            text = { Text(LocalFilesLogic.clearQuestion(state.snapshot)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmClear) { Text("全部删除") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelClear) { Text("取消") }
            },
        )
    }

    if (showSort) {
        SortDialog(
            options = SortOrder.entries.map { it.label },
            selectedIndex = SortOrder.entries.indexOf(state.sort.order),
            descending = state.sort.descending,
            onSelect = { viewModel.setSortOrder(SortOrder.entries[it]) },
            onToggleDirection = viewModel::toggleSortDirection,
            onDismiss = { showSort = false },
        )
    }
}


/** 一个文件一张卡片：左边图标，中间名字与副标题，右边一个更多菜单。 */
@Composable
private fun LocalFileCard(
    row: LocalFileRow,
    busy: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSaveAs: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // 选择态下点卡片是勾选，不是打开：一进去就播放/打开文件会把人吓一跳。
    // 不在选择态时长按卡片也能进入勾选，和浏览页的手势一致。
    PanCard(
        onClick = when {
            busy -> null
            selecting -> onToggleSelect
            else -> onOpen
        },
        onLongClick = if (busy || selecting) null else onToggleSelect,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
            }
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = PanColors.Blue,
                modifier = Modifier.size(28.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.md),
            ) {
                Text(
                    text = row.entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = !busy) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("打开") }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("分享") }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text("另存到…") }, onClick = { menu = false; onSaveAs() })
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/**
 * 选择态下的底栏。
 *
 * 删除放在右下角而不是顶栏也有，是因为这里手指落点最近；
 * 但真正动手前仍然要走确认框，底栏这一下只是「我决定了」。
 */
@Composable
private fun SelectionBar(
    summary: String,
    busy: Boolean,
    onDelete: () -> Unit,
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
        Text(
            text = summary,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDelete, enabled = !busy) { Text("删除") }
    }
}

