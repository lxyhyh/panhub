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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 上传整个本地文件夹的测试。
 *
 * 关注的是层级有没有保住：目录树是我们自己在网盘里一层层建出来的，
 * 建错一级用户传上去的文件就会摊平成一堆同名文件。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferUploadFolderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val account = AccountId("acc")

    /** 记录建了哪些目录、传了哪些文件。 */
    private class FolderUploadProvider(
        var createError: PanError? = null,
        var uploadResult: PanResult<RemoteNode>? = null,
    ) : PanProvider {
        /** 三元组：父目录、名字、新建出来的 id。 */
        val created = ArrayList<Triple<NodeId?, String, NodeId>>()

        /** 文件名到落点目录 id。 */
        val uploads = ArrayList<Pair<String, NodeId?>>()
        private val ids = AtomicLong(0L)

        override val descriptor = ProviderDescriptor(
            id = ProviderId("fake"),
            displayName = "Fake",
            capabilities = ProviderCapabilities(upload = true, createFolder = true),
        )

        override suspend fun login(credentials: ProviderCredentials) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun verify(session: ProviderSession) = PanResult.Success(Unit)

        override suspend fun list(session: ProviderSession, parentId: NodeId?, page: PageRequest) =
            PanResult.Success(NodePage(emptyList()))

        override suspend fun stat(session: ProviderSession, nodeId: NodeId) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun quota(session: ProviderSession) = PanResult.Success(Quota(0, null))

        override suspend fun createFolder(
            session: ProviderSession,
            parentId: NodeId?,
            name: String,
        ): PanResult<RemoteNode> {
            createError?.let { return PanResult.Failure(it) }
            val id = NodeId("dir-" + ids.incrementAndGet())
            created += Triple(parentId, name, id)
            return PanResult.Success(
                RemoteNode(id = id, parentId = parentId, name = name, kind = NodeKind.DIR),
            )
        }

        override suspend fun rename(session: ProviderSession, nodeId: NodeId, newName: String) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun move(session: ProviderSession, nodeId: NodeId, newParentId: NodeId?) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun delete(session: ProviderSession, nodeId: NodeId) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun downloadUrl(session: ProviderSession, nodeId: NodeId) =
            PanResult.Failure(PanError.notSupported())

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
            uploads += source.name to parentId
            listener.onProgress(source.size, source.size)
            uploadResult?.let { return it }
            return PanResult.Success(
                RemoteNode(
                    id = NodeId("file-" + source.name),
                    parentId = parentId,
                    name = source.name,
                    kind = NodeKind.FILE,
                    size = source.size,
                ),
            )
        }

        /** 沿建目录记录回溯出某个目录的完整路径。 */
        fun pathOf(folder: NodeId?): String {
            if (folder == null) return ""
            val entry = created.firstOrNull { it.third == folder } ?: return ""
            val parent = pathOf(entry.first)
            return if (parent.isEmpty()) entry.second else parent + "/" + entry.second
        }

        /** 文件名到落点完整路径。 */
        fun landingPaths(): Map<String, String> =
            uploads.associate { (name, parent) -> name to pathOf(parent) }
    }

    private fun engine(scope: TestScope, provider: PanProvider): TransferEngine {
        val session = ProviderSession(
            accountId = account,
            credentials = ProviderCredentials.Token("t"),
        )
        return TransferEngine(
            scope = scope,
            sessionProvider = { PanResult.Success(ScopedPanProvider(provider, session)) },
            downloader = object : FileDownloader {
                override suspend fun download(
                    target: DownloadTarget,
                    destination: File,
                    listener: ProgressListener,
                ): PanResult<Unit> = PanResult.Success(Unit)
            },
            sink = DirectorySink(temp.newFolder("out")),
            clock = Clock { 900L },
        )
    }

    private fun localTree(): File {
        val root = temp.newFolder("docs")
        File(root, "a.txt").writeText("aaa")
        val photos = File(root, "photos").apply { mkdirs() }
        File(photos, "b.txt").writeText("bb")
        val sub = File(photos, "sub").apply { mkdirs() }
        File(sub, "c.txt").writeText("c")
        return root
    }

    @Test
    fun nestedLocalFoldersKeepTheirShape() = runTest {
        val provider = FolderUploadProvider()
        val engine = engine(this, provider)

        val id = engine.uploadFolder(account, null, localTree().absolutePath, "docs")
        advanceUntilIdle()

        val summary = engine.tasks.value.first { it.id == id }
        assertEquals(TransferDirection.UPLOAD_FOLDER, summary.direction)
        assertEquals(TransferState.COMPLETED, summary.state)
        assertEquals("3 个文件", summary.phase)
        // landingPaths 给的是每个文件落进的目录，文件名另算。
        assertEquals(
            mapOf("a.txt" to "docs", "b.txt" to "docs/photos", "c.txt" to "docs/photos/sub"),
            provider.landingPaths(),
        )
        // 三个文件各一条普通上传任务，都从队列里自己跑完。
        val children = engine.tasks.value.filter { it.direction == TransferDirection.UPLOAD }
        assertEquals(3, children.size)
        assertTrue(children.all { it.state == TransferState.COMPLETED })
    }

    @Test
    fun targetParentWrapsTheNewFolder() = runTest {
        val provider = FolderUploadProvider()
        val engine = engine(this, provider)

        engine.uploadFolder(account, NodeId("target-9"), localTree().absolutePath, "docs")
        advanceUntilIdle()

        assertEquals(NodeId("target-9"), provider.created.first().first)
        assertEquals("docs", provider.created.first().second)
    }

    @Test
    fun emptyFolderFinishesWithoutChildTasks() = runTest {
        val provider = FolderUploadProvider()
        val engine = engine(this, provider)
        val empty = temp.newFolder("empty")

        val id = engine.uploadFolder(account, null, empty.absolutePath, "empty")
        advanceUntilIdle()

        val summary = engine.tasks.value.first { it.id == id }
        assertEquals(TransferState.COMPLETED, summary.state)
        assertEquals("空文件夹", summary.phase)
        assertEquals(1, engine.tasks.value.size)
        // 空目录本身也要在网盘上建出来，否则用户看不到自己传了东西。
        assertEquals(1, provider.created.size)
    }

    @Test
    fun emptyFilesAreSkippedAndCounted() = runTest {
        val provider = FolderUploadProvider()
        val engine = engine(this, provider)
        val root = temp.newFolder("mixed")
        File(root, "real.txt").writeText("x")
        File(root, "zero.txt").writeText("")

        val id = engine.uploadFolder(account, null, root.absolutePath, "mixed")
        advanceUntilIdle()

        val summary = engine.tasks.value.first { it.id == id }
        assertEquals("1 个文件（跳过 1 个空文件）", summary.phase)
        assertEquals(listOf("real.txt"), provider.uploads.map { it.first })
    }

    @Test
    fun onlyEmptyFilesSaysSoInsteadOfPretendingEmptyFolder() = runTest {
        val provider = FolderUploadProvider()
        val engine = engine(this, provider)
        val root = temp.newFolder("zeros")
        File(root, "zero.txt").writeText("")

        val id = engine.uploadFolder(account, null, root.absolutePath, "zeros")
        advanceUntilIdle()

        val summary = engine.tasks.value.first { it.id == id }
        assertEquals("空文件夹（跳过 1 个空文件）", summary.phase)
        assertTrue(provider.uploads.isEmpty())
    }

    @Test
    fun missingLocalFolderFailsEarly() = runTest {
        val provider = FolderUploadProvider()
        val engine = engine(this, provider)

        engine.uploadFolder(account, null, "/no/such/dir", "ghost")
        advanceUntilIdle()

        val task = engine.tasks.value.single()
        assertEquals(TransferState.FAILED, task.state)
        assertEquals("本地文件夹已不存在", task.error)
        assertTrue(provider.created.isEmpty())
    }

    @Test
    fun createFolderFailureIsReported() = runTest {
        val provider = FolderUploadProvider(createError = PanError.notSupported("该网盘不支持新建文件夹"))
        val engine = engine(this, provider)

        engine.uploadFolder(account, null, localTree().absolutePath, "docs")
        advanceUntilIdle()

        val task = engine.tasks.value.single()
        assertEquals(TransferState.FAILED, task.state)
        assertEquals("网盘新建文件夹失败：该网盘不支持新建文件夹", task.error)
    }

    @Test
    fun folderNameIsSanitizedBeforeCreatingIt() = runTest {
        val provider = FolderUploadProvider()
        val engine = engine(this, provider)

        engine.uploadFolder(account, null, localTree().absolutePath, "a/b:c")
        advanceUntilIdle()

        assertEquals("a_b_c", provider.created.first().second)
    }

    @Test
    fun uploadFailureInChildDoesNotStopTheOthers() = runTest {
        val provider = FolderUploadProvider(
            uploadResult = PanResult.Failure(PanError.unknown("服务端拒绝")),
        )
        val engine = engine(this, provider)
        val id = engine.uploadFolder(account, null, localTree().absolutePath, "docs")
        advanceUntilIdle()

        // 子任务各自失败，整理任务本身仍然是完成——它只负责排队。
        assertNotNull(engine.tasks.value.firstOrNull { it.id == id })
        val children = engine.tasks.value.filter { it.direction == TransferDirection.UPLOAD }
        assertEquals(3, children.size)
        assertTrue(children.all { it.state == TransferState.FAILED })
    }
}