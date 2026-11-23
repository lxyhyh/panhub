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
import kotlinx.coroutines.Dispatchers
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
 * 队列存档的测试。
 *
 * 关心的不是「JSON 能不能写出来」，而是三条会被用户直接看到的边界：
 * 重启后任务还在、上次没跑完的任务能接着跑、存档坏了不能把 App 拖崩。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val account = AccountId("acc")

    /** 什么都不做的下载器：这些用例只关心队列本身。 */
    private class NoopDownloader : FileDownloader {
        override suspend fun download(
            target: DownloadTarget,
            destination: File,
            listener: ProgressListener,
        ): PanResult<Unit> = PanResult.Success(Unit)
    }

    private class FakeProvider : PanProvider {
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

    /** 目录只建一次：TemporaryFolder.newFolder 同名第二次会抛 IOException。 */
    private val outDir: File by lazy { temp.newFolder("out") }
    private val scratchDir: File by lazy { temp.newFolder("scratch") }
    private val stateDir: File by lazy { temp.newFolder("state") }

    /**
     * 存档读写跑在 [Dispatchers.Unconfined] 上。
     *
     * 默认的 IO 线程池不受测试调度器控制，`advanceUntilIdle` 会在文件真正读出来之前
     * 就返回，用例只能碰运气。
     */
    private fun store(name: String = "transfers.json"): JsonTransferStore =
        JsonTransferStore(File(stateDir, name), Dispatchers.Unconfined)

    private fun engine(scope: TestScope, store: TransferStore): TransferEngine {
        val session = ProviderSession(
            accountId = account,
            credentials = ProviderCredentials.Token("t"),
        )
        return TransferEngine(
            scope = scope,
            sessionProvider = { PanResult.Success(ScopedPanProvider(FakeProvider(), session)) },
            downloader = NoopDownloader(),
            sink = DirectorySink(outDir),
            clock = Clock { 1_000L },
            scratchDir = scratchDir,
            store = store,
        )
    }

    private fun task(
        id: String,
        state: TransferState = TransferState.QUEUED,
        direction: TransferDirection = TransferDirection.DOWNLOAD,
    ) = TransferTask(
        id = id,
        accountId = account,
        nodeId = NodeId("n-" + id),
        name = id + ".bin",
        direction = direction,
        state = state,
        totalBytes = 10L,
        createdAt = 5L,
        updatedAt = 6L,
    )

    @Test
    fun jsonStoreRoundTripsEveryField() = runTest {
        val file = File(stateDir, "transfers.json")
        val store = JsonTransferStore(file, Dispatchers.Unconfined)
        val original = task("a").copy(
            bytes = 4L,
            relativePath = "photos/2019",
            phase = "下载中",
            targetAccountId = AccountId("acc2"),
            targetParentId = NodeId("dir"),
            attempt = 2,
        )

        store.save(listOf(original))

        assertEquals(listOf(original), store.load())
    }

    @Test
    fun missingFileLoadsAsEmpty() = runTest {
        val store = JsonTransferStore(File(stateDir, "nope.json"), Dispatchers.Unconfined)

        assertEquals(emptyList<TransferTask>(), store.load())
    }

    @Test
    fun corruptedFileLoadsAsEmptyInsteadOfThrowing() = runTest {
        val file = File(stateDir, "transfers.json")
        file.writeText("{ this is not json")

        assertEquals(emptyList<TransferTask>(), JsonTransferStore(file, Dispatchers.Unconfined).load())
    }

    @Test
    fun unknownDirectionIsDroppedInsteadOfGuessed() = runTest {
        val file = File(stateDir, "transfers.json")
        file.writeText(
            """
            {"tasks":[
              {"id":"a","accountId":"acc","name":"a.bin","direction":"DOWNLOAD"},
              {"id":"b","accountId":"acc","name":"b.bin","direction":"TELEPORT"}
            ],"version":1}
            """.trimIndent(),
        )

        val restored = JsonTransferStore(file, Dispatchers.Unconfined).load()

        assertEquals(listOf("a"), restored.map { it.id })
    }

    @Test
    fun queueSurvivesARestart() = runTest {
        val file = File(stateDir, "transfers.json")
        val first = engine(this, JsonTransferStore(file, Dispatchers.Unconfined))
        first.enqueue(account, NodeId("n1"), "a.bin", 10L)
        advanceUntilIdle()
        assertEquals(1, first.tasks.value.size)

        val second = engine(this, JsonTransferStore(file, Dispatchers.Unconfined))
        advanceUntilIdle()

        assertEquals(listOf("a.bin"), second.tasks.value.map { it.name })
    }

    @Test
    fun unfinishedTaskIsQueuedAgainAfterRestart() = runTest {
        val file = File(stateDir, "transfers.json")
        JsonTransferStore(file, Dispatchers.Unconfined).save(
            listOf(task("a", state = TransferState.RUNNING).copy(bytes = 4L)),
        )

        val engine = engine(this, JsonTransferStore(file, Dispatchers.Unconfined))
        advanceUntilIdle()

        // 上次的协程已经不存在了，停在 RUNNING 会永远卡住；退回 QUEUED 重跑，
        // 于是它真的又跑了一遍（这里的下载器总是成功，所以直接到 COMPLETED）。
        val restored = engine.tasks.value.single()
        assertFalse(restored.state == TransferState.RUNNING)
        assertEquals(TransferState.COMPLETED, restored.state)
    }

    @Test
    fun finishedTasksAreRestoredAsIs() = runTest {
        val file = File(stateDir, "transfers.json")
        JsonTransferStore(file, Dispatchers.Unconfined).save(listOf(task("a", state = TransferState.COMPLETED)))

        val engine = engine(this, JsonTransferStore(file, Dispatchers.Unconfined))
        advanceUntilIdle()

        assertTrue(engine.tasks.value.single().state == TransferState.COMPLETED)
    }
}
