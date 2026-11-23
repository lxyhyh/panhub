package com.panhub.provider.webdav

import com.panhub.core.network.HttpEngine
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * COPY 的请求形状。
 *
 * 坚果云的 WebDAV 走 nginx 代理，方法名拼错、Destination 只是相对路径、
 * Depth 少写，都会被回一个看不出原因的 400。这几条用例把请求形状钉死。
 */
class WebDavCopyTest {

    private class Recorder(private val code: Int = 201) : Interceptor {
        var method: String? = null
        var url: String? = null
        var destination: String? = null
        var depth: String? = null

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            method = request.method
            url = request.url.toString()
            destination = request.header("Destination")
            depth = request.header("Depth")
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("Created")
                .body("".toResponseBody("text/plain".toMediaType()))
                .build()
        }
    }

    private fun client(recorder: Recorder) = WebDavClient(
        engine = HttpEngine(okhttp3.OkHttpClient.Builder().addInterceptor(recorder).build()),
        baseUrl = "https://dav.jianguoyun.com/dav/",
        username = "me@example.com",
        password = "app-password",
    )

    @Test
    fun copyUsesTheCopyVerb() = runTest {
        val recorder = Recorder()
        val result = client(recorder).copy("docs/a.txt", "docs/b.txt")

        assertEquals("COPY", recorder.method)
        assertEquals(true, result is com.panhub.core.common.PanResult.Success)
    }

    @Test
    fun destinationIsAnAbsoluteUrl() = runTest {
        val recorder = Recorder()
        client(recorder).copy("docs/a.txt", "backup/a.txt")

        assertEquals("https://dav.jianguoyun.com/dav/backup/a.txt", recorder.destination)
    }

    @Test
    fun sourceAndDestinationAreUrlEncoded() = runTest {
        val recorder = Recorder()
        client(recorder).copy("我的/报告 2024.pdf", "备份/报告 2024.pdf")

        assertEquals(
            "https://dav.jianguoyun.com/dav/%E5%A4%87%E4%BB%BD/%E6%8A%A5%E5%91%8A%202024.pdf",
            recorder.destination,
        )
        assertEquals(
            "https://dav.jianguoyun.com/dav/%E6%88%91%E7%9A%84/%E6%8A%A5%E5%91%8A%202024.pdf",
            recorder.url,
        )
    }

    @Test
    fun depthIsInfinitySoFoldersCopyTheirChildren() = runTest {
        val recorder = Recorder()
        client(recorder).copy("photos", "photos-copy")

        assertEquals("infinity", recorder.depth)
    }

    @Test
    fun failureStatusIsReported() = runTest {
        val recorder = Recorder(code = 404)
        val result = client(recorder).copy("gone.txt", "here.txt")

        assertEquals(true, result is com.panhub.core.common.PanResult.Failure)
    }
}
