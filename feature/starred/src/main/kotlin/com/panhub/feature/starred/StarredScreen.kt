package com.panhub.feature.starred

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.panhub.core.design.ChipRow
import com.panhub.core.design.EmptyState
import com.panhub.core.design.FilterBar
import com.panhub.core.design.LoadingState
import com.panhub.core.design.PanCard
import com.panhub.core.design.PanColors
import com.panhub.core.design.Spacing
import com.panhub.core.design.formatBytes
import com.panhub.core.model.FileKinds
import com.panhub.core.model.RemoteNode

/**
 * 收藏夹。
 *
 * 收藏夹是**跨目录**的：条目来自不同文件夹，所以列表里不显示路径，
 * 点进去才由调用方决定落到哪。取消收藏做成每行一个星形按钮，
 * 而不是藏进菜单：这一页最大的用途就是把收藏过的东西清理掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredScreen(
    viewModel: StarredViewModel,
    onBack: () -> Unit,
    onOpen: (RemoteNode) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // 筛选条默认收起：收藏夹通常只有几件东西。
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
            TopAppBar(
                title = {
                    Text(
                        text = if (state.items.isEmpty()) {
                            "收藏夹"
                        } else {
                            "收藏夹（" + state.items.size + "）"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.supported) {
                        IconButton(
                            onClick = {
                                if (showFilter) {
                                    viewModel.clearQuery()
                                    viewModel.clearKind()
                                }
                                showFilter = !showFilter
                            },
                        ) {
                            Icon(Icons.Default.FilterList, contentDescription = "筛选")
                        }
                        IconButton(onClick = viewModel::refresh, enabled = !state.refreshing) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            if (showFilter && state.items.isNotEmpty()) {
                ChipRow(
                    options = FileKinds.filterable.map { it.label },
                    selectedIndex = FileKinds.indexOf(state.kind),
                    onSelect = { viewModel.toggleKind(FileKinds.filterable[it]) },
                )
                FilterBar(
                    query = state.query,
                    summary = state.filterSummary,
                    onQuery = viewModel::setQuery,
                    // 「清空」把类型一起清掉：留着一个看不见的类型条件，
                    // 用户会以为筛选条已经关了，列表却仍然少东西。
                    onClear = {
                        viewModel.clearQuery()
                        viewModel.clearKind()
                    },
                    placeholder = "收藏夹里的文件名",
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    !state.supported -> EmptyState(
                        icon = Icons.Outlined.StarBorder,
                        title = "这个网盘没有收藏夹",
                        message = "收藏是这个网盘没提供的功能",
                    )
                    state.loading -> LoadingState()
                    state.items.isEmpty() && state.error != null -> EmptyState(
                        icon = Icons.Outlined.StarBorder,
                        title = "打不开收藏夹",
                        message = state.error?.message,
                    )
                    state.visible.isEmpty() -> EmptyState(
                        icon = if (state.filtering) Icons.Default.FilterList else Icons.Outlined.StarBorder,
                        title = state.emptyTitle,
                        message = state.emptyHint,
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
                        items(state.visible, key = { it.id.value }) { node ->
                            StarredCard(
                                node = node,
                                busy = state.busy,
                                onClick = { onOpen(node) },
                                onUnstar = { viewModel.unstar(node) },
                            )
                        }
                        if (state.hasMore) {
                            item {
                                TextButton(
                                    onClick = viewModel::loadMore,
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
    }
}

@Composable
private fun StarredCard(
    node: RemoteNode,
    busy: Boolean,
    onClick: () -> Unit,
    onUnstar: () -> Unit,
) {
    PanCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
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
                    imageVector = if (node.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    tint = if (node.isDir) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = StarredLogic.subtitle(node) { formatBytes(it) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onUnstar, enabled = !busy) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "取消收藏",
                    tint = PanColors.Orange,
                )
            }
        }
    }
}