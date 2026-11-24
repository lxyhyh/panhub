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
import com.panhub.core.model.ShareOptions
import com.panhub.provider.api.DownloadTarget
import com.panhub.provider.api.PanProvider
import com.panhub.provider.api.ProviderCredentials
import com.panhub.provider.api.ProviderSession
import com.panhub.provider.api.ScopedPanProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File

/**
 * 「全部暂停 / 全部继续」的批量开关。
 *
 * 这里刻意不用 advanceUntilIdle：假下载器用 CompletableDeferred 卡住当前任务，
 * 用 advanceUntilIdle 会一直等一个永远不会静止的调度器。改为 runCurrent 逐步
 * 推进，并且每个用例结束时都把建出来的 gate 全部放行，不留悬空协程。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferPauseAllTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** 挂死时让 JUnit 自己报错并给出栈，而不是让 CI 一直等下去。 */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val account = AccountId("acc")

    /** 先报一点进度再卡住，和 TransferPauseTest 里同一个写法。 */
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

    private class FakeProvider : PanProvider {
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

        override suspend fun downloadUrl(session: ProviderSession, nodeId: NodeId) =
            PanResult.Success(DownloadTarget("https://x/y", size = 10L))

        override suspend fun createShare(
            session: ProviderSession,
            nodeIds: List<NodeId>,
            options: ShareOptions,
        ) = PanResult.Failure(PanError.notSupported())

        override suspend fun cancelShare(session: ProviderSession, shareId: String) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun listShares(session: ProviderSession, page: PageRequest) =
            PanResult.Failure(PanError.notSupported())
    }

    private fun engine(scope: TestScope, downloader: FileDownloader): TransferEngine {
        val session = ProviderSession(
            accountId = account,
            credentials = ProviderCredentials.Token("t"),
        )
        return TransferEngine(
            scope = scope,
            sessionProvider = { PanResult.Success(ScopedPanProvider(FakeProvider(), session)) },
            downloader = downloader,
            sink = DirectorySink(temp.newFolder("out")),
            clock = Clock { 1_000L },
        )
    }

    private fun TestScope.release(downloader: ReportingDownloader) {
        if (downloader.gates.isNotEmpty() && !downloader.gates.last().isCompleted) {
            downloader.gates.last().complete(Unit)
        }
        runCurrent()
    }

    @Test
    fun pauseAllHoldsTheWholeQueueAndCountsOnlyLiveTasks() = runTest {
        val downloader = ReportingDownloader()
        val engine = engine(this, downloader)

        engine.enqueue(account, NodeId("n1"), "a.bin", 10L)
        runCurrent()
        engine.enqueue(account, NodeId("n2"), "b.bin", 10L)
        engine.enqueue(account, NodeId("n3"), "c.bin", 10L)
        runCurrent()

        // 第一个卡在 gate（RUNNING），后两个还在排队。
        assertEquals(3, engine.pauseAll())
        runCurrent()
        assertEquals(3, engine.tasks.value.count { it.state == TransferState.PAUSED })
        assertEquals(1, downloader.calls.size)

        // 继续之后串行跑完三个。
        assertEquals(3, engine.resumeAll())
        runCurrent()
        assertEquals(TransferState.RUNNING, engine.tasks.value.first { it.name == "a.bin" }.state)

        release(downloader)
        release(downloader)
        release(downloader)

        assertEquals(3, engine.tasks.value.count { it.state == TransferState.COMPLETED })
        // 全部结束之后没什么可按住的了。
        assertEquals(0, engine.pauseAll())
    }

    @Test
    fun pauseAllPreservesQueueOrderWhenResumed() = runTest {
        val downloader = ReportingDownloader()
        val engine = engine(this, downloader)

        engine.enqueue(account, NodeId("n1"), "a.bin", 10L)
        runCurrent()
        engine.enqueue(account, NodeId("n2"), "b.bin", 10L)
        runCurrent()

        assertEquals(2, engine.pauseAll())
        runCurrent()
        assertEquals(2, engine.resumeAll())
        runCurrent()

        // 串行队列按恢复后的顺序放行：第一个先跑。
        assertEquals("a.bin", downloader.calls.first())

        release(downloader)
        release(downloader)

        assertEquals(2, engine.tasks.value.count { it.state == TransferState.COMPLETED })
    }

    @Test
    fun resumeAllOnEmptyQueueReturnsZero() = runTest {
        val downloader = ReportingDownloader()
        val engine = engine(this, downloader)

        assertEquals(0, engine.pauseAll())
        assertEquals(0, engine.resumeAll())
        assertFalse(downloader.calls.isNotEmpty())
    }
}
