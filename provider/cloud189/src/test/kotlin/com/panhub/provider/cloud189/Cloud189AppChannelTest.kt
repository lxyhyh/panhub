package com.panhub.provider.cloud189

import com.panhub.core.common.Clock
import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanResult
import com.panhub.core.network.HttpEngine
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import kotlin.random.Random

/**
 * App 通道的请求层。
 *
 * 没有真实账号，所以这里用脚本化的响应把四件事钉住：
 * 换会话、签名头齐全且签名可复算、会话失效后刷新一次并重放、刷新失败的错误翻译。
 * 这些行为一旦写错，服务端只会回一个 InvalidSignature 或 InvalidSessionKey，
 * 从响应里看不出是哪一步，所以必须靠测试锁死。
 */
class Cloud189AppChannelTest {

    private val dateMillis = 1136214245000L
    private val date = "Mon, 02 Jan 2006 15:04:05 GMT"

    private fun channel(server: ScriptedServer) = Cloud189AppChannel(
        engine = HttpEngine(OkHttpClient.Builder().addInterceptor(server).build()),
        clock = Clock { dateMillis },
        random = Random(5),
    )

    @Test
    fun openSessionExchangesTokenForKeys() = runTest {
        val server = ScriptedServer(
            listOf(
                200 to """{"res_code":0,"loginName":"138****0000","sessionKey":"SK-9","sessionSecret":"0123456789abcdef","accessToken":"AT-1","refreshToken":"RT-1"}""",
            ),
        )
        val channel = channel(server)

        val result = channel.openSession("AT-0")

        assertTrue(result is PanResult.Success)
        val session = (result as PanResult.Success).value
        assertEquals("SK-9", session.sessionKey)
        assertEquals("0123456789abcdef", session.sessionSecret)
        assertEquals("138****0000", session.loginName)
        // 服务端没回 accessToken 时沿用传进去的那个，不能变成空串。
        val request = server.requests.single()
        assertEquals("/getSessionForPC.action", request.path)
        assertEquals(Cloud189AppSign.APP_ID, request.query["appId"])
        assertEquals("AT-0", request.query["accessToken"])
        assertEquals("JSON", request.query["returnType"])
        assertNotNull(request.headers["X-Request-ID"])
        // 换会话本身不签名。
        assertNull(request.headers["Signature"])
        assertNull(request.headers["SessionKey"])
    }

    @Test
    fun openSessionRejectsMissingKeys() = runTest {
        // 服务端 200 但没给密钥：说明 token 无效，必须是鉴权错误而不是空指针。
        val server = ScriptedServer(listOf(200 to """{"res_code":0,"loginName":"x"}"""))
        val result = channel(server).openSession("AT-0")
        assertTrue(result is PanResult.Failure)
        assertEquals(ErrorKind.AUTH, (result as PanResult.Failure).error.kind)
    }

    @Test
    fun signedGetCarriesVerifiableSignature() = runTest {
        val server = ScriptedServer(
            listOf(
                200 to """{"res_code":0,"sessionKey":"SK-9","sessionSecret":"0123456789abcdef"}""",
                200 to """{"res_code":0,"fileListAO":{"count":0}}""",
            ),
        )
        val channel = channel(server)
        channel.openSession("AT-1")

        val result = channel.get("/file/listFiles.action", listOf("folderId" to "-11"))
        assertTrue(result is PanResult.Success)

        val request = server.requests[1]
        assertEquals("GET", request.method)
        assertEquals("/file/listFiles.action", request.path)
        assertEquals("-11", request.query["folderId"])
        // 每个请求都带客户端标识，缺了服务端会当成未知客户端。
        assertEquals(Cloud189AppSign.PC, request.query["clientType"])
        assertEquals(Cloud189AppSign.VERSION, request.query["version"])
        assertEquals("SK-9", request.headers["SessionKey"])
        assertEquals(date, request.headers["Date"])
        // 签名必须能用同一份材料复算出来：段序或大小写错了这里就会挂。
        assertEquals(
            Cloud189AppSign.signature(
                sessionSecret = "0123456789abcdef",
                sessionKey = "SK-9",
                method = "GET",
                path = "/file/listFiles.action",
                date = date,
            ),
            request.headers["Signature"],
        )
    }

    @Test
    fun signedPostPutsFieldsInBodyNotInSignature() = runTest {
        val server = ScriptedServer(
            listOf(
                200 to """{"res_code":0,"sessionKey":"SK-9","sessionSecret":"0123456789abcdef"}""",
                200 to """{"res_code":0}""",
            ),
        )
        val channel = channel(server)
        channel.openSession("AT-1")

        val result = channel.postForm("/createFolder.action", listOf("folderName" to "a b"))
        assertTrue(result is PanResult.Success)

        val request = server.requests[1]
        assertEquals("POST", request.method)
        // 参数在 form body 里，所以签名只有四段（没有 params 段）。
        assertEquals(
            Cloud189AppSign.signature(
                sessionSecret = "0123456789abcdef",
                sessionKey = "SK-9",
                method = "POST",
                path = "/createFolder.action",
                date = date,
            ),
            request.headers["Signature"],
        )
        // 项目里的编码器把空格编成 %20（不是 form 标准的 +），两种服务端都认。
        assertEquals("folderName=a%20b", request.body)
    }

    @Test
    fun expiredSessionRefreshesThenReplaysOnce() = runTest {
        val server = ScriptedServer(
            listOf(
                200 to """{"res_code":0,"sessionKey":"SK-1","sessionSecret":"0123456789abcdef","refreshToken":"RT-1"}""",
                200 to """{"res_code":"InvalidSessionKey","res_message":"session expired"}""",
                200 to """{"accessToken":"AT-2","refreshToken":"RT-2","expiresIn":7200,"result":0}""",
                200 to """{"res_code":0,"sessionKey":"SK-2","sessionSecret":"fedcba9876543210"}""",
                200 to """{"res_code":0,"fileListAO":{"count":1}}""",
            ),
        )
        val channel = channel(server)
        channel.openSession("AT-1")

        val result = channel.get("/file/listFiles.action")

        assertTrue(result is PanResult.Success)
        assertEquals("SK-2", channel.session?.sessionKey)
        // 第一次失败、刷新、重放成功，一共五个请求。
        assertEquals(5, server.requests.size)
        assertEquals("/api/oauth2/refreshToken.do", server.requests[2].path)
        assertEquals("RT-1", server.requests[2].body?.substringAfter("refreshToken=")?.substringBefore("&"))
        // 重放用的是新会话的密钥。
        assertEquals("SK-2", server.requests[4].headers["SessionKey"])
    }

    @Test
    fun replayHappensOnlyOnce() = runTest {
        // 刷新后仍然失效，必须失败而不是无限循环。
        val server = ScriptedServer(
            listOf(
                200 to """{"res_code":0,"sessionKey":"SK-1","sessionSecret":"0123456789abcdef","refreshToken":"RT-1"}""",
                200 to """{"res_code":"InvalidSessionKey"}""",
                200 to """{"accessToken":"AT-2","refreshToken":"RT-2","result":0}""",
                200 to """{"res_code":0,"sessionKey":"SK-2","sessionSecret":"fedcba9876543210"}""",
                200 to """{"res_code":"InvalidSessionKey"}""",
            ),
        )
        val channel = channel(server)
        channel.openSession("AT-1")

        val result = channel.get("/file/listFiles.action")

        assertEquals(5, server.requests.size)
        assertTrue(result is PanResult.Success)
        // 第二次仍然失效的响应原样返回，由上层决定怎么处理，不再刷新。
        assertTrue((result as PanResult.Success).value.contains("InvalidSessionKey"))
    }

    @Test
    fun refreshFailureIsReportedAsAuth() = runTest {
        // 这个接口失败也回 HTTP 200，只看 result 与 msg。
        val server = ScriptedServer(
            listOf(
                200 to """{"res_code":0,"sessionKey":"SK-1","sessionSecret":"0123456789abcdef","refreshToken":"RT-1"}""",
                200 to """{"res_code":"InvalidSessionKey"}""",
                200 to """{"result":1,"msg":"refresh token 已失效"}""",
            ),
        )
        val channel = channel(server)
        channel.openSession("AT-1")

        val result = channel.get("/file/listFiles.action")

        assertTrue(result is PanResult.Failure)
        val error = (result as PanResult.Failure).error
        assertEquals(ErrorKind.AUTH, error.kind)
        assertEquals("refresh token 已失效", error.message)
    }

    @Test
    fun requestWithoutSessionFailsFast() = runTest {
        val server = ScriptedServer(emptyList())
        val result = channel(server).get("/file/listFiles.action")
        assertTrue(result is PanResult.Failure)
        assertEquals(ErrorKind.AUTH, (result as PanResult.Failure).error.kind)
        // 没有会话就不该发请求。
        assertEquals(0, server.requests.size)
    }

    @Test
    fun httpErrorStatusBecomesTypedFailure() = runTest {
        val server = ScriptedServer(
            listOf(
                200 to """{"res_code":0,"sessionKey":"SK-1","sessionSecret":"0123456789abcdef"}""",
                403 to """{"code":"Forbidden"}""",
            ),
        )
        val channel = channel(server)
        channel.openSession("AT-1")
        val result = channel.get("/file/listFiles.action")
        assertTrue(result is PanResult.Failure)
        assertEquals(ErrorKind.AUTH, (result as PanResult.Failure).error.kind)
    }

    private class RecordedRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String?,
    )

    private class ScriptedServer(replies: List<Pair<Int, String>>) : Interceptor {
        private val pending = ArrayDeque(replies)
        val requests = mutableListOf<RecordedRequest>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            requests += RecordedRequest(
                method = request.method,
                path = request.url.encodedPath,
                query = request.url.queryParameterNames.associateWith {
                    request.url.queryParameter(it).orEmpty()
                },
                headers = request.headers.names().associateWith {
                    request.header(it).orEmpty()
                },
                body = buffer.readUtf8().takeIf { it.isNotEmpty() },
            )
            val (code, payload) = if (pending.isEmpty()) {
                200 to "{}"
            } else {
                pending.removeFirst()
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("scripted")
                .body(payload.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }
}
