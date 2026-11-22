package com.panhub.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 蓝云风格的白色圆角卡片。
 *
 * 之所以做成组件而不是让每个页面自己写 Card：圆角、阴影、内边距
 * 在蓝云风格里是统一的，散落在各处就会很快漂移。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PanCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Radius.card)
    val base = modifier
        .fillMaxWidth()
        .clip(shape)
    val clickable = when {
        onClick == null -> base
        onLongClick == null -> base.clickable(onClick = onClick)
        else -> base.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    }
    Card(
        modifier = clickable,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
    ) {
        Box(modifier = Modifier.padding(Spacing.lg)) { content() }
    }
}

/**
 * 容量条：已用百分比 + 文字。
 *
 * 超过 85% 转成强调橙色，这是蓝云里唯一用橙色的地方。
 */
@Composable
fun QuotaBar(
    used: Long,
    total: Long?,
    modifier: Modifier = Modifier,
) {
    val ratio = if (total != null && total > 0) {
        (used.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
    } else {
        null
    }
    val barColor = when {
        ratio == null -> MaterialTheme.colorScheme.primary
        ratio >= 0.85 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (ratio != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(ratio.toFloat().coerceAtLeast(0.001f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(barColor),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatBytes(used),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = total?.let { formatBytes(it) } ?: "未知容量",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 空状态：图标 + 一句话 + 可选补充说明。 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.md),
        )
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
}

/** 加载态。 */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp,
        )
    }
}

/** 单行「标签 - 值」，用于详情页。 */
@Composable
fun LabeledRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Spacing.lg),
        )
    }
}

/**
 * 人类可读的字节数。
 *
 * 放在设计层而不是业务层：它是"展示格式"，属于界面关注点，
 * 而且需要全 App 完全一致（1000 还是 1024 不能各处不一样）。
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "${bytes} B"
    val units = listOf("KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    val text = if (value >= 100) "${value.toInt()}" else String.format("%.1f", value)
    return "$text ${units[index]}"
}

/** 颜色常量给需要直接画的地方用（图表、标签）。 */
object PanColors {
    val Blue = Color(0xFF3D6BF5)
    val Orange = Color(0xFFFF8A3D)
    val Green = Color(0xFF2FA36B)
    val Red = Color(0xFFE5484D)
}
