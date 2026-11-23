package com.panhub.provider.pan123

import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.OfflineKind
import com.panhub.core.model.OfflineLink
import com.panhub.core.model.OfflineResource
import com.panhub.core.network.HttpEngine
import com.panhub.provider.api.ProviderCredentials
import com.panhub.provider.api.ProviderSession
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 123 云盘的离线下载。
 *
 * 盯的是「错了会让用户以为保存成功」的地方：
 * 1. 外层 code 是 0 但资源级 err_code 非 0 时必须算失败；
 * 2. 链接走 urls、磁力走 info_hash，两者混用服务端会当成另一个资源；
 * 3. 提交时 upload_dir 必须是数字，字符串 "0" 会被拒。
 */
class Pan123OfflineTest {

    private class ScriptedServer(private val replies: List<String>) : Interceptor {
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            paths += request.url.encodedPath
            bodies += request.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }.orEmpty()
            val reply = replies.getOrNull(paths.size - 1)
                ?: error("unexpected request #" + paths.size + ": " + paths.last())
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(reply.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private class Ready(val server: ScriptedServer, val provider: Pan123Provider)

    private fun ready(vararg replies: String): Ready {
        val server = ScriptedServer(replies.toList())
        val provider = Pan123Provider(
            HttpEngine(okhttp3.OkHttpClient.Builder().addInterceptor(server).build()),
        )
        return Ready(server, provider)
    }

    private val session = ProviderSession(
        accountId = AccountId("pan123:test"),
        credentials = ProviderCredentials.Token("tok"),
    )

    private val link = OfflineLink(OfflineKind.LINK, "https://example.com/a.mkv")

    @Test
    fun linkIsResolvedThroughTheUrlsField() = runTest {
        val ready = ready(
            """{"code":0,"data":{"list":[{"id":42,"name":"影片","file_nums":2,"url":"https://example.com/a.mkv",
               "files":[{"id":1,"name":"a.mkv","size":100},{"id":2,"name":"b.srt","size":5}]}]}}""",
        )
        val result = ready.provider.resolveOffline(session, link)
        assertTrue(result is PanResult.Success)
        val body = ready.server.bodies[0]
        assertTrue("链接应该放在 urls 里：" + body, body.contains("\"urls\":[\"https://example.com/a.mkv\"]"))
        assertFalse("链接不该带 info_hash：" + body, body.contains("info_hash"))
        assertEquals(Pan123Api.PATH_OFFLINE_RESOLVE, ready.server.paths[0])
    }

    @Test
    fun magnetIsResolvedThroughTheInfoHashField() = runTest {
        val ready = ready(
            """{"code":0,"data":{"list":[{"id":7,"name":"BT","file_nums":1,"hash":"abc",
               "files":[{"id":3,"name":"c.iso","size":2048}]}]}}""",
        )
        val bt = OfflineLink(OfflineKind.BT, "c9e15763f722f23e98a29decdfae341b98d53056")
        val result = ready.provider.resolveOffline(session, bt)
        assertTrue(result is PanResult.Success)
        val body = ready.server.bodies[0]
        assertTrue("磁力应该走 info_hash：" + body, body.contains("\"info_hash\":"))
        assertFalse("磁力不该带 urls：" + body, body.contains("urls"))
    }

    @Test
    fun parsedResourceCarriesItsFiles() = runTest {
        val ready = ready(
            """{"code":0,"data":{"list":[{"id":42,"name":"影片","file_nums":2,
               "files":[{"id":1,"name":"a.mkv","size":100},{"id":2,"name":"b.srt","size":5}]}]}}""",
        )
        val resource = (ready.provider.resolveOffline(session, link) as PanResult.Success).value
        assertEquals("42", resource.id)
        assertEquals("影片", resource.name)
        assertEquals(2, resource.files.size)
        assertEquals("a.mkv", resource.files[0].name)
        assertEquals(105L, resource.totalBytes)
    }

    @Test
    fun resourceLevelErrorFailsEvenWhenTheEnvelopeIsFine() = runTest {
        // 外层 code=0 但资源自己报错（比如链接失效、要会员），只看外层会显示一个空清单。
        val ready = ready(
            """{"code":0,"data":{"list":[{"id":0,"err_code":1001,"err_msg":"链接已失效"}]}}""",
        )
        val result = ready.provider.resolveOffline(session, link)
        assertTrue(result is PanResult.Failure)
        assertEquals("链接已失效", (result as PanResult.Failure).error.message)
    }

    @Test
    fun missingResourceIdFailsWithoutServerMessage() = runTest {
        val ready = ready("""{"code":0,"data":{"list":[{"name":"空"}]}}""")
        val result = ready.provider.resolveOffline(session, link)
        assertTrue(result is PanResult.Failure)
    }

    @Test
    fun emptyListFailsToo() = runTest {
        val ready = ready("""{"code":0,"data":{"list":[]}}""")
        assertTrue(ready.provider.resolveOffline(session, link) is PanResult.Failure)
    }

    @Test
    fun submitSendsTheChosenFilesAndTheTargetFolder() = runTest {
        val ready = ready("""{"code":0,"data":{}}""")
        val resource = OfflineResource(
            id = "42",
            name = "影片",
            kind = OfflineKind.LINK,
            files = emptyList(),
        )
        val result = ready.provider.createOffline(
            session,
            resource,
            listOf("1", "2"),
            NodeId("123456"),
        )
        assertTrue(result.toString(), result is PanResult.Success)
        val body = ready.server.bodies[0]
        assertTrue(body, body.contains("\"resource_id\":\"42\""))
        assertTrue(body, body.contains("\"select_file_id\":[\"1\",\"2\"]"))
        // upload_dir 是数字字段：发 "123456" 会被服务端拒掉。
        assertTrue(body, body.contains("\"upload_dir\":123456"))
        assertEquals(Pan123Api.PATH_OFFLINE_SUBMIT, ready.server.paths[0])
    }

    @Test
    fun submitWithoutAFolderGoesToTheRoot() = runTest {
        val ready = ready("""{"code":0,"data":{}}""")
        val resource = OfflineResource(id = "42", name = "影片", kind = OfflineKind.LINK)
        ready.provider.createOffline(session, resource, listOf("1"), parentId = null)
        val body = ready.server.bodies[0]
        assertTrue(body, body.contains("\"upload_dir\":0"))
    }

    @Test
    fun authFailureIsNotSwallowed() = runTest {
        val ready = ready("""{"code":401,"message":"cookie token is empty","data":null}""")
        val result = ready.provider.resolveOffline(session, link)
        assertTrue(result is PanResult.Failure)
        assertFalse((result as PanResult.Failure).error.retryable)
    }

    @Test
    fun submissionFailureIsReported() = runTest {
        // 5250 是「会员权限不足」——bundle 里网页端专门为它弹了购买提示。
        val ready = ready("""{"code":5250,"message":"需要会员","data":null}""")
        val resource = OfflineResource(id = "42", name = "影片", kind = OfflineKind.LINK)
        val result = ready.provider.createOffline(session, resource, listOf("1"), null)
        assertTrue(result is PanResult.Failure)
    }
}
