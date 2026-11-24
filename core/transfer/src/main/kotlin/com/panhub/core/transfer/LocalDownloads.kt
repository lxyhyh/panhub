package com.panhub.core.transfer

import java.io.File

/**
 * 应用私有下载目录里的一个文件。
 *
 * [relativePath] 相对下载根目录，既用来定位文件，也用来判断层级：
 * 整目录下载的子文件会落在子目录里，界面上要能看出来。
 */
data class DownloadedEntry(
    val relativePath: String,
    val name: String,
    val size: Long,
    val modifiedAt: Long,
) {
    /** 文件所在的子目录（相对根目录），根目录下的文件为空串。 */
    val folder: String
        get() {
            val cut = relativePath.lastIndexOf('/')
            return if (cut <= 0) "" else relativePath.substring(0, cut)
        }
}

/** 一次盘点结果。总字节数在这里算好，界面不必再遍历一遍。 */
data class DownloadsSnapshot(
    val entries: List<DownloadedEntry> = emptyList(),
    val totalBytes: Long = 0L,
) {
    val isEmpty: Boolean get() = entries.isEmpty()
}

/**
 * 下载目录的盘点与清理。
 *
 * 为什么需要它：下载落在应用私有目录里，传输列表只是记录。
 * 用户把已完成的任务清空之后，文件仍然占着空间，而界面上再无入口能看见它们。
 * 「这份文件还在不在、有多大、能不能删」需要一个不依赖队列的答案。
 *
 * 目录规则只在这里定义：路径规则一变，只有这一处要改。
 */
class LocalDownloads(private val root: File) {

    /**
     * 扫一遍整棵目录。
     *
     * 按修改时间从新到旧排：用户来找的多半是刚下完的那个。
     * 目录本身不进结果——点一个文件夹没有意义，里面的文件才是内容。
     */
    fun scan(): DownloadsSnapshot {
        if (!root.isDirectory) return DownloadsSnapshot()
        val base = canonicalOf(root) ?: return DownloadsSnapshot()
        val entries = mutableListOf<DownloadedEntry>()
        walk(root, 0) { file ->
            val path = runCatching { file.canonicalFile }.getOrNull() ?: return@walk
            val rel = path.path.removePrefix(base.path + File.separator)
            if (rel.isEmpty() || rel == path.path) return@walk
            val size = file.length().coerceAtLeast(0L)
            entries += DownloadedEntry(
                relativePath = rel,
                name = file.name,
                size = size,
                modifiedAt = file.lastModified(),
            )
        }
        return DownloadsSnapshot(
            entries = entries.sortedWith(
                compareByDescending<DownloadedEntry> { it.modifiedAt }.thenBy { it.name },
            ),
            totalBytes = entries.sumOf { it.size },
        )
    }

    /**
     * 删掉一个文件，返回它是否已经不在了。
     *
     * 只接受下载目录里的相对路径：绝对路径、带 .. 的路径一律当调用方搞错了处理。
     * 这不是洁癖——这个方法最终会被界面按钮直接调用，任何一处路径拼接出错
     * 都会变成「删掉了别的文件」。
     */
    fun delete(relativePath: String): Boolean {
        val file = resolve(relativePath) ?: return false
        if (file.isDirectory) return false
        if (!file.exists()) return true
        return file.delete()
    }

    /** 路径是否落在下载目录里；文件不存在也算合法（可能已经被清理掉了）。 */
    fun contains(relativePath: String): Boolean = resolve(relativePath) != null

    /** 把相对路径解析成下载目录里的文件；越界或非法路径返回 null。 */
    private fun resolve(relativePath: String): File? {
        if (relativePath.isBlank()) return null
        // 绝对路径必须当场拒绝：File(parent, "/a/b") 在 Unix 上会把开头的斜杠
        // 当成普通分隔符拼在 parent 后面，得到 "<root>/a/b"——它看起来在根目录内，
        // 于是 startsWith 校验通过了，而调用方原本指的是另一个地方的 /a/b。
        if (File(relativePath).isAbsolute) return null
        val base = canonicalOf(root) ?: return null
        val target = runCatching { File(base, relativePath).canonicalFile }.getOrNull() ?: return null
        val prefix = base.path + File.separator
        return if (target.path.startsWith(prefix)) target else null
    }

    private fun canonicalOf(file: File): File? = runCatching { file.canonicalFile }.getOrNull()

    /**
     * 递归遍历。
     *
     * 刻意不用 File.walkTopDown：它对符号链接与权限不足的目录会把异常抛给调用方，
     * 而盘点文件这件事「读不到就跳过」比「整页对你报错」合理得多。
     * [depth] 是防符号链接成环的最后一道保险。
     */
    private fun walk(dir: File, depth: Int, onFile: (File) -> Unit) {
        if (depth > MAX_DEPTH) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            when {
                child.isDirectory -> walk(child, depth + 1, onFile)
                child.isFile -> onFile(child)
            }
        }
    }

    companion object {
        /** 下载根目录在应用私有 filesDir 下的名字，与容器装配处必须一致。 */
        const val ROOT_DIR_NAME = "downloads"

        /** 目录层数上限，防止符号链接把扫描带进循环。 */
        const val MAX_DEPTH = 32
    }
}

