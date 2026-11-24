package com.panhub.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.panhub.core.design.LabeledRow
import com.panhub.core.design.PanCard
import com.panhub.core.design.Spacing
import com.panhub.core.platform.ThemeMode

/**
 * 设置页。
 *
 * 与其他页面同一个骨架：浅灰底、白色分组卡片、不做修改时没有确认按钮。
 * 这里刻意没有「保存」——外观是立即生效的开关，不是表单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val message = state.message
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        kotlinx.coroutines.delay(3000)
        viewModel.clearMessage()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.sm,
                bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                PanCard {
                    Column {
                        SectionTitle("外观")
                        state.options.forEach { option ->
                            ThemeRow(
                                option = option,
                                selected = option.mode == state.theme,
                                onClick = { viewModel.setTheme(option.mode) },
                            )
                        }
                    }
                }
            }
            item {
                PanCard {
                    Column {
                        SectionTitle("传输")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setWifiOnly(!state.wifiOnly) }
                                .padding(vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "仅 Wi-Fi 传输",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "移动数据下排队不动，连上 Wi-Fi 自己接着传",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = state.wifiOnly,
                                onCheckedChange = { viewModel.setWifiOnly(it) },
                            )
                        }
                    }
                }
            }
            item {
                PanCard {
                    Column {
                        SectionTitle("存储")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = state.canClearCache) { viewModel.clearCache() }
                                .padding(vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "清理缓存",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = state.cacheLine,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (state.clearing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text(
                                    text = "清理",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (state.canClearCache) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                        Hint(
                            "只清上传前复制的副本和跨网盘中转的临时文件。" +
                                "下载下来的文件、账号、传输队列都不受影响。",
                        )
                        state.message?.let { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = Spacing.sm),
                            )
                        }
                    }
                }
            }
            item {
                PanCard {
                    Column {
                        SectionTitle("关于")
                        LabeledRow("版本", state.info.versionName)
                        LabeledRow("摘要内核", state.info.kernel)
                        LabeledRow("下载目录", state.info.downloadsPath)
                    }
                }
            }
            item {
                PanCard {
                    Column {
                        SectionTitle("说明")
                        Hint(
                            "下载的文件放在应用私有目录里，卸载 App 会一起删掉。" +
                                "想长期保留，可以在传输页把文件「另存到」系统里的位置。",
                        )
                        Hint(
                            "各家网盘的接口按客户端实际使用的地址实现，" +
                                "用户名密码只在登录时用一次，之后本地只留会话凭据。",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = Spacing.sm),
    )
}

@Composable
private fun ThemeRow(
    option: ThemeOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = Spacing.sm)) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = option.hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = Spacing.sm).size(18.dp),
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(vertical = Spacing.xs),
    )
}