package com.panhub.core.common

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * 文件内容摘要。
 *
 * 少数网盘把 MD5 当作「文件指纹」：上传前先算出来提交给服务端，
 * 服务端命中已有文件就直接秒传，省掉整个传输过程。
 */
object FileDigest {

    private const val BUFFER_SIZE = 256 * 1024

    /** 流式计算 MD5，内存占用固定为一个缓冲块。[onProgress] 报的是已读字节数。 */
    fun md5(file: File, onProgress: ((Long) -> Unit)? = null): String =
        file.inputStream().use { md5(it, onProgress) }

    /** 同上，但由调用方决定流的来源（上传源可能不是本地文件）。 */
    fun md5(input: InputStream, onProgress: ((Long) -> Unit)? = null): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(BUFFER_SIZE)
        var read = 0L
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            digest.update(buffer, 0, n)
            read += n
            onProgress?.invoke(read)
        }
        return digest.digest().toHex()
    }

    /**
     * 一次遍历同时算出 MD5 与 SHA-1。
     *
     * 夸克网盘上传前要同时提交这两个摘要（服务端据此判断能否秒传），
     * 分两遍读同一个文件既慢又要求流可重放，所以这里一次喂给两个摘要器。
     */
    fun md5AndSha1(
        input: InputStream,
        onProgress: ((Long) -> Unit)? = null,
    ): Pair<String, String> {
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(BUFFER_SIZE)
        var read = 0L
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            md5.update(buffer, 0, n)
            sha1.update(buffer, 0, n)
            read += n
            onProgress?.invoke(read)
        }
        return md5.digest().toHex() to sha1.digest().toHex()
    }

    /** 小数据量的 MD5，主要给测试用。 */
    fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).toHex()

    private fun ByteArray.toHex(): String {
        val hex = CharArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            hex[i * 2] = HEX[v ushr 4]
            hex[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(hex)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
