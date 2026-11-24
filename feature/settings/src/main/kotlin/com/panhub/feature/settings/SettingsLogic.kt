package com.panhub.feature.settings

import com.panhub.core.design.formatBytes
import com.panhub.core.platform.ThemeMode

/** 一个外观选项：模式、标题、一句话解释。 */
data class ThemeOption(
    val mode: ThemeMode,
    val label: String,
    val hint: String,
)

/**
 * 设置页里不依赖 Android 的那部分。
 *
 * 抽出来是为了能被 JVM 单测直接覆盖：文案与选项顺序是最容易被
 * 「顺手改一下」破坏、又最难在界面上发现出错的东西。
 */
object SettingsLogic {

    /** 外观的三个选项，顺序固定：跟随系统在最前，它是默认值。 */
    fun themeOptions(): List<ThemeOption> = listOf(
        ThemeOption(ThemeMode.SYSTEM, "跟随系统", "系统是深色就用深色"),
        ThemeOption(ThemeMode.LIGHT, "浅色", "白底，白天看得清"),
        ThemeOption(ThemeMode.DARK, "深色", "夜间不刺眼"),
    )

    /** 当前选中项的标题；认不出时按默认值给。 */
    fun labelOf(mode: ThemeMode): String =
        themeOptions().firstOrNull { it.mode == mode }?.label ?: "跟随系统"

    /**
     * 当前该用深色还是浅色。
     *
     * 跟随系统时把系统值原样传出去，而不是自己判断时间：
     * 系统的深色可能是用户手动开的、也可能是按日落自动切的，
     * 只有它知道现在该是什么。
     */
    /**
     * 缓存那一行的说明文字。
     *
     * [bytes] 为 null 表示还没扫完——这时候不能写「0 B」，那会让人以为
     * 缓存是空的、按钮也点不动，而事实是还没数完。
     */
    fun cacheLine(bytes: Long?): String = when {
        bytes == null -> "正在统计…"
        bytes <= 0L -> "没有可清理的临时文件"
        else -> formatBytes(bytes) + " · 上传与中转的临时文件"
    }

    /** 清理完成后的提示。 */
    fun clearedMessage(removed: Int): String = when {
        removed <= 0 -> "没有需要清理的文件"
        removed == 1 -> "已清理 1 个临时文件"
        else -> "已清理 " + removed + " 个临时文件"
    }

    fun darkTheme(mode: ThemeMode, systemInDark: Boolean): Boolean = when (mode) {
        ThemeMode.SYSTEM -> systemInDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}