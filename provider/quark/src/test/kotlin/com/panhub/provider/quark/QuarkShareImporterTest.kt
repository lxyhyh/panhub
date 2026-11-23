package com.panhub.provider.quark

import com.panhub.core.common.PanResult
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.PageRequest
import com.panhub.core.model.ShareEntry
import com.panhub.core.network.HttpEngine
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打开分享 / 列目录 / 转存的端到端测试。
 *
 * 用假 Interceptor 顶替网络，按脚本回放服务端响应，并记录请求顺序。
 * 这一层值得测的原因和创建分享一样：三步里少带一个票据参数，
 * 在真机上只表现为「转存失败」四个字，看不出是哪一步。
 */
class QuarkShareImporterTest {

    private class ScriptedServer(private val replies: List<String>) : Interceptor {
        val requested = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requested += request.method + " " + request.url.encodedPath + "?" + (request.url.query ?: "")
            val body = replies.getOrNull(requested.size - 1)
                ?: error("unexpected request #" + requested.size + ": " + requested.last())
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun importer(server: ScriptedServer) = QuarkShareImporter(
        api = QuarkApi(HttpEngine(okhttp3.OkHttpClient.Builder().addInterceptor(server).build())),
        pollAttempts = 5,
        pollIntervalMs = 0L,
    )

    private val tokenReply =
        """{"status":200,"code":0,"data":{"stoken":"st-1","url_type":1,""" +
            """"title":"资料","author":{"nick_name":"某人"}}}"""

    private val detailReply =
        """{"status":200,"code":0,"data":{"list":[""" +
            """{"fid":"f1","file_name":"a.txt","pdir_fid":"0","dir":false,"size":12,""" +
            """"share_fid_token":"tk1"},""" +
            """{"fid":"d1","file_name":"相册","pdir_fid":"0","dir":true,""" +
            """"share_fid_token":"tk2"}]},""" +
            """"metadata":{"_size":100,"_page":1,"_count":2,"_total":2}}"""

    @Test
    fun openReturnsTokenAndTitle() = runTest {
        val server = ScriptedServer(listOf(tokenReply))
        val result = importer(server).open("c", "https://pan.quark.cn/s/pwd1?pwd=1234", null, {})
        assertTrue(result is PanResult.Success)
        val source = (result as PanResult.Success).value
        assertEquals("pwd1", source.linkId)
        assertEquals("st-1", source.token)
        assertEquals("资料", source.title)
        assertEquals("某人", source.ownerName)
        assertTrue(server.requested[0].startsWith("POST /1/clouddrive/share/sharepage/token?"))
    }

    @Test
    fun passcodeFromLinkIsUsedWhenNotGiven() = runTest {
        val server = ScriptedServer(listOf(tokenReply))
        importer(server).open("c", "https://pan.quark.cn/s/pwd1?pwd=1234", null, {})
        assertEquals(1, server.requested.size)
    }

    @Test
    fun invalidLinkFailsBeforeAnyRequest() = runTest {
        val server = ScriptedServer(emptyList())
        val result = importer(server).open("c", "https://example.com/whatever", null, {})
        assertTrue(result is PanResult.Failure)
        assertEquals(0, server.requested.size)
    }

    @Test
    fun missingTokenFails() = runTest {
        val server = ScriptedServer(listOf("""{"status":200,"code":0,"data":{}}"""))
        val result = importer(server).open("c", "https://pan.quark.cn/s/pwd1", "1", {})
        assertTrue(result is PanResult.Failure)
    }

    @Test
    fun listMapsEntriesWithTickets() = runTest {
        val server = ScriptedServer(listOf(detailReply))
        val source = com.panhub.core.model.ShareSource(linkId = "pwd1", token = "st-1")
        val result = importer(server).list("c", source, null, PageRequest(), {})
        assertTrue(result is PanResult.Success)
        val page = (result as PanResult.Success).value
        assertEquals(2, page.items.size)
        assertEquals("a.txt", page.items[0].name)
        assertEquals(NodeKind.FILE, page.items[0].kind)
        assertEquals("tk1", page.items[0].ticket)
        assertEquals(NodeKind.DIR, page.items[1].kind)
        assertNull(page.nextCursor)
        assertTrue(server.requested[0].contains("pwd_id=pwd1"))
        assertTrue(server.requested[0].contains("stoken=st-1"))
        assertTrue(server.requested[0].contains("_fetch_share=1"))
    }

    @Test
    fun subDirectorySkipsBannerParams() = runTest {
        val server = ScriptedServer(listOf(detailReply))
        val source = com.panhub.core.model.ShareSource(linkId = "pwd1", token = "st-1")
        importer(server).list("c", source, NodeId("d1"), PageRequest(), {})
        assertTrue(!server.requested[0].contains("_fetch_banner=1"))
    }

    @Test
    fun saveUsesTicketsAndTarget() = runTest {
        val server = ScriptedServer(
            listOf(
                """{"status":200,"code":0,"data":{"task_id":"t1","task_sync":false}}""",
                """{"status":200,"code":0,"data":{"task_id":"t1","status":2,""" +
                    """"save_as":{"save_as_top_fids":["n1","n2"],"save_as_sum_num":2}}}""",
            ),
        )
        val source = com.panhub.core.model.ShareSource(linkId = "pwd1", token = "st-1")
        val entries = listOf(
            ShareEntry(id = NodeId("f1"), parentId = NodeId("0"), name = "a.txt", kind = NodeKind.FILE, ticket = "tk1"),
            ShareEntry(id = NodeId("f2"), parentId = NodeId("0"), name = "b.txt", kind = NodeKind.FILE, ticket = "tk2"),
        )
        val result = importer(server).save("c", source, entries, NodeId("dest"), {})
        assertTrue(result is PanResult.Success)
        assertEquals(2, (result as PanResult.Success).value)
        assertTrue(server.requested[0].startsWith("POST /1/clouddrive/share/sharepage/save?"))
        assertTrue(server.requested[1].startsWith("GET /1/clouddrive/task?"))
    }

    @Test
    fun synchronousSaveSkipsPolling() = runTest {
        val server = ScriptedServer(
            listOf(
                """{"status":200,"code":0,"data":{"task_id":"t1","task_sync":true,""" +
                    """"task_resp":{"data":{"task_id":"t1","status":2,""" +
                    """"save_as":{"save_as_sum_num":1}}}}}""",
            ),
        )
        val source = com.panhub.core.model.ShareSource(linkId = "pwd1", token = "st-1")
        val entries = listOf(
            ShareEntry(id = NodeId("f1"), parentId = NodeId("0"), name = "a.txt", kind = NodeKind.FILE, ticket = "tk1"),
        )
        val result = importer(server).save("c", source, entries, NodeId("dest"), {})
        assertEquals(1, (result as PanResult.Success).value)
        assertEquals(1, server.requested.size)
    }

    @Test
    fun entryWithoutTicketIsRejectedBeforeRequest() = runTest {
        val server = ScriptedServer(emptyList())
        val source = com.panhub.core.model.ShareSource(linkId = "pwd1", token = "st-1")
        val entries = listOf(
            ShareEntry(id = NodeId("f1"), parentId = NodeId("0"), name = "a.txt", kind = NodeKind.FILE),
        )
        val result = importer(server).save("c", source, entries, null, {})
        assertTrue(result is PanResult.Failure)
        assertEquals(0, server.requested.size)
    }

    @Test
    fun emptySelectionIsRejected() = runTest {
        val server = ScriptedServer(emptyList())
        val source = com.panhub.core.model.ShareSource(linkId = "pwd1", token = "st-1")
        val result = importer(server).save("c", source, emptyList(), null, {})
        assertTrue(result is PanResult.Failure)
        assertEquals(0, server.requested.size)
    }

    @Test
    fun failedTaskSurfacesServerMessage() = runTest {
        val server = ScriptedServer(
            listOf(
                """{"status":200,"code":0,"data":{"task_id":"t1","task_sync":false}}""",
                """{"status":200,"code":0,"data":{"task_id":"t1","status":3,""" +
                    """"error_message":"空间不足"}}""",
            ),
        )
        val source = com.panhub.core.model.ShareSource(linkId = "pwd1", token = "st-1")
        val entries = listOf(
            ShareEntry(id = NodeId("f1"), parentId = NodeId("0"), name = "a.txt", kind = NodeKind.FILE, ticket = "tk1"),
        )
        val result = importer(server).save("c", source, entries, null, {})
        assertTrue(result is PanResult.Failure)
        assertEquals("空间不足", (result as PanResult.Failure).error.message)
    }
}
