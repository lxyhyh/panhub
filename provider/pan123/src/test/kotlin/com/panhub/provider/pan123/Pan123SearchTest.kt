package com.panhub.provider.pan123

import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 123 云盘的文件搜索。
 *
 * 这家的搜索没有独立端点：就是普通列表请求带上 searchData 与 isSearchOrder
 * （见 docs/recon/pan123.md）。所以测试盯的是「参数有没有带全」和
 * 「游标的两个终止值有没有被当成分页」——这两处错了都是静默的：
 * 前者表现为搜出来永远是整个目录，后者表现为一直能点「加载更多」。
 */
class Pan123SearchTest {

    private class ScriptedServer(private val replies: List<String>) : Interceptor {
        val requested = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            // 用 encodedQuery：query 是解码后的，空格与中文都会还原，
            // 看不出编码方式，也就钉不住「%20 而不是 +」这条。
            requested += request.url.encodedPath + "?" + (request.url.encodedQuery ?: "")
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

    private fun provider(server: ScriptedServer) = Pan123Provider(
        HttpEngine(okhttp3.OkHttpClient.Builder().addInterceptor(server).build()),
    )

    private val session = ProviderSession(
        accountId = AccountId("pan123:test"),
        credentials = ProviderCredentials.Token("token"),
    )

    private fun queryOf(server: ScriptedServer): String = server.requested.single()

    @Test
    fun searchSendsKeywordWithSearchSwitch() = runTest {
        val server = ScriptedServer(
            listOf("""{"code":0,"data":{"Next":"-1","InfoList":[]}}"""),
        )
        val result = provider(server).search(session, "报告", PageRequest(limit = 50))
        assertTrue(result is PanResult.Success)

        val query = queryOf(server)
        assertTrue("关键字要编码后放进 searchData：" + query, query.contains("&searchData=%E6%8A%A5%E5%91%8A"))
        // 不带上这两个开关，服务端会当成普通列表，返回整个根目录。
        assertTrue("搜索必须带 isSearchOrder：" + query, query.contains("&isSearchOrder=true"))
        assertTrue("搜索要限定在目录树里：" + query, query.contains("&parentFileId=0"))
        assertTrue(query.contains("&limit=50"))
    }

    @Test
    fun spacesInKeywordArePercentEncodedNotPlus() = runTest {
        val server = ScriptedServer(
            listOf("""{"code":0,"data":{"Next":"-1","InfoList":[]}}"""),
        )
        provider(server).search(session, "my report", PageRequest())
        val query = queryOf(server)
        // 表单编码会把空格变加号，服务端当字面量加号处理，带空格的关键字永远搜不到。
        assertTrue("空格要编成 %20 而不是 +：" + query, query.contains("&searchData=my%20report"))
    }

    @Test
    fun plainListDoesNotClaimToBeASearch() = runTest {
        val server = ScriptedServer(
            listOf("""{"code":0,"data":{"Next":"-1","InfoList":[]}}"""),
        )
        provider(server).list(session, null, PageRequest())
        val query = queryOf(server)
        assertTrue("普通列表不该带搜索开关：" + query, !query.contains("isSearchOrder"))
        assertTrue("没有关键字时 searchData 是空值：" + query, query.contains("&searchData=&"))
    }

    @Test
    fun emptyKeywordDoesNotHitTheNetwork() = runTest {
        val server = ScriptedServer(emptyList())
        val result = provider(server).search(session, "   ", PageRequest())
        assertTrue(result is PanResult.Success)
        assertEquals(0, (result as PanResult.Success).value.items.size)
        assertEquals("空关键字不该发请求", 0, server.requested.size)
    }

    @Test
    fun zeroCursorIsAlsoAnEndOfResults() = runTest {
        val server = ScriptedServer(
            listOf("""{"code":0,"data":{"Next":"0","InfoList":[]}}"""),
        )
        val result = provider(server).search(session, "a", PageRequest())
        val page = (result as PanResult.Success).value
        // 搜索结果为空时服务端回的是 "0"，不是 "-1"。当成游标会让界面白翻一页。
        assertNull("Next 为 0 时不能再翻页", page.nextCursor)
    }

    @Test
    fun realCursorIsKept() = runTest {
        val server = ScriptedServer(
            listOf("""{"code":0,"data":{"Next":"9527","InfoList":[]}}"""),
        )
        val result = provider(server).search(session, "a", PageRequest())
        assertEquals("9527", (result as PanResult.Success).value.nextCursor)
    }

    @Test
    fun searchResultCarriesTheFolderItLivesIn() = runTest {
        val server = ScriptedServer(
            listOf(
                """{"code":0,"data":{"Next":"-1","InfoList":[""" +
                    """{"FileId":7,"FileName":"季度报告.pdf","Type":0,"Size":12,"ParentFileId":42,"ParentName":"财务"}]}}""",
            ),
        )
        val result = provider(server).search(session, "报告", PageRequest())
        val node = (result as PanResult.Success).value.items.single()
        assertEquals("季度报告.pdf", node.name)
        assertEquals(NodeKind.FILE, node.kind)
        // 搜索结果的父目录不在当前列表里，只能靠 ParentName 告诉用户它在哪。
        assertEquals("财务", node.providerPath)
        assertEquals("42", node.parentId?.value)
    }

    @Test
    fun plainListingDoesNotInventAFolderName() = runTest {
        val server = ScriptedServer(
            listOf(
                """{"code":0,"data":{"Next":"-1","InfoList":[""" +
                    """{"FileId":7,"FileName":"季度报告.pdf","Type":0,"Size":12,"ParentFileId":42}]}}""",
            ),
        )
        val result = provider(server).list(session, null, PageRequest())
        val node = (result as PanResult.Success).value.items.single()
        // 普通列表的父目录就是用户正站着的那一格，再写一遍路径是噪音。
        assertNull(node.providerPath)
    }

    @Test
    fun highlightMarkupIsNotUsedAsTheFileName() = runTest {
        val server = ScriptedServer(
            listOf(
                """{"code":0,"data":{"Next":"-1","InfoList":[""" +
                    """{"FileId":7,"FileName":"季度报告.pdf","HighLight":"<em>季度</em>报告.pdf","Type":0}]}}""",
            ),
        )
        val result = provider(server).search(session, "季度", PageRequest())
        val node = (result as PanResult.Success).value.items.single()
        // HighLight 是带标记的同一份文件名，直接拿来用列表里会看到一堆标记串。
        assertEquals("季度报告.pdf", node.name)
    }
}
