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
 * 整目录下载的测试。
 *
 * 这个功能真正的风险全在「扫描」这一段：层级错了会让文件互相覆盖，
 * 翻页漏了会让用户以为下完了，扫描失败却报成功则更糟。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferFolderDownloadTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val account = AccountId("acc")

    /** 记录每个文件落在下载根目录下的哪条相对路径上。 */
    private class Recorder(private val root: File) : FileDownloader {
        val names = mutableListOf<String>()

        override suspend fun download(
            target: DownloadTarget,
            destination: File,
            listener: ProgressListener,
        ): PanResult<Unit> {
            destination.parentFile?.mkdirs()
            destination.writeText("payload")
            names += destination.relativeTo(root).path
            listener.onProgress(7L, 7L)
            return PanResult.Success(Unit)
        }
    }

    private class Fixture(val engine: TransferEngine, val recorder: Recorder)

    /** 用一棵写死的目录树当服务端：列表接口按 parentId 查表，支持翻页。 */
    private class TreeProvider(
        private val tree: Map<String, List<RemoteNode>>,
        private val pageSize: Int = 100,
        private val listError: PanError? = null,
    ) : PanProvider {
        override val descriptor = ProviderDescriptor(
            id = ProviderId("fake"),
            displayName = "Fake",
            capabilities = ProviderCapabilities(download = true),
        )

        override suspend fun login(credentials: ProviderCredentials) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun verify(session: ProviderSession) = PanResult.Success(Unit)

        override suspend fun list(session: ProviderSession, parentId: NodeId?, page: PageRequest) =
            if (listError != null) {
                PanResult.Failure(listError)
            } else {
                val all = tree[parentId?.value ?: ""].orEmpty()
                val start = page.cursor?.toIntOrNull() ?: 0
                val slice = all.drop(start).take(pageSize)
                val next = start + slice.size
                PanResult.Success(
                    NodePage(
                        items = slice,
                        nextCursor = if (next < all.size) next.toString() else null,
                    ),
                )
            }

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
            PanResult.Success(DownloadTarget("https://x/" + nodeId.value, size = 7L))

        override suspend fun createShare(
            session: ProviderSession,
            nodeIds: List<NodeId>,
            options: ShareOptions,
        ) = PanResult.Failure(PanError.notSupported())

        override suspend fun cancelShare(session: ProviderSession, shareId: String) =
            PanResult.Failure(PanError.notSupported())
    }

    /** 目录与记录器共用同一个下载根目录，记录器才能算出相对路径。 */
    private fun fixture(scope: TestScope, provider: PanProvider): Fixture {
        val session = ProviderSession(
            accountId = account,
            credentials = ProviderCredentials.Token("t"),
        )
        val root = temp.newFolder("out")
        val recorder = Recorder(root)
        val engine = TransferEngine(
            scope = scope,
            sessionProvider = { PanResult.Success(ScopedPanProvider(provider, session)) },
            downloader = recorder,
            sink = DirectorySink(root),
            clock = Clock { 1_000L },
            scratchDir = temp.newFolder("scratch"),
        )
        return Fixture(engine, recorder)
    }

    private fun file(id: String, name: String, size: Long = 7L) = RemoteNode(
        id = NodeId(id),
        parentId = null,
        name = name,
        kind = NodeKind.FILE,
        size = size,
    )

    private fun dir(id: String, name: String) = RemoteNode(
        id = NodeId(id),
        parentId = null,
        name = name,
        kind = NodeKind.DIR,
    )

    @Test
    fun nestedFilesKeepTheirFolderStructure() = runTest {
        val provider = TreeProvider(
            mapOf(
                "root" to listOf(file("f1", "a.txt"), dir("d1", "sub")),
                "d1" to listOf(file("f2", "b.txt")),
            ),
        )
        val fixture = fixture(this, provider)

        fixture.engine.downloadFolder(account, NodeId("root"), "photos")
        advanceUntilIdle()

        val tasks = fixture.engine.tasks.value
        assertEquals(3, tasks.size)
        val folder = tasks.first { it.direction == TransferDirection.DOWNLOAD_FOLDER }
        assertEquals(TransferState.COMPLETED, folder.state)
        assertEquals("2 个文件", folder.phase)
        assertEquals(listOf("photos/a.txt", "photos/sub/b.txt"), fixture.recorder.names.sorted())

        val children = tasks.filter { it.direction == TransferDirection.DOWNLOAD }
        assertEquals(listOf("photos", "photos/sub"), children.mapNotNull { it.relativePath }.distinct().sorted())
        assertTrue(children.all { it.state == TransferState.COMPLETED })
    }

    @Test
    fun emptyFolderFinishesWithoutChildTasks() = runTest {
        val provider = TreeProvider(
            mapOf(
                "root" to listOf(dir("d1", "empty")),
                "d1" to emptyList(),
            ),
        )
        val fixture = fixture(this, provider)

        fixture.engine.downloadFolder(account, NodeId("root"), "empty")
        advanceUntilIdle()

        val tasks = fixture.engine.tasks.value
        assertEquals(1, tasks.size)
        assertEquals(TransferState.COMPLETED, tasks.single().state)
        assertTrue(fixture.recorder.names.isEmpty())
    }

    @Test
    fun everyPageIsWalked() = runTest {
        val provider = TreeProvider(
            mapOf(
                "root" to (1..5).map { file("f" + it, "p" + it + ".txt") },
            ),
            pageSize = 2,
        )
        val fixture = fixture(this, provider)

        fixture.engine.downloadFolder(account, NodeId("root"), "many")
        advanceUntilIdle()

        assertEquals(5, fixture.recorder.names.size)
        val folder = fixture.engine.tasks.value.first { it.direction == TransferDirection.DOWNLOAD_FOLDER }
        assertEquals("5 个文件", folder.phase)
    }

    @Test
    fun listFailureFailsTheFolderTask() = runTest {
        val provider = TreeProvider(
            tree = emptyMap(),
            listError = PanError.auth("Cookie 已过期"),
        )
        val fixture = fixture(this, provider)

        fixture.engine.downloadFolder(account, NodeId("root"), "gone")
        advanceUntilIdle()

        val folder = fixture.engine.tasks.value.single()
        assertEquals(TransferState.FAILED, folder.state)
        assertEquals("Cookie 已过期", folder.error)
        assertNull(folder.phase)
        assertTrue(fixture.recorder.names.isEmpty())
    }

    @Test
    fun folderNameIsSanitizedInChildPaths() = runTest {
        val provider = TreeProvider(mapOf("root" to listOf(file("f1", "a.txt"))))
        val fixture = fixture(this, provider)

        fixture.engine.downloadFolder(account, NodeId("root"), "a/b:c")
        advanceUntilIdle()

        // 斜杠与冒号都换成下划线：不能因为一个文件夹名就多出几层目录。
        assertEquals(listOf("a_b_c/a.txt"), fixture.recorder.names)
    }
}