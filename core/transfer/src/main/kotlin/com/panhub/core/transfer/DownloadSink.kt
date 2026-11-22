package com.panhub.core.transfer

import java.io.File

/**
 * 决定下载文件落在哪里。
 *
 * 抽成接口的理由：Android 上"存到哪"和平台强相关（应用私有目录 / MediaStore /
 * SAF 授权的目录），而队列本身是纯 JVM 逻辑，不该认识这些。
 */
interface DownloadSink {
    /** 为任务准备一个可写的目标文件，负责创建父目录。 */
    fun destinationFor(task: TransferTask): File
}

/**
 * 落在某个目录下，重名时自动加 (1)、(2) 后缀。
 *
 * 为什么不覆盖：用户重复下载同一个文件时，覆盖会让上一次的结果凭空消失，
 * 这在文件管理器里是不可接受的。
 */
class DirectorySink(private val root: File) : DownloadSink {

    override fun destinationFor(task: TransferTask): File {
        // 整目录下载的子任务要还原目录层级，否则几十个同名文件全挤在根目录里。
        val parent = task.relativePath?.let { dirFor(it) } ?: root
        if (!parent.exists()) parent.mkdirs()
        val safeName = sanitize(task.name)
        var candidate = File(parent, safeName)
        if (!candidate.exists()) return candidate

        val dot = safeName.lastIndexOf('.')
        val base = if (dot > 0) safeName.substring(0, dot) else safeName
        val ext = if (dot > 0) safeName.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(parent, "$base ($index)$ext")
            index++
        }
        return candidate
    }

    /**
     * 相对路径 → 实际目录。
     *
     * 每一段单独清洗：只对整串调用 sanitize 会把斜杠也换成下划线，
     * 层级就没了。空段（连续斜杠、结尾斜杠）直接丢掉。
     */
    private fun dirFor(relative: String): File {
        var dir = root
        for (segment in relative.split('/')) {
            if (segment.isEmpty()) continue
            dir = File(dir, sanitize(segment))
        }
        return dir
    }

    companion object {
        /** 文件系统不接受的字符统一换成下划线，同时去掉首尾空白与控制字符。 */
        fun sanitize(name: String): String {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return "未命名"
            val cleaned = buildString(trimmed.length) {
                for (ch in trimmed) {
                    when {
                        ch == '/' || ch == '\\' || ch == ':' || ch == '*' ||
                            ch == '?' || ch == '"' || ch == '<' || ch == '>' ||
                            ch == '|' || ch.code < 0x20 -> append('_')
                        else -> append(ch)
                    }
                }
            }.trim()
            val bounded = if (cleaned.length > 120) cleaned.take(120) else cleaned
            return bounded.ifEmpty { "未命名" }
        }
    }
}
