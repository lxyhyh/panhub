package com.panhub.core.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 一行按名字筛选的输入框，外加一行「筛掉了多少」的小字。
 *
 * 网盘浏览页、本机文件页、传输队列页的筛选条长得一模一样，差别只有
 * 占位提示那一句。三份各写一遍，改一次圆角或内边距就要改三处，
 * 迟早只改对一半。
 *
 * [summary] 为 null 时不显示小字：全都命中时报「10/10」只是噪音。
 */
@Composable
fun FilterBar(
    query: String,
    summary: String?,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String = "按名字筛选",
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Close, contentDescription = "清空筛选")
                    }
                }
            } else {
                null
            },
        )
        if (summary != null) {
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.sm, top = Spacing.xs),
            )
        }
    }
}