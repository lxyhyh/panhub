package com.panhub.core.platform

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream

/**
 * 把应用私有目录里的文件交给别的应用。
 *
 * 文件在私有目录里，直接 file:// 从 Android 7 起会被 FileUriExposedException 拒绝，
 * 必须经 FileProvider 换成 content:// 并临时授权。这套「取 uri、给权限、选 mime」
 * 的写法在没有抽出来之前，传输页与本地文件页各写了一遍——两处任何一处漏了
 * FLAG_GRANT_READ_URI_PERMISSION，对方应用就会读到空文件，而错误现场很难看出来。
 */
object LocalFileActions {

    private const val BUFFER_SIZE = 64 * 1024

    /** 取不出文件名时用的兜底，避免系统对话框里出现一个空标题。 */
    private const val FALLBACK_NAME = "下载文件"

    /**
     * 用系统里能打开这类文件的应用打开它。
     *
     * 返回 false 表示手头没有能处理的应用（或 FileProvider 配错了）。
     * 调用方拿这个返回值提示用户，而不是让异常一路冒到顶层。
     */
    fun open(context: Context, file: File): Boolean = runCatching {
        val uri = uriOf(context, file)
        val mime = context.contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** 把文件分享给别的应用（微信、邮件等）。 */
    fun share(context: Context, file: File): Boolean = runCatching {
        val uri = uriOf(context, file)
        val mime = context.contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(intent, "分享文件")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

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
     * 系统对话框会把它预填成文件名，用户多半直接确认，所以要先保留扩展名
     * ——扩展名丢了，保存下来的东西在文件管理器里就打不开了。
     */
    fun suggestName(path: String?): String {
        val trimmed = path?.trim().orEmpty()
        if (trimmed.isEmpty()) return FALLBACK_NAME
        val last = trimmed.trimEnd('/', '\\')
            .substringAfterLast('/')
            .substringAfterLast('\\')
        return last.ifBlank { FALLBACK_NAME }
    }

    /**
     * FileProvider 的 authority 由 applicationId 加后缀得到。
     *
     * 写死包名会让 debug 变体（带 .debug 后缀）取到别人的 provider，
     * 所以这里必须问运行时包名。
     */
    private fun uriOf(context: Context, file: File): android.net.Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", file)
}

