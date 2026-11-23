package com.panhub.provider.cloud189

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
 * 天翼云盘的复制。
 *
 * 复制、移动、删除共用一个批量任务端点，靠 type 区分。这里守的是三件事：
 * type 是 COPY、taskInfos 里带的是**真实文件名**（服务端拿它给副本命名）、
 * 目标目录要换成裸 id（天翼的节点 id 带 folder:/file: 前缀）。
 */
class Cloud189CopyTest {

    private class Recorder : Interceptor {
        val paths = mutableListOf<String>()
        val forms = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            paths += request.url.encodedPath
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            forms += URLDecoder.decode(buffer.readUtf8(), "UTF-8")
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{\"res_code\":0}".toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun provider(recorder: Recorder) =
        Cloud189Provider(HttpEngine(okhttp3.OkHttpClient.Builder().addInterceptor(recorder).build()))

    private fun session() = ProviderSession(
        accountId = AccountId("cloud189:1"),
        credentials = ProviderCredentials.Token("COOKIE=abc"),
        displayName = "天翼云盘",
    )

    private fun folder(id: String, name: String) = RemoteNode(
        id = NodeId("folder:" + id),
        parentId = null,
        name = name,
        kind = NodeKind.DIR,
    )

    @Test
    fun copyUsesTheCopyType() = runTest {
        val recorder = Recorder()
        val result = provider(recorder).copy(session(), folder("111", "照片"), NodeId("folder:222"))

        assertTrue(result is PanResult.Success)
        assertEquals("/api/open/batch/createBatchTask.action", recorder.paths.single())
        val form = recorder.forms.single()
        assertTrue("type 应该是 COPY：" + form, form.contains("type=COPY"))
    }

    @Test
    fun copySendsTheRealFileNameNotTheId() = runTest {
        // 之前的 move/delete 版把 fileName 传成了节点 id，复制时这会造出
        // 一个名字像 "folder:111" 的副本。复制必须用真实名字。
        val recorder = Recorder()
        provider(recorder).copy(session(), folder("111", "照片"), NodeId("folder:222"))

        val form = recorder.forms.single()
        assertTrue("fileName 应该是真实名字：" + form, form.contains("\"fileName\":\"照片\""))
        assertTrue("fileId 应该是裸 id：" + form, form.contains("\"fileId\":\"111\""))
    }

    @Test
    fun copyStripsTheFolderPrefixFromTheTarget() = runTest {
        val recorder = Recorder()
        provider(recorder).copy(session(), folder("111", "照片"), NodeId("folder:222"))

        val form = recorder.forms.single()
        assertTrue("targetFolderId 应该是裸 id：" + form, form.contains("targetFolderId=222"))
        assertTrue("不该把前缀带上：" + form, !form.contains("folder:222"))
    }

    @Test
    fun copyToRootUsesTheRootFolderId() = runTest {
        val recorder = Recorder()
        provider(recorder).copy(session(), folder("111", "照片"), null)

        assertTrue(recorder.forms.single().contains("targetFolderId=-11"))
    }

    @Test
    fun folderFlagFollowsTheNodeKind() = runTest {
        val recorder = Recorder()
        provider(recorder).copy(session(), folder("111", "照片"), NodeId("folder:222"))

        assertTrue(recorder.forms.single().contains("\"isFolder\":1"))
    }

    @Test
    fun copyReportsServerError() = runTest {
        val failing = object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response = Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    "{\"res_code\":\"ShareFileLimit\",\"res_message\":\"不能复制\"}"
                        .toResponseBody("application/json".toMediaType())
                )
                .build()
        }
        val provider = Cloud189Provider(
            HttpEngine(okhttp3.OkHttpClient.Builder().addInterceptor(failing).build()),
        )
        val result = provider.copy(session(), folder("111", "照片"), NodeId("folder:222"))

        assertEquals("不能复制", (result as PanResult.Failure).error.message)
    }
}
