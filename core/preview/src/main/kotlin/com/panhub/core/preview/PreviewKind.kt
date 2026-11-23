package com.panhub.core.preview

import com.panhub.core.model.RemoteNode

/**
 * 能就地看一眼的文件类型。
 *
 * 只有两种：图片和文本。它们是**在对话框里就能画出来**的类型，不需要额外
 * 解码器，也不需要跳出 App。PDF 和音视频也能"预览"，但那要交给系统阅读器或
 * 播放器，本质是"打开"——那是下载功能的活，放进预览只会多一个含糊的入口。
 */
enum class PreviewKind {
    IMAGE,
    TEXT,
    NONE,
}

/**
 * 判断一个节点能不能预览、按哪种方式预览。
 *
 * 顺序是先信 MIME 再信扩展名：上传路径会把 MIME 带上来，列表路径大多没有，
 * 两家都没有时按扩展名兜底。之所以不反过来，是因为服务商的 MIME 偶尔是错的
 * （天翼对 .md 常回 application/octet-stream），但扩展名几乎总是对的。
 */
object PreviewKinds {

    private val IMAGE_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif",
    )

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "json", "xml", "log", "csv", "tsv",
        "yml", "yaml", "ini", "conf", "properties", "toml",
        "kt", "kts", "java", "py", "js", "mjs", "ts", "jsx", "tsx",
        "c", "h", "cpp", "hpp", "rs", "go", "rb", "php", "sh", "bash",
        "html", "htm", "css", "scss", "sql", "gradle",
    )

    fun of(node: RemoteNode): PreviewKind {
        if (node.isDir) return PreviewKind.NONE
        val mime = node.mimeType?.lowercase()?.substringBefore(';')?.trim()
        if (!mime.isNullOrEmpty()) {
            when {
                mime.startsWith("image/") -> return PreviewKind.IMAGE
                mime.startsWith("text/") -> return PreviewKind.TEXT
                // 服务商把文本当成二进制流回是很常见的事，这里不直接判 NONE，
                // 落到下面按扩展名再判一次。
                mime == "application/octet-stream" -> Unit
                else -> return PreviewKind.NONE
            }
        }
        return ofExtension(node.name)
    }

    /** 只按文件名判断，给拿不到完整节点的地方用。 */
    fun ofExtension(name: String): PreviewKind {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return PreviewKind.NONE
        return when (name.substring(dot + 1).lowercase()) {
            in IMAGE_EXTENSIONS -> PreviewKind.IMAGE
            in TEXT_EXTENSIONS -> PreviewKind.TEXT
            else -> PreviewKind.NONE
        }
    }
}
