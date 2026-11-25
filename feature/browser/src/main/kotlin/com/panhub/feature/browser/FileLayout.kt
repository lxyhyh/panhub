package com.panhub.feature.browser

/**
 * 文件列表怎么摆。
 *
 * 只有两种：列表能一眼看清名字、大小和时间，适合找具体的文件；
 * 网格用大缩略图，适合翻照片和截图。做成一个显式枚举而不是布尔，
 * 是为了以后加第三种（比如大图墙）时不用把 true/false 的含义重新解释一遍。
 */
enum class FileLayout {
    LIST,
    GRID,
    ;

    fun toggled(): FileLayout = if (this == LIST) GRID else LIST

    companion object {
        /** 网格每行放几个。手机上两列太挤、四列又看不清缩略图，三列是个折中。 */
        const val GRID_COLUMNS: Int = 3
    }
}
