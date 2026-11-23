package com.panhub.core.transfer

import com.panhub.core.common.Clock
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodePage
import com.panhub.core.model.PageRequest
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class TransferEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val account = AccountId("acc")

    /** 让下载器在测试里可控：默认立刻成功，也可以卡住等外部放行。 */
    private class FakeDownloader : FileDownloader {
        var failWith: PanError? = null
        var gate: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<String>()

        override suspend fun download(
            target: DownloadTarget,
            destination: File,
            listener: ProgressListener,
        ): PanResult<Unit> {
            calls += destination.name
            gate?.await()
            if (destination.parentFile?.exists() == false) {
                PanResult.Failure(PanError.unknown("目录不存在"))
            }
            destination.writeText("payload")
            listener.onProgress(7L, 7L)
            return failWith?.let { PanResult.Failure(it) } ?: PanResult.Success(Unit)
        }
    }

    private class FakeProvider(
        private val target: PanResult<DownloadTarget>,
    ) : PanProvider {
        override val descriptor = ProviderDescriptor(
            id = ProviderId("fake"),
            displayName = "Fake",
            capabilities = com.panhub.core.model.ProviderCapabilities(),
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

        override suspend fun downloadUrl(session: ProviderSession, nodeId: NodeId) = target

        override suspend fun createShare(
            session: ProviderSession,
            nodeIds: List<NodeId>,
            options: ShareOptions,
        ) = PanResult.Failure(PanError.notSupported())

        override suspend fun cancelShare(session: ProviderSession, shareId: String) =
            PanResult.Failure(PanError.notSupported())
    }

    private fun scopeOf(scope: TestScope, provider: PanProvider): ScopedPanProvider {
        val session = ProviderSession(
            accountId = account,
            credentials = ProviderCredentials.Token("t"),
        )
        return ScopedPanProvider(provider, session)
    }

    private fun engine(
        scope: TestScope,
        provider: PanProvider,
        downloader: FileDownloader,
        sink: DownloadSink = DirectorySink(temp.newFolder("out")),
    ) = TransferEngine(
        scope = scope,
        sessionProvider = { PanResult.Success(scopeOf(scope, provider)) },
        downloader = downloader,
        sink = sink,
        clock = Clock { 1_000L },
    )

    @Test
    fun enqueueRunsToCompletion() = runTest {
        val downloader = FakeDownloader()
        val provider = FakeProvider(PanResult.Success(DownloadTarget("https://x/y", size = 7L)))
        val engine = engine(this, provider, downloader)

        val id = engine.enqueue(account, NodeId("n1"), "a.bin", 7L)
        advanceUntilIdle()

        val task = engine.tasks.value.single()
        assertEquals(id, task.id)
        assertEquals(TransferState.COMPLETED, task.state)
        assertEquals(7L, task.bytes)
        assertTrue(File(task.filePath!!).exists())
    }

    @Test
    fun downloadUrlFailureMarksTaskFailed() = runTest {
        val provider = FakeProvider(PanResult.Failure(PanError.notFound()))
        val engine = engine(this, provider, FakeDownloader())

        engine.enqueue(account, NodeId("n1"), "a.bin", null)
        advanceUntilIdle()

        val task = engine.tasks.value.single()
        assertEquals(TransferState.FAILED, task.state)
        assertEquals("文件不存在", task.error)
    }

    @Test
    fun retryOnFailedTaskRequeuesAndSucceeds() = runTest {
        val downloader = FakeDownloader().apply { failWith = PanError.network("断网") }
        val provider = FakeProvider(PanResult.Success(DownloadTarget("https://x/y")))
        val engine = engine(this, provider, downloader)

        val id = engine.enqueue(account, NodeId("n1"), "a.bin", 1L)
        advanceUntilIdle()
        assertEquals(TransferState.FAILED, engine.tasks.value.single().state)

        downloader.failWith = null
        assertTrue(engine.retry(id))
        advanceUntilIdle()
        assertEquals(TransferState.COMPLETED, engine.tasks.value.single().state)
        assertEquals(0L, engine.tasks.value.single().error?.length?.toLong() ?: 0L)
    }

    @Test
    fun retryRejectsCompletedTask() = runTest {
        val provider = FakeProvider(PanResult.Success(DownloadTarget("https://x/y")))
        val engine = engine(this, provider, FakeDownloader())

        val id = engine.enqueue(account, NodeId("n1"), "a.bin", 1L)
        advanceUntilIdle()
        assertEquals(TransferState.COMPLETED, engine.tasks.value.single().state)
        assertFalse(engine.retry(id))
    }

    @Test
    fun cancelStopsRunningTask() = runTest {
        val downloader = FakeDownloader().apply { gate = CompletableDeferred() }
        val provider = FakeProvider(PanResult.Success(DownloadTarget("https://x/y")))
        val engine = engine(this, provider, downloader)

        val id = engine.enqueue(account, NodeId("n1"), "a.bin", 1L)
        advanceUntilIdle()
        assertEquals(TransferState.RUNNING, engine.tasks.value.single().state)

        assertTrue(engine.cancel(id))
        advanceUntilIdle()
        assertEquals(TransferState.CANCELED, engine.tasks.value.single().state)
        downloader.gate?.complete(Unit)
    }

    @Test
    fun tasksRunOneAtATime() = runTest {
        val downloader = FakeDownloader().apply { gate = CompletableDeferred() }
        val provider = FakeProvider(PanResult.Success(DownloadTarget("https://x/y")))
        val engine = engine(this, provider, downloader)

        engine.enqueue(account, NodeId("n1"), "a.bin", 1L)
        engine.enqueue(account, NodeId("n2"), "b.bin", 1L)
        advanceUntilIdle()

        // 第一个卡在 gate 上，第二个必须还在排队。
        val states = engine.tasks.value.map { it.state }
        assertEquals(listOf(TransferState.RUNNING, TransferState.QUEUED), states)
        assertEquals(1, downloader.calls.size)

        downloader.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, downloader.calls.size)
        assertTrue(engine.tasks.value.all { it.state == TransferState.COMPLETED })
    }

    @Test
    fun clearFinishedKeepsActiveTasks() = runTest {
        val downloader = FakeDownloader().apply { gate = CompletableDeferred() }
        val provider = FakeProvider(PanResult.Success(DownloadTarget("https://x/y")))
        val engine = engine(this, provider, downloader)

        engine.enqueue(account, NodeId("n1"), "a.bin", 1L)
        advanceUntilIdle()
        engine.clearFinished()
        assertEquals(1, engine.tasks.value.size)

        downloader.gate?.complete(Unit)
        advanceUntilIdle()
        engine.clearFinished()
        assertEquals(0, engine.tasks.value.size)
    }

    @Test
    fun retryAllFailedRequeuesEveryFailedTask() = runTest {
        val downloader = FakeDownloader().apply { failWith = PanError.auth("登录已过期") }
        val provider = FakeProvider(PanResult.Success(DownloadTarget("https://x/y")))
        val engine = engine(this, provider, downloader)

        engine.enqueue(account, NodeId("n1"), "a.bin", 1L)
        engine.enqueue(account, NodeId("n2"), "b.bin", 1L)
        advanceUntilIdle()
        assertTrue(engine.tasks.value.all { it.state == TransferState.FAILED })

        downloader.failWith = null
        assertEquals(2, engine.retryAllFailed())
        advanceUntilIdle()

        assertTrue(engine.tasks.value.all { it.state == TransferState.COMPLETED })
        assertEquals(4, downloader.calls.size)
    }

    @Test
    fun retryAllFailedLeavesCanceledTasksAlone() = runTest {
        val downloader = FakeDownloader().apply { gate = CompletableDeferred() }
        val provider = FakeProvider(PanResult.Success(DownloadTarget("https://x/y")))
        val engine = engine(this, provider, downloader)

        val canceled = engine.enqueue(account, NodeId("n1"), "a.bin", 1L)
        engine.enqueue(account, NodeId("n2"), "b.bin", 1L)
        advanceUntilIdle()
        engine.cancel(canceled)
        downloader.gate?.complete(Unit)
        advanceUntilIdle()

        // 取消是用户自己不要的，批量重试不该把它捡回来。
        assertEquals(0, engine.retryAllFailed())
        assertEquals(TransferState.CANCELED, engine.tasks.value.first().state)
    }

    @Test
    fun retryAllFailedOnEmptyQueueIsZero() = runTest {
        val provider = FakeProvider(PanResult.Success(DownloadTarget("https://x/y")))
        val engine = engine(this, provider, FakeDownloader())

        assertEquals(0, engine.retryAllFailed())
    }

    @Test
    fun deleteLocalFileRemovesTheDownloadedFile() = runTest {
        val downloader = FakeDownloader()
        val provider = FakeProvider(PanResult.Success(DownloadTarget("https://x/y", size = 7L)))
        val engine = engine(this, provider, downloader)

        val id = engine.enqueue(account, NodeId("n1"), "a.bin", 7L)
        advanceUntilIdle()
        val path = engine.tasks.value.single().filePath!!
        assertTrue(File(path).exists())

        assertTrue(engine.deleteLocalFile(id))
        assertFalse(File(path).exists())
        // 记录还在，只是不再指向任何文件：任务本身是成功的，
        // 用户要的只是「别再占地方」。
        assertEquals(TransferState.COMPLETED, engine.tasks.value.single().state)
        assertEquals(null, engine.tasks.value.single().filePath)
    }

    @Test
    fun deleteLocalFileLeavesUploadsAlone() = runTest {
        val downloader = FakeDownloader()
        val provider = FakeProvider(PanResult.Success(DownloadTarget("https://x/y", size = 7L)))
        val engine = engine(this, provider, downloader)
        val local = temp.newFile("source.bin")

        val id = engine.upload(account, NodeId("parent"), local.absolutePath, local.name, local.length())
        // 上传还没跑起来就取消，省掉真正走网络那一步；这里要验的是删除的边界。
        engine.cancel(id)

        assertFalse(engine.deleteLocalFile(id))
        assertTrue(local.exists())
    }

    @Test
    fun progressFractionIsNullWhenTotalUnknown() {
        val task = TransferTask(
            id = "t",
            accountId = account,
            nodeId = NodeId("n"),
            name = "a",
            bytes = 10L,
            totalBytes = null,
        )
        assertEquals(null, task.progress)
    }

    @Test
    fun progressFractionIsClamped() {
        val task = TransferTask(
            id = "t",
            accountId = account,
            nodeId = NodeId("n"),
            name = "a",
            bytes = 10L,
            totalBytes = 10L,
        )
        assertEquals(1f, task.progress)
    }
}
