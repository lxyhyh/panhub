package com.panhub.provider.baidu

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * 百度网盘的回收站。
 *
 * 三个端点都来自 APK dex（classes15.dex 里有 recycle/list、recycle/restore、
 * recycle/clear、recycle/delete 四个字符串），请求参数与 cssxsh/baidu-client
 * 的开源实现一致。这里钉住容易错的三处：fidlist 的形状、faillist 的检查、
 * 「还原到指定目录」必须明确拒绝而不是假装成功。
 */
class BaiduTrashTest {

    private class Recorder(private val body: String = "{\"errno\":0}") : Interceptor {
        val paths = mutableListOf<String>()
        val queries = mutableListOf<String>()
        val forms = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            paths += request.url.encodedPath
            queries += request.url.query.orEmpty()
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            forms += URLDecoder.decode(buffer.readUtf8(), "UTF-8")
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun provider(recorder: Recorder) = BaiduProvider(
        engine = HttpEngine(okhttp3.OkHttpClient.Builder().addInterceptor(recorder).build()),
    )

    private fun session() = ProviderSession(
        accountId = AccountId("baidu:1"),
        credentials = ProviderCredentials.Token("BDUSS=abc; STOKEN=def"),
        displayName = "百度网盘",
    )

    @Test
    fun listHitsTheRecycleListEndpointWithPaging() = runTest {
        val body = "{\"errno\":0,\"list\":[{\"fs_id\":281730,\"path\":\"/docs/a.txt\",\"server_filename\":\"a.txt\",\"size\":32,\"isdir\":0,\"server_mtime\":1700000000,\"leftTime\":86400}]}"
        val recorder = Recorder(body)
        val result = provider(recorder).listTrash(session(), PageRequest(limit = 100))

        assertTrue(result is PanResult.Success)
        assertEquals("/api/recycle/list", recorder.paths.single())
        assertTrue(recorder.queries.single().contains("page=1"))
        assertTrue(recorder.queries.single().contains("num=100"))
        val page = (result as PanResult.Success).value
        assertEquals(1, page.items.size)
        assertEquals("a.txt", page.items.single().name)
        // leftTime 是「还剩多少秒」，映射成将来的时刻；不自动清理的没有时刻。
        assertTrue(page.items.single().expiresAt != null)
    }

    @Test
    fun noAutoCleanupMeansNoExpiry() = runTest {
        val body = "{\"errno\":0,\"list\":[{\"fs_id\":1,\"path\":\"/a\",\"leftTime\":0}]}"
        val recorder = Recorder(body)
        val result = provider(recorder).listTrash(session(), PageRequest())

        val page = (result as PanResult.Success).value
        assertNull(page.items.single().expiresAt)
    }

    @Test
    fun restoreSendsFsIdArrayOnTheRestoreEndpoint() = runTest {
        val recorder = Recorder()
        val result = provider(recorder).restoreTrash(
            session(),
            listOf(NodeId("281730"), NodeId("281731")),
        )

        assertTrue(result is PanResult.Success)
        assertEquals("/api/recycle/restore", recorder.paths.single())
        assertTrue(
            "表单里要有 fidlist 数组：" + recorder.forms.single(),
            recorder.forms.single().contains("fidlist=[281730, 281731]"),
        )
    }

    @Test
    fun restoreToAFolderIsRejectedUpFront() = runTest {
        val recorder = Recorder()
        val result = provider(recorder).restoreTrash(
            session(),
            listOf(NodeId("281730")),
            parentId = NodeId("/elsewhere"),
        )

        assertTrue(result is PanResult.Failure)
        // 请求都不该发：这个接口没有指定目录的能力。
        assertTrue(recorder.paths.isEmpty())
    }

    @Test
    fun failedIdsAreNotReportedAsSuccess() = runTest {
        val recorder = Recorder("{\"errno\":0,\"faillist\":[281731]}")
        val result = provider(recorder).restoreTrash(session(), listOf(NodeId("281731")))

        // 服务端把部分失败也算 errno 0，必须自己查 faillist。
        assertTrue(result is PanResult.Failure)
    }

    @Test
    fun purgeHitsTheRecycleDeleteEndpoint() = runTest {
        val recorder = Recorder()
        val result = provider(recorder).purgeTrash(session(), listOf(NodeId("281730")))

        assertTrue(result is PanResult.Success)
        assertEquals("/api/recycle/delete", recorder.paths.single())
    }

    @Test
    fun emptyCallsTheClearEndpointWithoutFidlist() = runTest {
        val recorder = Recorder()
        val result = provider(recorder).emptyTrash(session())

        assertTrue(result is PanResult.Success)
        assertEquals("/api/recycle/clear", recorder.paths.single())
        assertTrue(
            "清空不带 fidlist：" + recorder.forms.single(),
            !recorder.forms.single().contains("fidlist"),
        )
    }

    @Test
    fun nonNumericNodeIdIsRejectedWithoutNetwork() = runTest {
        val recorder = Recorder()
        val result = provider(recorder).purgeTrash(session(), listOf(NodeId("/some/path")))

        // 百度回收站用 fs_id 寻址；路径进来说明上游把节点搞混了，直接拒绝。
        assertTrue(result is PanResult.Failure)
        assertTrue(recorder.paths.isEmpty())
    }
}