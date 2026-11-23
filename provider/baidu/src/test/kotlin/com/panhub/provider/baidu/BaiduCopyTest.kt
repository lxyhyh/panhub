package com.panhub.provider.baidu

import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.RemoteNode
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * 百度网盘的复制。
 *
 * 复制的形状与移动几乎一样，只有两处不同：opera 是 copy，ondup 是 newcopy。
 * 这两处错了不会报错，只会静默失败（同名被拒、或者副本落到预期之外的地方），
 * 所以钉死在测试里。
 */
class BaiduCopyTest {

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

    private fun file(path: String) = RemoteNode(
        id = NodeId(path),
        parentId = null,
        name = path.trimEnd('/').substringAfterLast('/'),
        kind = NodeKind.FILE,
    )

    @Test
    fun copyUsesTheCopyOperaOnTheSameEndpointAsMove() = runTest {
        val recorder = Recorder()
        val result = provider(recorder).copy(session(), file("/docs/a.txt"), NodeId("/backup"))

        assertTrue(result is PanResult.Success)
        assertEquals("/rest/2.0/xpan/file", recorder.paths.single())
        assertTrue(
            "query 里要有 opera=copy，实际是 " + recorder.queries.single(),
            recorder.queries.single().contains("opera=copy"),
        )
    }

    @Test
    fun copyAllowsADuplicateName() = runTest {
        // 移动用 ondup=fail（同名就该报错，否则等于偷改名），
        // 复制则应该让服务端另起一个副本。
        val recorder = Recorder()
        provider(recorder).copy(session(), file("/docs/a.txt"), NodeId("/backup"))

        assertTrue(
            "表单里要有 ondup=newcopy，实际是 " + recorder.forms.single(),
            recorder.forms.single().contains("ondup=newcopy"),
        )
    }

    @Test
    fun copySendsPathDestinationAndName() = runTest {
        val recorder = Recorder()
        provider(recorder).copy(session(), file("/docs/a.txt"), NodeId("/backup"))

        val form = recorder.forms.single()
        assertTrue("缺 path：" + form, form.contains("\"path\":\"/docs/a.txt\""))
        assertTrue("缺 dest：" + form, form.contains("\"dest\":\"/backup\""))
        assertTrue("缺 newname：" + form, form.contains("\"newname\":\"a.txt\""))
    }

    @Test
    fun copyToRootUsesTheRootPath() = runTest {
        val recorder = Recorder()
        provider(recorder).copy(session(), file("/docs/a.txt"), null)

        val form = recorder.forms.single()
        assertTrue("目标为根目录时 dest 应该是 /，实际是 " + form, form.contains("\"dest\":\"/\""))
    }

    @Test
    fun moveStillFailsOnDuplicateName() = runTest {
        // 回归用例：把 ondup 提成参数之后，移动必须仍然是 fail。
        val recorder = Recorder()
        provider(recorder).move(session(), NodeId("/docs/a.txt"), NodeId("/backup"))

        assertTrue(
            "移动要用 ondup=fail，实际是 " + recorder.forms.single(),
            recorder.forms.single().contains("ondup=fail"),
        )
    }

    @Test
    fun copyReportsServerError() = runTest {
        val recorder = Recorder("{\"errno\":2,\"show_msg\":\"空间不足\"}")
        val result = provider(recorder).copy(session(), file("/docs/a.txt"), NodeId("/backup"))

        assertEquals("空间不足", (result as PanResult.Failure).error.message)
    }
}
