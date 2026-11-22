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
        val target = uniqueFile(dir, sanitize(name))
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            target.takeIf { it.length() > 0L }
        }.getOrNull()
    }

    /**
     * 批量版本的 [copyToCache]：返回成功复制的「文件 + 显示名」对。
     *
     * 逐个复制而不是失败即放弃：用户一次选十个文件，其中某一个读不出来时，
     * 把其余九个也丢掉是没有道理的。
     */
    fun copyAllToCache(context: Context, uris: List<Uri>): List<Pair<File, String>> {
        val dir = File(context.cacheDir, "upload").apply { mkdirs() }
        return uris.mapNotNull { uri ->
            val name = displayName(context, uri)
            val target = uniqueFile(dir, sanitize(name))
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: return@mapNotNull null
                target.takeIf { it.length() > 0L }
            }.getOrNull() ?: return@mapNotNull null
            copied to name
        }
    }

    /**
     * 同名文件不覆盖，改成加序号。
     *
     * 缓存目录会跨多次上传保留，重名很常见；直接覆盖会让「先入队、后开始」
     * 的那个任务读到别人的内容，而且传出去的文件名还是错的。
     */
    private fun uniqueFile(dir: File, name: String): File {
        val direct = File(dir, name)
        if (!direct.exists()) return direct
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var index = 1
        while (true) {
            val candidate = if (ext.isEmpty()) {
                File(dir, base + "(" + index + ")")
            } else {
                File(dir, base + "(" + index + ")." + ext)
            }
            if (!candidate.exists()) return candidate
            index++
        }
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
