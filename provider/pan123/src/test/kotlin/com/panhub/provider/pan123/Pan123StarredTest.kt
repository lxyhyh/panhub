package com.panhub.provider.pan123

import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.PageRequest
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 123 云盘的收藏夹。
 *
 * 这组测试盯的是三处「错了会静默出错」的地方：
 * 1. 收藏状态是 255 / 1 这种取值域，不是布尔，传错不会报错只是没生效；
 * 2. 收藏夹列表的字段是小写驼峰、时间戳是秒，照普通列表的 DTO 读会全变默认值；
 * 3. 空选择不该发请求——带空数组打过去，有的接口会当成「全部」。
 */
class Pan123StarredTest {

    private class ScriptedServer(private val replies: List<String>) : Interceptor {
        val paths = mutableListOf<String>()
        val queries = mutableListOf<String>()
        val bodies = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            paths += request.url.encodedPath
            queries += request.url.encodedQuery ?: ""
            bodies += request.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }.orEmpty()
            val reply = replies.getOrNull(paths.size - 1)
                ?: error("unexpected request: " + paths.last())
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
        credentials = ProviderCredentials.Token("token"),
    )

    @Test
    fun starringSendsTheMagicStatusNotABoolean() = runTest {
        val r = ready("""{"code":0}""")
        val result = r.provider.setStarred(session, listOf(NodeId("42"), NodeId("43")), true)
        assertTrue(result is PanResult.Success)

        // 255 是网页端「已收藏」的取值；写成 true 或 1 都会被服务端当成取消收藏。
        assertEquals("/api/restful/goapi/v1/file/starred", r.server.paths[0])
        assertEquals("""{"fileIdList":[42,43],"starredStatus":255}""", r.server.bodies[0])
    }

    @Test
    fun unstarringSendsOne() = runTest {
        val r = ready("""{"code":0}""")
        val result = r.provider.setStarred(session, listOf(NodeId("42")), false)
        assertTrue(result is PanResult.Success)
        assertTrue(r.server.bodies[0].contains(""""starredStatus":1"""))
    }

    @Test
    fun emptySelectionDoesNotHitTheNetwork() = runTest {
        val r = ready()
        val result = r.provider.setStarred(session, emptyList(), true)
        assertTrue(result is PanResult.Success)
        assertTrue(r.server.paths.isEmpty())
    }

    @Test
    fun starredListUsesTheLowercaseEndpoint() = runTest {
        val r = ready("""{"code":0,"data":{"next":"-1","starredFileInfos":[]}}""")
        r.provider.listStarred(session, PageRequest())
        assertEquals("/api/restful/goapi/v1/file/starred/list", r.server.paths[0])
        assertTrue(r.server.queries[0].contains("orderBy=update_time"))
        assertTrue(r.server.queries[0].contains("trashed=false"))
    }

    @Test
    fun starredEntryIsReadFromTheLowercaseFields() = runTest {
        val r = ready(
            """{"code":0,"data":{"next":"-1","starredFileInfos":[""" +
                """{"fileId":7,"parentFileId":9,"fileName":"report.pdf","fileType":0,"fileSize":1024,"updateAt":1730000000}""" +
                """]}}""",
        )
        val result = r.provider.listStarred(session, PageRequest())
        val page = (result as PanResult.Success).value
        val node = page.items.single()
        assertEquals("7", node.id.value)
        assertEquals("report.pdf", node.name)
        assertEquals(1024L, node.size)
        assertEquals("9", node.parentId?.value)
        assertTrue(node.starred)
        // 服务端给的是秒，直接当毫秒会让收藏夹里的文件全部显示成 1970 年。
        assertEquals(1_730_000_000_000L, node.modifiedAt)
    }

    @Test
    fun starredFolderKeepsZeroSize() = runTest {
        val r = ready(
            """{"code":0,"data":{"next":"-1","starredFileInfos":[""" +
                """{"fileId":8,"fileName":"docs","fileType":1,"fileSize":0}""" +
                """]}}""",
        )
        val page = (r.provider.listStarred(session, PageRequest()) as PanResult.Success).value
        val node = page.items.single()
        assertTrue(node.isDir)
        assertNull(node.modifiedAt)
    }

    @Test
    fun plainListingCarriesTheStarredFlag() = runTest {
        val r = ready(
            """{"code":0,"data":{"next":"-1","InfoList":[""" +
                """{"FileId":1,"FileName":"a.txt","Type":0,"Size":5,"StarredStatus":255},""" +
                """{"FileId":2,"FileName":"b.txt","Type":0,"Size":6,"StarredStatus":1}]}}""",
        )
        val page = (r.provider.list(session, null, PageRequest()) as PanResult.Success).value
        assertTrue(page.items[0].starred)
        assertFalse(page.items[1].starred)
    }

    @Test
    fun missingStarredStatusMeansNotStarred() = runTest {
        val r = ready(
            """{"code":0,"data":{"next":"-1","InfoList":[""" +
                """{"FileId":1,"FileName":"a.txt","Type":0,"Size":5}]}}""",
        )
        val page = (r.provider.list(session, null, PageRequest()) as PanResult.Success).value
        assertFalse(page.items.single().starred)
    }
}