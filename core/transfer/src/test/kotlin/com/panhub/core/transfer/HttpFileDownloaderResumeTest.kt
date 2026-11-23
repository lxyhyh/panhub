package com.panhub.core.transfer

import com.panhub.core.common.PanResult
import com.panhub.core.network.HttpEngine
import com.panhub.provider.api.DownloadTarget
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 断点续传的测试。
 *
 * 这里断言的不是「能下完」，而是几条容易被写错的分支：
 * 没有残片时从头下、有残片且服务端给 206 时接着下、
 * 服务端忽略 Range 给 200 时不能把整份内容追加到旧残片后面、
 * 残片比目标还大时必须丢掉、网络错误要留着残片等下次。
 */
class HttpFileDownloaderResumeTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** 只会回放一种响应的假服务端，顺便记下收到的请求。 */
    private class FakeServer(
        private val code: Int,
        private val body: ByteArray,
        private val contentRange: String? = null,
    ) {
        val requests = mutableListOf<okhttp3.Request>()

        fun engine(): HttpEngine = HttpEngine(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requests += request
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message(if (code == 200) "OK" else "Status")
                        .apply { contentRange?.let { header("Content-Range", it) } }
                        .body(body.toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                }
                .build(),
        )
    }

    private fun target(size: Long?) = DownloadTarget("https://example.com/file.bin", size = size)

    private fun destination(): File = File(temp.newFolder("out"), "file.bin")

    private fun partOf(file: File) = File(file.parentFile, file.name + HttpFileDownloader.PART_SUFFIX)

    @Test
    fun freshDownloadWritesWholeBody() = runBlocking {
        val server = FakeServer(200, "0123456789".toByteArray())
        val file = destination()

        val result = HttpFileDownloader(server.engine()).download(target(10L), file) { _, _ -> }

        assertTrue(result is PanResult.Success)
        assertEquals("0123456789", file.readText())
        assertFalse(partOf(file).exists())
        assertNull(server.requests.single().header("Range"))
    }

    @Test
    fun existingPartIsResumedWithRangeHeader() = runBlocking {
        val file = destination()
        partOf(file).writeText("01234")

        val server = FakeServer(206, "56789".toByteArray(), "bytes 5-9/10")

        val result = HttpFileDownloader(server.engine()).download(target(10L), file) { _, _ -> }

        assertTrue(result is PanResult.Success)
        assertEquals("bytes=5-", server.requests.single().header("Range"))
        assertEquals("0123456789", file.readText())
    }

    @Test
    fun serverIgnoringRangeRestartsInsteadOfAppending() = runBlocking {
        val file = destination()
        partOf(file).writeText("01234")

        // 服务端不认 Range，把整份内容又发了一遍。
        val server = FakeServer(200, "0123456789".toByteArray())

        val result = HttpFileDownloader(server.engine()).download(target(10L), file) { _, _ -> }

        assertTrue(result is PanResult.Success)
        assertEquals("0123456789", file.readText())
    }

    @Test
    fun oversizedPartIsDiscarded() = runBlocking {
        val file = destination()
        partOf(file).writeText("0123456789ABCDEF")

        val server = FakeServer(200, "0123456789".toByteArray())

        val result = HttpFileDownloader(server.engine()).download(target(10L), file) { _, _ -> }

        assertTrue(result is PanResult.Success)
        assertNull(server.requests.single().header("Range"))
        assertEquals("0123456789", file.readText())
    }

    @Test
    fun rangeNotSatisfiableDropsPartAndFails() = runBlocking {
        val file = destination()
        partOf(file).writeText("01234")

        val server = FakeServer(416, ByteArray(0))

        val result = HttpFileDownloader(server.engine()).download(target(10L), file) { _, _ -> }

        assertTrue(result is PanResult.Failure)
        assertFalse(partOf(file).exists())
        assertFalse(file.exists())
    }

    @Test
    fun httpErrorKeepsPartForNextAttempt() = runBlocking {
        val file = destination()
        partOf(file).writeText("01234")

        val server = FakeServer(503, ByteArray(0))

        val result = HttpFileDownloader(server.engine()).download(target(10L), file) { _, _ -> }

        assertTrue(result is PanResult.Failure)
        assertTrue(partOf(file).exists())
    }

    @Test
    fun progressReportsCumulativeBytesWhenResuming() = runBlocking {
        val file = destination()
        partOf(file).writeText("01234")

        val server = FakeServer(206, "56789".toByteArray(), "bytes 5-9/10")

        val seen = mutableListOf<Long>()
        HttpFileDownloader(server.engine()).download(target(10L), file) { bytes, _ -> seen += bytes }

        // 续传时进度必须从 5 开始累加，从 0 开始会让进度条往回跳。
        assertEquals(listOf(10L), seen)
    }
}
