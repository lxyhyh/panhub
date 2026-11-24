package com.panhub.feature.browser

import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.AuthMethod
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.NodePage
import com.panhub.core.model.PageRequest
import com.panhub.core.model.ProviderCapabilities
import com.panhub.core.model.ProviderDescriptor
import com.panhub.core.model.ProviderId
import com.panhub.core.model.Quota
import com.panhub.core.model.RemoteNode
import com.panhub.core.model.ShareLink
import com.panhub.core.model.ShareOptions
import com.panhub.core.model.SharePage
import com.panhub.core.session.AccountSummary
import com.panhub.core.session.SessionManager
import com.panhub.core.transfer.UploadLauncher
import com.panhub.provider.api.DownloadTarget
import com.panhub.provider.api.PanProvider
import com.panhub.provider.api.ProviderCredentials
import com.panhub.provider.api.ProviderSession
import com.panhub.provider.api.ScopedPanProvider
import com.panhub.provider.api.UploadSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 上传前的同名确认。
 *
 * 守的是「旧文件会不会被悄悄顶掉」这件事：各网盘对同名文件的处理不一致，
 * 有的覆盖有的自动改名，用户按下上传之前根本无从知道。所以只在他真要覆盖时才问，
 * 问的时候把「可能覆盖」这句话说清楚，别让他以为只是个普通提示。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserUploadConfirmTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val account = AccountId("fake:1")

    private class FakeProvider(private val nodes: List<RemoteNode>) : PanProvider {
        override val descriptor = ProviderDescriptor(
            id = ProviderId("fake"),
            displayName = "假网盘",
            authHint = "",
            capabilities = ProviderCapabilities(list = true, upload = true),
            authMethod = AuthMethod.TOKEN,
        )

        override suspend fun login(credentials: ProviderCredentials): PanResult<ProviderSession> =
            error("不该调用")

        override suspend fun verify(session: ProviderSession): PanResult<Unit> =
            PanResult.Success(Unit)

        override suspend fun list(
            session: ProviderSession,
            parentId: NodeId?,
            page: PageRequest,
        ): PanResult<NodePage> = PanResult.Success(NodePage(nodes, null))

        override suspend fun stat(session: ProviderSession, nodeId: NodeId): PanResult<RemoteNode> =
            PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun quota(session: ProviderSession): PanResult<Quota> =
            PanResult.Success(Quota(used = 0L, total = 100L))

        override suspend fun createFolder(
            session: ProviderSession,
            parentId: NodeId?,
            name: String,
        ): PanResult<RemoteNode> = PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun rename(
            session: ProviderSession,
            nodeId: NodeId,
            newName: String,
        ): PanResult<RemoteNode> = PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun move(
            session: ProviderSession,
            nodeId: NodeId,
            newParentId: NodeId?,
        ): PanResult<Unit> = PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun delete(session: ProviderSession, nodeId: NodeId): PanResult<Unit> =
            PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun downloadUrl(
            session: ProviderSession,
            nodeId: NodeId,
        ): PanResult<DownloadTarget> = PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun upload(
            session: ProviderSession,
            parentId: NodeId?,
            source: UploadSource,
            listener: PanProvider.UploadProgress,
        ): PanResult<RemoteNode> = PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun createShare(
            session: ProviderSession,
            nodeIds: List<NodeId>,
            options: ShareOptions,
        ): PanResult<ShareLink> = PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun cancelShare(
            session: ProviderSession,
            shareId: String,
        ): PanResult<Unit> = PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun listShares(
            session: ProviderSession,
            page: PageRequest,
        ): PanResult<SharePage> = PanResult.Failure(PanError.notSupported("不支持"))
    }

    /** 记下每次入队的参数，好分辨「弹了框」和「真的进了队列」。 */
    private class FakeUploader : UploadLauncher {
        val files = mutableListOf<String>()
        val folders = mutableListOf<String>()

        override fun upload(
            accountId: AccountId,
            parentId: NodeId?,
            localPath: String,
            name: String,
            size: Long,
        ): String {
            files += name
            return "u" + files.size
        }

        override fun uploadFolder(
            accountId: AccountId,
            parentId: NodeId?,
            localDir: String,
            rootName: String,
        ): String {
            folders += rootName
            return "f" + folders.size
        }
    }

    private class FakeSession(
        private val provider: PanProvider,
        private val accountId: AccountId,
    ) : SessionManager {
        override val accounts: Flow<List<AccountSummary>> = flowOf(
            listOf(
                AccountSummary(
                    id = accountId,
                    providerId = provider.descriptor.id,
                    providerName = provider.descriptor.displayName,
                    displayName = "假网盘",
                    lastUsedAt = 0L,
                    capabilities = provider.descriptor.capabilities,
                ),
            ),
        )

        override fun descriptors(): List<ProviderDescriptor> = listOf(provider.descriptor)

        override suspend fun login(
            providerId: ProviderId,
            credentials: ProviderCredentials,
        ): PanResult<AccountSummary> = error("不该调用")

        override suspend fun verify(accountId: AccountId): PanResult<Unit> =
            PanResult.Success(Unit)

        override suspend fun connect(accountId: AccountId): PanResult<ScopedPanProvider> =
            PanResult.Success(
                ScopedPanProvider(
                    provider,
                    ProviderSession(
                        accountId = accountId,
                        credentials = ProviderCredentials.Token("t"),
                        displayName = "假网盘",
                    ),
                ),
            )

        override suspend fun logout(accountId: AccountId) = error("不该调用")

        override suspend fun rename(accountId: AccountId, alias: String?) = error("不该调用")

        override suspend fun reauthenticate(
            accountId: AccountId,
            credentials: ProviderCredentials,
        ): PanResult<Unit> = error("不该调用")
    }

    private fun viewModel(provider: PanProvider, uploader: UploadLauncher) = BrowserViewModel(
        session = FakeSession(provider, account),
        accountId = account,
        uploader = uploader,
    )

    private fun file(name: String) = RemoteNode(
        id = NodeId("/" + name),
        parentId = null,
        name = name,
        kind = NodeKind.FILE,
    )

    private fun folder(name: String) = RemoteNode(
        id = NodeId("/" + name + "/"),
        parentId = null,
        name = name,
        kind = NodeKind.DIR,
    )

    /** 造一个真的存在、大小为 [size] 字节的本地文件——入队前会按实际长度筛掉空文件。 */
    private fun localFile(name: String, size: Int = 8): Pair<File, String> {
        val file = temp.newFile(name)
        file.writeBytes(ByteArray(size))
        return file to name
    }

    @Test
    fun aNameNobodyUsesIsQueuedStraightAway() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("old.txt")))
        val uploader = FakeUploader()
        val vm = viewModel(provider, uploader)
        vm.start()
        advanceUntilIdle()

        val (file, name) = localFile("new.txt")
        vm.upload(file, name)
        advanceUntilIdle()

        // 绝大多数上传都不撞名，不该为此多弹一次框。
        assertFalse(vm.uploadConfirm.value.visible)
        assertEquals(listOf("new.txt"), uploader.files)
    }

    @Test
    fun aClashingNameIsAskedAboutBeforeQueuing() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("a.txt")))
        val uploader = FakeUploader()
        val vm = viewModel(provider, uploader)
        vm.start()
        advanceUntilIdle()

        val (file, name) = localFile("a.txt")
        vm.upload(file, name)
        advanceUntilIdle()

        // 先问再传：此刻队列里应该还是空的。
        assertTrue(uploader.files.isEmpty())
        assertTrue(vm.uploadConfirm.value.visible)
        assertEquals("「a.txt」已经存在", vm.uploadConfirm.value.title)
    }

    @Test
    fun backingOutQueuesNothing() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("a.txt")))
        val uploader = FakeUploader()
        val vm = viewModel(provider, uploader)
        vm.start()
        advanceUntilIdle()

        val (file, name) = localFile("a.txt")
        vm.upload(file, name)
        vm.cancelUpload()
        advanceUntilIdle()

        assertTrue(uploader.files.isEmpty())
        assertFalse(vm.uploadConfirm.value.visible)
    }

    @Test
    fun sayingYesAnywayQueuesTheFile() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("a.txt")))
        val uploader = FakeUploader()
        val vm = viewModel(provider, uploader)
        vm.start()
        advanceUntilIdle()

        val (file, name) = localFile("a.txt")
        vm.upload(file, name)
        vm.confirmUpload()
        advanceUntilIdle()

        assertEquals(listOf("a.txt"), uploader.files)
        assertFalse(vm.uploadConfirm.value.visible)
    }

    @Test
    fun caseDifferenceIsNotAClash() = runTest(dispatcher) {
        // 多数网盘里 a.txt 与 A.txt 是两个不同的文件，把它们当成重名
        // 会让每次上传都白弹一次框，弹几次之后用户就学会闭眼点确认。
        val provider = FakeProvider(listOf(file("a.txt")))
        val uploader = FakeUploader()
        val vm = viewModel(provider, uploader)
        vm.start()
        advanceUntilIdle()

        val (file, name) = localFile("A.TXT")
        vm.upload(file, name)
        advanceUntilIdle()

        assertFalse(vm.uploadConfirm.value.visible)
        assertEquals(listOf("A.TXT"), uploader.files)
    }

    @Test
    fun uploadingAFolderComparesItsOwnName() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(folder("photos")))
        val uploader = FakeUploader()
        val vm = viewModel(provider, uploader)
        vm.start()
        advanceUntilIdle()

        vm.uploadFolder(LocalDirectory(dir = temp.newFolder("photos"), name = "photos"))
        advanceUntilIdle()

        assertTrue(uploader.folders.isEmpty())
        assertTrue(vm.uploadConfirm.value.visible)

        vm.confirmUpload()
        advanceUntilIdle()

        assertEquals(listOf("photos"), uploader.folders)
    }

    @Test
    fun emptyFilesAreStillSkipped() = runTest(dispatcher) {
        val provider = FakeProvider(emptyList())
        val uploader = FakeUploader()
        val vm = viewModel(provider, uploader)
        vm.start()
        advanceUntilIdle()

        val (file, name) = localFile("empty.txt", size = 0)
        vm.upload(file, name)
        advanceUntilIdle()

        // 空文件传上去必然失败，挡在入队之前比让它变成一条失败记录好。
        assertTrue(uploader.files.isEmpty())
        assertEquals("选中的文件都是空的，无法上传", vm.state.value.message)
    }

    @Test
    fun severalClashesAreCountedNotListedOneByOne() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("a.txt"), file("b.txt"), file("c.txt")))
        val uploader = FakeUploader()
        val vm = viewModel(provider, uploader)
        vm.start()
        advanceUntilIdle()

        val first = localFile("a.txt")
        val second = localFile("b.txt")
        val third = localFile("c.txt")
        vm.uploadAll(listOf(first, second, third))
        advanceUntilIdle()

        val pending = vm.uploadConfirm.value
        assertTrue(pending.visible)
        assertEquals("有 3 个文件重名", pending.title)
        assertEquals(3, pending.clashes.size)
        assertTrue(pending.question.contains("覆盖"))
    }

    @Test
    fun theQuestionSaysTheProviderDecides() = runTest(dispatcher) {
        // 各家处理不同，不能替服务商打包票说「不会覆盖」。
        val provider = FakeProvider(listOf(file("a.txt")))
        val uploader = FakeUploader()
        val vm = viewModel(provider, uploader)
        vm.start()
        advanceUntilIdle()

        val (file, name) = localFile("a.txt")
        vm.upload(file, name)

        val question = vm.uploadConfirm.value.question
        assertTrue(question.contains("由网盘自己决定"))
        assertEquals("仍然上传", UploadConflicts.CONFIRM_LABEL)
        // 按钮不写「确定」：按下去可能丢文件，措辞得让人犹豫一下。
    }
}
