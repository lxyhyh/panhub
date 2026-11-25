package com.panhub.core.model

/**
 * 文件按用途分的大类。
 *
 * 只分用户真的会拿来筛的几类：找照片、找视频、找文档。图片和视频分开，因为
 * 「翻这次旅行的照片」和「找那个录屏」在目录里是两件不同的事。
 *
 * [FOLDER] 不是文件类型。列在这里是为了让 [FileKinds.of] 对任何节点都有答案，
 * 调用方不用先判 [RemoteNode.isDir] 再决定要不要分类。
 */
enum class FileKind(val label: String) {
    IMAGE("图片"),
    VIDEO("视频"),
    AUDIO("音频"),
    DOCUMENT("文档"),
    ARCHIVE("压缩包"),
    OTHER("其它"),
    FOLDER("文件夹"),
}

/**
 * 判断一个节点属于哪一类，并按类筛一批节点。
 *
 * 与 [NodeFilter] 是同一层的两件事：那个按名字收窄，这个按类型收窄，都不联网、
 * 都只作用于已经加载出来的这一批。分开放是因为名字是连续输入框，类型是几个
 * 固定的选项，混在一个对象里会让两边都多出一堆用不上的参数。
 */
object FileKinds {

    private val IMAGE_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif",
    )

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp",
        "rmvb", "rm", "mpg", "mpeg",
    )

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "opus", "amr", "ape",
    )

    /**
     * .ts 刻意归到文档而不是视频。
     *
     * 它既是 MPEG 传输流也是 TypeScript 源码，而预览那边已经把它当文本读。
     * 两个地方对同一个扩展名给出不同答案，用户点开发现是源码、筛「视频」却也能
     * 看到它，只会觉得这个 App 在乱猜。统一按文本算，输的那一边是视频片段。
     */
    private val DOCUMENT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "pdf", "doc", "docx", "xls", "xlsx",
        "ppt", "pptx", "csv", "tsv", "json", "xml", "yml", "yaml",
        "html", "htm", "rtf", "epub", "log", "ini", "conf", "toml", "ts",
    )

    private val ARCHIVE_EXTENSIONS = setOf(
        "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso", "apk", "jar",
    )

    /** 界面上给出的筛选项。不含 [FileKind.OTHER] 与 [FileKind.FOLDER]，见 [matches]。 */
    val filterable: List<FileKind> = listOf(
        FileKind.IMAGE,
        FileKind.VIDEO,
        FileKind.AUDIO,
        FileKind.DOCUMENT,
        FileKind.ARCHIVE,
    )

    /** 节点属于哪一类。文件夹一律是 [FileKind.FOLDER]。 */
    fun of(node: RemoteNode): FileKind {
        if (node.isDir) return FileKind.FOLDER
        return ofName(node.name, node.mimeType)
    }

    /**
     * 只按文件名（可选再带 MIME）判断文件属于哪一类。
     *
     * 顺序与预览的类型判断保持一致：先信 MIME 的顶层类型，认不出再按扩展名。
     * 列表路径大多不带 MIME，这时全靠扩展名；上传路径带了 MIME 就以它为准。
     */
    fun ofName(name: String, mimeType: String? = null): FileKind {
        val mime = mimeType?.lowercase()?.substringBefore(';')?.trim()
        if (!mime.isNullOrEmpty()) {
            when {
                mime.startsWith("image/") -> return FileKind.IMAGE
                mime.startsWith("video/") -> return FileKind.VIDEO
                mime.startsWith("audio/") -> return FileKind.AUDIO
                mime.startsWith("text/") -> return FileKind.DOCUMENT
            }
        }
        return ofExtension(name)
    }

    private fun ofExtension(name: String): FileKind {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return FileKind.OTHER
        return when (name.substring(dot + 1).lowercase()) {
            in IMAGE_EXTENSIONS -> FileKind.IMAGE
            in VIDEO_EXTENSIONS -> FileKind.VIDEO
            in AUDIO_EXTENSIONS -> FileKind.AUDIO
            in DOCUMENT_EXTENSIONS -> FileKind.DOCUMENT
            in ARCHIVE_EXTENSIONS -> FileKind.ARCHIVE
            else -> FileKind.OTHER
        }
    }

    /**
     * 这一项在按 [kind] 筛的时候要不要留下。
     *
     * 文件夹永远保留：目录项没有类型，它本身不是文件，硬给它归一类只是猜。
     * 更重要的是「进子目录」是浏览里最常见的动作，把文件夹一起藏掉，用户筛完
     * 还得先清筛选才进得去，多出来的一步每天都在交学费。
     */
    fun matches(node: RemoteNode, kind: FileKind?): Boolean {
        if (kind == null) return true
        if (node.isDir) return true
        return of(node) == kind
    }

    /** 过滤一批节点。只收窄，不重排：顺序是用户在排序里选的。 */
    fun apply(nodes: List<RemoteNode>, kind: FileKind?): List<RemoteNode> {
        if (kind == null) return nodes
        return nodes.filter { matches(it, kind) }
    }

    /** 有没有在按类型筛。 */
    fun active(kind: FileKind?): Boolean = kind != null

    /**
     * 名字和类型一起筛完之后一条都不剩时的说明。
     *
     * 得说清是被哪个条件筛掉的：只按类型筛却提示「换个关键字」，用户会去改那个
     * 根本没填的输入框。
     */
    fun emptyMessage(query: String, kind: FileKind?): String {
        val keyword = query.trim()
        return when {
            keyword.isNotEmpty() && kind != null ->
                "没有名字包含「" + keyword + "」的" + kind.label
            keyword.isNotEmpty() -> NodeFilter.emptyMessage(query)
            kind != null -> "这里没有" + kind.label
            else -> NodeFilter.emptyMessage(query)
        }
    }

    /** 输入框下面那行小字。两个条件任一生效、且确实筛掉了东西才报数。 */
    fun summary(shown: Int, total: Int, query: String, kind: FileKind?): String? {
        if (!NodeFilter.active(query) && !active(kind)) return null
        if (shown >= total) return null
        return "筛选中 " + shown + "/" + total + " 项"
    }
}
