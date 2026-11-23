package com.panhub.core.preview

import java.nio.charset.StandardCharsets

/**
 * 把一段字节当文本读。
 *
 * 两个坑必须在这里处理掉，否则用户看到的是乱码或者一屏方框：
 * 1. UTF-8 BOM 会被当成正文，第一个字符变成不可见的 U+FEFF；
 * 2. 二进制文件（服务商把 MIME 标成 text/plain 的情况不少见）解码出来是一堆
 *    控制字符，Compose 排版会直接崩，所以先判一下里面有没有 NUL。
 */
object PreviewText {

    /**
     * 只嗅开头这么多字节。
     *
     * 为了判类型把 12 MB 的图片整个扫一遍不值得：真正的二进制文件在前几 KB
     * 里就一定会出现 NUL。
     */
    const val SNIFF_BYTES: Int = 8_000

    private const val BOM = '\uFEFF'

    /** 前 [SNIFF_BYTES] 字节里出现 NUL 就认为不是文本。 */
    fun looksBinary(bytes: ByteArray): Boolean {
        val end = minOf(bytes.size, SNIFF_BYTES)
        for (index in 0 until end) {
            if (bytes[index] == 0.toByte()) return true
        }
        return false
    }

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val text = String(bytes, StandardCharsets.UTF_8)
        return if (text.isNotEmpty() && text[0] == BOM) text.substring(1) else text
    }

    /** 统一换行符。预览是只读的，没必要保留 Windows 的 \r。 */
    fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
}
