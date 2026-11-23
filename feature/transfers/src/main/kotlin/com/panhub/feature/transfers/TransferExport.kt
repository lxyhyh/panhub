package com.panhub.feature.transfers

import java.io.File
import java.io.OutputStream

/**
 * 把下载好的文件交给用户自己挑的位置。
 *
 * 下载落在应用私有目录里，卸载 App 就一起没了；「分享」能把文件递给别的应用，
 * 但那是借别人的手转存，用户想要的是「这份文件从此归我管」。
 * 系统提供的「保存到…」正是这件事，它给回来的是一个真正的输出流，
 * 所以这里的复制逻辑不关心目标是本地目录还是某个文档提供者。
 */
object TransferExport {

    private const val BUFFER_SIZE = 64 * 1024

    /** 取不出文件名时用的兜底，避免系统对话框里出现一个空标题。 */
    private const val FALLBACK_NAME = "下载文件"

    /**
     * 把 [source] 复制进 [out]，返回写入的字节数。
     *
     * 源文件不存在返回 null 而不是抛异常：它是调用方已经知道的事实
     * （任务刚完成时文件还在，用户手动清理过就没了），用返回值表达更省事。
     *
     * 流的关闭交给调用方：谁打开谁关闭，这里只负责搬字节。
     */
    fun copyTo(source: File, out: OutputStream): Long? {
        if (!source.isFile) return null
        var written = 0L
        source.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                out.write(buffer, 0, read)
                written += read
            }
        }
        out.flush()
        return written
    }

    /**
     * 建议文件名：路径的最后一段。
     *
     * 系统对话框会把它预填成文件名，用户多半直接确认，
     * 所以这里要尽量保留原来的名字与扩展名——扩展名丢了，保存下来的东西
     * 在文件管理器里就打不开了。
     */
    fun suggestName(path: String?): String {
        val trimmed = path?.trim().orEmpty()
        if (trimmed.isEmpty()) return FALLBACK_NAME
        val last = trimmed.trimEnd('/', '\\')
            .substringAfterLast('/')
            .substringAfterLast('\\')
        return last.ifBlank { FALLBACK_NAME }
    }
}
