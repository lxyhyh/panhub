package com.panhub.feature.transfers

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.panhub.core.design.FilterBar
import com.panhub.core.design.PanCard
import com.panhub.core.design.PanColors
import com.panhub.core.design.Spacing
import com.panhub.core.model.NodeFilter
import com.panhub.core.transfer.TransferDirection
import com.panhub.core.transfer.TransferEstimate
import com.panhub.core.design.formatBytes
import com.panhub.core.transfer.TransferState
import com.panhub.core.transfer.TransferTask
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // 筛选条默认收起：多数时候用户是来看进度的，不是来找名字的。
    var showFilter by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 待另存的文件路径。系统文件选择器是另一个界面，回调只能拿到 uri，
    // 所以「要保存哪一个」必须在这里先记下来。
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val source = pendingExport
        pendingExport = null
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        scope.launch {
            val message = withContext(Dispatchers.IO) { saveCopy(context, source, uri) }
            snackbar.showSnackbar(message)
        }
    }
    val startExport: (String) -> Unit = { path ->
        pendingExport = path
        exportLauncher.launch(TransferExport.suggestName(path))
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    // 有任务在跑时标题直接带上百分比：用户从别的页面切回来，
                    // 一眼就能看出是刚起步还是快好了，不用再找那一行小字。
                    val percent = state.digest.percent
                    Text(
                        when {
                            state.activeCount == 0 -> "传输"
                            percent != null -> "传输 " + percent + "%（" + state.activeCount + "）"
                            else -> "传输（" + state.activeCount + "）"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // 收起搜索框时顺手清掉关键字：只藏输入框却留着筛选，
                        // 列表会莫名其妙少东西。
                        if (showFilter) viewModel.clearQuery()
                        showFilter = !showFilter
                    }) {
                        Icon(Icons.Default.FilterList, contentDescription = "筛选")
                    }
                    // 「先停一下」很少只针对一个文件：全暂停把排队与在跑的一起按住，
                    // 恢复时一个按钮全部放行。按钮按队列状态出现或消失。
                    if (state.activeCount > 0) {
                        TextButton(onClick = viewModel::pauseAll) { Text("全部暂停") }
                    } else if (state.pausedCount > 0) {
                        TextButton(onClick = viewModel::resumeAll) { Text("全部继续") }
                    }
                    // 网络断了之后常常一次攒下好几个失败的任务，一个个点重试很烦。
                    if (state.failedCount > 0) {
                        TextButton(onClick = viewModel::retryFailed) { Text("重试失败") }
                    }
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
            // 队列被挡住时（例如开着「仅 Wi-Fi」却在用移动数据）要说清楚原因，
            // 否则用户看到一堆任务一动不动会以为是坏了。
            state.gateMessage?.let { reason -> GateBanner(reason) }
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

            if (showFilter && state.tasks.isNotEmpty()) {
                FilterBar(
                    query = state.query,
                    placeholder = "这个队列里的任务",
                    summary = state.filterSummary,
                    onQuery = viewModel::setQuery,
                    onClear = viewModel::clearQuery,
                )
            }

            val visible = state.visible
            if (visible.isEmpty()) {
                EmptyState(
                    icon = if (state.filtering) Icons.Default.FilterList else Icons.Default.Download,
                    title = state.emptyTitle,
                    message = state.emptyHint,
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
                            speed = state.speeds[task.id],
                            accountNote = TransferAccountNote.note(task, state.accountLabels),
                            // 用全部任务算，不用当前筛选后的列表：切到「已结束」时
                            // 前面的排队任务会全被筛掉，那样算出来的位置是假的。
                            queuedAhead = TransferStatusText.aheadOf(state.tasks, task),
                            onCancel = { viewModel.cancel(task.id) },
                            onPause = { viewModel.pause(task.id) },
                            onResume = { viewModel.resume(task.id) },
                            onRetry = { viewModel.retry(task.id) },
                            onMoveToFront = { viewModel.moveToFront(task.id) },
                            onOpen = { path -> context.openDownloaded(path) },
                            onShare = { path -> context.shareDownloaded(path) },
                            onSaveAs = startExport,
                            onDeleteLocal = {
                                val done = viewModel.deleteLocalFile(task.id)
                                scope.launch {
                                    snackbar.showSnackbar(
                                        if (done) "已删除本地文件" else "没能删掉这个文件",
                                    )
                                }
                            },
                        )
                    }
                    item { Spacer(Modifier.height(Spacing.xl)) }
                }
            }
        }
    }
}


/** 队列被挡住的原因。用自己的卡片而不是 Snackbar：它会一直成立到网络改变为止。 */
@Composable
private fun GateBanner(reason: String) {
    PanCard(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.WifiOff,
                contentDescription = null,
                tint = PanColors.Orange,
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = Spacing.sm),
            )
        }
    }
}

@Composable
private fun TaskCard(
    task: TransferTask,
    speed: Long?,
    accountNote: String,
    /** 队列里排在它前面的任务数；0 表示下一个就是它。 */
    queuedAhead: Int,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onMoveToFront: () -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    onSaveAs: (String) -> Unit,
    onDeleteLocal: () -> Unit,
) {
    PanCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (task.state) {
                        TransferState.COMPLETED -> Icons.Default.CheckCircle
                        TransferState.FAILED -> Icons.Default.ErrorOutline
                        else -> when (task.direction) {
                            TransferDirection.UPLOAD,
                            TransferDirection.UPLOAD_FOLDER,
                            -> Icons.Default.Upload
                            TransferDirection.CLOUD_COPY,
                            TransferDirection.CLOUD_COPY_FOLDER,
                            -> Icons.Default.CloudUpload
                            else -> Icons.Default.Download
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
                        text = statusLine(task, speed, queuedAhead) + accountNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 已完成任务的操作针对本地文件：打开 / 分享出去。
                // 上传任务的 filePath 是源文件，打开它同样有意义。
                val donePath = task.filePath?.takeIf { task.state == TransferState.COMPLETED }
                when {
                    donePath != null -> Row {
                        // 只有真正下载下来的文件才值得另存：转存任务的 filePath
                        // 是中间用的临时文件，任务结束后随时会被清掉；
                        // 上传任务的 filePath 是用户自己的源文件，另存一份没有意义。
                        if (task.direction == TransferDirection.DOWNLOAD) {
                            IconButton(onClick = { onSaveAs(donePath) }) {
                                Icon(
                                    Icons.Default.SaveAlt,
                                    contentDescription = "另存到",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { onShare(donePath) }) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "分享",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 下载的文件一直占着空间，清空列表不会把它带走，
                        // 所以删除必须是单独一个动作，和「从列表移除」区分开。
                        if (task.direction == TransferDirection.DOWNLOAD) {
                            IconButton(onClick = onDeleteLocal) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除本地文件",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        TextButton(onClick = { onOpen(donePath) }) { Text("打开") }
                    }
                    task.state == TransferState.PAUSED ->
                        Row {
                            TextButton(onClick = onResume) { Text("继续") }
                            TextButton(onClick = onCancel) { Text("取消") }
                        }
                    task.state == TransferState.QUEUED ->
                        Row {
                            // 串行队列里排了十个文件之后想先拿某一个，
                            // 以前只能全部取消重排，所以给排队中的任务一个置顶。
                            if (queuedAhead > 0) {
                                TextButton(onClick = onMoveToFront) { Text("优先") }
                            }
                            TextButton(onClick = onPause) { Text("暂停") }
                            TextButton(onClick = onCancel) { Text("取消") }
                        }
                    task.state == TransferState.RUNNING ->
                        Row {
                            TextButton(onClick = onPause) { Text("暂停") }
                            TextButton(onClick = onCancel) { Text("取消") }
                        }
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

private fun statusLine(task: TransferTask, speed: Long?, queuedAhead: Int): String {
    // 整理型任务（整目录下载、整目录转存）自己都不传字节，进度要看那些子任务，
    // 按普通任务拼「已传 / 总共」只会给出一行看不懂的数字。
    if (task.direction == TransferDirection.DOWNLOAD_FOLDER) return folderLine(task)
    if (task.direction == TransferDirection.CLOUD_COPY_FOLDER) return copyFolderLine(task)
    if (task.direction == TransferDirection.UPLOAD_FOLDER) return uploadFolderLine(task)
    val done = formatBytes(task.bytes)
    val total = task.totalBytes?.let { formatBytes(it) }
    return when (task.state) {
        TransferState.QUEUED -> queuedLine(task) + attemptNote(task) + (total?.let { " · " + it } ?: "") + TransferStatusText.queueNote(queuedAhead)
        TransferState.RUNNING -> runningLine(task, done, total) + TransferStatusText.speedNote(task, speed) + attemptNote(task)
        TransferState.COMPLETED -> finishedLine(task) + " · " + done
        TransferState.FAILED -> "失败" + (task.error?.let { " · " + it } ?: "")
        TransferState.CANCELED -> "已取消 · " + done
        TransferState.PAUSED -> pausedLine(done, total)
    }
}

/** 暂停的任务要能看出「已经到哪儿了」，否则和还没开始没区别。 */
private fun pausedLine(done: String, total: String?): String =
    if (total != null) "已暂停 · " + done + " / " + total else "已暂停 · " + done

/**
 * 自动重试的提示。
 *
 * 重试期间进度会归零重来，不说明的话用户会以为任务出了问题；
 * 第一次尝试不提示，那是正常流程。
 */
private fun attemptNote(task: TransferTask): String {
    if (task.attempt <= 1) return ""
    return if (task.state == TransferState.QUEUED) {
        " · 第 " + (task.attempt + 1) + " 次尝试即将开始"
    } else {
        " · 第 " + task.attempt + " 次尝试"
    }
}

private fun folderLine(task: TransferTask): String = when (task.state) {
    TransferState.QUEUED -> "等待整理文件列表"
    TransferState.RUNNING -> "正在整理文件列表"
    TransferState.COMPLETED -> task.phase?.let { "已整理 " + it } ?: "已整理文件列表"
    TransferState.FAILED -> "整理失败" + (task.error?.let { " · " + it } ?: "")
    TransferState.CANCELED -> "已取消整理"
    TransferState.PAUSED -> "已暂停整理"
}

private fun copyFolderLine(task: TransferTask): String = when (task.state) {
    TransferState.QUEUED -> "等待复制文件夹"
    TransferState.RUNNING -> task.phase?.let { "正在" + it } ?: "正在复制文件夹"
    TransferState.COMPLETED -> task.phase?.let { "已复制 " + it } ?: "已复制文件夹"
    TransferState.FAILED -> "复制失败" + (task.error?.let { " · " + it } ?: "")
    TransferState.CANCELED -> "已取消复制文件夹"
    TransferState.PAUSED -> "已暂停复制文件夹"
}

private fun uploadFolderLine(task: TransferTask): String = when (task.state) {
    TransferState.QUEUED -> "等待整理本地文件夹"
    TransferState.RUNNING -> task.phase?.let { "正在" + it } ?: "正在整理本地文件夹"
    TransferState.COMPLETED -> task.phase?.let { "已整理 " + it } ?: "已整理本地文件夹"
    TransferState.FAILED -> "整理失败" + (task.error?.let { " · " + it } ?: "")
    TransferState.CANCELED -> "已取消整理"
    TransferState.PAUSED -> "已暂停整理"
}

private fun queuedLine(task: TransferTask): String = when (task.direction) {
    TransferDirection.UPLOAD -> "等待上传"
    TransferDirection.CLOUD_COPY -> "等待转存"
    TransferDirection.DOWNLOAD_FOLDER -> "等待整理文件列表"
    TransferDirection.CLOUD_COPY_FOLDER -> "等待复制文件夹"
    TransferDirection.UPLOAD_FOLDER -> "等待整理本地文件夹"
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
    TransferDirection.DOWNLOAD_FOLDER -> "已整理文件列表"
    TransferDirection.CLOUD_COPY_FOLDER -> "已复制文件夹"
    TransferDirection.UPLOAD_FOLDER -> "已整理本地文件夹"
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

/**
 * 把下载好的文件写进用户选的位置，返回给用户看的一句话。
 *
 * 打开输出流和复制都可能失败（目标被删、空间不够），这些都不该让 App 崩掉：
 * 下载本身已经成功，这里失败的只是「另存」这一步。
 */
private fun saveCopy(context: android.content.Context, path: String, uri: android.net.Uri): String {
    val source = File(path)
    if (!source.isFile) return "原文件已经不在了，没法另存"
    val written = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            TransferExport.copyTo(source, out)
        }
    }.getOrNull() ?: return "没能打开那个位置，换个地方试试"
    return "已保存 · " + formatBytes(written)
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

