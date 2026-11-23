package com.panhub.core.preview

import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.NodePage
import com.panhub.core.model.PageRequest
import com.panhub.core.model.ProviderCapabilities
import com.panhub.core.model.ProviderDescriptor
import com.panhub.core.model.ProviderId
import com.panhub.core.model.Quota
import com.panhub.core.model.RemoteNode
import com.panhub.core.model.ShareLink
import com.panhub.core.model.ShareOptions
import com.panhub.core.model.SharePage
import com.panhub.core.network.HttpEngine
import com.panhub.provider.api.DownloadTarget
import com.panhub.provider.api.PanProvider
import com.panhub.provider.api.ProviderCredentials
import com.panhub.provider.api.ProviderSession
import com.panhub.provider.api.ScopedPanProvider
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpPreviewLoaderTest {

    /** 记录请求并回放一段字节。 */
    private class ScriptedServer(
        private val body: ByteArray,
        private val code: Int = 200,
    ) : Interceptor {
        val requests = mutableListOf<okhttp3.Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("OK")
                .body(body.toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }
    }

    private class FakeProvider(private val url: PanResult<DownloadTarget>) : PanProvider {
        override val descriptor = ProviderDescriptor(
            id = ProviderId("fake"),
            displayName = "假网盘",
            capabilities = ProviderCapabilities(list = true, download = true),
        )

        override suspend fun login(credentials: ProviderCredentials) = error("不该调用")

        override suspend fun verify(session: ProviderSession) = PanResult.Success(Unit)

        override suspend fun list(
            session: ProviderSession,
            parentId: NodeId?,
            page: PageRequest,
        ): PanResult<NodePage> = PanResult.Success(NodePage(emptyList()))

        override suspend fun stat(session: ProviderSession, nodeId: NodeId) = error("不该调用")

        override suspend fun quota(session: ProviderSession): PanResult<Quota> = error("不该调用")

        override suspend fun createFolder(
            session: ProviderSession,
            parentId: NodeId?,
            name: String,
        ) = error("不该调用")

        override suspend fun rename(
            session: ProviderSession,
            nodeId: NodeId,
            newName: String,
        ) = error("不该调用")

        override suspend fun move(
            session: ProviderSession,
            nodeId: NodeId,
            newParentId: NodeId?,
        ) = error("不该调用")

        override suspend fun delete(session: ProviderSession, nodeId: NodeId) = error("不该调用")

        override suspend fun downloadUrl(session: ProviderSession, nodeId: NodeId) = url

        override suspend fun createShare(
            session: ProviderSession,
            nodeIds: List<NodeId>,
            options: ShareOptions,
        ): PanResult<ShareLink> = error("不该调用")

        override suspend fun cancelShare(session: ProviderSession, shareId: String) = error("不该调用")

        override suspend fun listShares(
            session: ProviderSession,
            page: PageRequest,
        ): PanResult<SharePage> = error("不该调用")
    }

    private val accountId = AccountId("fake:1")
    private val nodeId = NodeId("file-1")

    private fun sessionOf(provider: PanProvider) = ProviderSession(
        accountId = accountId,
        credentials = ProviderCredentials.Token("t"),
    )

    private fun loader(server: ScriptedServer, url: PanResult<DownloadTarget>): HttpPreviewLoader {
        val provider = FakeProvider(url)
        val engine = HttpEngine(OkHttpClient.Builder().addInterceptor(server).build())
        return HttpPreviewLoader(engine) {
            PanResult.Success(ScopedPanProvider(provider, sessionOf(provider)))
        }
    }

    @Test
    fun imageBytesAreReturnedAsIs() = runTest {
        val body = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val server = ScriptedServer(body)
        val result = loader(server, PanResult.Success(DownloadTarget("https://x/a.png")))
            .load(accountId, nodeId, PreviewKind.IMAGE)

        assertTrue(result is PanResult.Success)
        assertEquals(5, (result as PanResult.Success).value.bytes?.size)
    }

    @Test
    fun rangeHeaderAsksOnlyForTheFirstChunk() = runTest {
        val server = ScriptedServer(byteArrayOf(1, 2))
        loader(server, PanResult.Success(DownloadTarget("https://x/a.txt")))
            .load(accountId, nodeId, PreviewKind.TEXT)

        // 预览不该把整个文件拉下来，所以一定要带 Range。
        assertEquals(
            "bytes=0-" + (PreviewLimits.MAX_TEXT_BYTES - 1),
            server.requests.single().header("Range"),
        )
    }

    @Test
    fun downloadHeadersFromTheProviderAreForwarded() = runTest {
        val server = ScriptedServer(byteArrayOf(1))
        val target = DownloadTarget("https://x/a.txt", headers = mapOf("Referer" to "https://pan"))
        loader(server, PanResult.Success(target)).load(accountId, nodeId, PreviewKind.TEXT)

        assertEquals("https://pan", server.requests.single().header("Referer"))
    }

    @Test
    fun bodyLongerThanTheLimitIsMarkedTruncated() = runTest {
        // 服务端无视 Range 时，客户端必须自己停下来并说明内容不全。
        val body = ByteArray((PreviewLimits.MAX_TEXT_BYTES + 10).toInt()) { 'a'.code.toByte() }
        val server = ScriptedServer(body)
        val result = loader(server, PanResult.Success(DownloadTarget("https://x/a.txt")))
            .load(accountId, nodeId, PreviewKind.TEXT)

        val content = (result as PanResult.Success).value
        assertEquals(PreviewLimits.MAX_TEXT_BYTES.toInt(), content.text?.length)
        assertTrue(content.truncated)
    }

    @Test
    fun knownSizeBiggerThanReadBytesIsMarkedTruncated() = runTest {
        val server = ScriptedServer(byteArrayOf('a'.code.toByte()))
        val result = loader(server, PanResult.Success(DownloadTarget("https://x/a.txt")))
            .load(accountId, nodeId, PreviewKind.TEXT, knownSize = 1_000L)

        assertTrue((result as PanResult.Success).value.truncated)
    }

    @Test
    fun serverErrorBecomesNetworkFailure() = runTest {
        val server = ScriptedServer(ByteArray(0), code = 500)
        val result = loader(server, PanResult.Success(DownloadTarget("https://x/a.txt")))
            .load(accountId, nodeId, PreviewKind.TEXT)

        assertTrue(result is PanResult.Failure)
    }

    @Test
    fun unsupportedKindNeverTouchesTheNetwork() = runTest {
        val server = ScriptedServer(byteArrayOf(1))
        val result = loader(server, PanResult.Success(DownloadTarget("https://x/a.bin")))
            .load(accountId, nodeId, PreviewKind.NONE)

        assertTrue(result is PanResult.Failure)
        assertEquals(0, server.requests.size)
    }

    @Test
    fun downloadUrlFailureIsPropagated() = runTest {
        val server = ScriptedServer(byteArrayOf(1))
        val failure = PanResult.Failure(PanError.auth())
        val result = loader(server, failure).load(accountId, nodeId, PreviewKind.TEXT)

        assertTrue(result is PanResult.Failure)
        assertEquals(0, server.requests.size)
    }
}
