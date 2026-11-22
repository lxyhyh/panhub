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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 跨网盘复制整个文件夹的测试。
 *
 * 这个流程同时用到两个账号：源端只负责列目录和给下载直链，目标端要建目录、收文件。
 * 目标端的目录树是我们自己搭的，所以这里断言的也是「文件最后落在哪一级目录」，
 * 而不是「建目录的调用顺序」——顺序是实现细节，落点才是用户看得见的东西。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferCloudCopyFolderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val source = AccountId("source")
    private val target = AccountId("target")

    /**
     * 假网盘。[tree] 的键是目录 id（根目录用空串），值是里面的条目。
     *
     * 建目录的返回 id 是自增的，[created] 里记下「谁建在哪一级」，
     * 测试据此把上传时收到的 parentId 还原成一条人类可读的路径。
     */
    private class FolderProvider(
        private val tree: Map<String, List<RemoteNode>> = emptyMap(),
        private val listError: PanError? = null,
        private val createError: PanError? = null,
    ) : PanProvider {
        val created = ArrayList<Triple<NodeId?, String, NodeId>>()
        val uploads = ArrayList<Pair<NodeId?, String>>()
        private val ids = AtomicLong(0L)

        override val descriptor = ProviderDescriptor(
            id = ProviderId("fake"),
            displayName = "Fake",
            capabilities = ProviderCapabilities(download = true, upload = true, createFolder = true),
        )

        override suspend fun login(credentials: ProviderCredentials) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun verify(session: ProviderSession) = PanResult.Success(Unit)

        override suspend fun list(
            session: ProviderSession,
            parentId: NodeId?,
            page: PageRequest,
        ): PanResult<NodePage> {
            if (listError != null) return PanResult.Failure(listError)
            return PanResult.Success(NodePage(tree[parentId?.value ?: ""].orEmpty()))
        }

        override suspend fun stat(session: ProviderSession, nodeId: NodeId) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun quota(session: ProviderSession) = PanResult.Success(Quota(0, null))

        override suspend fun createFolder(
            session: ProviderSession,
            parentId: NodeId?,
            name: String,
        ): PanResult<RemoteNode> {
            if (createError != null) return PanResult.Failure(createError)
            val id = NodeId("dir-" + ids.incrementAndGet())
            created += Triple(parentId, name, id)
            return PanResult.Success(
                RemoteNode(id = id, parentId = parentId, name = name, kind = NodeKind.DIR),
            )
        }

        override suspend fun rename(session: ProviderSession, nodeId: NodeId, newName: String) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun move(
            session: ProviderSession,
            nodeId: NodeId,
            newParentId: NodeId?,
        ) = PanResult.Failure(PanError.notSupported())

        override suspend fun delete(session: ProviderSession, nodeId: NodeId) =
            PanResult.Failure(PanError.notSupported())

        override suspend fun downloadUrl(
            session: ProviderSession,
            nodeId: NodeId,
        ): PanResult<DownloadTarget> =
            PanResult.Success(DownloadTarget(url = "https://example.test/" + nodeId.value))

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
            uploads += parentId to source.name
            listener.onProgress(source.size, source.size)
            return PanResult.Success(
                RemoteNode(
                    id = NodeId("up-" + source.name),
                    parentId = parentId,
                    name = source.name,
                    kind = NodeKind.FILE,
                    size = source.size,
                ),
            )
        }

        /** 把某个目录 id 还原成一条从根算起的路径，例如 docs/photos。 */
        fun pathOf(folder: NodeId?): String {
            if (folder == null) return ""
            val chain = ArrayList<String>()
            var current: NodeId? = folder
            while (current != null) {
                val entry = created.firstOrNull { it.third == current } ?: break
                chain.add(0, entry.second)
                current = entry.first
            }
            return chain.joinToString("/")
        }

        fun landingPaths(): Map<String, String> =
            uploads.associate { (parent, name) ->
                val folder = pathOf(parent)
                name to if (folder.isEmpty()) name else folder + "/" + name
            }
    }

    private fun engine(scope: TestScope, from: FolderProvider, to: FolderProvider): TransferEngine {
        val credentials = ProviderCredentials.Token("t")
        return TransferEngine(
            scope = scope,
            sessionProvider = { id ->
                val provider = if (id == source) from else to
                PanResult.Success(ScopedPanProvider(provider, ProviderSession(id, credentials)))
            },
            downloader = object : FileDownloader {
                override suspend fun download(
                    target: DownloadTarget,
                    destination: File,
                    listener: ProgressListener,
                ): PanResult<Unit> {
                    destination.parentFile?.mkdirs()
                    destination.writeText("payload")
                    return PanResult.Success(Unit)
                }
            },
            sink = DirectorySink(temp.newFolder("out")),
            clock = Clock { 700L },
            scratchDir = temp.newFolder("scratch"),
        )
    }

    private fun dir(id: String, name: String, parent: String? = null) = RemoteNode(
        id = NodeId(id),
        parentId = parent?.let { NodeId(it) },
        name = name,
        kind = NodeKind.DIR,
    )

    private fun file(id: String, name: String, size: Long, parent: String? = null) = RemoteNode(
        id = NodeId(id),
        parentId = parent?.let { NodeId(it) },
        name = name,
        kind = NodeKind.FILE,
        size = size,
    )

    private fun nestedTree(): Map<String, List<RemoteNode>> = mapOf(
        "src-root" to listOf(
            dir("p1", "photos", "src-root"),
            file("f1", "a.txt", 1L, "src-root"),
        ),
        "p1" to listOf(
            file("f2", "b.txt", 2L, "p1"),
            dir("s1", "sub", "p1"),
        ),
        "s1" to listOf(file("f3", "c.txt", 3L, "s1")),
    )

    @Test
    fun nestedFoldersKeepTheirShapeOnTheTarget() = runTest {
        val from = FolderProvider(tree = nestedTree())
        val to = FolderProvider()
        val engine = engine(this, from, to)

        engine.copyFolderBetween(
            fromAccountId = source,
            nodeId = NodeId("src-root"),
            name = "docs",
            toAccountId = target,
            toParentId = null,
        )
        advanceUntilIdle()

        // 父任务只负责摆结构，本身不传字节。
        val parent = engine.tasks.value.first { it.direction == TransferDirection.CLOUD_COPY_FOLDER }
        assertEquals(TransferState.COMPLETED, parent.state)
        assertEquals("3 个文件", parent.phase)

        val children = engine.tasks.value.filter { it.direction == TransferDirection.CLOUD_COPY }
        assertEquals(3, children.size)
        assertTrue("子任务都应完成", children.all { it.state == TransferState.COMPLETED })

        assertEquals(
            mapOf(
                "a.txt" to "docs/a.txt",
                "b.txt" to "docs/photos/b.txt",
                "c.txt" to "docs/photos/sub/c.txt",
            ),
            to.landingPaths(),
        )
    }

    @Test
    fun targetParentWrapsTheNewFolder() = runTest {
        val from = FolderProvider(tree = nestedTree())
        val to = FolderProvider()
        val engine = engine(this, from, to)

        engine.copyFolderBetween(
            fromAccountId = source,
            nodeId = NodeId("src-root"),
            name = "docs",
            toAccountId = target,
            toParentId = NodeId("dir-7"),
        )
        advanceUntilIdle()

        // 第一层的目录建在调用方指定的位置，其余都在它下面。
        assertEquals(NodeId("dir-7"), to.created.first().first)
        assertEquals("docs", to.created.first().second)
        assertEquals("docs/photos/b.txt", to.landingPaths()["b.txt"])
    }

    @Test
    fun emptyFolderFinishesWithoutChildTasks() = runTest {
        val from = FolderProvider(tree = mapOf("src-root" to emptyList()))
        val to = FolderProvider()
        val engine = engine(this, from, to)

        engine.copyFolderBetween(
            fromAccountId = source,
            nodeId = NodeId("src-root"),
            name = "empty",
            toAccountId = target,
            toParentId = null,
        )
        advanceUntilIdle()

        val parent = engine.tasks.value.single()
        assertEquals(TransferState.COMPLETED, parent.state)
        assertEquals("空文件夹", parent.phase)
        assertEquals(0L, parent.totalBytes)
        // 空文件夹在目标端照样存在，只是里面没有东西。
        assertEquals(listOf("empty"), to.created.map { it.second })
    }

    @Test
    fun folderNameIsSanitizedBeforeCreatingIt() = runTest {
        val from = FolderProvider(tree = mapOf("src-root" to emptyList()))
        val to = FolderProvider()
        val engine = engine(this, from, to)

        engine.copyFolderBetween(
            fromAccountId = source,
            nodeId = NodeId("src-root"),
            name = "a/b:c",
            toAccountId = target,
            toParentId = null,
        )
        advanceUntilIdle()

        assertEquals("a_b_c", to.created.single().second)
    }

    @Test
    fun sourceListFailureIsReportedAsSourceSide() = runTest {
        val from = FolderProvider(
            tree = nestedTree(),
            listError = PanError.auth("Cookie 已过期"),
        )
        val to = FolderProvider()
        val engine = engine(this, from, to)

        engine.copyFolderBetween(
            fromAccountId = source,
            nodeId = NodeId("src-root"),
            name = "docs",
            toAccountId = target,
            toParentId = null,
        )
        advanceUntilIdle()

        val task = engine.tasks.value.single()
        assertEquals(TransferState.FAILED, task.state)
        assertEquals("读取源文件夹失败：Cookie 已过期", task.error)
    }

    @Test
    fun createFolderFailureIsReportedAsTargetSide() = runTest {
        val from = FolderProvider(tree = nestedTree())
        val to = FolderProvider(createError = PanError.notSupported("该网盘不支持新建文件夹"))
        val engine = engine(this, from, to)

        engine.copyFolderBetween(
            fromAccountId = source,
            nodeId = NodeId("src-root"),
            name = "docs",
            toAccountId = target,
            toParentId = null,
        )
        advanceUntilIdle()

        val task = engine.tasks.value.single()
        assertEquals(TransferState.FAILED, task.state)
        assertEquals("目标网盘新建文件夹失败：该网盘不支持新建文件夹", task.error)
        assertTrue("结构都没搭起来，不该放出子任务", engine.tasks.value.size == 1)
    }
}
