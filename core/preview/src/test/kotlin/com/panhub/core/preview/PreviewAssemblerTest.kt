package com.panhub.core.preview

import com.panhub.core.common.PanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class PreviewAssemblerTest {

    private fun bytes(text: String) = text.toByteArray(StandardCharsets.UTF_8)

    private fun success(result: PanResult<PreviewContent>): PreviewContent {
        assertTrue("期望成功，实际是 " + result, result is PanResult.Success)
        return (result as PanResult.Success).value
    }

    @Test
    fun imageKeepsRawBytes() {
        val content = success(PreviewAssembler.assemble(PreviewKind.IMAGE, byteArrayOf(1, 2, 3), false))
        assertEquals(PreviewKind.IMAGE, content.kind)
        assertEquals(3, content.bytes?.size)
        assertNull(content.text)
    }

    @Test
    fun textIsDecodedAndNormalised() {
        val content = success(PreviewAssembler.assemble(PreviewKind.TEXT, bytes("a\r\nb"), false))
        assertEquals("a\nb", content.text)
        assertNull(content.bytes)
    }

    @Test
    fun binaryBytesUnderTextKindFailWithExplanation() {
        val result = PreviewAssembler.assemble(PreviewKind.TEXT, byteArrayOf(0x41, 0x00), false)
        assertTrue(result is PanResult.Failure)
        assertEquals("这个文件看起来不是文本，没法预览", (result as PanResult.Failure).error.message)
    }

    @Test
    fun noneKindFails() {
        val result = PreviewAssembler.assemble(PreviewKind.NONE, ByteArray(0), false)
        assertTrue(result is PanResult.Failure)
    }

    @Test
    fun truncatedFlagIsCarriedThrough() {
        val content = success(PreviewAssembler.assemble(PreviewKind.TEXT, bytes("hello"), true))
        assertTrue(content.truncated)
    }

    @Test
    fun emptyTextIsAllowed() {
        val content = success(PreviewAssembler.assemble(PreviewKind.TEXT, ByteArray(0), false))
        assertEquals("", content.text)
        assertFalse(content.truncated)
    }
}
