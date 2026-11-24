package com.panhub.core.preview

import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 把一段字节当文本读。

 * 三个坑必须在这里处理掉，否则用户看到的是乱码或者一屏方框：
 * 1. UTF-8 / UTF-16 的 BOM 会被当成正文，第一个字符变成不可见的 U+FEFF；
 * 2. 二进制文件（服务商把 MIME 标成 text/plain 的情况不少见）解码出来是一堆
 *    控制字符，Compose 排版会直接崩，所以先判一下里面有没有 NUL；
 * 3. 中文的 .txt 大量是 GBK/GB18030 编的（Windows 记事本、Excel 导出、
 *    老系统日志），按 UTF-8 硬解会得到一串问号方块。
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

    /** GB18030 是 GBK 的超集，能一起认下来；拿不到就退回 UTF-8 宽松解码。 */
    private val gb18030: Charset? by lazy {
        runCatching { Charset.forName("GB18030") }.getOrNull()
    }

    /** 前 [SNIFF_BYTES] 字节里出现 NUL 就认为不是文本。 */
    fun looksBinary(bytes: ByteArray): Boolean {
        val end = minOf(bytes.size, SNIFF_BYTES)
        for (index in 0 until end) {
            if (bytes[index] == 0.toByte()) return true
        }
        return false
    }

    /**
     * 解码成字符串。
     *
     * 顺序是「BOM 说了算 → 严格 UTF-8 → GB18030」。严格 UTF-8 用的是遇到坏字节
     * 就报错的解码器：默认的 [String] 构造会把坏字节换成 U+FFFD 而不报错，
     * 那就永远走不到 GB18030 这一步，乱码照样是乱码。
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val decoded = when {
            startsWith(bytes, BOM_UTF8) -> String(bytes, BOM_UTF8.size, bytes.size - BOM_UTF8.size, StandardCharsets.UTF_8)
            startsWith(bytes, BOM_UTF16_LE) -> String(bytes, BOM_UTF16_LE.size, bytes.size - BOM_UTF16_LE.size, StandardCharsets.UTF_16LE)
            startsWith(bytes, BOM_UTF16_BE) -> String(bytes, BOM_UTF16_BE.size, bytes.size - BOM_UTF16_BE.size, StandardCharsets.UTF_16BE)
            else -> decodeWithoutBom(bytes)
        }
        return if (decoded.isNotEmpty() && decoded[0] == BOM) decoded.substring(1) else decoded
    }

    private fun decodeWithoutBom(bytes: ByteArray): String {
        strictUtf8(bytes)?.let { return it }
        val gb = gb18030 ?: return String(bytes, StandardCharsets.UTF_8)
        // GB18030 几乎能解任何字节序列，解不出来时退回宽松 UTF-8：
        // 与其抛异常让整次预览失败，不如让用户看到一串问号。
        return runCatching { String(bytes, gb) }.getOrElse { String(bytes, StandardCharsets.UTF_8) }
    }

    /** 严格 UTF-8 解码；出现坏字节就返回 null，交给别的编码去试。 */
    private fun strictUtf8(bytes: ByteArray): String? = runCatching {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (index in prefix.indices) {
            if (bytes[index] != prefix[index]) return false
        }
        return true
    }

    private val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val BOM_UTF16_LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val BOM_UTF16_BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /** 统一换行符。预览是只读的，没必要保留 Windows 的 \r。 */
    fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
}
