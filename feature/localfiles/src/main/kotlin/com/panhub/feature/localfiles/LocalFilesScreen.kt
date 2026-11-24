package com.panhub.feature.localfiles

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
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
import com.panhub.core.design.EmptyState
import com.panhub.core.design.LoadingState
import com.panhub.core.design.PanCard
import com.panhub.core.design.PanColors
import com.panhub.core.design.Spacing
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
        topBar = {
            TopAppBar(
                title = { Text("本机文件") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.canClear) {
                        TextButton(onClick = viewModel::askClear) { Text("清空") }
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
            if (state.empty) {
                EmptyState(
                    icon = Icons.Default.DownloadDone,
                    title = "本机没有文件",
                    message = LocalFilesLogic.emptyMessage(),
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
                items(state.rows, key = { it.entry.relativePath }) { row ->
                    LocalFileCard(
                        row = row,
                        busy = state.busy,
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

    state.confirming?.let { entry ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("删除这个文件？") },
            text = { Text(LocalFilesLogic.deleteQuestion(entry)) },
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
}

/** 一个文件一张卡片：左边图标，中间名字与副标题，右边一个更多菜单。 */
@Composable
private fun LocalFileCard(
    row: LocalFileRow,
    busy: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSaveAs: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    PanCard(onClick = if (busy) null else onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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

