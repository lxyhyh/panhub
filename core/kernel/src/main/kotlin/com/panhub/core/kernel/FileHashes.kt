package com.panhub.core.kernel

/**
 * 一个文件（或一段字节）的全套摘要。
 *
 * 为什么同时算四种：不同网盘要的东西不一样——
 *  - 百度网盘要 MD5（整文件 + 每 4 MiB 分片）；
 *  - 123 云盘要 MD5；
 *  - 阿里云盘要 SHA1；
 *  - 坚果云 WebDAV 不需要，但下载校验时 CRC32 最便宜。
 * 一次读盘全算完，避免同一个文件读四遍。
 */
data class FileHashes(
    val md5: String,
    val sha1: String,
    val sha256: String,
    val crc32: Long,
    val size: Long,
) {
    companion object {
        @Suppress("LongParameterList")
        fun empty(): FileHashes = FileHashes(md5 = "", sha1 = "", sha256 = "", crc32 = 0L, size = 0L)
    }
}
