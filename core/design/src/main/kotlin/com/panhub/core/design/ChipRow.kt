package com.panhub.core.design

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 横向一排可以点选的 chip，用于「按类型筛」这类固定选项。
 *
 * 只认字符串和下标，不认识业务枚举：网盘浏览页与本机文件页分属两个 feature 模块，
 * 让它们各自维护一份 chip 排布，改一次间距就要改两处，迟早只改对一半。
 *
 * [selectedIndex] 为 -1 表示什么都没选。再点一次已选项由调用方决定要不要取消——
 * 这里只负责把点击原样报出去。
 */
@Composable
fun ChipRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        options.forEachIndexed { index, label ->
            FilterChip(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                label = { Text(label) },
            )
        }
    }
}
