package com.panhub.feature.transfers

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransferExportTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun suggestNameKeepsTheLastSegment() {
        assertEquals("报告.pdf", TransferExport.suggestName("/data/user/0/app/files/downloads/报告.pdf"))
    }

    @Test
    fun suggestNameAcceptsWindowsSeparators() {
        // 有些提供者回传的路径是反斜杠拼的，取最后一段的规则不能只认一种。
        assertEquals("a.txt", TransferExport.suggestName("C:\\tmp\\a.txt"))
    }

    @Test
    fun suggestNameIgnoresTrailingSeparator() {
        assertEquals("dir", TransferExport.suggestName("/tmp/dir/"))
    }

    @Test
    fun suggestNameFallsBackWhenPathIsMissing() {
        assertEquals("下载文件", TransferExport.suggestName(null))
        assertEquals("下载文件", TransferExport.suggestName("   "))
        assertEquals("下载文件", TransferExport.suggestName("/"))
    }

    @Test
    fun copyToWritesEveryByte() {
        val source = temp.newFile("hello.txt").apply { writeText("hello 云端") }
        val out = ByteArrayOutputStream()
        val written = TransferExport.copyTo(source, out)
        assertEquals(source.length(), written)
        assertEquals("hello 云端", out.toString("UTF-8"))
    }

    @Test
    fun copyToReportsMissingSourceInsteadOfThrowing() {
        // 任务完成时文件还在，用户手动清理过就没了；
        // 这种情况用返回值说明，不该让界面上崩一次。
        assertNull(TransferExport.copyTo(File(temp.root, "gone.bin"), ByteArrayOutputStream()))
    }

    @Test
    fun copyToHandlesEmptyFile() {
        val source = temp.newFile("empty.txt")
        val out = ByteArrayOutputStream()
        assertEquals(0L, TransferExport.copyTo(source, out))
        assertEquals(0, out.size())
    }

    @Test
    fun copyToLeavesTheStreamOpenForTheCaller() {
        // 谁打开谁关闭：这里不能替调用方关掉目标流，
        // 否则系统返回的文档流会被提前关掉，后续写入报错。
        val source = temp.newFile("a.txt").apply { writeText("abc") }
        val out = ByteArrayOutputStream()
        TransferExport.copyTo(source, out)
        out.write('d'.code)
        assertEquals("abcd", out.toString("UTF-8"))
    }
}
