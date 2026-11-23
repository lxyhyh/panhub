package com.panhub.provider.baidu

import java.io.InputStream
import java.security.MessageDigest

/**
 * 一次上传要报给百度的三个摘要。
 *
 * [contentMd5] 是整个文件的 MD5，[sliceMd5] 是**前 256 KiB** 的 MD5（不是全部
 * 分片 MD5 的再哈希，这一点和天翼云盘完全不同），[blockList] 是每个分片的 MD5
 * 十六进制，按顺序排列。
 */
internal data class BaiduUploadDigests(
    val contentMd5: String,
    val sliceMd5: String,
    val blockList: List<String>,
)

/**
 * 按百度的分片规则算摘要。
 *
 * 规则来自 OpenList drivers/baidu_netdisk/driver.go 的 Put：分片大小由会员等级
 * 决定（非会员固定 4 MiB），分片数 = ceil(size / sliceSize)，最后一片按剩余
 * 字节算；block_list 里存的是**每片**的 MD5 十六进制，slice-md5 只取前 256 KiB。
 *
 * 三个摘要一趟扫完：文件可能有几个 GB，扫三遍是浪费。
 */
internal object BaiduUploadDigest {

    /** 非会员的固定分片大小。 */
    const val SLICE_SIZE = 4L * 1024 * 1024

    /** slice-md5 只看开头这么多字节。 */
    const val SLICE_MD5_LIMIT = 256L * 1024

    private const val BUFFER_SIZE = 64 * 1024

    fun sliceCount(size: Long, sliceSize: Long = SLICE_SIZE): Int {
        if (size <= 0L) return 0
        return if (size <= sliceSize) 1 else ((size + sliceSize - 1) / sliceSize).toInt()
    }

    fun compute(
        input: InputStream,
        size: Long,
        sliceSize: Long = SLICE_SIZE,
    ): BaiduUploadDigests {
        require(size > 0L) { "size must be positive" }
        require(sliceSize > 0L) { "sliceSize must be positive" }

        val count = sliceCount(size, sliceSize)
        val lastBlockSize = if (size % sliceSize == 0L) sliceSize else size % sliceSize

        val whole = MessageDigest.getInstance("MD5")
        val prefix = MessageDigest.getInstance("MD5")
        var prefixRemaining = SLICE_MD5_LIMIT
        val blockList = ArrayList<String>(count)
        val buffer = ByteArray(BUFFER_SIZE)

        for (index in 1..count) {
            val blockSize = if (index == count) lastBlockSize else sliceSize
            val slice = MessageDigest.getInstance("MD5")
            var remaining = blockSize
            while (remaining > 0L) {
                val want = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, want)
                if (read <= 0) break
                whole.update(buffer, 0, read)
                slice.update(buffer, 0, read)
                if (prefixRemaining > 0L) {
                    val take = minOf(prefixRemaining, read.toLong()).toInt()
                    prefix.update(buffer, 0, take)
                    prefixRemaining -= take
                }
                remaining -= read
            }
            blockList += slice.digest().toLowerHex()
        }

        return BaiduUploadDigests(
            contentMd5 = whole.digest().toLowerHex(),
            sliceMd5 = prefix.digest().toLowerHex(),
            blockList = blockList,
        )
    }
}

internal fun ByteArray.toLowerHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(HEX_DIGITS[value shr 4]).append(HEX_DIGITS[value and 0x0F])
    }
    return out.toString()
}

private const val HEX_DIGITS = "0123456789abcdef"
