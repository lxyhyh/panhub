package com.panhub.feature.browser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * 把用户选中的文档复制到应用缓存，返回可上传的本地文件。
 *
 * 为什么必须先复制：content:// 是一次性授权、随进程结束失效，
 * 而上传是异步排队、可能在用户离开页面后才开始。核心层也不该依赖
 * ContentResolver（那是 Android 平台能力，不是网盘协议的一部分）。
 *
 * 复制到 cacheDir/upload：系统在空间紧张时会自动清理，正好符合
 * 「传完就不再需要」的语义。
 */
internal object LocalFilePicker {

    /** 读不到名字时用时间戳兜底，绝不返回空串（空文件名会被服务端拒绝）。 */
    fun copyToCache(context: Context, uri: Uri): File? {
        val name = displayName(context, uri)
        val dir = File(context.cacheDir, "upload").apply { mkdirs() }
        val target = File(dir, sanitize(name))
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            target.takeIf { it.length() > 0L }
        }.getOrNull()
    }

    fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "upload-${System.currentTimeMillis()}"
    }

    /** 只替换文件系统真正不接受的字符，保留中文与空格。 */
    fun sanitize(name: String): String {
        val cleaned = name.map { if (it in ILLEGAL || it < ' ') '_' else it }.joinToString("")
        val trimmed = cleaned.trim()
        return when {
            trimmed.isEmpty() -> "upload-${System.currentTimeMillis()}"
            trimmed.length > 120 -> trimmed.take(120)
            else -> trimmed
        }
    }

    private val ILLEGAL = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
}
