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
 * 队列是串行的，顺序就是用户能控制的唯一调度手段。
 * 这些用例盯的是「置顶只改顺序、不动任务本身」这条界线。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferReorderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val account = AccountId("acc")

    /** 卡住直到被放行，这样能精确控制「哪个任务在跑」。 */
    private class GatedDownloader : FileDownloader {
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
        ): PanResult<ShareLink> = PanResult.Failure(PanError.notSupported())

        override suspend fun cancelShare(session: ProviderSession, shareId: String) =
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

    /**
     * 把每个卡在 gate 上的下载都放行，直到队列里没有还在跑的任务。
     *
     * 不这么做的话，最后一个被放行的任务之后又会有下一个任务开始并卡住，
     * runTest 会等到超时报 UncompletedCoroutinesError。
     */
    private suspend fun TestScope.drain(downloader: GatedDownloader) {
        while (true) {
            advanceUntilIdle()
            val gate = downloader.gates.firstOrNull { !it.isCompleted } ?: return
            gate.complete(Unit)
        }
    }

    @Test
    fun movingToFrontPutsTheTaskFirstInTheList() = runTest {
        val downloader = GatedDownloader()
        val engine = engine(this, downloader)

        // 第一个进队列就会开始跑并卡在 gate 上，后面两个只能排队。
        engine.enqueue(account, NodeId("n1"), "a.bin", 10L)
        advanceUntilIdle()
        engine.enqueue(account, NodeId("n2"), "b.bin", 10L)
        val third = engine.enqueue(account, NodeId("n3"), "c.bin", 10L)
        advanceUntilIdle()

        assertTrue(engine.moveToFront(third))
        // 只改顺序：总数不变，被挪的那个排在最前。
        assertEquals(3, engine.tasks.value.size)
        assertEquals(third, engine.tasks.value.first().id)

        drain(downloader)
        // 放行第一个之后，下一个就跑被置顶的那个。
        assertEquals(listOf("a.bin", "c.bin", "b.bin"), downloader.calls)
    }

    @Test
    fun movingToFrontKeepsTheProgressOfTheTask() = runTest {
        val downloader = GatedDownloader()
        val engine = engine(this, downloader)

        engine.enqueue(account, NodeId("n1"), "a.bin", 10L)
        advanceUntilIdle()
        val second = engine.enqueue(account, NodeId("n2"), "b.bin", 10L)
        advanceUntilIdle()
        engine.pause(second)
        advanceUntilIdle()

        assertTrue(engine.moveToFront(second))
        // 暂停中的任务挪到前面仍然暂停——置顶不是「继续」。
        assertEquals(TransferState.PAUSED, engine.tasks.value.first().state)

        drain(downloader)
        assertEquals(TransferState.PAUSED, engine.tasks.value.first().state)
    }

    @Test
    fun movingToFrontRejectsRunningAndFinishedTasks() = runTest {
        val downloader = GatedDownloader()
        val engine = engine(this, downloader)

        val first = engine.enqueue(account, NodeId("n1"), "a.bin", 10L)
        advanceUntilIdle()
        // 正在跑的任务挪了也没用，只有一个执行位。
        assertFalse(engine.moveToFront(first))

        drain(downloader)
        assertEquals(TransferState.COMPLETED, engine.tasks.value.single().state)
        assertFalse(engine.moveToFront(first))
    }

    @Test
    fun movingTheFirstTaskToFrontChangesNothing() = runTest {
        val downloader = GatedDownloader()
        val engine = engine(this, downloader)

        engine.enqueue(account, NodeId("n1"), "a.bin", 10L)
        advanceUntilIdle()
        val second = engine.enqueue(account, NodeId("n2"), "b.bin", 10L)
        advanceUntilIdle()

        assertTrue(engine.moveToFront(second))
        // 已经在最前面了，再置顶一次没有意义，返回 false 免得界面白闪一下。
        assertFalse(engine.moveToFront(second))

        drain(downloader)
    }

    @Test
    fun movingAnUnknownTaskIsRejected() = runTest {
        val downloader = GatedDownloader()
        val engine = engine(this, downloader)

        assertFalse(engine.moveToFront("nope"))
    }
}
