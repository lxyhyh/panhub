package com.panhub.feature.browser

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.panhub.core.design.Radius
import com.panhub.core.design.Spacing
import com.panhub.core.preview.PreviewContent
import com.panhub.core.preview.PreviewKind

/**
 * 就地预览。
 *
 * 用对话框而不是新页面：预览是「顺手看一眼」，看完就该回到原来的位置，
 * 跳一个页面会把用户从正在浏览的目录里带出去。
 *
 * 内容区固定高度并且自己滚动，是因为文件名和内容长度都不可预知——
 * 让对话框随内容长高，一张大图或一段长文本会把按钮顶到屏幕外面。
 */
@Composable
internal fun PreviewDialog(
    state: PreviewState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val node = state.node ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(text = node.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // 序号只在确实有一串同类文件时才显示。单独一个文件写「1 / 1」纯属噪音。
                val position = state.cursors.position
                if (position != null) {
                    Text(
                        text = position,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when {
                    state.loading -> Box(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                    state.error != null -> Text(
                        text = state.error.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    state.content != null -> PreviewBody(state.content)

                    else -> Text("没有可显示的内容")
                }
                if (state.content?.truncated == true) {
                    // 截断必须说出来：用户看到一段文本戛然而止，
                    // 不说清楚会以为是文件坏了。
                    Text(
                        text = "内容较长，这里只显示了开头一部分",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
                if (state.cursors.canBrowse) {
                    // 照片说「张」，文档说「份」——同一句「上一张」用在文本上很别扭。
                    val unit = if (state.kind == PreviewKind.IMAGE) "张" else "份"
                    PreviewPager(
                        hasPrevious = state.cursors.previous != null,
                        hasNext = state.cursors.next != null,
                        previousLabel = "上一" + unit,
                        nextLabel = "下一" + unit,
                        onPrevious = onPrevious,
                        onNext = onNext,
                    )
                }
            }
        },
        confirmButton = {
            if (state.canRetry) {
                TextButton(onClick = onRetry) { Text("重试") }
            } else {
                TextButton(onClick = onDownload) { Text("下载") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * 翻页那一行。
 *
 * 到底了就把按钮禁用而不是藏起来：按钮忽然消失会让整行左右跳动，
 * 而「不能往前了」本身也是有用的信息。
 */
@Composable
private fun PreviewPager(
    hasPrevious: Boolean,
    hasNext: Boolean,
    previousLabel: String,
    nextLabel: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onPrevious, enabled = hasPrevious) { Text(previousLabel) }
        TextButton(onClick = onNext, enabled = hasNext) { Text(nextLabel) }
    }
}

@Composable
private fun PreviewBody(content: PreviewContent) {
    when (content.kind) {
        PreviewKind.IMAGE -> ImagePreview(content)
        PreviewKind.TEXT -> TextPreview(content.text.orEmpty())
        PreviewKind.NONE -> Text("没有可显示的内容")
    }
}

@Composable
private fun ImagePreview(content: PreviewContent) {
    val bytes = content.bytes
    // 解码放在 remember 里：每次重组都解一遍，滚动一下就会卡。
    val bitmap = remember(bytes) {
        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
    }
    if (bitmap == null) {
        // 服务商说这是图片但字节解不开（常见于只存了缩略图的服务商）。
        Text("这张图片没法显示")
        return
    }
    Image(
        bitmap = bitmap,
        contentDescription = "图片预览",
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .clip(RoundedCornerShape(Radius.card)),
    )
}

@Composable
private fun TextPreview(text: String) {
    Surface(
        shape = RoundedCornerShape(Radius.card),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                // 代码和日志里长行很常见，横向也要能滚，否则只能看到前半截。
                .horizontalScroll(rememberScrollState())
                .padding(Spacing.md),
        ) {
            Text(
                text = text.ifEmpty { "（空文件）" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
