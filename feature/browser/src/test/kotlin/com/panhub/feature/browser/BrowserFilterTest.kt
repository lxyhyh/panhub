package com.panhub.feature.browser

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
import com.panhub.core.model.SortOrder
import com.panhub.core.model.SortSpec
import com.panhub.core.session.AccountSummary
import com.panhub.core.session.SessionManager
import com.panhub.provider.api.PanProvider
import com.panhub.provider.api.ProviderCredentials
import com.panhub.provider.api.ProviderSession
import com.panhub.provider.api.ScopedPanProvider
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
 * 目录内过滤接进浏览页之后的行为。
 *
 * 过滤的判定本身在 core:model 里已经测过，这里守的是接线：
 * 有没有真的作用到要画的那份列表上、进目录时会不会把上一层的词带过去。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserFilterTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val account = AccountId("fake:1")

    private class FakeProvider(private val nodes: List<RemoteNode>) : PanProvider {
        var listCalls = 0

        override val descriptor = ProviderDescriptor(
            id = ProviderId("fake"),
            displayName = "假网盘",
            authHint = "",
            capabilities = ProviderCapabilities(list = true),
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
            PanResult.Failure(com.panhub.core.common.PanError.notSupported("不支持"))

        override suspend fun quota(session: ProviderSession): PanResult<Quota> =
            PanResult.Success(Quota(used = 0L, total = 100L))

        override suspend fun createFolder(
            session: ProviderSession,
            parentId: NodeId?,
            name: String,
        ): PanResult<RemoteNode> = PanResult.Failure(com.panhub.core.common.PanError.notSupported("不支持"))

        override suspend fun rename(
            session: ProviderSession,
            nodeId: NodeId,
            newName: String,
        ): PanResult<RemoteNode> = PanResult.Failure(com.panhub.core.common.PanError.notSupported("不支持"))

        override suspend fun move(
            session: ProviderSession,
            nodeId: NodeId,
            newParentId: NodeId?,
        ): PanResult<Unit> = PanResult.Failure(com.panhub.core.common.PanError.notSupported("不支持"))

        override suspend fun delete(session: ProviderSession, nodeId: NodeId): PanResult<Unit> =
            PanResult.Success(Unit)

        override suspend fun downloadUrl(
            session: ProviderSession,
            nodeId: NodeId,
        ): PanResult<com.panhub.provider.api.DownloadTarget> =
            PanResult.Failure(com.panhub.core.common.PanError.notSupported("不支持"))

        override suspend fun createShare(
            session: ProviderSession,
            nodeIds: List<NodeId>,
            options: com.panhub.core.model.ShareOptions,
        ): PanResult<com.panhub.core.model.ShareLink> =
            PanResult.Failure(com.panhub.core.common.PanError.notSupported("不支持"))

        override suspend fun cancelShare(session: ProviderSession, shareId: String): PanResult<Unit> =
            PanResult.Failure(com.panhub.core.common.PanError.notSupported("不支持"))
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
        id = NodeId(name),
        parentId = null,
        name = name,
        kind = NodeKind.FILE,
    )

    private fun dir(name: String) = RemoteNode(
        id = NodeId(name),
        parentId = null,
        name = name,
        kind = NodeKind.DIR,
    )

    @Test
    fun `typing a keyword narrows the list without asking the provider`() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("报告.pdf"), file("照片.jpg")))
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.visible.size)

        vm.setFilter("报告")

        assertEquals(listOf("报告.pdf"), vm.state.value.visible.map { it.name })
        assertEquals(1, provider.listCalls)
    }

    @Test
    fun `clearing the keyword brings everything back`() = runTest(dispatcher) {
        val vm = viewModel(FakeProvider(listOf(file("a.txt"), file("b.txt"))))
        vm.start()
        advanceUntilIdle()
        vm.setFilter("a")
        assertEquals(1, vm.state.value.visible.size)

        vm.clearFilter()

        assertEquals(2, vm.state.value.visible.size)
    }

    @Test
    fun `entering a folder drops the keyword`() = runTest(dispatcher) {
        val vm = viewModel(FakeProvider(listOf(dir("照片"), dir("文档"))))
        vm.start()
        advanceUntilIdle()
        vm.setFilter("照片")
        assertTrue(vm.state.value.filtering)

        vm.enter(vm.state.value.nodes.first { it.isDir })
        advanceUntilIdle()

        assertEquals("", vm.state.value.filter)
        assertFalse(vm.state.value.filtering)
    }

    @Test
    fun `going up also drops the keyword`() = runTest(dispatcher) {
        val vm = viewModel(FakeProvider(listOf(dir("照片"))))
        vm.start()
        advanceUntilIdle()
        vm.enter(vm.state.value.nodes.first())
        advanceUntilIdle()
        vm.setFilter("照")

        vm.goUp()
        advanceUntilIdle()

        assertEquals("", vm.state.value.filter)
    }

    @Test
    fun `filtering keeps the chosen sort order`() = runTest(dispatcher) {
        val vm = viewModel(FakeProvider(listOf(file("a-小.txt"), file("a-大.txt"))))
        vm.start()
        advanceUntilIdle()
        // 默认就是按名称升序（「大」在「小」前面），这里不额外动排序，
        // 要守的是「过滤没有把它重排成别的顺序」。
        vm.setFilter("a-")

        assertEquals(listOf("a-大.txt", "a-小.txt"), vm.state.value.visible.map { it.name })
        assertEquals(SortOrder.NAME, vm.state.value.sort.order)
    }

    @Test
    fun `a keyword that matches nothing leaves an empty visible list`() = runTest(dispatcher) {
        val vm = viewModel(FakeProvider(listOf(file("a.txt"))))
        vm.start()
        advanceUntilIdle()

        vm.setFilter("zzz")

        assertTrue(vm.state.value.visible.isEmpty())
        // 原始列表不受影响：清掉关键字就该原样回来。
        assertEquals(1, vm.state.value.nodes.size)
    }

    @Test
    fun `sorting is remembered as a spec including direction`() = runTest(dispatcher) {
        val vm = viewModel(FakeProvider(listOf(file("a.txt"))))
        vm.start()
        advanceUntilIdle()

        vm.toggleSortDirection()

        assertEquals(SortSpec(order = SortOrder.NAME, descending = true), vm.state.value.sort)
    }

    @Test
    fun `select all in a filtered list only takes what is visible`() = runTest(dispatcher) {
        val vm = viewModel(FakeProvider(listOf(file("a-1.txt"), file("a-2.txt"), file("b.txt"))))
        vm.start()
        advanceUntilIdle()
        vm.setFilter("a-")

        vm.toggleSelectAll()

        assertEquals(setOf(NodeId("a-1.txt"), NodeId("a-2.txt")), vm.state.value.selected)
    }

    @Test
    fun `select all clears when everything visible is already picked`() = runTest(dispatcher) {
        val vm = viewModel(FakeProvider(listOf(file("a.txt"), file("b.txt"))))
        vm.start()
        advanceUntilIdle()

        vm.toggleSelectAll()
        assertTrue(vm.state.value.allSelected)

        vm.toggleSelectAll()
        assertTrue(vm.state.value.selected.isEmpty())
    }
}