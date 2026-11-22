package com.panhub.core.transfer

import com.panhub.core.common.Clock
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodePage
import com.panhub.core.model.PageRequest
import com.panhub.core.model.ProviderCapabilities
import com.panhub.core.model.ProviderDescriptor
import com.panhub.core.model.ProviderId
import com.panhub.core.model.Quota
import com.panhub.core.model.RemoteNode
import com.panhub.core.model.ShareLink
import com.panhub.core.model.ShareOptions
import com.panhub.provider.api.DownloadTarget
import com.panhub.provider.api.PanProvider
import com.panhub.provider.api.ProviderCredentials
import com.panhub.provider.api.ProviderSession
import com.panhub.provider.api.ScopedPanProvider
import com.panhub.provider.api.UploadSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 上传路径的测试。
 *
 * 单独一个文件而不是塞进 TransferEngineTest：下载与上传共用一个状态机，
 * 但**失败原因完全不同**（找不到本地文件、provider 不支持上传），
 * 分开写让每个失败场景更容易定位。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferUploadTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val account = AccountId("acc")

    /** 记录调用参数的假 provider；[uploadResult] 决定上传成功还是失败。 */
    private class UploadProvider(
        var uploadResult: PanResult<RemoteNode> = PanResult.Failure(PanError.notSupported()),
    ) : PanProvider {
        var lastParentId: NodeId? = null
        var lastName: String? = null
        var lastSize: Long = -1L
        var lastContent: String? = null
        var progressCalls = 0

        override val descriptor = ProviderDescriptor(
            id = ProviderId("fake"),
            displayName = "Fake",
            capabilities = ProviderCapabilities(upload = true),
        )

        override suspend fun login(credentials: ProviderCredentials) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun verify(session: ProviderSession) = PanResult.Success(Unit)

        override suspend fun list(session: ProviderSession, parentId: NodeId?, page: PageRequest) =
            PanResult.Success(NodePage(emptyList()))

        override suspend fun stat(session: ProviderSession, nodeId: NodeId) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun quota(session: ProviderSession) = PanResult.Success(Quota(0, null))

        override suspend fun createFolder(
            session: ProviderSession,
            parentId: NodeId?,
            name: String,
        ) = PanResult.Failure(PanError.notSupported())

        override suspend fun rename(session: ProviderSession, nodeId: NodeId, newName: String) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun move(session: ProviderSession, nodeId: NodeId, newParentId: NodeId?) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun delete(session: ProviderSession, nodeId: NodeId) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun downloadUrl(session: ProviderSession, nodeId: NodeId) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun createShare(
            session: ProviderSession,
            nodeIds: List<NodeId>,
            options: ShareOptions,
        ) = PanResult.Failure(PanError.notSupported())

        override suspend fun cancelShare(session: ProviderSession, shareId: String) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun upload(
            session: ProviderSession,
            parentId: NodeId?,
            source: UploadSource,
            listener: PanProvider.UploadProgress,
        ): PanResult<RemoteNode> {
            lastParentId = parentId
            lastName = source.name
            lastSize = source.size
            lastContent = source.openStream().reader().readText()
            listener.onProgress(1L, source.size)
            progressCalls++
            return uploadResult
        }
    }

    private fun engine(scope: TestScope, provider: PanProvider): TransferEngine {
        val session = ProviderSession(
            accountId = account,
            credentials = ProviderCredentials.Token("t"),
        )
        return TransferEngine(
            scope = scope,
            sessionProvider = { PanResult.Success(ScopedPanProvider(provider, session)) },
            downloader = object : FileDownloader {
                override suspend fun download(
                    target: DownloadTarget,
                    destination: File,
                    listener: ProgressListener,
                ): PanResult<Unit> = PanResult.Success(Unit)
            },
            sink = DirectorySink(temp.newFolder("out")),
            clock = Clock { 500L },
        )
    }

    private fun node(name: String = "报告.pdf"): RemoteNode = RemoteNode(
        id = NodeId("new-1"),
        parentId = NodeId("folder-9"),
        name = name,
        kind = com.panhub.core.model.NodeKind.FILE,
        size = 3L,
    )

    @Test
    fun uploadReadsLocalFileAndReportsProgress() = runTest {
        val local = temp.newFile("a.txt").apply { writeText("abc") }
        val provider = UploadProvider(PanResult.Success(node()))
        val engine = engine(this, provider)

        val id = engine.upload(account, NodeId("folder-9"), local.absolutePath, "报告.pdf", 3L)
        advanceUntilIdle()

        val task = engine.tasks.value.single()
        assertEquals(id, task.id)
        assertEquals(TransferDirection.UPLOAD, task.direction)
        assertEquals(TransferState.COMPLETED, task.state)
        assertEquals("abc", provider.lastContent)
        assertEquals("报告.pdf", provider.lastName)
        assertEquals(NodeId("folder-9"), provider.lastParentId)
        assertEquals(1, provider.progressCalls)
        assertEquals(3L, task.bytes)
    }

    @Test
    fun uploadFailsWhenLocalFileMissing() = runTest {
        val provider = UploadProvider(PanResult.Success(node()))
        val engine = engine(this, provider)

        engine.upload(account, null, "/no/such/file.bin", "x.bin", 10L)
        advanceUntilIdle()

        val task = engine.tasks.value.single()
        assertEquals(TransferState.FAILED, task.state)
        assertEquals("本地文件已不存在", task.error)
        assertEquals(0, provider.progressCalls)
    }

    @Test
    fun uploadFailureFromProviderMarksTaskFailed() = runTest {
        val local = temp.newFile("b.txt").apply { writeText("xy") }
        val provider = UploadProvider(PanResult.Failure(PanError.notSupported("该网盘不支持上传")))
        val engine = engine(this, provider)

        engine.upload(account, NodeId("folder-9"), local.absolutePath, "b.txt", 2L)
        advanceUntilIdle()

        val task = engine.tasks.value.single()
        assertEquals(TransferState.FAILED, task.state)
        assertEquals("该网盘不支持上传", task.error)
    }

    @Test
    fun emptyParentIdMeansRoot() = runTest {
        val local = temp.newFile("c.txt").apply { writeText("z") }
        val provider = UploadProvider(PanResult.Success(node()))
        val engine = engine(this, provider)

        engine.upload(account, null, local.absolutePath, "c.txt", 1L)
        advanceUntilIdle()

        assertEquals(null, provider.lastParentId)
        assertEquals(TransferState.COMPLETED, engine.tasks.value.single().state)
    }

    @Test
    fun uploadAndDownloadShareOneQueue() = runTest {
        val local = temp.newFile("d.txt").apply { writeText("q") }
        val provider = UploadProvider(PanResult.Success(node()))
        val engine = engine(this, provider)

        engine.upload(account, null, local.absolutePath, "d.txt", 1L)
        advanceUntilIdle()

        val directions = engine.tasks.value.map { it.direction }
        assertTrue(directions.contains(TransferDirection.UPLOAD))
    }
}
