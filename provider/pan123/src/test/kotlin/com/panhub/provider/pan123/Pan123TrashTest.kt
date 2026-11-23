package com.panhub.provider.pan123

import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 123 云盘的回收站。
 *
 * 这一组测试盯的三件事都是「错了会静默毁数据」的地方：
 * 1. 文件列表里的删除必须走 file/trash（进回收站），不是 file/delete（彻底删）；
 * 2. 还原时服务端把「原目录没了」放在 AbnormalFileIdList 里返回，不能当成成功；
 * 3. 回收站列表靠 trashed=true 切过去，漏了就等于把普通目录当回收站展示。
 */
class Pan123TrashTest {

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
        credentials = ProviderCredentials.Token("token"),
    )

    @Test
    fun deletingInTheFileListGoesToTheRecycleBin() = runTest {
        val r = ready("""{"code":0}""")
        val result = r.provider.delete(session, NodeId("42"))
        assertTrue(result is PanResult.Success)

        // 关键：不能用 file/delete。那是回收站里的「彻底删除」，
        // 在文件列表里点一次删除就永久丢文件。
        assertEquals("/api/file/trash", r.server.paths.single())
        val body = r.server.bodies.single()
        assertTrue("要带 fileTrashInfoList：" + body, body.contains("\"fileTrashInfoList\""))
        assertTrue("operation:true 才是删除：" + body, body.contains("\"operation\":true"))
        assertTrue(body.contains("{\"fileId\":42}"))
    }

    @Test
    fun trashListSwitchesTheSameEndpointIntoTheRecycleBin() = runTest {
        val r = ready("""{"code":0,"data":{"Next":"-1","InfoList":[]}}""")
        val result = r.provider.listTrash(session, PageRequest(limit = 30))
        assertTrue(result is PanResult.Success)

        // 回收站没有独立端点，靠普通列表加 trashed=true 切过去。
        assertEquals("/api/file/list/new", r.server.paths.single())
        val query = r.server.queries.single()
        assertTrue("要带 trashed=true：" + query, query.contains("&trashed=true"))
        assertTrue("网页端靠这个事件名走回收站分支：" + query, query.contains("&event=recycleListFile"))
        assertTrue("按删除时间倒序才是最新删的在前：" + query, query.contains("&orderBy=trashed_at"))
        assertTrue(query.contains("&limit=30"))
    }

    @Test
    fun trashEntryCarriesWhenItWasDeletedAndWhenItExpires() = runTest {
        val r = ready(
            """{"code":0,"data":{"Next":"-1","InfoList":[""" +
                """{"FileId":7,"FileName":"旧报表.xlsx","Type":0,"Size":1024,""" +
                """"TrashedAt":"2026-11-20 12:00:00","TrashedExpire":"2026-12-20 12:00:00"}]}}""",
        )
        val page = (r.provider.listTrash(session, PageRequest()) as PanResult.Success).value
        val item = page.items.single()
        assertEquals("旧报表.xlsx", item.name)
        assertEquals(NodeKind.FILE, item.kind)
        assertEquals(1795147200000L, item.deletedAt)
        assertEquals(1797739200000L, item.expiresAt)
    }

    @Test
    fun entryWithoutTrashedAtFallsBackToTheUpdateTime() = runTest {
        val r = ready(
            """{"code":0,"data":{"Next":"-1","InfoList":[""" +
                """{"FileId":7,"FileName":"旧报表.xlsx","Type":0,"UpdateAt":"2026-11-10 08:30:00"}]}}""",
        )
        val page = (r.provider.listTrash(session, PageRequest()) as PanResult.Success).value
        val item = page.items.single()
        // 早期条目没有 TrashedAt，网页端也是用 UpdateAt 兜底，否则时间那一栏是空的。
        assertEquals(1794270600000L, item.deletedAt)
        assertNull(item.expiresAt)
    }

    @Test
    fun trashListKeepsTheSameCursorRules() = runTest {
        val r = ready("""{"code":0,"data":{"Next":"0","InfoList":[]}}""")
        val page = (r.provider.listTrash(session, PageRequest()) as PanResult.Success).value
        // 与普通列表一致：0 与 -1 都是终止值，只有真游标才能继续翻。
        assertNull(page.nextCursor)
    }

    @Test
    fun restoringUsesTheTrashEndpointWithOperationFalse() = runTest {
        val r = ready("""{"code":0,"data":{}}""")
        val result = r.provider.restoreTrash(session, listOf(NodeId("7"), NodeId("8")))
        assertTrue(result is PanResult.Success)

        assertEquals("/api/file/trash", r.server.paths.single())
        val body = r.server.bodies.single()
        assertTrue("operation:false 才是还原：" + body, body.contains("\"operation\":false"))
        assertTrue(body.contains("{\"fileId\":7},{\"fileId\":8}"))
        assertTrue(body.contains("recycleRestore"))
    }

    @Test
    fun entriesWhoseOriginalFolderIsGoneAreReportedNotSilentlyAccepted() = runTest {
        val r = ready("""{"code":0,"data":{"AbnormalFileIdList":[8]}}""")
        val result = r.provider.restoreTrash(session, listOf(NodeId("7"), NodeId("8")))
        // 服务端把这种情况放在 AbnormalFileIdList 里而不是报错，
        // 只看 code 会告诉用户「已还原」，实际有一项没动。
        assertTrue(result is PanResult.Failure)
        assertTrue((result as PanResult.Failure).error.message.contains("1 项没能还原"))
    }

    @Test
    fun restoringIntoAFolderUsesTheByPathEndpoint() = runTest {
        val r = ready("""{"code":0,"data":{}}""")
        val result = r.provider.restoreTrash(session, listOf(NodeId("7")), NodeId("99"))
        assertTrue(result is PanResult.Success)

        assertEquals("/api/file/recover/by_path", r.server.paths.single())
        val body = r.server.bodies.single()
        assertTrue("按路径还原要带目标目录：" + body, body.contains("\"parentFileId\":99"))
        // 网页端是 fileIds: r.map(e => e.FileId)，FileId 本身就是数字，
        // 所以这里是数字数组，不带引号。
        assertTrue("id 在按路径接口里是数字数组：" + body, body.contains("\"fileIds\":[7]"))
    }

    @Test
    fun purgingFromTheRecycleBinReallyDeletes() = runTest {
        val r = ready("""{"code":0}""")
        val result = r.provider.purgeTrash(session, listOf(NodeId("7")))
        assertTrue(result is PanResult.Success)

        // 回收站里的「彻底删除」才是 file/delete。
        assertEquals("/api/file/delete", r.server.paths.single())
        val body = r.server.bodies.single()
        assertTrue(body.contains("\"fileIdList\":[{\"fileId\":7}]"))
        assertTrue(body.contains("\"operatePlace\":1"))
    }

    @Test
    fun emptyTrashHitsItsOwnEndpoint() = runTest {
        val r = ready("""{"code":0}""")
        val result = r.provider.emptyTrash(session)
        assertTrue(result is PanResult.Success)
        assertEquals("/api/file/trash_delete_all", r.server.paths.single())
    }

    @Test
    fun emptySelectionNeverHitsTheNetwork() = runTest {
        val r = ready()
        assertTrue(r.provider.restoreTrash(session, emptyList()) is PanResult.Success)
        assertTrue(r.provider.purgeTrash(session, emptyList()) is PanResult.Success)
        // 空选中必须就地返回：带着空数组请求服务端，有的接口会当成「全部」。
        assertEquals(0, r.server.paths.size)
    }
}
