package com.panhub.core.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
