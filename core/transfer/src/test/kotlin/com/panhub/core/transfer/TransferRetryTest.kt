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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 自动重试的测试。
 *
 * 重点不是「重试能成功」，而是三条边界：
 * 只有 retryable 的错误才重、次数用完就认输、退避期间不能把队列堵死。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferRetryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val account = AccountId("acc")

    /** 按 [plan] 的顺序逐次失败，plan 用完就成功。null 表示这一次成功。 */
    private class PlannedDownloader(
        private val plan: MutableList<PanError?> = mutableListOf(),
    ) : FileDownloader {
        val calls = mutableListOf<String>()

        override suspend fun download(
            target: DownloadTarget,
            destination: File,
            listener: ProgressListener,
        ): PanResult<Unit> {
            calls += destination.name
            val error = if (plan.isEmpty()) null else plan.removeAt(0)
            if (error != null) return PanResult.Failure(error)
            destination.writeText("payload")
            listener.onProgress(7L, 7L)
            return PanResult.Success(Unit)
        }
    }

    private class FakeProvider(
        private val target: PanResult<DownloadTarget> = PanResult.Success(
            DownloadTarget("https://x/y", size = 7L),
        ),
    ) : PanProvider {
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
        provider: PanProvider = FakeProvider(),
        maxAttempts: Int = TransferEngine.DEFAULT_MAX_ATTEMPTS,
    ): TransferEngine {
        val session = ProviderSession(
            accountId = account,
            credentials = ProviderCredentials.Token("t"),
        )
        return TransferEngine(
            scope = scope,
            sessionProvider = { PanResult.Success(ScopedPanProvider(provider, session)) },
            downloader = downloader,
            sink = DirectorySink(temp.newFolder("out")),
            clock = Clock { 1_000L },
            scratchDir = temp.newFolder("scratch"),
            maxAttempts = maxAttempts,
            retryBaseDelayMillis = 1_000L,
        )
    }

    private fun TransferEngine.task(id: String): TransferTask =
        tasks.value.first { it.id == id }

    @Test
    fun retryableFailureIsRetriedUntilSuccess() = runTest {
        val downloader = PlannedDownloader(
            mutableListOf(PanError.network("断网"), PanError.network("断网")),
        )
        val engine = engine(this, downloader)

        val id = engine.enqueue(account, NodeId("n1"), "a.bin", 7L)
        advanceUntilIdle()

        assertEquals(TransferState.COMPLETED, engine.task(id).state)
        assertEquals(3, downloader.calls.size)
        assertEquals(3, engine.task(id).attempt)
    }

    @Test
    fun permanentFailureIsNotRetried() = runTest {
        val downloader = PlannedDownloader(mutableListOf(PanError.auth("登录已过期")))
        val engine = engine(this, downloader)

        val id = engine.enqueue(account, NodeId("n1"), "a.bin", 7L)
        advanceUntilIdle()

        assertEquals(TransferState.FAILED, engine.task(id).state)
        assertEquals("登录已过期", engine.task(id).error)
        assertEquals(1, downloader.calls.size)
    }

    @Test
    fun retryGivesUpAfterMaxAttempts() = runTest {
        val downloader = PlannedDownloader(
            mutableListOf(
                PanError.network("断网"),
                PanError.network("断网"),
                PanError.network("断网"),
                PanError.network("断网"),
            ),
        )
        val engine = engine(this, downloader, maxAttempts = 3)

        val id = engine.enqueue(account, NodeId("n1"), "a.bin", 7L)
        advanceUntilIdle()

        assertEquals(TransferState.FAILED, engine.task(id).state)
        assertEquals(3, downloader.calls.size)
        assertEquals(3, engine.task(id).attempt)
    }

    @Test
    fun backoffDoesNotBlockTheQueue() = runTest {
        val downloader = PlannedDownloader(mutableListOf(PanError.network("断网")))
        val engine = engine(this, downloader)

        val first = engine.enqueue(account, NodeId("n1"), "a.bin", 7L)
        val second = engine.enqueue(account, NodeId("n2"), "b.bin", 7L)
        // 只推进到「第一次尝试失败、进入退避」这一刻，不看后面的结果。
        runCurrent()

        assertEquals(TransferState.QUEUED, engine.task(first).state)
        assertEquals(TransferState.COMPLETED, engine.task(second).state)
        assertEquals(listOf("a.bin", "b.bin"), downloader.calls)

        advanceUntilIdle()
        assertEquals(TransferState.COMPLETED, engine.task(first).state)
    }

    @Test
    fun cancelDuringBackoffStopsRetrying() = runTest {
        val downloader = PlannedDownloader(
            mutableListOf(PanError.network("断网"), PanError.network("断网")),
        )
        val engine = engine(this, downloader)

        val id = engine.enqueue(account, NodeId("n1"), "a.bin", 7L)
        runCurrent()
        assertEquals(TransferState.QUEUED, engine.task(id).state)

        engine.cancel(id)
        advanceUntilIdle()

        assertEquals(TransferState.CANCELED, engine.task(id).state)
        assertEquals(1, downloader.calls.size)
    }

    @Test
    fun manualRetryResetsAttemptCounter() = runTest {
        val downloader = PlannedDownloader(
            mutableListOf(
                PanError.auth("登录已过期"),
                PanError.auth("登录已过期"),
            ),
        )
        val engine = engine(this, downloader)

        val id = engine.enqueue(account, NodeId("n1"), "a.bin", 7L)
        advanceUntilIdle()
        assertEquals(1, engine.task(id).attempt)

        engine.retry(id)
        advanceUntilIdle()

        // 第二次仍然是失败，但计数已从手动重试重新开始。
        assertEquals(1, engine.task(id).attempt)
        assertEquals(2, downloader.calls.size)
    }
}
