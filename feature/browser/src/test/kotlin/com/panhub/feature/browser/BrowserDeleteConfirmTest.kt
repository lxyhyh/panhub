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
 * 删除前的确认框。
 *
 * 这里守的不是措辞好看，而是「在按下按钮之前，用户知道能不能捞回来」。
 * 各家网盘对删除的语义不同（百度与 123 会进回收站，其余永久删），
 * 文案必须跟着 capabilities.trash 走，不能一刀切。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserDeleteConfirmTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val account = AccountId("fake:1")

    private class FakeProvider(
        private val nodes: List<RemoteNode>,
        private val toTrash: Boolean = false,
        private val failing: Set<String> = emptySet(),
    ) : PanProvider {
        val deleted = mutableListOf<NodeId>()

        override val descriptor = ProviderDescriptor(
            id = ProviderId("fake"),
            displayName = "假网盘",
            authHint = "",
            capabilities = ProviderCapabilities(list = true, delete = true, trash = toTrash),
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

        override suspend fun delete(session: ProviderSession, nodeId: NodeId): PanResult<Unit> {
            deleted += nodeId
            // 单测里要分辨「发出去的请求」和「成功的删除」，所以失败项也要记一笔。
            return if (nodeId.value in failing) {
                PanResult.Failure(PanError.network("网络不通"))
            } else {
                PanResult.Success(Unit)
            }
        }

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

        override suspend fun reauthenticate(
            accountId: AccountId,
            credentials: ProviderCredentials,
        ): PanResult<Unit> = error("不该调用")
    }

    private fun viewModel(provider: PanProvider) = BrowserViewModel(
        session = FakeSession(provider, account),
        accountId = account,
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

    @Test
    fun askingToDeleteShowsTheQuestionWithoutTouchingTheNetwork() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("a.txt")));
        val vm = viewModel(provider);
        vm.start()
        advanceUntilIdle()

        vm.openDelete(listOf(file("a.txt")))

        // 先弹框才是重点：这一步点错不该已经发出去了。
        assertTrue(provider.deleted.isEmpty())
        assertTrue(vm.delete.value.visible)
        assertEquals("删除「a.txt」？", vm.delete.value.title)
    }

    @Test
    fun cancellingSendsNothing() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("a.txt")));
        val vm = viewModel(provider);
        vm.start()
        advanceUntilIdle()

        vm.openDelete(listOf(file("a.txt")))
        vm.cancelDelete()
        advanceUntilIdle()

        assertTrue(provider.deleted.isEmpty())
        assertFalse(vm.delete.value.visible)
    }

    @Test
    fun confirmingReallyDeletesAndSaysSo() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("a.txt")));
        val vm = viewModel(provider);
        vm.start()
        advanceUntilIdle()

        vm.openDelete(listOf(file("a.txt")))
        vm.confirmDelete()
        advanceUntilIdle()

        assertEquals(listOf(NodeId("/a.txt")), provider.deleted)
        assertFalse(vm.delete.value.visible)
        assertEquals("已删除 1 项", vm.state.value.message)
    }

    @Test
    fun selectingSeveralDeletesEveryOne() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("a.txt"), file("b.txt")));
        val vm = viewModel(provider);
        vm.start()
        advanceUntilIdle()

        vm.toggleSelect(file("a.txt"))
        vm.toggleSelect(file("b.txt"))
        vm.deleteSelected()
        advanceUntilIdle()

        // 批量删除同样先弹框，此刻一个都还没删。
        assertTrue(provider.deleted.isEmpty())
        assertEquals("删除这 2 项？", vm.delete.value.title)

        vm.confirmDelete()
        advanceUntilIdle()

        assertEquals(listOf(NodeId("/a.txt"), NodeId("/b.txt")), provider.deleted)
    }

    @Test
    fun aProviderWithTrashPromisesRestoring() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("a.txt")), toTrash = true);
        val vm = viewModel(provider);
        vm.start()
        advanceUntilIdle()

        vm.openDelete(listOf(file("a.txt")))

        assertTrue(vm.delete.value.toTrash)
        assertEquals("移入回收站", vm.delete.value.confirmLabel)
        assertTrue(vm.delete.value.question.contains("回收站"));
        assertFalse(vm.delete.value.question.contains("找不回来"))
    }

    @Test
    fun aProviderWithoutTrashSaysItIsGoneForGood() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("a.txt")), toTrash = false);
        val vm = viewModel(provider);
        vm.start()
        advanceUntilIdle()

        vm.openDelete(listOf(file("a.txt")))

        assertFalse(vm.delete.value.toTrash)
        assertEquals("永久删除", vm.delete.value.confirmLabel)
        assertTrue(vm.delete.value.question.contains("找不回来"))
    }

    @Test
    fun deletingAFolderWarnsAboutItsContents() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(folder("photos")));
        val vm = viewModel(provider);
        vm.start()
        advanceUntilIdle()

        vm.openDelete(listOf(folder("photos")))

        assertTrue(vm.delete.value.question.contains("文件夹里的内容"))
    }

    @Test
    fun aPartialFailureReportsHowManyWentThrough() = runTest(dispatcher) {
        val provider = FakeProvider(
            listOf(file("a.txt"), file("b.txt")),
            failing = setOf("/b.txt"),
        );
        val vm = viewModel(provider);
        vm.start()
        advanceUntilIdle()

        vm.openDelete(listOf(file("a.txt"), file("b.txt")))
        vm.confirmDelete()
        advanceUntilIdle()

        assertEquals("已删除 1/2 项", vm.state.value.message)
    }

    @Test
    fun deletingEverythingClearsTheSelection() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("a.txt"), file("b.txt")));
        val vm = viewModel(provider);
        vm.start()
        advanceUntilIdle()

        vm.toggleSelect(file("a.txt"))
        vm.toggleSelect(file("b.txt"))
        vm.deleteSelected()
        vm.confirmDelete()
        advanceUntilIdle()

        assertTrue(vm.state.value.selected.isEmpty())
    }
}
