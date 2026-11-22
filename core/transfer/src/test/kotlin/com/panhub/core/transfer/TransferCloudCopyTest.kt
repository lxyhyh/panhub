package com.panhub.core.transfer

import com.panhub.core.common.Clock
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 跨账号转存的测试。
 *
 * 转存是本工程唯一"两个账号同时在场"的流程，失败时必须能说清是源端还是目标端
 * 出的问题，所以这里三条失败路径分别断言错误文案前缀，而不是只看 FAILED。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferCloudCopyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val source = AccountId("source")
    private val target = AccountId("target")

    private class CopyProvider(
        var download: PanResult<DownloadTarget> = PanResult.Success(
            DownloadTarget(url = "https://example.test/a.bin"),
        ),
        var upload: PanResult<RemoteNode> = PanResult.Success(
            RemoteNode(
                id = NodeId("new-1"),
                parentId = null,
                name = "a.bin",
                kind = NodeKind.FILE,
                size = 3L,
            ),
        ),
    ) : PanProvider {
        var uploadedName: String? = null
        var uploadedParent: NodeId? = null
        var uploadedContent: String? = null
        var downloadCalls = 0

        override val descriptor = ProviderDescriptor(
            id = ProviderId("fake"),
            displayName = "Fake",
            capabilities = ProviderCapabilities(download = true, upload = true),
        )

        override suspend fun login(credentials: ProviderCredentials) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun verify(session: ProviderSession) = PanResult.Success(Unit)

        override suspend fun list(session: ProviderSession, parentId: NodeId?, page: PageRequest) =
            PanResult.Success(NodePage(emptyList()))

        override suspend fun stat(session: ProviderSession, nodeId: NodeId) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun quota(session: ProviderSession) = PanResult.Success(Quota(0, null))

        override suspend fun createFolder(session: ProviderSession, parentId: NodeId?, name: String) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun rename(session: ProviderSession, nodeId: NodeId, newName: String) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun move(session: ProviderSession, nodeId: NodeId, newParentId: NodeId?) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun delete(session: ProviderSession, nodeId: NodeId) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun downloadUrl(session: ProviderSession, nodeId: NodeId): PanResult<DownloadTarget> {
            downloadCalls++
            return download
        }

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
            uploadedName = source.name
            uploadedParent = parentId
            uploadedContent = source.openStream().reader().readText()
            return upload
        }
    }

    private fun engine(scope: TestScope, from: CopyProvider, to: CopyProvider): TransferEngine {
        val credentials = ProviderCredentials.Token("t")
        return TransferEngine(
            scope = scope,
            sessionProvider = { id ->
                val provider = if (id == source) from else to
                PanResult.Success(ScopedPanProvider(provider, ProviderSession(id, credentials)))
            },
            downloader = object : FileDownloader {
                override suspend fun download(
                    target: DownloadTarget,
                    destination: File,
                    listener: ProgressListener,
                ): PanResult<Unit> {
                    destination.parentFile?.mkdirs()
                    destination.writeText("payload")
                    return PanResult.Success(Unit)
                }
            },
            sink = DirectorySink(temp.newFolder("out")),
            clock = Clock { 700L },
            scratchDir = temp.newFolder("scratch"),
        )
    }

    @Test
    fun cloudCopyDownloadsThenUploads() = runTest {
        val from = CopyProvider()
        val to = CopyProvider()
        val engine = engine(this, from, to)

        val id = engine.copyBetween(
            fromAccountId = source,
            nodeId = NodeId("file-1"),
            name = "a.bin",
            size = 3L,
            toAccountId = target,
            toParentId = null,
        )
        advanceUntilIdle()

        val task = engine.tasks.value.single()
        assertEquals(id, task.id)
        assertEquals(TransferDirection.CLOUD_COPY, task.direction)
        assertEquals(TransferState.COMPLETED, task.state)
        assertEquals(source, task.accountId)
        assertEquals(target, task.targetAccountId)
        assertNull(task.phase)
        assertEquals("a.bin", to.uploadedName)
        assertEquals(null, to.uploadedParent)
        assertEquals("payload", to.uploadedContent)
        assertEquals(1, from.downloadCalls)
    }

    @Test
    fun cloudCopyKeepsTargetFolder() = runTest {
        val from = CopyProvider()
        val to = CopyProvider()
        val engine = engine(this, from, to)

        engine.copyBetween(
            fromAccountId = source,
            nodeId = NodeId("file-1"),
            name = "a.bin",
            size = 3L,
            toAccountId = target,
            toParentId = NodeId("dir-7"),
        )
        advanceUntilIdle()

        assertEquals(NodeId("dir-7"), to.uploadedParent)
    }

    @Test
    fun sourceDownloadFailureIsReportedAsSourceSide() = runTest {
        val from = CopyProvider(download = PanResult.Failure(PanError.auth("Cookie 已过期")))
        val to = CopyProvider()
        val engine = engine(this, from, to)

        engine.copyBetween(
            fromAccountId = source,
            nodeId = NodeId("file-1"),
            name = "a.bin",
            size = 3L,
            toAccountId = target,
            toParentId = null,
        )
        advanceUntilIdle()

        val task = engine.tasks.value.single()
        assertEquals(TransferState.FAILED, task.state)
        assertEquals("源网盘下载失败：Cookie 已过期", task.error)
        assertNull(to.uploadedName)
    }

    @Test
    fun targetUploadFailureIsReportedAsTargetSide() = runTest {
        val from = CopyProvider()
        val to = CopyProvider(upload = PanResult.Failure(PanError.network("连接被重置")))
        val engine = engine(this, from, to)

        engine.copyBetween(
            fromAccountId = source,
            nodeId = NodeId("file-1"),
            name = "a.bin",
            size = 3L,
            toAccountId = target,
            toParentId = null,
        )
        advanceUntilIdle()

        val task = engine.tasks.value.single()
        assertEquals(TransferState.FAILED, task.state)
        assertEquals("目标网盘上传失败：连接被重置", task.error)
    }

    @Test
    fun scratchFileIsRemovedAfterSuccess() = runTest {
        val from = CopyProvider()
        val to = CopyProvider()
        val engine = engine(this, from, to)

        engine.copyBetween(
            fromAccountId = source,
            nodeId = NodeId("file-1"),
            name = "a.bin",
            size = 3L,
            toAccountId = target,
            toParentId = null,
        )
        advanceUntilIdle()

        val leftover = File(temp.root, "scratch").listFiles()?.toList().orEmpty()
        assertTrue("临时文件应被清理，实际剩下：" + leftover, leftover.isEmpty())
    }

    @Test
    fun scratchFileIsRemovedAfterFailure() = runTest {
        val from = CopyProvider()
        val to = CopyProvider(upload = PanResult.Failure(PanError.unknown("拒绝")))
        val engine = engine(this, from, to)

        engine.copyBetween(
            fromAccountId = source,
            nodeId = NodeId("file-1"),
            name = "a.bin",
            size = 3L,
            toAccountId = target,
            toParentId = null,
        )
        advanceUntilIdle()

        val leftover = File(temp.root, "scratch").listFiles()?.toList().orEmpty()
        assertTrue("临时文件应被清理，实际剩下：" + leftover, leftover.isEmpty())
    }
}
