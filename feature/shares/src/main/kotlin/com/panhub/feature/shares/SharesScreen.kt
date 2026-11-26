package com.panhub.feature.shares

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.panhub.core.design.EmptyState
import com.panhub.core.design.FilterBar
import com.panhub.core.design.LoadingState
import com.panhub.core.design.PanCard
import com.panhub.core.design.PanColors
import com.panhub.core.design.SortDialog
import com.panhub.core.design.Spacing
import com.panhub.core.model.ShareLink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 我的分享。
 *
 * 每条分享只有三个动作：复制链接、发给别人、取消。
 * 取消是破坏性的（取消之后链接立刻失效，且没有恢复入口），
 * 所以固定走一次确认，不放在长按或滑动里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharesScreen(
    viewModel: SharesViewModel,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.start() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    // 确认框要能同时管一条和一整批，所以存列表而不是单条。
    var pendingCancel by remember { mutableStateOf<List<ShareRow>>(emptyList()) }
    // 筛选条默认收起：分享列表通常只有几条，多一行输入框反而挡住链接。
    var showFilter by remember { mutableStateOf(false) }
    // 排序默认收起：分享通常只有十几条，多一层菜单反而挡住链接。
    var showSort by remember { mutableStateOf(false) }

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
                            Text("我的分享")
                            if (state.accountName.isNotBlank()) {
                                Text(
                                    text = state.accountName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (state.canCancel && state.visible.isNotEmpty()) {
                            IconButton(onClick = viewModel::toggleSelectAll) {
                                Icon(
                                    imageVector = Icons.Default.SelectAll,
                                    contentDescription = if (state.allSelected) "取消全选" else "全选",
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                if (showFilter) viewModel.clearQuery()
                                showFilter = !showFilter
                            },
                        ) {
                            Icon(Icons.Default.FilterList, contentDescription = "筛选")
                        }
                        if (state.rows.size > 1) {
                            IconButton(onClick = { showSort = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                            }
                        }
                        IconButton(onClick = viewModel::refresh, enabled = !state.refreshing) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                if (state.refreshing) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().size(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.selectionMode) {
                SelectionBar(
                    count = state.selectedCount,
                    busy = state.cancelling,
                    onCancel = { pendingCancel = state.selectedRows },
                    onClear = viewModel::clearSelection,
                )
            }
        },
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            if (showFilter && state.rows.isNotEmpty()) {
                FilterBar(
                    query = state.query,
                    placeholder = "分享的标题或链接",
                    summary = state.filterSummary,
                    onQuery = viewModel::setQuery,
                    onClear = viewModel::clearQuery,
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.loading -> LoadingState()
                    state.error != null && state.rows.isEmpty() -> EmptyState(
                        icon = Icons.Default.LinkOff,
                        title = "没能读到分享",
                        message = state.error?.message.orEmpty(),
                    )
                    // 一条分享都没有，和「有分享但被关键字筛没了」是两件事，
                    // 说法必须分开，否则用户不知道该改关键字还是去创建分享。
                    state.visible.isEmpty() -> EmptyState(
                        icon = if (state.filtering) Icons.Default.FilterList else Icons.Default.Link,
                        title = state.emptyTitle,
                        message = state.emptyHint,
                    )
                    else -> LazyColumn(
                        contentPadding = PaddingValues(
                            start = Spacing.lg,
                            end = Spacing.lg,
                            top = Spacing.sm,
                            bottom = Spacing.xxl + Spacing.xxl,
                        ),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        items(state.visible, key = { it.key }) { row ->
                            ShareCard(
                                row = row,
                                busy = state.cancelling || state.busyKey == row.key,
                                canCancel = state.canCancel,
                                selecting = state.selectionMode,
                                selected = row.key in state.selected,
                                onCopy = { text -> context.copyText(text) },
                                onShare = { text -> context.shareText(text) },
                                onOpen = { url -> context.openUrl(url) },
                                onCancel = { pendingCancel = listOf(row) },
                                onToggleSelect = { viewModel.toggleSelect(row) },
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
    }

    if (pendingCancel.isNotEmpty()) {
        val rows = pendingCancel
        AlertDialog(
            onDismissRequest = { pendingCancel = emptyList() },
            title = { Text(SharesLogic.cancelTitle(rows)) },
            text = { Text(SharesLogic.cancelQuestion(rows.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingCancel = emptyList()
                        viewModel.cancelRows(rows)
                    },
                ) { Text(SharesLogic.cancelConfirmLabel(rows.size), color = PanColors.Red) }
            },
            dismissButton = {
                TextButton(onClick = { pendingCancel = emptyList() }) { Text("保留") }
            },
        )
    }

    if (showSort) {
        SortDialog(
            options = SharesLogic.SORT_ORDERS.map { it.label },
            selectedIndex = SharesLogic.SORT_ORDERS.indexOf(state.sort.order),
            descending = state.sort.descending,
            onSelect = { index -> viewModel.setSortOrder(SharesLogic.SORT_ORDERS[index]) },
            onToggleDirection = viewModel::toggleSortDirection,
            onDismiss = { showSort = false },
        )
    }
}

/** 一条分享。标题可能为空（部分服务商不返回），退化成链接尾段。 */
@Composable
private fun ShareCard(
    row: ShareRow,
    busy: Boolean,
    canCancel: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onOpen: (String) -> Unit,
    onCancel: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    val link = row.link
    val text = shareTextOf(link)
    // 选择态下点卡片是勾选，不是打开链接：批量取消时要连着点好几张，
    // 顺手打开浏览器会打断整个流程。
    PanCard(
        onClick = {
            if (selecting) onToggleSelect() else onOpen(link.url)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 选择态才画勾选框：平时没有它，行首就不该凭空多出空白。
            if (selecting) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() },
                    enabled = !busy,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = shareTitleOf(link),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = link.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = statusLine(link),
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    link.expired -> PanColors.Red
                    link.expiresAt != null -> PanColors.Orange
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Box(modifier = Modifier.weight(1f))
            IconButton(onClick = { onCopy(text) }, enabled = !busy && !selecting) {
                Icon(Icons.Default.ContentCopy, contentDescription = "复制链接")
            }
            IconButton(onClick = { onShare(text) }, enabled = !busy && !selecting) {
                Icon(Icons.Default.Share, contentDescription = "发送链接")
            }
            if (canCancel) {
                IconButton(onClick = onCancel, enabled = !busy) {
                    Icon(
                        Icons.Default.LinkOff,
                        contentDescription = "取消分享",
                        tint = PanColors.Red,
                    )
                }
            }
        }
    }
}

/** 复制或发送时带上的完整文案：提取码跟着链接走，免得用户拷了链接还要回头找密码。 */
internal fun shareTextOf(link: ShareLink): String = buildString {
    append(link.url)
    link.password?.takeIf { it.isNotBlank() }?.let {
        append("  提取码：")
        append(it)
    }
}

internal fun shareTitleOf(link: ShareLink): String =
    link.title?.takeIf { it.isNotBlank() }
        ?: link.url.substringAfterLast('/').takeIf { it.isNotBlank() }
        ?: "分享"

/** 到期状态。服务商判定的失效优先于按时间推算。 */
internal fun statusLine(link: ShareLink): String {
    if (link.expired) return "已失效"
    val expiresAt = link.expiresAt ?: return "长期有效"
    val text = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(expiresAt))
    return text + " 到期"
}

@Composable
private fun LoadMoreRow(loading: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onClick) { Text("加载更多") }
        }
    }
}

/**
 * 选择态的底栏。
 *
 * 只放一个破坏性动作，而且必须先弹确认：取消分享是不可撤回的，
 * 一次点掉十几条链接之后再发现点错，没有任何地方能恢复。
 */
@Composable
private fun SelectionBar(
    count: Int,
    busy: Boolean,
    onCancel: () -> Unit,
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
        Text(text = SharesLogic.selectionSummary(count), style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.weight(1f))
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        TextButton(onClick = onCancel, enabled = !busy) {
            Icon(
                imageVector = Icons.Default.LinkOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text("取消分享", modifier = Modifier.padding(start = 4.dp))
        }
    }
}

private fun Context.copyText(text: String) {
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("PanHub", text))
    android.widget.Toast.makeText(this, "已复制", android.widget.Toast.LENGTH_SHORT).show()
}

private fun Context.shareText(text: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "分享链接").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun Context.openUrl(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
