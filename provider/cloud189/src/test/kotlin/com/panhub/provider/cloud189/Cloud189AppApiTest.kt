package com.panhub.provider.cloud189

import com.panhub.core.common.Clock
import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanResult
import com.panhub.core.network.HttpEngine
import com.panhub.provider.api.ProviderCredentials
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
 * App 通道的业务端点层。
 *
 * 这里钉的是「参数放哪」这类容易写错、错了又看不出原因的东西：
 * 建目录与改名在参考实现里参数放 query 而不是 form body，
 * 放错地方服务端只会回缺参数。
 */
class Cloud189AppApiTest {

    private val sessionJson =
        """{"res_code":0,"sessionKey":"SK-1","sessionSecret":"0123456789abcdef"}"""

    /** 一次性的「已换好会话」组合：channel 与 api 共用同一个签名器。 */
    private class Ready(val channel: Cloud189AppChannel, val api: Cloud189AppApi)

    private suspend fun ready(server: ScriptedServer): Ready {
        val channel = Cloud189AppChannel(
            engine = HttpEngine(OkHttpClient.Builder().addInterceptor(server).build()),
            clock = Clock { 1136214245000L },
            random = Random(3),
        )
        // 每个用例都先换一次会话，后面第 2 个请求才是被测的那次。
        channel.openSession("AT-1")
        return Ready(channel, Cloud189AppApi(channel))
    }

    @Test
    fun listFilesSendsPagingQueryAndParsesFolders() = runTest {
        val server = ScriptedServer(
            listOf(
                200 to sessionJson,
                200 to """{"res_code":0,"fileListAO":{"count":1,"folderList":[{"id":11,"name":"docs"}]}}""",
            ),
        )
        val api = ready(server).api

        val result = api.listFiles("-11", pageNum = 2)

        assertTrue(result is PanResult.Success)
        val request = server.requests[1]
        assertEquals("/file/listFiles.action", request.path)
        assertEquals("-11", request.query["folderId"])
        assertEquals("2", request.query["pageNum"])
        assertEquals("1000", request.query["pageSize"])
        assertEquals("lastOpTime", request.query["orderBy"])
        assertEquals("true", request.query["descending"])
        // 根目录也要显式带上 recursive=0，缺了会变成递归列举。
        assertEquals("0", request.query["recursive"])
    }

    @Test
    fun createFolderPutsParametersInQuery() = runTest {
        val server = ScriptedServer(listOf(200 to sessionJson, 200 to """{"res_code":0}"""))
        val api = ready(server).api

        api.createFolder("-11", "新文件夹")

        val request = server.requests[1]
        assertEquals("POST", request.method)
        assertEquals("/createFolder.action", request.path)
        // 参考实现把这三个放 query，body 是空的。
        assertEquals("新文件夹", request.query["folderName"])
        assertEquals("", request.query["relativePath"])
        assertEquals("-11", request.query["parentFolderId"])
        assertNull(request.body)
    }

    @Test
    fun renameUsesDifferentFieldNamesPerKind() = runTest {
        val server = ScriptedServer(
            listOf(200 to sessionJson, 200 to """{"res_code":0}""", 200 to """{"res_code":0}"""),
        )
        val api = ready(server).api

        api.renameFile("F1", "a.txt")
        api.renameFolder("D1", "b")

        assertEquals("/renameFile.action", server.requests[1].path)
        assertEquals("F1", server.requests[1].query["fileId"])
        assertEquals("a.txt", server.requests[1].query["destFileName"])
        assertEquals("/renameFolder.action", server.requests[2].path)
        assertEquals("D1", server.requests[2].query["folderId"])
        assertEquals("b", server.requests[2].query["destFolderName"])
    }

    @Test
    fun batchTaskSendsJsonArrayWithNumericIsFolder() = runTest {
        val server = ScriptedServer(listOf(200 to sessionJson, 200 to """{"res_code":0,"taskId":"T1"}"""))
        val api = ready(server).api

        api.createBatchTask(Cloud189AppApi.TYPE_MOVE, "D9", "F1", "a.txt", isFolder = false)

        val request = server.requests[1]
        assertEquals("/batch/createBatchTask.action", request.path)
        // 批量任务参数在 form body，和建目录相反。
        val body = request.body.orEmpty()
        assertTrue(body.startsWith("type=MOVE"))
        // isFolder 是数字不是布尔：发 true 服务端会当成非法参数。
        assertTrue(body.contains("%22isFolder%22%3A0"))
        assertTrue(body.contains("%22fileId%22%3A%22F1%22"))
        assertTrue(body.contains("targetFolderId=D9"))
    }

    @Test
    fun batchTaskOmitsTargetFolderWhenNull() = runTest {
        val server = ScriptedServer(listOf(200 to sessionJson, 200 to """{"res_code":0}"""))
        val api = ready(server).api

        api.createBatchTask(Cloud189AppApi.TYPE_DELETE, null, "F1", "a.txt", isFolder = false)

        // 删除不该带目标目录，带了服务端会以为要移动。
        assertTrue(!server.requests[1].body.orEmpty().contains("targetFolderId"))
    }

    @Test
    fun checkBatchTaskReadsNumericStatus() = runTest {
        val server = ScriptedServer(listOf(200 to sessionJson, 200 to """{"taskStatus":4}"""))
        val api = ready(server).api

        val result = api.checkBatchTask(Cloud189AppApi.TYPE_MOVE, "T1")

        assertEquals(PanResult.Success(4), result)
    }

    @Test
    fun downloadUrlPrefersFileDownloadUrl() = runTest {
        val server = ScriptedServer(
            listOf(
                200 to sessionJson,
                200 to """{"res_code":0,"fileDownloadUrl":"https://x/a.txt?a=1&amp;b=2","downloadUrl":"https://y"}""",
            ),
        )
        val api = ready(server).api

        val result = api.downloadUrl("F1")

        val url = (result as PanResult.Success).value
        assertNotNull(url)
        assertTrue(url!!.startsWith("https://x/a.txt"))
        assertEquals("3", server.requests[1].query["dt"])
        assertEquals("1", server.requests[1].query["flag"])
    }

    @Test
    fun downloadUrlReturnsNullWhenAbsent() = runTest {
        val server = ScriptedServer(listOf(200 to sessionJson, 200 to """{"res_code":0}"""))
        val api = ready(server).api
        val result = api.downloadUrl("F1")
        assertNull((result as PanResult.Success).value)
    }

    @Test
    fun quotaParsesCapacityEnvelope() = runTest {
        val server = ScriptedServer(
            listOf(
                200 to sessionJson,
                200 to """{"res_code":0,"cloudCapacityInfo":{"usedSize":100,"totalSize":1000}}""",
            ),
        )
        val api = ready(server).api

        val result = api.quota()

        val envelope = (result as PanResult.Success).value
        assertEquals(100L, envelope.cloudCapacity?.usedSize)
        assertEquals(1000L, envelope.cloudCapacity?.totalSize)
    }

    @Test
    fun gatewayReturnsNullWithoutAppToken() = runTest {
        // Cookie 账号没有 App token，属于正常情况，不是错误。
        val server = ScriptedServer(emptyList())
        val engine = HttpEngine(OkHttpClient.Builder().addInterceptor(server).build())
        val credentials = ProviderCredentials.Token(accessToken = "COOKIE-STRING")
        assertNull(Cloud189AppGateway.open(engine, credentials))
        assertEquals(0, server.requests.size)
        assertTrue(!Cloud189AppGateway.configured(credentials))
    }

    @Test
    fun gatewayOpensChannelWhenTokenPresent() = runTest {
        val server = ScriptedServer(listOf(200 to sessionJson, 200 to """{"res_code":0}"""))
        val engine = HttpEngine(OkHttpClient.Builder().addInterceptor(server).build())
        val credentials = Cloud189AppGateway.credentialsWithTokens("AT-9", "RT-9")
        assertTrue(Cloud189AppGateway.configured(credentials))

        val opened = Cloud189AppGateway.open(engine, credentials)

        assertTrue(opened is PanResult.Success)
        val api = (opened as PanResult.Success).value
        api.quota()
        // 换会话带的是 App token，不是 Cookie。
        assertEquals("AT-9", server.requests[0].query["accessToken"])
        assertEquals("/portal/getUserSizeInfo.action", server.requests[1].path)
    }

    @Test
    fun gatewayKeepsRefreshTokenFromCredentials() = runTest {
        // 换会话的响应不一定回 refreshToken，但过期后必须有一个能用来刷新的。
        val server = ScriptedServer(listOf(200 to sessionJson))
        val channel = Cloud189AppChannel(
            engine = HttpEngine(OkHttpClient.Builder().addInterceptor(server).build()),
            clock = Clock { 1136214245000L },
            random = Random(3),
        )
        val established = channel.openSession("AT-9", "RT-9")
        assertEquals("RT-9", (established as PanResult.Success).value.refreshToken)
    }

    @Test
    fun gatewaySurfacesExpiredToken() = runTest {
        val server = ScriptedServer(listOf(200 to """{"res_code":0,"loginName":"x"}"""))
        val engine = HttpEngine(OkHttpClient.Builder().addInterceptor(server).build())

        val opened = Cloud189AppGateway.open(engine, Cloud189AppGateway.credentialsWithTokens("AT-x"))

        assertTrue(opened is PanResult.Failure)
        assertEquals(ErrorKind.AUTH, (opened as PanResult.Failure).error.kind)
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
