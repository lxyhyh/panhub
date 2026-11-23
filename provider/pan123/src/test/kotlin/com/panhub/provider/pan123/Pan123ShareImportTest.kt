package com.panhub.provider.pan123

import com.panhub.core.common.PanResult
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.PageRequest
import com.panhub.core.model.ShareEntry
import com.panhub.core.model.ShareSource
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
 * 打开 123 云盘分享 / 列目录 / 转存的端到端测试。
 *
 * 用假 Interceptor 顶替网络，按脚本回放服务端响应并记录请求顺序。
 * 值得测的原因是这几步里有三个只在真机上暴露的坑：
 * Next 的终止值是字符串 "-1"、没有提取码时不能带空 SharePwd、
 * 转存要等一个异步任务号。
 */
class Pan123ShareImportTest {

    private class ScriptedServer(private val replies: List<String>) : Interceptor {
        val requested = mutableListOf<String>()
        val bodies = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requested += request.method + " " + request.url.encodedPath + "?" + (request.url.query ?: "")
            bodies += readBody(request)
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

        private fun readBody(request: okhttp3.Request): String {
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            return buffer.readUtf8()
        }
    }

    private fun importer(server: ScriptedServer) = Pan123ShareImporter(
        api = Pan123Api(HttpEngine(okhttp3.OkHttpClient.Builder().addInterceptor(server).build())),
        pollAttempts = 5,
        pollIntervalMs = 0L,
    )

    private val rootPage =
        """{"code":0,"message":"ok","data":{"Next":"-1","InfoList":[""" +
            """{"FileId":11,"FileName":"资料.zip","Size":2048,"Type":0,"ParentFileId":0,""" +
            """"S3KeyFlag":"1816216065-0","Etag":"et1","StorageNode":"node1"},""" +
            """{"FileId":12,"FileName":"相册","Size":0,"Type":1,"ParentFileId":0}""" +
            """]}}"""

    @Test
    fun openReturnsShareKeyAndPasscode() = runTest {
        val server = ScriptedServer(listOf(rootPage))
        val result = importer(server).open(null, "https://www.123pan.cn/s/abcde-fghij?pwd=1234", null)
        assertTrue(result is PanResult.Success)
        val source = (result as PanResult.Success).value
        assertEquals("abcde-fghij", source.linkId)
        assertEquals("1234", source.token)
        assertTrue(server.requested[0].startsWith("GET /api/share/get?"))
        assertTrue(server.requested[0].contains("SharePwd=1234"))
    }

    @Test
    fun explicitPasscodeWinsOverLink() = runTest {
        val server = ScriptedServer(listOf(rootPage))
        importer(server).open(null, "https://www.123pan.cn/s/abcde-fghij?pwd=1111", "2222")
        assertTrue(server.requested[0].contains("SharePwd=2222"))
    }

    @Test
    fun shareWithoutPasscodeOmitsTheParameter() = runTest {
        val server = ScriptedServer(listOf(rootPage))
        importer(server).open(null, "https://www.123pan.cn/s/abcde-fghij", null)
        // 空 SharePwd 会被服务端当成参数缺失回 400，所以整段都不能出现。
        assertTrue(!server.requested[0].contains("SharePwd="))
    }

    @Test
    fun invalidLinkFailsBeforeAnyRequest() = runTest {
        val server = ScriptedServer(emptyList())
        val result = importer(server).open(null, "https://example.com/whatever", null)
        assertTrue(result is PanResult.Failure)
        assertEquals(0, server.requested.size)
    }

    @Test
    fun expiredShareFails() = runTest {
        val server = ScriptedServer(listOf("""{"code":0,"data":{"Expired":true,"InfoList":[]}}"""))
        val result = importer(server).open(null, "https://www.123pan.cn/s/abcde-fghij", null)
        assertTrue(result is PanResult.Failure)
    }

    @Test
    fun listMapsEntriesAndTickets() = runTest {
        val server = ScriptedServer(listOf(rootPage))
        val source = ShareSource(linkId = "abcde-fghij", token = "")
        val result = importer(server).list(null, source, null, PageRequest())
        assertTrue(result is PanResult.Success)
        val page = (result as PanResult.Success).value
        assertEquals(2, page.items.size)
        assertEquals("资料.zip", page.items[0].name)
        assertEquals(NodeKind.FILE, page.items[0].kind)
        assertEquals("1816216065-0|et1|node1", page.items[0].ticket)
        assertEquals(NodeKind.DIR, page.items[1].kind)
        assertNull(page.items[1].ticket)
        // Next 是 "-1" 表示末页，不能当成还有下一页。
        assertNull(page.nextCursor)
        assertTrue(server.requested[0].contains("ParentFileId=0"))
    }

    @Test
    fun nextCursorIsPageNumberNotServerCursor() = runTest {
        val page =
            """{"code":0,"data":{"Next":"","InfoList":[""" +
                """{"FileId":21,"FileName":"a.txt","Size":1,"Type":0,"S3KeyFlag":"9-0"}""" +
                """]}}"""
        val server = ScriptedServer(listOf(page))
        val source = ShareSource(linkId = "abcde-fghij", token = "")
        val result = importer(server).list(null, source, null, PageRequest())
        assertEquals("2", (result as PanResult.Success).value.nextCursor)
    }

    @Test
    fun subDirectoryUsesGivenParent() = runTest {
        val server = ScriptedServer(listOf("""{"code":0,"data":{"Next":"-1","InfoList":[]}}"""))
        val source = ShareSource(linkId = "abcde-fghij", token = "")
        importer(server).list(null, source, NodeId("12"), PageRequest())
        assertTrue(server.requested[0].contains("ParentFileId=12"))
    }

    @Test
    fun savePostsFileListAndPollsTask() = runTest {
        val server = ScriptedServer(
            listOf(
                """{"code":0,"data":{"taskID":"t-1"}}""",
                """{"code":0,"data":{"finished":true}}""",
            ),
        )
        val source = ShareSource(linkId = "abcde-fghij", token = "1234")
        val entries = listOf(
            ShareEntry(
                id = NodeId("11"),
                name = "资料.zip",
                kind = NodeKind.FILE,
                ticket = "1816216065-0|et1|node1",
                size = 2048,
            ),
        )
        val result = importer(server).save(null, source, entries, NodeId("88"))
        assertTrue(result is PanResult.Success)
        assertEquals(1, (result as PanResult.Success).value)
        assertTrue(server.requested[0].startsWith("POST /api/restful/goapi/v1/file/copy/save?"))
        assertTrue(server.bodies[0].contains("\"shareKey\":\"abcde-fghij\""))
        assertTrue(server.bodies[0].contains("\"sharePwd\":\"1234\""))
        assertTrue(server.bodies[0].contains("\"parentFileID\":88"))
        assertTrue(server.bodies[0].contains("\"S3KeyFlag\":\"1816216065-0\""))
        assertTrue(server.requested[1].startsWith("GET /api/restful/goapi/v1/file/copy/save/get?taskID=t-1"))
    }

    @Test
    fun saveWithoutPasscodeSendsEmptyString() = runTest {
        val server = ScriptedServer(
            listOf(
                """{"code":0,"data":{"taskId":"t-2"}}""",
                """{"code":0,"data":{"status":2}}""",
            ),
        )
        val source = ShareSource(linkId = "abcde-fghij", token = "")
        val entries = listOf(
            ShareEntry(id = NodeId("11"), name = "a.txt", kind = NodeKind.FILE, ticket = "9-0|e|n"),
        )
        importer(server).save(null, source, entries, null)
        // 空提取码要发空串而不是 null，否则服务端判参数缺失。
        assertTrue(server.bodies[0].contains("\"sharePwd\":\"\""))
        assertTrue(server.bodies[0].contains("\"parentFileID\":0"))
    }

    @Test
    fun emptySelectionIsRejected() = runTest {
        val server = ScriptedServer(emptyList())
        val source = ShareSource(linkId = "abcde-fghij", token = "")
        val result = importer(server).save(null, source, emptyList(), null)
        assertTrue(result is PanResult.Failure)
        assertEquals(0, server.requested.size)
    }

    @Test
    fun missingTaskIdFails() = runTest {
        val server = ScriptedServer(listOf("""{"code":0,"data":{}}"""))
        val source = ShareSource(linkId = "abcde-fghij", token = "")
        val entries = listOf(
            ShareEntry(id = NodeId("11"), name = "a.txt", kind = NodeKind.FILE, ticket = "9-0|e|n"),
        )
        val result = importer(server).save(null, source, entries, null)
        assertTrue(result is PanResult.Failure)
    }

    @Test
    fun unfinishedTaskReportsRetryableFailure() = runTest {
        val pending = """{"code":0,"data":{"status":1}}"""
        // 第一条是转存请求的响应（必须有 taskID），后面才是还没完成的轮询。
        val server = ScriptedServer(listOf("""{"code":0,"data":{"taskID":"t-9"}}""") + List(6) { pending })
        val source = ShareSource(linkId = "abcde-fghij", token = "")
        val entries = listOf(
            ShareEntry(id = NodeId("11"), name = "a.txt", kind = NodeKind.FILE, ticket = "9-0|e|n"),
        )
        val result = importer(server).save(null, source, entries, null)
        assertTrue(result is PanResult.Failure)
        val error = (result as PanResult.Failure).error
        assertTrue(error.message + " / kind=" + error.kind, error.retryable)
    }
}
