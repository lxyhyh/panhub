package com.panhub.core.common

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * 一遍扫描得到的上传摘要。
 *
 * 为什么要把四个值捆在一起返回：不同网盘要的东西不一样，但它们都能从
 * **同一次读盘**里拿到——
 *  - 123 云盘只要整文件 MD5；
 *  - 夸克要 MD5 + SHA-1；
 *  - 百度要整文件 MD5 + 前 256 KiB 的 MD5 + 每个 4 MiB 分片的 MD5；
 *  - 天翼要整文件 MD5 + 每个 10 MiB 分片的 MD5。
 *
 * 分开扫就是把这些字节读四遍。一个 2 GB 的视频在手机上多读三遍要多花
 * 十几秒，而且中途文件被改动还会算出互相矛盾的摘要。
 *
 * [sliceMd5s] 的顺序就是分片顺序，最后一片不满时按实际字节算；空文件
 * 返回空列表（上传本来也不接受空文件）。
 */
data class UploadScan(
    val md5: String,
    val sha1: String,
    val size: Long,
    val prefixMd5: String,
    val sliceMd5s: List<String>,
) {
    companion object {
        /** 占位值。只在扫描失败又不允许抛异常的地方用。 */
        fun empty(): UploadScan = UploadScan(
            md5 = "",
            sha1 = "",
            size = 0L,
            prefixMd5 = "",
            sliceMd5s = emptyList(),
        )
    }
}

/**
 * 上传摘要扫描器。
 *
 * 为什么是一个接口而不是直接调内核：Rust 内核在单元测试、没有 NDK 产物的
 * 构建里加载不起来（.so 只在 CI 的 cargo-ndk 步骤生成），这时必须能退回
 * 纯 Kotlin 实现。provider 层只认这个接口，不关心背后是谁。
 *
 * 只接受 [File]：内核走的是路径 + 流式读，拿不到路径的来源（内容提供者、
 * 内存字节）没法享受加速，调用方自己退回原有实现即可。
 */
interface UploadScanner {

    /**
     * 扫描 [file]。
     *
     * [sliceSize] 传 0 表示不算分片，[prefixLimit] 传 0 表示不算前缀摘要。
     * 实现不支持时返回 null，调用方必须退回自己的实现。
     */
    fun scan(file: File, sliceSize: Long, prefixLimit: Long): UploadScan?

    companion object {
        /** 什么都不做的默认值：让不关心加速的调用方少写一个 null 判断。 */
        val None: UploadScanner = object : UploadScanner {
            override fun scan(file: File, sliceSize: Long, prefixLimit: Long): UploadScan? = null
        }
    }
}

/**
 * 纯 Kotlin 兜底实现。
 *
 * 与 Rust 内核的 scan_file 逐字节等价（同样的缓冲、同样的分片切法），
 * 差别只是跑在 ART 上、慢一些。有它才能保证「没有 .so 的构建」也正确。
 */
object KotlinUploadScanner : UploadScanner {

    private const val BUFFER_SIZE = 256 * 1024

    override fun scan(file: File, sliceSize: Long, prefixLimit: Long): UploadScan =
        file.inputStream().use { scan(it, sliceSize, prefixLimit) }

    /** 流版本，给不方便提供 [File] 的调用方用。 */
    fun scan(input: InputStream, sliceSize: Long, prefixLimit: Long): UploadScan {
        require(sliceSize >= 0L) { "sliceSize must not be negative" }
        require(prefixLimit >= 0L) { "prefixLimit must not be negative" }

        val wholeMd5 = MessageDigest.getInstance("MD5")
        val wholeSha1 = MessageDigest.getInstance("SHA-1")
        val prefix = MessageDigest.getInstance("MD5")
        val slice = MessageDigest.getInstance("MD5")

        var prefixRemaining = prefixLimit
        var sliceFilled = 0L
        val sliceMd5s = ArrayList<String>()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L

        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            wholeMd5.update(buffer, 0, read)
            wholeSha1.update(buffer, 0, read)

            if (prefixRemaining > 0L) {
                val take = minOf(prefixRemaining, read.toLong()).toInt()
                prefix.update(buffer, 0, take)
                prefixRemaining -= take
            }

            if (sliceSize > 0L) {
                var offset = 0
                // 一次 read 可能跨多个分片边界，所以按剩余空间切开喂。
                while (offset < read) {
                    val room = (sliceSize - sliceFilled).toInt()
                    val take = minOf(room, read - offset)
                    slice.update(buffer, offset, take)
                    offset += take
                    sliceFilled += take
                    if (sliceFilled == sliceSize) {
                        sliceMd5s += slice.digest().toLowerHex()
                        slice.reset()
                        sliceFilled = 0L
                    }
                }
            }
            total += read
        }

        // 最后不满一片的尾巴也要记下来，否则分片数与服务端算出来的对不上。
        if (sliceSize > 0L && sliceFilled > 0L) {
            sliceMd5s += slice.digest().toLowerHex()
        }

        return UploadScan(
            md5 = wholeMd5.digest().toLowerHex(),
            sha1 = wholeSha1.digest().toLowerHex(),
            size = total,
            prefixMd5 = if (prefixLimit == 0L) "" else prefix.digest().toLowerHex(),
            sliceMd5s = sliceMd5s,
        )
    }

    /** 十六进制转字节，天翼要把分片 MD5 转成 Base64 用。 */
    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex length must be even" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "not a hex string: " + hex }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

private const val HEX_DIGITS = "0123456789abcdef"

private fun ByteArray.toLowerHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(HEX_DIGITS[value shr 4]).append(HEX_DIGITS[value and 0x0F])
    }
    return out.toString()
}
