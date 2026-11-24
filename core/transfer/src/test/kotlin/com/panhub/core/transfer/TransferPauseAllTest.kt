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

/**
 * 「全部暂停 / 全部继续」的批量版。
 *
 * 单个 pause/resume 的行为已有专门测试，这里只钉住批量版特有的部分：
 * 一次按住整条队列、返回值是真实个数、已结束的任务不被动、恢复后按
 * 原来的顺序继续跑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferPauseAllTest {

    @get:Rule
    val temp = TemporaryFolder()

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

        override suspend fun listShares(session: ProviderSession, page: PageRequest) =
            PanResult.Failure(PanError.notSupported())
    }

    private fun engine(
        scope: TestScope,
        downloader: FileDownloader,
    ): TransferEngine {
        val session = ProviderSession(
            accountId = account,
            credentials = ProviderCredentials.Token("t"),
        )
        val provider = FakeProvider(PanResult.Success(DownloadTarget("https://x/y", size = 10L)))
        return TransferEngine(
            scope = scope,
            sessionProvider = { PanResult.Success(ScopedPanProvider(provider, session)) },
            downloader = downloader,
            sink = DirectorySink(temp.newFolder("out")),
            clock = Clock { 1_000L },
        )
    }

    @Test
    fun pauseAllHoldsTheWholeQueueAndCountsOnlyLiveTasks() = runTest {
        val downloader = ReportingDownloader()
        val engine = engine(this, downloader)

        val first = engine.enqueue(account, NodeId("n1"), "a.bin", 10L)
        advanceUntilIdle()
        val second = engine.enqueue(account, NodeId("n2"), "b.bin", 10L)
        val third = engine.enqueue(account, NodeId("n3"), "c.bin", 10L)
        advanceUntilIdle()

        // 第一个卡在 gate（RUNNING），后两个还在排队。
        assertEquals(3, engine.pauseAll())
        assertEquals(3, engine.tasks.value.count { it.state == TransferState.PAUSED })
        assertEquals(1, downloader.calls.size)

        // 已经结束的任务不算「按住」的对象。
        engine.resumeAll()
        advanceUntilIdle()
        downloader.gates.first().complete(Unit)
        advanceUntilIdle()
        downloader.gates.last().complete(Unit)
        advanceUntilIdle()
        assertEquals(3, engine.tasks.value.count { it.state == TransferState.COMPLETED })
        assertEquals(0, engine.pauseAll())
    }

    @Test
    fun pauseAllPreservesQueueOrderWhenResumed() = runTest {
        val downloader = ReportingDownloader()
        val engine = engine(this, downloader)

        engine.enqueue(account, NodeId("n1"), "a.bin", 10L)
        advanceUntilIdle()
        engine.enqueue(account, NodeId("n2"), "b.bin", 10L)
        advanceUntilIdle()

        assertEquals(2, engine.pauseAll())
        advanceUntilIdle()
        assertEquals(2, engine.resumeAll())

        // 串行队列按恢复后的顺序放行：第一个先跑。
        advanceUntilIdle()
        assertEquals("a.bin", downloader.calls.first())

        downloader.gates.first().complete(Unit)
        advanceUntilIdle()
        downloader.gates.last().complete(Unit)
        advanceUntilIdle()
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