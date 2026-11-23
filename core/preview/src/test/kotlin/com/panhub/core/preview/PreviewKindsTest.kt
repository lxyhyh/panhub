package com.panhub.core.preview

import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.RemoteNode
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewKindsTest {

    private fun node(
        name: String,
        mime: String? = null,
        kind: NodeKind = NodeKind.FILE,
    ) = RemoteNode(id = NodeId("n"), parentId = null, name = name, kind = kind, mimeType = mime)

    @Test
    fun imageMimeWinsEvenWhenNameHasNoExtension() {
        assertEquals(PreviewKind.IMAGE, PreviewKinds.of(node("photo", "image/png")))
    }

    @Test
    fun textMimeWinsEvenWhenNameHasNoExtension() {
        assertEquals(PreviewKind.TEXT, PreviewKinds.of(node("notes", "text/plain")))
    }

    @Test
    fun mimeWithCharsetIsStrippedBeforeMatching() {
        assertEquals(PreviewKind.TEXT, PreviewKinds.of(node("a.txt", "text/plain; charset=utf-8")))
    }

    @Test
    fun octetStreamFallsBackToExtension() {
        // 天翼对 .md 常回 application/octet-stream，只看 MIME 会漏掉这类文件。
        assertEquals(PreviewKind.TEXT, PreviewKinds.of(node("readme.md", "application/octet-stream")))
    }

    @Test
    fun unknownMimeDoesNotFallBackToExtension() {
        // zip 是明确的非预览类型，不该因为名字像文本就去读它。
        assertEquals(PreviewKind.NONE, PreviewKinds.of(node("report.txt", "application/zip")))
    }

    @Test
    fun folderIsNeverPreviewable() {
        assertEquals(
            PreviewKind.NONE,
            PreviewKinds.of(node("photos", "image/png", NodeKind.DIR)),
        )
    }

    @Test
    fun extensionMatchIsCaseInsensitive() {
        assertEquals(PreviewKind.IMAGE, PreviewKinds.ofExtension("HOLIDAY.JPG"))
        assertEquals(PreviewKind.TEXT, PreviewKinds.ofExtension("README.MD"))
    }

    @Test
    fun dotfileWithoutExtensionIsNotText() {
        // ".gitignore" 的最后一个点在第 0 位，按 dot <= 0 排除；
        // 这是刻意的：把一堆隐藏文件当成文本会让列表里冒出一堆可预览项。
        assertEquals(PreviewKind.NONE, PreviewKinds.ofExtension(".gitignore"))
    }

    @Test
    fun trailingDotIsNotAnExtension() {
        assertEquals(PreviewKind.NONE, PreviewKinds.ofExtension("broken."))
    }

    @Test
    fun everyImageExtensionIsRecognised() {
        listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif").forEach {
            assertEquals(it, PreviewKind.IMAGE, PreviewKinds.ofExtension("x." + it))
        }
    }

    @Test
    fun limitsDifferByKind() {
        assertEquals(PreviewLimits.MAX_IMAGE_BYTES, PreviewLimits.forKind(PreviewKind.IMAGE))
        assertEquals(PreviewLimits.MAX_TEXT_BYTES, PreviewLimits.forKind(PreviewKind.TEXT))
        assertEquals(0L, PreviewLimits.forKind(PreviewKind.NONE))
    }
}
