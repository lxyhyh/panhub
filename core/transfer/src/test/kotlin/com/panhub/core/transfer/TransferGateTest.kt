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
 * 传输门控：什么时候不许开始新的传输。
 *
 * 钉住的是「挡开始、不打断已在跑的」这条边界——它决定了用户切到移动数据时
 * 手上那个文件会不会被砍成半截。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferGateTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val account = AccountId("acc")

    /** 可以在测试中途改变主意的门：null 是开，非 null 是挡。 */
    private class SwitchGate(var blocked: PanError? = null) : TransferGate {
        var asked = 0
        override fun blockedReason(): PanError? {
            asked++
            return blocked
        }
    }

    private class FakeDownloader : FileDownloader {
        val calls = mutableListOf<String>()
        var hang: CompletableDeferred<Unit>? = null

        override suspend fun download(
            target: DownloadTarget,
            destination: File,
            listener: ProgressListener,
        ): PanResult<Unit> {
            calls += destination.name
            hang?.await()
            destination.writeText("payload")
            listener.onProgress(7L, 7L)
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

        override suspend fun quota(session: ProviderSession) = PanResult.Success(Quota(0, null))

        override suspend fun createFolder(session: ProviderSession, parentId: NodeId?, name: String) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun rename(session: ProviderSession, nodeId: NodeId, newName: String) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun move(session: ProviderSession, nodeId: NodeId, newParentId: NodeId?) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun delete(session: ProviderSession, nodeId: NodeId) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun downloadUrl(session: ProviderSession, nodeId: NodeId) =
            PanResult.Success(DownloadTarget("https://x/y", size = 7L))

        override suspend fun createShare(
            session: ProviderSession,
            nodeIds: List<NodeId>,
            options: ShareOptions,
        ) = PanResult.Failure(PanError.notSupported())

        override suspend fun cancelShare(session: ProviderSession, shareId: String) =
            PanResult.Failure(PanError.notSupported())
    }

    private fun scoped(scope: TestScope): ScopedPanProvider = ScopedPanProvider(
        FakeProvider(),
        ProviderSession(accountId = account, credentials = ProviderCredentials.Token("t")),
    )

    private fun engine(scope: TestScope, downloader: FileDownloader, gate: TransferGate) =
        TransferEngine(
            scope = scope,
            sessionProvider = { PanResult.Success(scoped(scope)) },
            downloader = downloader,
            sink = DirectorySink(temp.newFolder("out")),
            clock = Clock { 1_000L },
            gate = gate,
        )

    @Test
    fun blockedGateKeepsTheQueueFromStarting() = runTest {
        val downloader = FakeDownloader()
        val gate = SwitchGate(PanError.network("已开启「仅 Wi-Fi 传输」"))
        val engine = engine(this, downloader, gate)

        engine.enqueue(account, NodeId("n1"), "a.bin", 7L)
        advanceUntilIdle()

        assertTrue(downloader.calls.isEmpty())
        assertEquals(TransferState.QUEUED, engine.tasks.value.single().state)
    }

    @Test
    fun blockedGateExplainsWhy() = runTest {
        val gate = SwitchGate(PanError.network("已开启「仅 Wi-Fi 传输」"))
        val engine = engine(this, FakeDownloader(), gate)

        engine.enqueue(account, NodeId("n1"), "a.bin", 7L)
        advanceUntilIdle()

        assertEquals("已开启「仅 Wi-Fi 传输」", engine.gateMessage.value)
    }

    @Test
    fun openGateLeavesNoMessage() = runTest {
        val engine = engine(this, FakeDownloader(), SwitchGate(null))

        engine.enqueue(account, NodeId("n1"), "a.bin", 7L)
        advanceUntilIdle()

        assertNull(engine.gateMessage.value)
    }

    @Test
    fun reopeningTheGateLetsTheQueueRun() = runTest {
        val downloader = FakeDownloader()
        val gate = SwitchGate(PanError.network("已开启「仅 Wi-Fi 传输」"))
        val engine = engine(this, downloader, gate)
        engine.enqueue(account, NodeId("n1"), "a.bin", 7L)
        advanceUntilIdle()

        // 连上 Wi-Fi 了，门自己不会开，得有人叫一声。
        gate.blocked = null
        engine.reevaluateGate()
        advanceUntilIdle()

        assertEquals(listOf("a.bin"), downloader.calls)
        assertEquals(TransferState.COMPLETED, engine.tasks.value.single().state)
    }

    @Test
    fun taskAlreadyRunningIsNotInterrupted() = runTest {
        val downloader = FakeDownloader()
        val hang = CompletableDeferred<Unit>()
        downloader.hang = hang
        val gate = SwitchGate(null)
        val engine = engine(this, downloader, gate)
        engine.enqueue(account, NodeId("n1"), "a.bin", 7L)
        advanceUntilIdle()
        assertEquals(listOf("a.bin"), downloader.calls)

        // 跑到一半掉到移动数据：门关上了，但手上这个文件不该被砍。
        gate.blocked = PanError.network("已开启「仅 Wi-Fi 传输」")
        engine.reevaluateGate()
        advanceUntilIdle()
        assertEquals(TransferState.RUNNING, engine.tasks.value.single().state)

        // 后面排队的那个才应该被拦。
        engine.enqueue(account, NodeId("n2"), "b.bin", 7L)
        advanceUntilIdle()
        assertEquals(listOf("a.bin"), downloader.calls)

        hang.complete(Unit)
        advanceUntilIdle()
        assertEquals(TransferState.COMPLETED, engine.tasks.value.first().state)
        assertEquals(TransferState.QUEUED, engine.tasks.value.last().state)
    }
}
