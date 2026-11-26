package com.panhub.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按类型筛文件。
 *
 * 这里守的是分类本身：一个扩展名只能归一类，猜错类别会让「筛图片」里冒出
 * 视频，那比筛不出来更让人不放心。
 */
class FileKindTest {

    private fun file(name: String, mime: String? = null) =
        RemoteNode(id = NodeId(name), parentId = null, name = name, kind = NodeKind.FILE, mimeType = mime)

    private fun dir(name: String) =
        RemoteNode(id = NodeId(name), parentId = null, name = name, kind = NodeKind.DIR)

    @Test
    fun commonExtensionsLandInTheRightBucket() {
        assertEquals(FileKind.IMAGE, FileKinds.of(file("a.jpg")))
        assertEquals(FileKind.VIDEO, FileKinds.of(file("a.mp4")))
        assertEquals(FileKind.AUDIO, FileKinds.of(file("a.flac")))
        assertEquals(FileKind.DOCUMENT, FileKinds.of(file("a.pdf")))
        assertEquals(FileKind.ARCHIVE, FileKinds.of(file("a.zip")))
    }

    @Test
    fun anUnknownExtensionIsNotGuessed() {
        assertEquals(FileKind.OTHER, FileKinds.of(file("a.qwerty")))
    }

    @Test
    fun aNameWithoutAnExtensionIsOther() {
        assertEquals(FileKind.OTHER, FileKinds.of(file("README")))
        assertEquals(FileKind.OTHER, FileKinds.of(file("trailing.")))
        assertEquals(FileKind.OTHER, FileKinds.of(file(".hidden")))
    }

    @Test
    fun theExtensionIsMatchedIgnoringCase() {
        assertEquals(FileKind.IMAGE, FileKinds.of(file("PHOTO.JPG")))
        assertEquals(FileKind.VIDEO, FileKinds.of(file("Movie.MKV")))
    }

    @Test
    fun aFolderIsAlwaysAFolder() {
        assertEquals(FileKind.FOLDER, FileKinds.of(dir("photos")))
        assertEquals(FileKind.FOLDER, FileKinds.of(dir("photos.jpg")))
    }

    @Test
    fun theMimeTypeWinsOverTheExtension() {
        // 服务商把 .md 标成 octet-stream 是常事，这时仍按扩展名走文档。
        assertEquals(FileKind.DOCUMENT, FileKinds.of(file("notes.md", "application/octet-stream")))
        // 反过来，明确给了 image/* 就以它为准，哪怕名字看着像文本。
        assertEquals(FileKind.IMAGE, FileKinds.of(file("scan.txt", "image/png")))
    }

    @Test
    fun aMimeTypeWithCharsetIsTrimmed() {
        assertEquals(FileKind.DOCUMENT, FileKinds.of(file("a.bin", "text/plain; charset=utf-8")))
    }

    @Test
    fun typescriptCountsAsADocumentNotAVideo() {
        // .ts 既是 TypeScript 也是 MPEG 传输流。预览那边把它当文本读，
        // 这里必须跟它保持一致，否则同一个文件在两处得到两种答案。
        assertEquals(FileKind.DOCUMENT, FileKinds.of(file("main.ts")))
    }

    @Test
    fun foldersSurviveEveryFilter() {
        // 筛「图片」时文件夹要留着：不然筛完还得先清筛选才进得去子目录。
        assertTrue(FileKinds.matches(dir("photos"), FileKind.IMAGE))
        assertFalse(FileKinds.matches(file("a.txt"), FileKind.IMAGE))
        assertTrue(FileKinds.matches(file("a.jpg"), FileKind.IMAGE))
    }

    @Test
    fun aCallerCanAskForFoldersToBeDroppedToo() {
        // 搜索结果里留文件夹会让人以为「搜到了图片文件夹」，所以那里关掉保留。
        assertFalse(FileKinds.matches(dir("photos"), FileKind.IMAGE, keepFolders = false))
        val nodes = listOf(dir("folder"), file("a.jpg"))
        assertEquals(listOf("a.jpg"), FileKinds.apply(nodes, FileKind.IMAGE, keepFolders = false).map { it.name })
    }

    @Test
    fun noKindMeansNoFilteringAtAll() {
        val nodes = listOf(file("a.jpg"), file("b.txt"), dir("c"))
        assertTrue(FileKinds.apply(nodes, null) === nodes)
    }

    @Test
    fun filteringOnlyNarrowsAndKeepsTheOrder() {
        val nodes = listOf(dir("folder"), file("b.jpg"), file("a.txt"), file("c.png"))
        assertEquals(listOf("folder", "b.jpg", "c.png"), FileKinds.apply(nodes, FileKind.IMAGE).map { it.name })
    }

    @Test
    fun theSummaryOnlyAppearsWhenSomethingWasHidden() {
        assertNull(FileKinds.summary(3, 3, "", null))
        assertNull(FileKinds.summary(3, 3, "", FileKind.IMAGE))
        assertEquals("筛选中 1/3 项", FileKinds.summary(1, 3, "", FileKind.IMAGE))
        assertEquals("筛选中 1/3 项", FileKinds.summary(1, 3, "a", null))
    }

    @Test
    fun theEmptyMessageNamesWhateverFilteredItOut() {
        assertEquals("这里没有图片", FileKinds.emptyMessage("", FileKind.IMAGE))
        assertEquals("没有名字包含「a」的图片", FileKinds.emptyMessage("a", FileKind.IMAGE))
        assertEquals("没有名字包含「a」的项目", FileKinds.emptyMessage("a", null))
        assertEquals("这里还没有文件", FileKinds.emptyMessage("", null))
    }

    @Test
    fun theSelectedChipIsIndexOfTheFilterableList() {
        assertEquals(0, FileKinds.indexOf(FileKind.IMAGE))
        assertEquals(-1, FileKinds.indexOf(null))
        // 不可筛的类别（其它、文件夹）在第 0 位也不该被选中。
        assertEquals(-1, FileKinds.indexOf(FileKind.OTHER))
    }

    @Test
    fun namesWithoutNodesCanBeFilteredToo() {
        // 本机下载记录不是 RemoteNode，但「筛图片」在两页得给出同一个答案。
        val names = listOf("a.jpg", "b.txt", "c.png")
        assertEquals(listOf("a.jpg", "c.png"), FileKinds.applyBy(names, FileKind.IMAGE) { it })
        assertTrue(FileKinds.applyBy(names, null) { it } === names)
    }

    @Test
    fun everyFilterableKindHasALabel() {
        assertEquals(5, FileKinds.filterable.size)
        assertTrue(FileKinds.filterable.all { it.label.isNotBlank() })
        assertFalse(FileKinds.filterable.contains(FileKind.OTHER))
        assertFalse(FileKinds.filterable.contains(FileKind.FOLDER))
    }
}
