package com.panhub.feature.browser

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
import com.panhub.core.model.ShareLink
import com.panhub.core.model.ShareOptions
import com.panhub.core.model.SharePage
import com.panhub.core.session.AccountSummary
import com.panhub.core.session.SessionManager
import com.panhub.provider.api.DownloadTarget
import com.panhub.provider.api.PanProvider
import com.panhub.provider.api.ProviderCredentials
import com.panhub.provider.api.ProviderSession
import com.panhub.provider.api.ScopedPanProvider
import com.panhub.provider.api.UploadSource
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
import org.junit.Test

/**
 * 「复制到」的界面行为。
 *
 * 移动与复制共用同一个目录选择器，所以这里守两件事：
 * 选择器知道自己是哪一个动作（标题与确认按钮不能都说「移动」），
 * 以及确认时真的调到了对应的接口（复制调 copy、移动调 move）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserCopyTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val account = AccountId("fake:1")

    /** 记录 move/copy 调用，列表返回一份固定的目录内容。 */
    private class FakeProvider(
        private val folders: List<RemoteNode> = emptyList(),
        private val canCopy: Boolean = true,
    ) : PanProvider {
        val moves = mutableListOf<Pair<NodeId, NodeId?>>()
        val copies = mutableListOf<Pair<NodeId, NodeId?>>()
        var copyResult: PanResult<Unit> = PanResult.Success(Unit)

        override val descriptor = ProviderDescriptor(
            id = ProviderId("fake"),
            displayName = "假网盘",
            authHint = "",
            capabilities = ProviderCapabilities(
                list = true,
                quota = true,
                move = true,
                copy = canCopy,
            ),
            authMethod = com.panhub.core.model.AuthMethod.TOKEN,
        )

        override suspend fun login(credentials: ProviderCredentials): PanResult<ProviderSession> =
            error("不该调用")

        override suspend fun verify(session: ProviderSession): PanResult<Unit> =
            PanResult.Success(Unit)

        override suspend fun list(
            session: ProviderSession,
            parentId: NodeId?,
            page: PageRequest,
        ): PanResult<NodePage> = PanResult.Success(NodePage(folders, null))

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
        ): PanResult<Unit> {
            moves += nodeId to newParentId
            return PanResult.Success(Unit)
        }

        override suspend fun copy(
            session: ProviderSession,
            node: RemoteNode,
            newParentId: NodeId?,
        ): PanResult<Unit> {
            copies += node.id to newParentId
            return copyResult
        }

        override suspend fun delete(session: ProviderSession, nodeId: NodeId): PanResult<Unit> =
            PanResult.Success(Unit)

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
    }

    private fun viewModel(provider: PanProvider) = BrowserViewModel(
        session = FakeSession(provider, account),
        accountId = account,
    )

    private fun file(name: String) =
        RemoteNode(id = NodeId("/" + name), parentId = null, name = name, kind = NodeKind.FILE)

    private fun pickedFolder(name: String) =
        RemoteNode(id = NodeId("/" + name), parentId = null, name = name, kind = NodeKind.DIR)

    @Test
    fun pickerKnowsWhetherItMovesOrCopies() = runTest(dispatcher) {
        val vm = viewModel(FakeProvider())
        vm.start()
        advanceUntilIdle()

        vm.openFolderPicker(listOf(file("a.txt")))
        assertEquals("移动到", vm.picker.value.titleText)
        assertEquals("移动", vm.picker.value.confirmText)

        vm.closeFolderPicker()
        vm.openFolderPicker(listOf(file("a.txt")), PickerAction.COPY)
        assertEquals("复制到", vm.picker.value.titleText)
        assertEquals("复制", vm.picker.value.confirmText)
    }

    @Test
    fun confirmingACopyCallsCopyInsteadOfMove() = runTest(dispatcher) {
        val provider = FakeProvider()
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        vm.openFolderPicker(listOf(file("a.txt")), PickerAction.COPY)
        advanceUntilIdle()
        // 选择器一进去就在根目录，根目录就是原目录时不能确认；
        // 这里直接在没有子文件夹的根目录上确认，走的是 targetId == null。
        vm.confirmPicker()
        advanceUntilIdle()

        assertEquals(1, provider.copies.size)
        assertEquals(0, provider.moves.size)
        assertEquals(NodeId("/a.txt"), provider.copies.single().first)
    }

    @Test
    fun copyToTheSameFolderIsRefused() = runTest(dispatcher) {
        val provider = FakeProvider(folders = listOf(pickedFolder("docs")))
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        // 文件本来就在 docs 里，把选择器也导航到 docs，
        // 目标等于原目录，确认应当被挡住，一个请求都不该发。
        val node = RemoteNode(
            id = NodeId("/docs/a.txt"),
            parentId = NodeId("/docs"),
            name = "a.txt",
            kind = NodeKind.FILE,
        )
        vm.openFolderPicker(listOf(node), PickerAction.COPY)
        advanceUntilIdle()
        vm.enterPickerFolder(pickedFolder("docs"))
        advanceUntilIdle()

        assertFalse(vm.picker.value.canConfirm)
        vm.confirmPicker()
        advanceUntilIdle()
        assertEquals(0, provider.copies.size)
    }

    @Test
    fun successfulCopyReportsHowManyWereCopied() = runTest(dispatcher) {
        val provider = FakeProvider(folders = listOf(pickedFolder("docs")))
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        val node = RemoteNode(
            id = NodeId("/a.txt"),
            parentId = NodeId("/elsewhere"),
            name = "a.txt",
            kind = NodeKind.FILE,
        )
        vm.openFolderPicker(listOf(node), PickerAction.COPY)
        advanceUntilIdle()
        vm.confirmPicker()
        advanceUntilIdle()

        assertEquals("已复制 1 项", vm.state.value.message)
    }

    @Test
    fun failedCopyShowsTheServerMessage() = runTest(dispatcher) {
        val provider = FakeProvider()
        provider.copyResult = PanResult.Failure(PanError.unknown("空间不足"))

        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        val node = RemoteNode(
            id = NodeId("/a.txt"),
            parentId = NodeId("/elsewhere"),
            name = "a.txt",
            kind = NodeKind.FILE,
        )
        vm.openFolderPicker(listOf(node), PickerAction.COPY)
        advanceUntilIdle()
        vm.confirmPicker()
        advanceUntilIdle()

        assertEquals("空间不足", vm.state.value.message)
    }

    @Test
    fun copyCapabilityComesFromTheProviderDescriptor() = runTest(dispatcher) {
        val withoutCopy = viewModel(FakeProvider(canCopy = false))
        withoutCopy.start()
        advanceUntilIdle()
        assertFalse(withoutCopy.state.value.canCopy)

        val withCopy = viewModel(FakeProvider(canCopy = true))
        withCopy.start()
        advanceUntilIdle()
        assertTrue(withCopy.state.value.canCopy)
    }
}
