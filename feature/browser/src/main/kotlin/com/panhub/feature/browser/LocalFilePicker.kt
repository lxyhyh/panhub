package com.panhub.feature.browser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
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

    /**
     * 把用户选中的整个目录复制到缓存，返回「缓存目录 + 显示名」。
     *
     * 为什么整个复制而不是传的时候再读：content:// 授权随进程结束失效，
     * 而上传是异步排队；目录里还可能有几十个文件，边传边读会让授权
     * 的生命周期和队列长度纠缠在一起。
     *
     * 返回的不是 [File] 而是 [LocalDirectory]：目录本身的父级要能对上，
     * 否则核心层按路径反推不出层级。
     */
    fun copyTreeToCache(context: Context, uri: Uri): LocalDirectory? {
        val name = sanitize(displayName(context, uri))
        val root = uniqueDir(File(context.cacheDir, "upload"), name)
        if (!root.mkdirs()) return null
        val copied = runCatching { copyTree(context, uri, root) }.getOrNull() ?: return null
        // 一个文件都没读到说明授权已经失效，留着空目录只会让用户
        // 看到一条「已整理 0 个文件」的任务，等于什么也没说。
        return if (copied == 0) null else LocalDirectory(root, name)
    }

    /** 递归复制，返回复制成功的文件数。读不出来的子项跳过而不是整体失败。 */
    private fun copyTree(context: Context, uri: Uri, target: File): Int {
        val dir = DocumentFile.fromTreeUri(context, uri) ?: return 0
        var copied = 0
        for (child in dir.listFiles()) {
            val childName = sanitize(child.name ?: continue)
            if (child.isDirectory) {
                val sub = File(target, childName)
                if (!sub.mkdirs() && !sub.isDirectory) continue
                copied += copyTree(context, child.uri, sub)
            } else if (child.isFile) {
                val output = uniqueFile(target, childName)
                val ok = runCatching {
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        output.outputStream().use { out -> input.copyTo(out) }
                    } ?: return@runCatching false
                    true
                }.getOrDefault(false)
                if (ok && output.length() > 0L) {
                    copied++
                } else {
                    output.delete()
                }
            }
        }
        return copied
    }

    /** 目录也要避重名：缓存目录会跨多次选择保留。 */
    private fun uniqueDir(parent: File, name: String): File {
        var candidate = File(parent, name)
        var index = 1
        while (candidate.exists()) {
            candidate = File(parent, name + "(" + index + ")")
            index++
        }
        return candidate
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

/**
 * 已经落到缓存里的一个本地目录。
 *
 * [path] 用绝对路径而不是 [File]：跨模块边界（到 core:transfer）时
 * 只传字符串，核心层不需要认识 Android 的任何类型。
 */
data class LocalDirectory(val dir: File, val name: String) {
    val path: String get() = dir.absolutePath
}
