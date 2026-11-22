package com.panhub.core.transfer

import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DirectorySinkTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun task(name: String) = TransferTask(
        id = "t1",
        accountId = AccountId("a"),
        nodeId = NodeId("n"),
        name = name,
    )

    @Test
    fun firstDownloadUsesPlainName() {
        val root = temp.newFolder("downloads")
        val sink = DirectorySink(root)
        assertEquals("报告.pdf", sink.destinationFor(task("报告.pdf")).name)
    }

    @Test
    fun duplicateGetsNumericSuffix() {
        val root = temp.newFolder("downloads")
        File(root, "报告.pdf").writeText("x")
        val sink = DirectorySink(root)
        assertEquals("报告 (1).pdf", sink.destinationFor(task("报告.pdf")).name)
    }

    @Test
    fun duplicateSuffixKeepsIncrementing() {
        val root = temp.newFolder("downloads")
        File(root, "a.txt").writeText("x")
        File(root, "a (1).txt").writeText("x")
        val sink = DirectorySink(root)
        assertEquals("a (2).txt", sink.destinationFor(task("a.txt")).name)
    }

    @Test
    fun createsRootDirectoryWhenMissing() {
        val root = File(temp.root, "not-yet")
        assertFalse(root.exists())
        DirectorySink(root).destinationFor(task("x.bin"))
        assertTrue(root.exists())
    }

    @Test
    fun sanitizeReplacesPathSeparators() {
        assertEquals("a_b_c", DirectorySink.sanitize("a/b\\c"))
    }

    @Test
    fun sanitizeReplacesReservedCharacters() {
        assertEquals("a_b_c_d_e_f_g_h", DirectorySink.sanitize("a:b*c?d\"e<f>g|h"))
    }

    @Test
    fun sanitizeKeepsChineseAndExtension() {
        assertEquals("我的文档.docx", DirectorySink.sanitize("我的文档.docx"))
    }

    @Test
    fun sanitizeFallsBackForBlankName() {
        assertEquals("未命名", DirectorySink.sanitize("   "))
    }

    @Test
    fun sanitizeBoundsLength() {
        val long = "a".repeat(300)
        assertEquals(120, DirectorySink.sanitize(long).length)
    }

    @Test
    fun sanitizeStripsControlCharacters() {
        assertEquals("a_b", DirectorySink.sanitize("a\u0001b"))
    }
}
