package com.panhub.feature.trash

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
import com.panhub.core.model.TrashNode
import com.panhub.core.model.TrashPage
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 还原到别的目录。
 *
 * 这条路径存在的唯一理由：原目录被删掉之后，服务端会把「父级不存在」的条目
 * 单独列出来而不是报错。没有这个出口，用户唯一的办法是把文件先还原到一个
 * 已经不存在的地方，然后看着它失败。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrashRestoreToTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val account = AccountId("fake:1")

    private class FakeProvider(
        private val folders: Map<String?, List<RemoteNode>> = emptyMap(),
    ) : PanProvider {
        val restoreCalls = mutableListOf<Pair<List<NodeId>, NodeId?>>()
        val listCalls = mutableListOf<NodeId?>()

        override val descriptor = ProviderDescriptor(
            id = ProviderId("fake"),
            displayName = "假网盘",
            authHint = "",
            capabilities = ProviderCapabilities(list = true, trash = true),
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
        ): PanResult<NodePage> {
            listCalls += parentId
            return PanResult.Success(NodePage(folders[parentId?.value] ?: emptyList(), null))
        }

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

        override suspend fun listTrash(session: ProviderSession, page: PageRequest): PanResult<TrashPage> =
            PanResult.Success(TrashPage(listOf(trashItem("gone.txt")), null))

        override suspend fun restoreTrash(
            session: ProviderSession,
            nodeIds: List<NodeId>,
            parentId: NodeId?,
        ): PanResult<Unit> {
            restoreCalls += nodeIds to parentId
            return PanResult.Success(Unit)
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

        override suspend fun verify(accountId: AccountId): PanResult<Unit> = PanResult.Success(Unit)

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

    private fun viewModel(provider: PanProvider) = TrashViewModel(
        session = FakeSession(provider, account),
        accountId = account,
        clock = { 1_000L },
    )

    @Test
    fun plainRestoreAsksForTheOriginalPlace() = runTest(dispatcher) {
        val provider = FakeProvider()
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        vm.toggleSelect(trashItem("gone.txt"))
        vm.restoreSelected()
        advanceUntilIdle()

        assertEquals(1, provider.restoreCalls.size)
        assertNull(provider.restoreCalls[0].second)
    }

    @Test
    fun restoreToPickedFolderSendsThatFolder() = runTest(dispatcher) {
        val provider = FakeProvider(mapOf(null to listOf(folder("photos"))))
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        vm.toggleSelect(trashItem("gone.txt"))
        vm.openRestorePicker()
        advanceUntilIdle()
        vm.enterPickerFolder(folder("photos"))
        advanceUntilIdle()
        vm.confirmRestoreTo()
        advanceUntilIdle()

        assertEquals(1, provider.restoreCalls.size)
        assertEquals(NodeId("/photos"), provider.restoreCalls[0].second)
    }

    @Test
    fun confirmingAtTheRootRestoresToTheRoot() = runTest(dispatcher) {
        val provider = FakeProvider()
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        vm.toggleSelect(trashItem("gone.txt"))
        vm.openRestorePicker()
        advanceUntilIdle()
        vm.confirmRestoreTo()
        advanceUntilIdle()

        assertEquals(1, provider.restoreCalls.size)
        // 在根目录按确认就是还原到根目录，与「回原位置」是两回事，
        // 不能退化成 null。
        assertNull(provider.restoreCalls[0].second)
    }

    @Test
    fun pickerOnlyListsFolders() = runTest(dispatcher) {
        val provider = FakeProvider(
            mapOf(null to listOf(folder("photos"), trashItem("a.txt").node)),
        )
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        vm.toggleSelect(trashItem("gone.txt"))
        vm.openRestorePicker()
        advanceUntilIdle()

        assertEquals(listOf(NodeId("/photos")), vm.state.value.picker.folders.map { it.id })
    }

    @Test
    fun pickerStartsAtTheRoot() = runTest(dispatcher) {
        val provider = FakeProvider()
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        vm.toggleSelect(trashItem("gone.txt"))
        vm.openRestorePicker()
        advanceUntilIdle()

        assertTrue(vm.state.value.picker.visible)
        assertTrue(vm.state.value.picker.stack.isEmpty())
        assertFalse(vm.state.value.picker.canGoUp)
    }

    @Test
    fun leavingThePickerGoesUpOneLevel() = runTest(dispatcher) {
        val provider = FakeProvider(mapOf(null to listOf(folder("photos"))))
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        vm.toggleSelect(trashItem("gone.txt"))
        vm.openRestorePicker()
        advanceUntilIdle()
        vm.enterPickerFolder(folder("photos"))
        advanceUntilIdle()
        vm.leavePickerFolder()
        advanceUntilIdle()

        assertTrue(vm.state.value.picker.stack.isEmpty())
        // 退回根目录时重新拉的是根目录的列表，不是刚离开的那一层。
        assertNull(provider.listCalls.last())
    }

    @Test
    fun closingThePickerDropsTheStack() = runTest(dispatcher) {
        val provider = FakeProvider(mapOf(null to listOf(folder("photos"))))
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        vm.toggleSelect(trashItem("gone.txt"))
        vm.openRestorePicker()
        advanceUntilIdle()
        vm.enterPickerFolder(folder("photos"))
        advanceUntilIdle()
        vm.closeRestorePicker()

        assertFalse(vm.state.value.picker.visible)
        assertTrue(vm.state.value.picker.stack.isEmpty())
        // 关掉选择器不该发出还原请求。
        assertTrue(provider.restoreCalls.isEmpty())
    }

    @Test
    fun pickerDoesNotOpenWithoutASelection() = runTest(dispatcher) {
        val provider = FakeProvider()
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        vm.openRestorePicker()
        advanceUntilIdle()

        assertFalse(vm.state.value.picker.visible)
        assertTrue(provider.listCalls.isEmpty())
    }
}

private fun trashItem(name: String) = TrashNode(
    node = RemoteNode(
        id = NodeId("/" + name),
        parentId = null,
        name = name,
        kind = NodeKind.FILE,
    ),
)

private fun folder(name: String, parent: String? = null) = RemoteNode(
    id = NodeId("/" + name),
    parentId = parent?.let { NodeId(it) },
    name = name,
    kind = NodeKind.DIR,
)
