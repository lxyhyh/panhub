package com.panhub.provider.quark

import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanResult
import com.panhub.core.model.NodeId
import com.panhub.core.model.PageRequest
import com.panhub.core.model.ShareOptions
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
 * 分享流程的端到端测试：用假 Interceptor 顶替网络，按顺序吐出服务端会吐的响应。
 *
 * 为什么值得这么测：夸克创建分享是三步异步（创建 → 轮询任务 → 换链接），
 * 任何一步漏掉或者顺序错了，在真机上表现为「一直转圈」或「链接是空的」，
 * 而这两种症状在单测里都能几毫秒复现。
 */
class QuarkShareManagerTest {

    /** 记录每次请求，并按预设脚本回放响应；脚本用完就报错，防止多打请求。 */
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

    private fun manager(server: ScriptedServer) = QuarkShareManager(
        api = QuarkApi(HttpEngine(okhttp3.OkHttpClient.Builder().addInterceptor(server).build())),
        pollAttempts = 5,
        pollIntervalMs = 0L,
    )

    @Test
    fun createWalksCreateThenTaskThenPassword() = runTest {
        val server = ScriptedServer(
            listOf(
                """{"status":200,"code":0,"data":{"task_id":"t1","task_sync":false}}""",
                """{"status":200,"code":0,"data":{"task_id":"t1","status":2,"share_id":"share-1"}}""",
                """{"status":200,"code":0,"data":{"share_id":"share-1","pwd_id":"pwd1",""" +
                    """"share_url":"https://pan.quark.cn/s/pwd1","title":"资料","url_type":1}}""",
            ),
        )
        val result = manager(server).create(
            cookie = "c",
            nodeIds = listOf(NodeId("fid-1")),
            options = ShareOptions(),
            onRenewed = {},
        )
        assertTrue(result is PanResult.Success)
        val link = (result as PanResult.Success).value
        assertEquals("share-1", link.id.value)
        assertEquals("https://pan.quark.cn/s/pwd1", link.url)
        assertEquals("资料", link.title)
        assertEquals(3, server.requested.size)
        assertTrue(server.requested[0].startsWith("POST /1/clouddrive/share?"))
        assertTrue(server.requested[1].startsWith("GET /1/clouddrive/task?"))
        assertTrue(server.requested[2].startsWith("POST /1/clouddrive/share/password?"))
    }

    @Test
    fun synchronousTaskSkipsPolling() = runTest {
        val server = ScriptedServer(
            listOf(
                """{"status":200,"code":0,"data":{"task_id":"t1","task_sync":true,""" +
                    """"task_resp":{"data":{"task_id":"t1","status":2,"share_id":"share-1"}}}}""",
                """{"status":200,"code":0,"data":{"share_id":"share-1","pwd_id":"pwd1",""" +
                    """"share_url":"https://pan.quark.cn/s/pwd1"}}""",
            ),
        )
        val result = manager(server).create("c", listOf(NodeId("fid-1")), ShareOptions(), {})
        assertTrue(result is PanResult.Success)
        // 服务端已经同步完成时不该再轮询一次，两次请求就够了。
        assertEquals(2, server.requested.size)
    }

    @Test
    fun pendingTaskIsPolledUntilDone() = runTest {
        val server = ScriptedServer(
            listOf(
                """{"status":200,"code":0,"data":{"task_id":"t1"}}""",
                """{"status":200,"code":0,"data":{"task_id":"t1","status":0}}""",
                """{"status":200,"code":0,"data":{"task_id":"t1","status":1}}""",
                """{"status":200,"code":0,"data":{"task_id":"t1","status":2,"share_id":"s9"}}""",
                """{"status":200,"code":0,"data":{"share_id":"s9","share_url":"https://pan.quark.cn/s/s9"}}""",
            ),
        )
        val result = manager(server).create("c", listOf(NodeId("fid-1")), ShareOptions(), {})
        assertTrue(result is PanResult.Success)
        assertEquals("s9", (result as PanResult.Success).value.id.value)
        assertEquals(5, server.requested.size)
    }

    @Test
    fun failedTaskSurfacesServerMessage() = runTest {
        val server = ScriptedServer(
            listOf(
                """{"status":200,"code":0,"data":{"task_id":"t1"}}""",
                """{"status":200,"code":0,"data":{"task_id":"t1","status":3,""" +
                    """"error_message":"文件涉及违规内容"}}""",
            ),
        )
        val result = manager(server).create("c", listOf(NodeId("fid-1")), ShareOptions(), {})
        assertTrue(result is PanResult.Failure)
        assertEquals("文件涉及违规内容", (result as PanResult.Failure).error.message)
    }

    @Test
    fun taskWithoutIdFails() = runTest {
        val server = ScriptedServer(
            listOf("""{"status":200,"code":0,"data":{"task_sync":false}}"""),
        )
        val result = manager(server).create("c", listOf(NodeId("fid-1")), ShareOptions(), {})
        assertTrue(result is PanResult.Failure)
    }

    @Test
    fun emptySelectionIsRejectedBeforeAnyRequest() = runTest {
        val server = ScriptedServer(emptyList())
        val result = manager(server).create("c", emptyList(), ShareOptions(), {})
        assertTrue(result is PanResult.Failure)
        assertTrue(server.requested.isEmpty())
    }

    @Test
    fun passwordGoesThroughAsUrlTypeTwo() = runTest {
        val server = ScriptedServer(
            listOf(
                """{"status":200,"code":0,"data":{"task_id":"t1","task_sync":true,""" +
                    """"task_resp":{"data":{"task_id":"t1","status":2,"share_id":"s1"}}}}""",
                """{"status":200,"code":0,"data":{"share_id":"s1","share_url":"https://pan.quark.cn/s/s1",""" +
                    """"passcode":"8888"}}""",
            ),
        )
        val result = manager(server).create(
            "c",
            listOf(NodeId("fid-1")),
            ShareOptions(password = " 8888 ", expireDays = 7),
            {},
        )
        assertTrue(result is PanResult.Success)
        val link = (result as PanResult.Success).value
        assertEquals("8888", link.password)
        assertEquals("https://pan.quark.cn/s/s1?pwd=8888", link.url)
    }

    @Test
    fun cancelPostsShareIds() = runTest {
        val server = ScriptedServer(listOf("""{"status":200,"code":0}"""))
        val result = manager(server).cancel("c", "share-1", {})
        assertTrue(result is PanResult.Success)
        assertTrue(server.requested.single().startsWith("POST /1/clouddrive/share/delete?"))
    }

    @Test
    fun cancelWithoutIdDoesNotHitNetwork() = runTest {
        val server = ScriptedServer(emptyList())
        val result = manager(server).cancel("c", "  ", {})
        assertTrue(result is PanResult.Failure)
        assertTrue(server.requested.isEmpty())
    }

    @Test
    fun listPagesThroughMetadataTotal() = runTest {
        val server = ScriptedServer(
            listOf(
                """{"status":200,"code":0,"metadata":{"_total":120},"data":{"list":[""" +
                    """{"share_id":"a","share_url":"https://pan.quark.cn/s/a"},""" +
                    """{"share_id":"b","share_url":"https://pan.quark.cn/s/b"}]}}""",
            ),
        )
        val result = manager(server).list("c", PageRequest(limit = 2), {})
        assertTrue(result is PanResult.Success)
        val page = (result as PanResult.Success).value
        assertEquals(2, page.items.size)
        assertEquals("2", page.nextCursor)
        assertTrue(server.requested.single().contains("_page=1"))
        assertTrue(server.requested.single().contains("_size=2"))
    }

    @Test
    fun listDropsEntriesWithoutShareId() = runTest {
        val server = ScriptedServer(
            listOf(
                """{"status":200,"code":0,"data":{"list":[""" +
                    """{"share_url":"https://pan.quark.cn/s/a"},""" +
                    """{"share_id":"b","share_url":"https://pan.quark.cn/s/b"}]}}""",
            ),
        )
        val result = manager(server).list("c", PageRequest(), {})
        assertTrue(result is PanResult.Success)
        val page = (result as PanResult.Success).value
        assertEquals(1, page.items.size)
        assertEquals("b", page.items.single().id.value)
        assertNull(page.nextCursor)
    }

    @Test
    fun unauthorizedCreateMapsToAuth() = runTest {
        val server = ScriptedServer(
            listOf("""{"status":401,"code":31001,"message":"require login [guest]"}"""),
        )
        val result = manager(server).create("c", listOf(NodeId("fid-1")), ShareOptions(), {})
        assertTrue(result is PanResult.Failure)
        assertEquals(ErrorKind.AUTH, (result as PanResult.Failure).error.kind)
    }
}
