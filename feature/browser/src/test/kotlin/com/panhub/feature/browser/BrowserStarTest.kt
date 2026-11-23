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
 * 收藏切换的界面行为。
 *
 * 收藏只是给文件打个标记，为了它把整页重新拉一遍会丢掉列表的滚动位置与
 * 当前选择，所以这里守的是「只改被点的那一条」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserStarTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val account = AccountId("fake:1")

    private class FakeProvider(
        private val nodes: List<RemoteNode>,
        private val starResult: PanResult<Unit> = PanResult.Success(Unit),
    ) : PanProvider {
        val starCalls = mutableListOf<Pair<List<NodeId>, Boolean>>()
        var listCalls = 0

        override val descriptor = ProviderDescriptor(
            id = ProviderId("fake"),
            displayName = "假网盘",
            authHint = "",
            capabilities = ProviderCapabilities(list = true, star = true),
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
            listCalls++
            return PanResult.Success(NodePage(nodes, null))
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

        override suspend fun setStarred(
            session: ProviderSession,
            nodeIds: List<NodeId>,
            starred: Boolean,
        ): PanResult<Unit> {
            starCalls += nodeIds to starred
            return starResult
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

    private fun viewModel(provider: PanProvider) = BrowserViewModel(
        session = FakeSession(provider, account),
        accountId = account,
    )

    private fun file(name: String, starred: Boolean = false) = RemoteNode(
        id = NodeId("/" + name),
        parentId = null,
        name = name,
        kind = NodeKind.FILE,
        starred = starred,
    )

    @Test
    fun starringSendsTheOppositeOfTheCurrentState() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("a.txt")))
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        vm.toggleStar(file("a.txt"))
        advanceUntilIdle()

        assertEquals(1, provider.starCalls.size)
        assertEquals(listOf(NodeId("/a.txt")), provider.starCalls[0].first)
        assertTrue(provider.starCalls[0].second)
    }

    @Test
    fun unstarringSendsFalse() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("a.txt", starred = true)))
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        vm.toggleStar(file("a.txt", starred = true))
        advanceUntilIdle()

        assertEquals(1, provider.starCalls.size)
        assertFalse(provider.starCalls[0].second)
    }

    @Test
    fun onlyTheToggledRowChanges() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("a.txt"), file("b.txt")))
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        vm.toggleStar(file("a.txt"))
        advanceUntilIdle()

        val stars = vm.state.value.nodes.associate { it.name to it.starred }
        assertTrue(stars.getValue("a.txt"))
        assertFalse(stars.getValue("b.txt"))
    }

    @Test
    fun starringDoesNotReloadTheWholeFolder() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("a.txt")))
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()
        val before = provider.listCalls

        vm.toggleStar(file("a.txt"))
        advanceUntilIdle()

        // 为了一次收藏把整页重拉会丢掉滚动位置，所以这里必须一次都没多拉。
        assertEquals(before, provider.listCalls)
    }

    @Test
    fun aFailureLeavesTheRowAlone() = runTest(dispatcher) {
        val provider = FakeProvider(
            listOf(file("a.txt")),
            starResult = PanResult.Failure(PanError.network("网络不通")),
        )
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        vm.toggleStar(file("a.txt"))
        advanceUntilIdle()

        assertFalse(vm.state.value.nodes.first().starred)
        assertEquals("网络不通", vm.state.value.message)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun selectingSeveralSendsThemInOneRequest() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("a.txt"), file("b.txt")))
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        vm.toggleSelect(file("a.txt"))
        vm.toggleSelect(file("b.txt"))
        vm.starSelected()
        advanceUntilIdle()

        // 收藏是幂等的标记，一次发完比逐个发更省事；服务端本来就收数组。
        assertEquals(1, provider.starCalls.size)
        assertEquals(
            listOf(NodeId("/a.txt"), NodeId("/b.txt")),
            provider.starCalls[0].first.sortedBy { it.value },
        )
        assertTrue(provider.starCalls[0].second)
    }

    @Test
    fun selectingOnlyStarredItemsUnstarsThem() = runTest(dispatcher) {
        val provider = FakeProvider(
            listOf(file("a.txt", starred = true), file("b.txt", starred = true)),
        )
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        vm.toggleSelect(file("a.txt", starred = true))
        vm.toggleSelect(file("b.txt", starred = true))
        vm.starSelected()
        advanceUntilIdle()

        assertEquals(1, provider.starCalls.size)
        assertFalse(provider.starCalls[0].second)
        assertTrue(vm.state.value.nodes.none { it.starred })
    }

    @Test
    fun aMixedSelectionStarsEverything() = runTest(dispatcher) {
        val provider = FakeProvider(
            listOf(file("a.txt", starred = true), file("b.txt")),
        )
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        vm.toggleSelect(file("a.txt", starred = true))
        vm.toggleSelect(file("b.txt"))
        vm.starSelected()
        advanceUntilIdle()

        // 混选时随「有没收藏的」走：用户想做的是把它们都收进来，
        // 而不是因为第一个已经收藏过就全取消。
        assertTrue(provider.starCalls[0].second)
        assertTrue(vm.state.value.nodes.all { it.starred })
    }

    @Test
    fun batchStarClearsTheSelection() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("a.txt")))
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        vm.toggleSelect(file("a.txt"))
        vm.starSelected()
        advanceUntilIdle()

        assertTrue(vm.state.value.selected.isEmpty())
    }

    @Test
    fun aProviderDeclaringStarSupportShowsTheAction() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("a.txt")))
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        assertTrue(vm.state.value.canStar)
    }
}
