package com.panhub.core.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 缓存目录里占了多少空间。
 *
 * 只统计我们自己造的两处临时目录：
 * - upload：选文件上传前先复制一份到应用私有缓存，传完没人删过；
 * - scratch：跨网盘复制时先落一份到本地再上传，正常结束会自己删掉，
 *   但进程被杀或任务取消时可能留下残片。
 *
 * 不包含 downloads：那是用户主动下载的文件，删不删由「本机文件」页决定，
 * 混进「清理缓存」里会让人以为自己下载的东西没了。
 */
data class CacheUsage(
    val uploadBytes: Long = 0L,
    val scratchBytes: Long = 0L,
) {
    val totalBytes: Long get() = uploadBytes + scratchBytes

    val isEmpty: Boolean get() = totalBytes <= 0L
}

/** 读缓存占用、清缓存。抽成接口是为了设置页不直接碰文件系统。 */
interface CacheCleaner {
    suspend fun usage(): CacheUsage

    /** 清掉临时文件，返回删掉的文件个数。 */
    suspend fun clear(): Int

    companion object {
        /** 没接文件系统时的空实现：界面显示 0，按钮点了也不做事。 */
        val None: CacheCleaner = object : CacheCleaner {
            override suspend fun usage(): CacheUsage = CacheUsage()
            override suspend fun clear(): Int = 0
        }
    }
}

/**
 * 真实实现：两个目录各算一次，清理时只删文件不删目录。
 *
 * 目录本身留着是有意的——下次上传还要往里写，删掉再建纯属多余；
 * 而且 mkdirs 失败时上传会莫名其妙地报错，多一个失败点没有好处。
 */
class LocalCacheCleaner(
    private val uploadDir: File,
    private val scratchDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CacheCleaner {

    override suspend fun usage(): CacheUsage = withContext(io) {
        CacheUsage(
            uploadBytes = CacheCleanup.sizeOf(uploadDir),
            scratchBytes = CacheCleanup.sizeOf(scratchDir),
        )
    }

    override suspend fun clear(): Int = withContext(io) {
        CacheCleanup.clear(uploadDir) + CacheCleanup.clear(scratchDir)
    }
}

/** 与 Android 无关的那部分：算大小、删文件、写文案。 */
object CacheCleanup {

    /** 目录里所有文件的总字节数；目录不存在按 0 算。 */
    fun sizeOf(dir: File): Long {
        var total = 0L
        walk(dir) { file -> total += file.length() }
        return total
    }

    /** 删掉目录里的所有文件，返回删掉的个数。目录本身保留。 */
    fun clear(dir: File): Int {
        var removed = 0
        walk(dir) { file -> if (file.delete()) removed++ }
        return removed
    }

    /**
     * 深度优先遍历并回调每个文件。
     *
     * 不用 File.walkTopDown：它对符号链接环和权限不足的目录会抛异常，
     * 而清理缓存这种「尽力而为」的动作，遇到读不了的目录应该跳过而不是崩掉。
     */
    private fun walk(dir: File, depth: Int = 0, onFile: (File) -> Unit) {
        if (depth > MAX_DEPTH) return
        val entries = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (entry in entries) {
            if (entry.isDirectory) {
                walk(entry, depth + 1, onFile)
            } else {
                onFile(entry)
            }
        }
    }

    private const val MAX_DEPTH = 16
}