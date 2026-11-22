package com.panhub.feature.transfers

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.panhub.core.design.EmptyState
import com.panhub.core.design.PanCard
import com.panhub.core.design.PanColors
import com.panhub.core.design.Spacing
import com.panhub.core.transfer.TransferDirection
import com.panhub.core.design.formatBytes
import com.panhub.core.transfer.TransferState
import com.panhub.core.transfer.TransferTask

/**
 * 传输列表页。
 *
 * 蓝云风格：顶部极简标题栏、筛选用胶囊 chip、每个任务一张白卡片，
 * 进度条只在运行中的任务上出现，结束的任务用文字与颜色表达结果。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(
    viewModel: TransfersViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.activeCount > 0) "传输（" + state.activeCount + "）" else "传输",
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.hasFinished) {
                        TextButton(onClick = viewModel::clearFinished) { Text("清空已完成") }
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                FilterChip(
                    selected = state.filter == TransferFilter.ALL,
                    onClick = { viewModel.setFilter(TransferFilter.ALL) },
                    label = { Text("全部") },
                )
                FilterChip(
                    selected = state.filter == TransferFilter.ACTIVE,
                    onClick = { viewModel.setFilter(TransferFilter.ACTIVE) },
                    label = { Text("进行中") },
                )
                FilterChip(
                    selected = state.filter == TransferFilter.FINISHED,
                    onClick = { viewModel.setFilter(TransferFilter.FINISHED) },
                    label = { Text("已结束") },
                )
            }

            val visible = state.visible
            if (visible.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Download,
                    title = "还没有传输任务",
                    message = "在文件列表里长按文件即可开始下载",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    item { Spacer(Modifier.height(Spacing.md)) }
                    items(visible, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            onCancel = { viewModel.cancel(task.id) },
                            onRetry = { viewModel.retry(task.id) },
                            onOpen = { path -> context.openDownloaded(path) },
                            onShare = { path -> context.shareDownloaded(path) },
                        )
                    }
                    item { Spacer(Modifier.height(Spacing.xl)) }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: TransferTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    PanCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (task.state) {
                        TransferState.COMPLETED -> Icons.Default.CheckCircle
                        TransferState.FAILED -> Icons.Default.ErrorOutline
                        else -> if (task.direction == TransferDirection.UPLOAD) {
                            Icons.Default.Upload
                        } else {
                            Icons.Default.Download
                        }
                    },
                    contentDescription = null,
                    tint = when (task.state) {
                        TransferState.COMPLETED -> PanColors.Green
                        TransferState.FAILED -> PanColors.Red
                        TransferState.CANCELED -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
                Spacer(Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = statusLine(task),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 已完成任务的操作针对本地文件：打开 / 分享出去。
                // 上传任务的 filePath 是源文件，打开它同样有意义。
                val donePath = task.filePath?.takeIf { task.state == TransferState.COMPLETED }
                when {
                    donePath != null -> Row {
                        IconButton(onClick = { onShare(donePath) }) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "分享",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onOpen(donePath) }) { Text("打开") }
                    }
                    task.state == TransferState.RUNNING || task.state == TransferState.QUEUED ->
                        TextButton(onClick = onCancel) { Text("取消") }
                    task.state == TransferState.FAILED || task.state == TransferState.CANCELED ->
                        TextButton(onClick = onRetry) { Text("重试") }
                    else -> Unit
                }
            }

            if (task.state == TransferState.RUNNING) {
                Spacer(Modifier.height(Spacing.sm))
                val fraction = task.progress
                if (fraction == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun statusLine(task: TransferTask): String {
    val done = formatBytes(task.bytes)
    val total = task.totalBytes?.let { formatBytes(it) }
    return when (task.state) {
        TransferState.QUEUED -> queuedLine(task) + (total?.let { " · " + it } ?: "")
        TransferState.RUNNING -> runningLine(task, done, total)
        TransferState.COMPLETED -> finishedLine(task) + " · " + done
        TransferState.FAILED -> "失败" + (task.error?.let { " · " + it } ?: "")
        TransferState.CANCELED -> "已取消 · " + done
    }
}

private fun queuedLine(task: TransferTask): String = when (task.direction) {
    TransferDirection.UPLOAD -> "等待上传"
    TransferDirection.CLOUD_COPY -> "等待转存"
    TransferDirection.DOWNLOAD -> "排队中"
}

private fun runningLine(task: TransferTask, done: String, total: String?): String {
    val amount = if (total != null) done + " / " + total else done
    // 转存要先后下载再上传，同一个进度数字在两个小时段含义不同，
    // 不标出当前阶段用户会以为卡住了。
    if (task.direction != TransferDirection.CLOUD_COPY) return amount
    val stage = task.phase ?: "转存中"
    return stage + " · " + amount
}

private fun finishedLine(task: TransferTask): String = when (task.direction) {
    TransferDirection.UPLOAD -> "已上传"
    TransferDirection.CLOUD_COPY -> "已转存"
    TransferDirection.DOWNLOAD -> "已完成"
}

/**
 * 用系统能力打开下载好的文件。
 *
 * 文件在应用私有目录里，直接 file:// 从 Android 7 起会被 FileUriExposedException 拒绝，
 * 必须经 FileProvider 换成 content:// 并临时授权给目标应用。
 */
private fun android.content.Context.openDownloaded(path: String) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            packageName + ".files",
            java.io.File(path),
        )
        val mime = contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }
}

/** 把下载好的文件分享给别的应用（微信、邮件等）。 */
private fun android.content.Context.shareDownloaded(path: String) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            packageName + ".files",
            java.io.File(path),
        )
        val mime = contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(
            Intent.createChooser(intent, "分享文件")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

