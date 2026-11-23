package com.panhub.core.preview

/**
 * 一次预览的结果。
 *
 * 图片留原始字节而不是 Bitmap：core 模块是纯 JVM，不认识 android.graphics，
 * 解码放在 UI 层做，这样这份逻辑能在普通单测里跑。
 */
class PreviewContent(
    val kind: PreviewKind,
    /** 图片原始字节，[kind] 不是 IMAGE 时为 null。 */
    val bytes: ByteArray? = null,
    /** 文本内容，[kind] 不是 TEXT 时为 null。 */
    val text: String? = null,
    /** 服务端文件比这次读到的更大，内容是截断的。 */
    val truncated: Boolean = false,
)
