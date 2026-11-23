package com.panhub.provider.cloud189

import java.io.InputStream
import java.security.MessageDigest

/**
 * 上传前要报给天翼云盘的两组摘要。
 *
 * [fileMd5] 是整个文件的小写 MD5，[sliceMd5] 是「所有分片 MD5（大写）用换行连起来」
 * 再取一次 MD5（小写）。后者只在文件大于一个分片时才有意义，否则服务端要求
 * 直接等于 fileMd5。
 */
internal data class Cloud189UploadDigests(
    val fileMd5: String,
    val sliceMd5: String,
    val sliceMd5s: List<String>,
)

/**
 * 一次遍历同时算整文件 MD5 与逐分片 MD5。
 *
 * 为什么不能分两次读：上传源可能是内容提供者的 Uri，重开流的代价很高，
 * 而且两次读之间文件内容可能变化，算出来的摘要就对不上了。
 */
internal object Cloud189UploadDigest {

    /** 天翼云盘客户端固定 10 MiB 一片，改小了服务端不接受。 */
    const val SLICE_SIZE = 10L * 1024 * 1024

    private const val BUFFER_SIZE = 256 * 1024

    fun compute(
        input: InputStream,
        size: Long,
        sliceSize: Long = SLICE_SIZE,
        onProgress: ((Long) -> Unit)? = null,
    ): Cloud189UploadDigests {
        val whole = MessageDigest.getInstance("MD5")
        val slice = MessageDigest.getInstance("MD5")
        val slices = ArrayList<String>()
        val buffer = ByteArray(BUFFER_SIZE)
        var filled = 0L
        var read = 0L

        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            whole.update(buffer, 0, n)
            var offset = 0
            // 一次 read 可能跨越分片边界，所以要按剩余空间切开喂给分片摘要器。
            while (offset < n) {
                val room = (sliceSize - filled).toInt()
                val take = minOf(room, n - offset)
                slice.update(buffer, offset, take)
                offset += take
                filled += take
                if (filled == sliceSize) {
                    slices += slice.digest().toLowerHex().uppercase()
                    slice.reset()
                    filled = 0L
                }
            }
            read += n
            onProgress?.invoke(read)
        }

        // 最后不满一片的尾巴要补记；空文件也补一个空摘要，保持「至少一片」。
        if (filled > 0L || slices.isEmpty()) {
            slices += slice.digest().toLowerHex().uppercase()
        }

        val fileMd5 = whole.digest().toLowerHex()
        val sliceMd5 = if (size > sliceSize && slices.isNotEmpty()) {
            MessageDigest.getInstance("MD5")
                .digest(slices.joinToString("\n").toByteArray(Charsets.UTF_8))
                .toLowerHex()
        } else {
            fileMd5
        }
        return Cloud189UploadDigests(fileMd5 = fileMd5, sliceMd5 = sliceMd5, sliceMd5s = slices)
    }
}
