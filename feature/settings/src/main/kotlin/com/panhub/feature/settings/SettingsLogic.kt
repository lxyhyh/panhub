package com.panhub.feature.settings

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
    fun darkTheme(mode: ThemeMode, systemInDark: Boolean): Boolean = when (mode) {
        ThemeMode.SYSTEM -> systemInDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}
