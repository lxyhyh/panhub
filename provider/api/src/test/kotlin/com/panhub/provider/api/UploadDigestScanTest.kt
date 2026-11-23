package com.panhub.provider.api

import com.panhub.core.common.UploadScan
import com.panhub.core.common.UploadScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * 摘要取数规则的边界：拿不到内核结果时必须干净地返回 null，
 * 让调用方走自己的实现，而不是抛异常或返回一份错摘要。
 */
class UploadDigestScanTest {

    private val file = kotlin.io.path.createTempFile("upload").toFile().apply {
        writeBytes(ByteArray(10) { it.toByte() })
    }

    private fun scanOf(content: ByteArray = ByteArray(10) { it.toByte() }): UploadScan =
        UploadScan(md5 = "m", sha1 = "s", size = content.size.toLong(), prefixMd5 = "p", sliceMd5s = listOf("a"))

    /** 固定返回一份结果的扫描器。 */
    private class FixedScanner(private val result: UploadScan?) : UploadScanner {
        var calls = 0
        override fun scan(file: File, sliceSize: Long, prefixLimit: Long): UploadScan? {
            calls++
            return result
        }
    }

    /** 没有本地文件的来源，模拟内容提供者拿到的 Uri。 */
    private class StreamOnlySource(private val bytes: ByteArray) : UploadSource {
        override val name = "stream.bin"
        override val size: Long get() = bytes.size.toLong()
        override fun openStream(): InputStream = ByteArrayInputStream(bytes)
    }

    @Test
    fun localFileIsHandedToTheScanner() {
        val scanner = FixedScanner(scanOf())
        val result = UploadDigestScan.scan(scanner, FileUploadSource(file), 0L, 0L)
        assertEquals("m", result?.md5)
        assertEquals(1, scanner.calls)
    }

    @Test
    fun sourceWithoutLocalFileSkipsTheScanner() {
        val scanner = FixedScanner(scanOf())
        val result = UploadDigestScan.scan(scanner, StreamOnlySource(ByteArray(10)), 0L, 0L)
        assertNull(result)
        assertEquals(0, scanner.calls)
    }

    @Test
    fun scannerThatDoesNotSupportItYieldsNull() {
        val result = UploadDigestScan.scan(UploadScanner.None, FileUploadSource(file), 0L, 0L)
        assertNull(result)
    }

    @Test
    fun sizeMismatchIsRejected() {
        // 文件在排队期间被改过：扫描出的大小与声明不符，这份摘要不能用。
        val scanner = FixedScanner(scanOf(ByteArray(5)))
        val result = UploadDigestScan.scan(scanner, FileUploadSource(file), 0L, 0L)
        assertNull(result)
    }

    @Test
    fun sliceSizeAndPrefixLimitAreForwarded() {
        var seenSlice = -1L
        var seenPrefix = -1L
        val scanner = object : UploadScanner {
            override fun scan(file: File, sliceSize: Long, prefixLimit: Long): UploadScan {
                seenSlice = sliceSize
                seenPrefix = prefixLimit
                return UploadScan("m", "s", 10L, "p", emptyList())
            }
        }
        UploadDigestScan.scan(scanner, FileUploadSource(file), 4L * 1024 * 1024, 256L * 1024)
        assertEquals(4L * 1024 * 1024, seenSlice)
        assertEquals(256L * 1024, seenPrefix)
    }
}
