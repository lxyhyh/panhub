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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 暂停与取消的差别只在磁盘上：暂停留着没下完的 .part，取消删掉。
 * 这些用例盯的就是这条界线有没有被守住。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferPauseTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val account = AccountId("acc")

    /** 先报一点进度再卡住，这样能看清暂停时进度有没有被抹掉。 */
    private class ReportingDownloader : FileDownloader {
        val gates = mutableListOf<CompletableDeferred<Unit>>()
        val calls = mutableListOf<String>()

        override suspend fun download(
            target: DownloadTarget,
            destination: File,
            listener: ProgressListener,
        ): PanResult<Unit> {
            calls += destination.name
            val gate = CompletableDeferred<Unit>()
            gates += gate
            listener.onProgress(4L, 10L)
            gate.await()
            destination.writeText("payload")
            listener.onProgress(10L, 10L)
            return PanResult.Success(Unit)
        }
    }

    private class FakeProvider(
        private val target: PanResult<DownloadTarget>,
    ) : PanProvider {
        override val descriptor = ProviderDescriptor(
            id = ProviderId("fake"),
            displayName = "Fake",
            capabilities = ProviderCapabilities(),
        )

        override suspend fun login(credentials: ProviderCredentials) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun verify(session: ProviderSession) = PanResult.Success(Unit)

        override suspend fun list(session: ProviderSession, parentId: NodeId?, page: PageRequest) =
            PanResult.Success(NodePage(emptyList()))

        override suspend fun stat(session: ProviderSession, nodeId: NodeId) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun quota(session: ProviderSession) = PanResult.Success(Quota(0L, 0L))

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

    private fun engine(
        scope: TestScope,
        downloader: FileDownloader,
        target: PanResult<DownloadTarget> = PanResult.Success(DownloadTarget("https://x/y", size = 10L)),
    ): TransferEngine {
        val session = ProviderSession(
            accountId = account,
            credentials = ProviderCredentials.Token("t"),
        )
        val provider = FakeProvider(target)
        return TransferEngine(
            scope = scope,
            sessionProvider = { PanResult.Success(ScopedPanProvider(provider, session)) },
            downloader = downloader,
            sink = DirectorySink(temp.newFolder("out")),
            clock = Clock { 1_000L },
        )
    }

    @Test
    fun pauseKeepsTheBytesAlreadyDone() = runTest {
        val downloader = ReportingDownloader()
        val engine = engine(this, downloader)

        val id = engine.enqueue(account, NodeId("n1"), "a.bin", 10L)
        advanceUntilIdle()
        assertEquals(TransferState.RUNNING, engine.tasks.value.single().state)
        assertEquals(4L, engine.tasks.value.single().bytes)

        assertTrue(engine.pause(id))
        advanceUntilIdle()

        val paused = engine.tasks.value.single()
        assertEquals(TransferState.PAUSED, paused.state)
        // 进度归零会让人以为白下了。
        assertEquals(4L, paused.bytes)
        assertNull(paused.error)
    }

    @Test
    fun resumeRunsTheTaskAgain() = runTest {
        val downloader = ReportingDownloader()
        val engine = engine(this, downloader)

        val id = engine.enqueue(account, NodeId("n1"), "a.bin", 10L)
        advanceUntilIdle()
        engine.pause(id)
        advanceUntilIdle()

        assertTrue(engine.resume(id))
        advanceUntilIdle()
        assertEquals(TransferState.RUNNING, engine.tasks.value.single().state)
        assertEquals(2, downloader.calls.size)

        downloader.gates.last().complete(Unit)
        advanceUntilIdle()
        assertEquals(TransferState.COMPLETED, engine.tasks.value.single().state)
    }

    @Test
    fun pausedTaskDoesNotHoldTheQueue() = runTest {
        val downloader = ReportingDownloader()
        val engine = engine(this, downloader)

        engine.enqueue(account, NodeId("n1"), "a.bin", 10L)
        advanceUntilIdle()
        val second = engine.enqueue(account, NodeId("n2"), "b.bin", 10L)
        advanceUntilIdle()

        assertTrue(engine.pause(second))
        advanceUntilIdle()
        // 暂停的任务不占执行位：第一个跑完不该等它。
        assertEquals(1, downloader.calls.size)

        downloader.gates.first().complete(Unit)
        advanceUntilIdle()
        assertEquals(1, downloader.calls.size)
        assertEquals(TransferState.COMPLETED, engine.tasks.value.first().state)
        assertEquals(TransferState.PAUSED, engine.tasks.value.last().state)
    }

    @Test
    fun pauseStopsTheRetryTimer() = runTest {
        val failing = object : FileDownloader {
            var calls = 0

            override suspend fun download(
                target: DownloadTarget,
                destination: File,
                listener: ProgressListener,
            ): PanResult<Unit> {
                calls++
                return PanResult.Failure(PanError.network("断网"))
            }
        }
        val engine = engine(this, failing)

        val id = engine.enqueue(account, NodeId("n1"), "a.bin", 10L)
        // 只跑到「第一次失败、进入退避等待」为止，不能让虚拟时间把等待跳过去。
        runCurrent()
        assertEquals(TransferState.QUEUED, engine.tasks.value.single().state)
        assertEquals(1, engine.tasks.value.single().attempt)

        // 退避等待期间按暂停：计时器必须一起停，否则几秒后它自己又跑起来了。
        assertTrue(engine.pause(id))
        advanceUntilIdle()

        val paused = engine.tasks.value.single()
        assertEquals(TransferState.PAUSED, paused.state)
        assertEquals(1, paused.attempt)
        assertEquals(1, failing.calls)
    }

    @Test
    fun pauseRejectsFinishedTask() = runTest {
        val downloader = ReportingDownloader()
        val engine = engine(this, downloader)

        val id = engine.enqueue(account, NodeId("n1"), "a.bin", 10L)
        advanceUntilIdle()
        downloader.gates.first().complete(Unit)
        advanceUntilIdle()
        assertEquals(TransferState.COMPLETED, engine.tasks.value.single().state)

        assertFalse(engine.pause(id))
        assertFalse(engine.resume(id))
    }

    @Test
    fun resumeRejectsRunningTask() = runTest {
        val downloader = ReportingDownloader()
        val engine = engine(this, downloader)

        val id = engine.enqueue(account, NodeId("n1"), "a.bin", 10L)
        advanceUntilIdle()

        assertFalse(engine.resume(id))
        assertEquals(TransferState.RUNNING, engine.tasks.value.single().state)

        // 放行，否则这个卡住的协程会让 runTest 一直等到超时。
        downloader.gates.first().complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun pauseSignalIsACancellationException() {
        // 协程机制只认 CancellationException；不是它的子类就没法靠取消停下 IO。
        val pause: CancellationException = TransferPause()
        assertTrue(pause is TransferPause)
    }

    @Test
    fun onlyPauseKeepsThePartialFile() {
        val downloader = HttpFileDownloader()
        assertTrue(downloader.keepsPartialOnPause(TransferPause()))
        assertFalse(downloader.keepsPartialOnPause(CancellationException("用户取消")))
    }
}
