package com.panhub.core.preview

/**
 * 预览的读取上限。
 *
 * 预览是"多看一眼"，不是"打开文件"：为了看一眼把 200 MB 的日志整个拉下来，
 * 既费流量又会让对话框转很久。超出的部分直接不要，并在界面上说明是截断的。
 */
object PreviewLimits {
    /** 图片最多读 12 MB——手机屏幕上的预览再大也没有意义。 */
    const val MAX_IMAGE_BYTES: Long = 12L * 1024 * 1024

    /** 文本最多读 256 KB，大约够看几千行。 */
    const val MAX_TEXT_BYTES: Long = 256L * 1024

    fun forKind(kind: PreviewKind): Long = when (kind) {
        PreviewKind.IMAGE -> MAX_IMAGE_BYTES
        PreviewKind.TEXT -> MAX_TEXT_BYTES
        PreviewKind.NONE -> 0L
    }
}
