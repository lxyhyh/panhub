package com.panhub.feature.offline

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.panhub.core.design.EmptyState
import com.panhub.core.design.PanCard
import com.panhub.core.design.Spacing
import com.panhub.core.design.formatBytes
import com.panhub.core.model.OfflineFile
import com.panhub.core.model.OfflineTaskView

/**
 * 离线下载页。
 *
 * 一屏到底：上面贴链接，解析出来下面换成熟文件清单，提交完回浏览页。
 * 不做两页导航，是因为解析失败时用户要能直接改链接再试一次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineScreen(viewModel: OfflineViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.start() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.clearMessage()
        onBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("离线下载") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.lg),
        ) {
            // 已经在跑的离线任务：只显示一次加载结果，删一条摘一条。
            if (state.tasks.isNotEmpty() || state.tasksLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (state.tasksLoading) "正在读取离线任务…" else "离线任务（" + state.tasks.size + "）",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (!state.tasksLoading) {
                        TextButton(onClick = viewModel::refreshTasks) { Text("刷新") }
                    }
                }
                state.tasks.forEach { task ->
                    OfflineTaskRow(
                        task = task,
                        deleting = task.id in state.deletingTaskIds,
                        onDelete = { viewModel.deleteTask(task.id) },
                    )
                }
                Spacer(Modifier.size(Spacing.sm))
            }
            Text(
                text = "保存到「" + state.parentLabel + "」",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(Spacing.sm))
            OutlinedTextField(
                value = state.link,
                onValueChange = viewModel::setLink,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://… 或 magnet:?xt=urn:btih:…") },
                label = { Text("下载链接") },
                minLines = 2,
                maxLines = 4,
                enabled = !state.busy,
            )
            Spacer(Modifier.size(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TextButton(
                    onClick = viewModel::resolve,
                    enabled = !state.busy && state.link.isNotBlank(),
                ) {
                    Text(if (state.resolving) "解析中…" else "解析")
                }
                if (state.resource != null) {
                    TextButton(onClick = viewModel::resetLink, enabled = !state.busy) {
                        Text("换一个")
                    }
                }
            }
            state.error?.let { error ->
                Text(
                    text = OfflineLogic.failureMessage(error.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.size(Spacing.sm))
            }
            if (state.resolving) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                return@Column
            }
            val resource = state.resource
            if (resource == null) {
                EmptyState(
                    icon = Icons.Default.CloudDownload,
                    title = "还没有解析出内容",
                    message = "贴一个下载链接或磁力链接，网盘会替你下好再存进来。",
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = resource.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.summary.orEmpty() +
                            if (resource.totalBytes > 0L) " · " + formatBytes(resource.totalBytes) else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = viewModel::toggleAll, enabled = !state.busy) {
                    Text(if (state.selected.size >= resource.files.size) "全不选" else "全选")
                }
            }
            Spacer(Modifier.size(Spacing.sm))
            if (resource.files.isEmpty()) {
                // 有些直链解析出来就是单个文件、没有清单，这时也要能提交。
                PanCard {
                    Text(
                        text = "服务端没有给出文件清单，提交后会把整个资源存下来。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    items(resource.files, key = { it.id }) { file ->
                        OfflineFileRow(
                            file = file,
                            checked = file.id in state.selected,
                            onClick = { viewModel.toggle(file.id) },
                        )
                    }
                }
            }
            TextButton(
                onClick = viewModel::submit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
            ) {
                Text(if (state.submitting) "提交中…" else "开始下载 " + state.selected.size + " 项")
            }
        }
    }
}

@Composable
private fun OfflineTaskRow(task: OfflineTaskView, deleting: Boolean, onDelete: () -> Unit) {
    PanCard(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.name.ifBlank { "离线任务" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = OfflineLogic.taskSubtitle(task, ::formatBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (deleting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@Composable
private fun OfflineFileRow(file: OfflineFile, checked: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(
                if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surface,
            )
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (checked) Icons.Default.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = if (checked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!file.isDir && file.size > 0L) {
                Text(
                    text = formatBytes(file.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
