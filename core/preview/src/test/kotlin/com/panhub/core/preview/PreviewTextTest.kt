package com.panhub.core.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class PreviewTextTest {

    private fun bytes(text: String) = text.toByteArray(StandardCharsets.UTF_8)

    @Test
    fun utf8IsDecodedAsIs() {
        assertEquals("你好，世界", PreviewText.decode(bytes("你好，世界")))
    }

    @Test
    fun byteOrderMarkIsStripped() {
        // 带 BOM 的 UTF-8 文件不剥掉 BOM，第一个字符会变成一个看不见的方块。
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes("hello")
        assertEquals("hello", PreviewText.decode(withBom))
    }

    @Test
    fun emptyInputDecodesToEmptyString() {
        assertEquals("", PreviewText.decode(ByteArray(0)))
    }

    @Test
    fun windowsLineEndingsAreNormalised() {
        assertEquals("a\nb\nc", PreviewText.normalize("a\r\nb\rc"))
    }

    @Test
    fun nulByteMeansBinary() {
        assertTrue(PreviewText.looksBinary(byteArrayOf(0x41, 0x00, 0x42)))
    }

    @Test
    fun plainTextIsNotBinary() {
        assertFalse(PreviewText.looksBinary(bytes("普通的文本\n第二行")))
    }

    @Test
    fun nulAfterSniffWindowIsNotScanned() {
        // 只嗅前 8000 字节：为了判类型把 12 MB 的图片全扫一遍不值得。
        val data = ByteArray(PreviewText.SNIFF_BYTES + 10) { 0x41 }
        data[data.size - 1] = 0
        assertFalse(PreviewText.looksBinary(data))
    }

    @Test
    fun shortBinaryIsStillDetected() {
        assertTrue(PreviewText.looksBinary(byteArrayOf(0)))
    }

    @Test
    fun gb18030TextIsNotShownAsGarbage() {
        // 中文 .txt 大量是记事本或 Excel 导出的 GBK，按 UTF-8 硬解就是一串方块。
        val gbk = "中文内容".toByteArray(Charset.forName("GB18030"))
        assertEquals("中文内容", PreviewText.decode(gbk))
    }

    @Test
    fun utf8StaysAheadOfTheFallback() {
        // 先试严格 UTF-8：能干净解出来就用它，绝不能让 GB18030 抢先
        // （GB18030 几乎什么字节都能解，抢先就用会把 UTF-8 解成乱码）。
        assertEquals("café 咖啡", PreviewText.decode(bytes("café 咖啡")))
    }

    @Test
    fun utf16LittleEndianWithBomIsDecoded() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "记事本另存为".toByteArray(StandardCharsets.UTF_16LE)
        assertEquals("记事本另存为", PreviewText.decode(le))
    }

    @Test
    fun utf16BigEndianWithBomIsDecoded() {
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            "hello".toByteArray(StandardCharsets.UTF_16BE)
        assertEquals("hello", PreviewText.decode(be))
    }

    @Test
    fun undecodableBytesFallBackInsteadOfThrowing() {
        // 两个孤立的续字节：既不是合法 UTF-8，也不该让整次预览失败。
        val broken = byteArrayOf(0x80.toByte(), 0x81.toByte())
        PreviewText.decode(broken)
    }
}
