package com.panhub.core.network

import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanResult
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fetchText 的契约：body 在这一层读完，调用方拿到的是已经关掉的纯文本。
 *
 * 这一组用例存在的理由是一次真实的崩溃：provider 在 ViewModel 的 Main 协程里
 * 读 response.body.string()，抛出 NetworkOnMainThreadException。把「读字节」
 * 收进引擎之后，就必须钉住它带回来的东西（状态码、正文、响应头）都是完整的，
 * 否则各 provider 改完会悄悄丢掉错误页正文或 Set-Cookie。
 */
class HttpFetchTextTest {

    @Test
    fun bodyAndStatusComeBackEvenWhenTheServerRefuses() = runTest {
        val engine = engineOf { request ->
            response(request, code = 403, body = """{"errno":-6}""")
        }

        val result = engine.fetchText(get("https://example.test/api"))

        assertTrue(result is PanResult.Success)
        val text = (result as PanResult.Success).value
        assertEquals(403, text.code)
        assertEquals("""{"errno":-6}""", text.body)
    }

    @Test
    fun fetchTextOkTurnsAnErrorStatusIntoAFailure() = runTest {
        val engine = engineOf { request -> response(request, code = 401, body = "nope") }

        val result = engine.fetchTextOk(get("https://example.test/api"))

        assertTrue(result is PanResult.Failure)
        assertEquals(
            ErrorKind.AUTH,
            (result as PanResult.Failure).error.kind,
        )
    }

    @Test
    fun fetchTextOkKeepsASuccessfulBody() = runTest {
        val engine = engineOf { request -> response(request, code = 200, body = "ok") }

        val result = engine.fetchTextOk(get("https://example.test/api"))

        assertEquals("ok", (result as PanResult.Success).value.body)
    }

    @Test
    fun responseHeadersAreCarriedThrough() = runTest {
        // 蓝奏与夸克都要从 Set-Cookie 里取轮换后的登录态，头丢了它们就掉线。
        val engine = engineOf { request ->
            response(request, code = 200, body = "{}").newBuilder()
                .header("Set-Cookie", "kps=abc")
                .build()
        }

        val result = engine.fetchText(get("https://example.test/api"))

        val text = (result as PanResult.Success).value
        assertEquals(listOf("kps=abc"), text.headers.values("Set-Cookie"))
    }

    @Test
    fun anEmptyBodyIsAnEmptyStringNotACrash() = runTest {
        val engine = engineOf { request -> response(request, code = 204, body = "") }

        val result = engine.fetchText(get("https://example.test/api"))

        assertEquals("", (result as PanResult.Success).value.body)
    }

    private fun get(url: String): Request = Request.Builder().url(url).get().build()

    private fun engineOf(handler: (Request) -> Response): HttpEngine = HttpEngine(
        OkHttpClient.Builder().addInterceptor(Interceptor { chain -> handler(chain.request()) }).build(),
    )

    private fun response(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody(null))
            .build()
}