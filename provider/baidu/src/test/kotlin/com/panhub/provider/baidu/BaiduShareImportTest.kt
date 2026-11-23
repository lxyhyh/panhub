package com.panhub.provider.baidu

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
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打开百度网盘分享 / 列目录 / 转存的端到端测试。
 *
 * 用假 Interceptor 顶替网络，按脚本回放服务端响应并记录请求。
 * 这几条用例守的是实测里最容易回退的约定：
 * 短链接口要带首位 1、提取码接口要去掉首位 1 且必须带分享页 Referer、
 * 分享列表的字段类型会从字符串变成数字、带密码的分享要把 randsk 写进 Cookie。
 */
class BaiduShareImportTest {

    private class ScriptedServer(private val replies: List<String>) : Interceptor {
        val requested = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val referers = mutableListOf<String>()
        val cookies = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requested += request.method + " " + request.url.encodedPath + "?" + (request.url.query ?: "")
            referers += request.header("Referer").orEmpty()
            cookies += request.header("Cookie").orEmpty()
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

        private fun readBody(request: Request): String {
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            return buffer.readUtf8()
        }
    }

    private fun importer(server: ScriptedServer) =
        BaiduShareImporter(BaiduApi(HttpEngine(okhttp3.OkHttpClient.Builder().addInterceptor(server).build())))

    private val shareInfo =
        """{"errno":0,"shareid":4110700678,"uk":2081241288,"title":"/资料","root":1}"""

    /** 根目录的条目字段是字符串，子目录是数字——两种形态都要能读。 */
    private val rootPage =
        """{"errno":0,"title":"/资料","share_id":4110700678,"uk":2081241288,"cur_total":2,"list":[""" +
            """{"fs_id":"257047345573339","server_filename":"报告.pdf","isdir":"0","size":"11377587",""" +
            """"path":"/资料/报告.pdf","server_mtime":"1700000000"},""" +
            """{"fs_id":"257047345573340","server_filename":"相册","isdir":"1","size":"0","path":"/资料/相册"}""" +
            """]}"""

    private val childPage =
        """{"errno":0,"list":[{"fs_id":11,"server_filename":"a.txt","isdir":0,"size":12,""" +
            """"path":"/资料/相册/a.txt","server_mtime":1700000001}]}"""

    @Test
    fun openReturnsShareIdAndTicket() = runTest {
        val server = ScriptedServer(listOf(shareInfo, rootPage))
        val result = importer(server).open("FOO=bar", "https://pan.baidu.com/s/1pJ0ok7T", null)
        assertTrue(result is PanResult.Success)
        val source = (result as PanResult.Success).value
        assertEquals("4110700678", source.linkId)
        assertEquals("2081241288|", source.token)
        assertEquals("/资料", source.title)
        // 短链接口要带首位 1 的完整 surl。
        assertTrue(server.requested[0], server.requested[0].contains("shorturl=1pJ0ok7T"))
    }

    @Test
    fun passcodeIsVerifiedWithShortSurlAndReferer() = runTest {
        val server = ScriptedServer(
            listOf(
                """{"errno":-9,"shareid":792398393,"uk":2081241288}""",
                """{"errno":0,"randsk":"u%2FwYDXnX1ErKHO6fqAO9J266ytwQQ6CbYmhnDKHjmNU%3D"}""",
                """{"errno":0,"list":[]}""",
            ),
        )
        val result = importer(server).open(
            "FOO=bar",
            "https://pan.baidu.com/s/1sleTpgT",
            "xc5j",
        )
        assertTrue(result is PanResult.Success)
        val source = (result as PanResult.Success).value
        assertEquals(
            "2081241288|u%2FwYDXnX1ErKHO6fqAO9J266ytwQQ6CbYmhnDKHjmNU%3D",
            source.token,
        )
        // 提取码接口要短形态 surl（去掉首位 1）。
        assertTrue(server.requested[1], server.requested[1].contains("surl=sleTpgT"))
        // 不带分享页 Referer 一律 errno 2。
        assertEquals("https://pan.baidu.com/s/1sleTpgT", server.referers[1])
        assertTrue(server.bodies[1].contains("pwd=xc5j"))
        // 列目录要带上 randsk，否则带密码的分享列不出来。
        assertTrue(server.cookies[2].contains("BDCLND="))
    }

    @Test
    fun cancelledShareFailsBeforeListing() = runTest {
        val server = ScriptedServer(
            listOf("""{"errno":-21,"show_msg":"来晚啦，该分享已被取消","shareid":1,"uk":2}"""),
        )
        val result = importer(server).open("FOO=bar", "https://pan.baidu.com/s/1pJ0ok7T", null)
        assertTrue(result is PanResult.Failure)
        assertEquals(1, server.requested.size)
    }

    @Test
    fun shareWithoutPasscodeButCodeRequiredFails() = runTest {
        val server = ScriptedServer(
            listOf("""{"errno":-9,"shareid":792398393,"uk":2081241288}"""),
        )
        val result = importer(server).open("FOO=bar", "https://pan.baidu.com/s/1sleTpgT", null)
        assertTrue(result is PanResult.Failure)
        assertTrue(
            (result as PanResult.Failure).error.message.contains("提取码"),
        )
    }

    @Test
    fun listMapsBothStringAndNumberFields() = runTest {
        val server = ScriptedServer(listOf(rootPage))
        val source = ShareSource(linkId = "4110700678", token = "2081241288|")
        val result = importer(server).list("FOO=bar", source, null, PageRequest(limit = 100))
        assertTrue(result is PanResult.Success)
        val page = (result as PanResult.Success).value
        assertEquals(2, page.items.size)
        val file = page.items[0]
        assertEquals("/资料/报告.pdf", file.id.value)
        assertEquals(NodeKind.FILE, file.kind)
        assertEquals(11377587L, file.size)
        assertEquals("257047345573339", file.ticket)
        assertEquals(1_700_000_000_000L, file.modifiedAt)
        val folder = page.items[1]
        assertEquals(NodeKind.DIR, folder.kind)
        assertEquals(0L, folder.size)
        // 根目录只能发 root=1，dir=/ 会 errno 2。
        assertTrue(server.requested[0], server.requested[0].contains("root=1"))
        assertTrue(server.requested[0], !server.requested[0].contains("dir="))
    }

    @Test
    fun subDirectoryUsesAbsolutePath() = runTest {
        val server = ScriptedServer(listOf(childPage))
        val source = ShareSource(linkId = "4110700678", token = "2081241288|")
        val result = importer(server).list(
            "FOO=bar",
            source,
            NodeId("/资料/相册"),
            PageRequest(limit = 100),
        )
        assertTrue(result is PanResult.Success)
        assertTrue(server.requested[0], server.requested[0].contains("dir="))
        assertTrue(server.requested[0], !server.requested[0].contains("root=1"))
        val page = (result as PanResult.Success).value
        assertEquals(12L, page.items[0].size)
    }

    @Test
    fun fullPageMeansThereIsMore() = runTest {
        val server = ScriptedServer(listOf(rootPage, childPage))
        val source = ShareSource(linkId = "4110700678", token = "2081241288|")
        val first = importer(server).list("FOO=bar", source, null, PageRequest(limit = 2))
        assertEquals("2", (first as PanResult.Success).value.nextCursor)
        val second = importer(server).list("FOO=bar", source, null, PageRequest(limit = 100))
        assertNull((second as PanResult.Success).value.nextCursor)
    }

    @Test
    fun savePostsFsidListAndTargetPath() = runTest {
        val server = ScriptedServer(
            listOf(
                """{"errno":0,"result":[{"bdstoken":"tok-1"}]}""",
                """{"errno":0,"info":[{"errno":0,"from_fs_id":11,"to_fs_id":99}]}""",
            ),
        )
        val source = ShareSource(linkId = "4110700678", token = "2081241288|")
        val entries = listOf(
            ShareEntry(id = NodeId("/资料/a.txt"), name = "a.txt", kind = NodeKind.FILE, ticket = "11"),
        )
        val result = importer(server).save("BDUSS=x", source, entries, NodeId("/备份"))
        assertTrue(result is PanResult.Success)
        assertEquals(1, (result as PanResult.Success).value)
        assertTrue(server.requested[1], server.requested[1].contains("/share/transfer?"))
        assertTrue(server.requested[1], server.requested[1].contains("from=2081241288"))
        assertTrue(server.requested[1], server.requested[1].contains("shareid=4110700678"))
        assertTrue(server.requested[1], server.requested[1].contains("bdstoken=tok-1"))
        assertTrue(server.bodies[1], server.bodies[1].contains("fsidlist="))
        assertTrue(server.bodies[1], server.bodies[1].contains("path=%2F%E5%A4%87%E4%BB%BD"))
    }

    @Test
    fun saveToRootUsesSlash() = runTest {
        val server = ScriptedServer(
            listOf(
                """{"errno":0,"result":[{"bdstoken":"tok-1"}]}""",
                """{"errno":0,"info":[{"errno":0}]}""",
            ),
        )
        val source = ShareSource(linkId = "4110700678", token = "2081241288|")
        val entries = listOf(
            ShareEntry(id = NodeId("/a.txt"), name = "a.txt", kind = NodeKind.FILE, ticket = "11"),
        )
        importer(server).save("BDUSS=x", source, entries, null)
        assertTrue(server.bodies[1], server.bodies[1].contains("path=%2F"))
    }

    @Test
    fun partialFailureCountsOnlySavedItems() = runTest {
        val server = ScriptedServer(
            listOf(
                """{"errno":0,"result":[{"bdstoken":"tok-1"}]}""",
                """{"errno":0,"info":[{"errno":0},{"errno":-8,"errmsg":"文件已存在"}]}""",
            ),
        )
        val source = ShareSource(linkId = "4110700678", token = "2081241288|")
        val entries = listOf(
            ShareEntry(id = NodeId("/a.txt"), name = "a.txt", kind = NodeKind.FILE, ticket = "11"),
            ShareEntry(id = NodeId("/b.txt"), name = "b.txt", kind = NodeKind.FILE, ticket = "12"),
        )
        val result = importer(server).save("BDUSS=x", source, entries, null)
        assertEquals(1, (result as PanResult.Success).value)
    }

    @Test
    fun allItemsFailedReportsServerMessage() = runTest {
        val server = ScriptedServer(
            listOf(
                """{"errno":0,"result":[{"bdstoken":"tok-1"}]}""",
                """{"errno":0,"info":[{"errno":-8,"errmsg":"文件已存在"}]}""",
            ),
        )
        val source = ShareSource(linkId = "4110700678", token = "2081241288|")
        val entries = listOf(
            ShareEntry(id = NodeId("/a.txt"), name = "a.txt", kind = NodeKind.FILE, ticket = "11"),
        )
        val result = importer(server).save("BDUSS=x", source, entries, null)
        assertTrue(result is PanResult.Failure)
        assertEquals("文件已存在", (result as PanResult.Failure).error.message)
    }

    @Test
    fun emptySelectionIsRejected() = runTest {
        val server = ScriptedServer(emptyList())
        val source = ShareSource(linkId = "4110700678", token = "2081241288|")
        val result = importer(server).save("BDUSS=x", source, emptyList(), null)
        assertTrue(result is PanResult.Failure)
        assertEquals(0, server.requested.size)
    }

    @Test
    fun missingBdstokenFails() = runTest {
        val server = ScriptedServer(listOf("""{"errno":0,"result":[]}"""))
        val source = ShareSource(linkId = "4110700678", token = "2081241288|")
        val entries = listOf(
            ShareEntry(id = NodeId("/a.txt"), name = "a.txt", kind = NodeKind.FILE, ticket = "11"),
        )
        val result = importer(server).save("BDUSS=x", source, entries, null)
        assertTrue(result is PanResult.Failure)
    }
}
