package com.panhub.feature.browser

import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.AuthMethod
import com.panhub.core.model.FileKind
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.NodePage
import com.panhub.core.model.PageRequest
import com.panhub.core.model.ProviderCapabilities
import com.panhub.core.model.ProviderDescriptor
import com.panhub.core.model.ProviderId
import com.panhub.core.model.Quota
import com.panhub.core.model.RemoteNode
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 类型筛选接进浏览页之后的行为。
 *
 * 分类本身在 core:model 里测过，这里守的是接线：筛完列表里剩什么、
 * 文件夹有没有被误伤、换目录时会不会把上一层的选择带过去。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserKindFilterTest {

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
            PanResult.Success(Unit)

        override suspend fun downloadUrl(
            session: ProviderSession,
            nodeId: NodeId,
        ): PanResult<com.panhub.provider.api.DownloadTarget> =
            PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun createShare(
            session: ProviderSession,
            nodeIds: List<NodeId>,
            options: com.panhub.core.model.ShareOptions,
        ): PanResult<com.panhub.core.model.ShareLink> =
            PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun cancelShare(session: ProviderSession, shareId: String): PanResult<Unit> =
            PanResult.Failure(PanError.notSupported("不支持"))
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

    private fun viewModel(nodes: List<RemoteNode>) = BrowserViewModel(
        session = FakeSession(FakeProvider(nodes), account),
        accountId = account,
    )

    private fun file(name: String, mime: String? = null) = RemoteNode(
        id = NodeId(name),
        parentId = null,
        name = name,
        kind = NodeKind.FILE,
        mimeType = mime,
    )

    private fun dir(name: String) = RemoteNode(
        id = NodeId(name),
        parentId = null,
        name = name,
        kind = NodeKind.DIR,
    )

    @Test
    fun `nothing is filtered until a kind is picked`() = runTest(dispatcher) {
        val vm = viewModel(listOf(file("a.jpg"), file("b.txt")))
        vm.start()
        advanceUntilIdle()

        assertNull(vm.state.value.kind)
        assertEquals(2, vm.state.value.visible.size)
        assertFalse(vm.state.value.filtering)
    }

    @Test
    fun `picking a kind keeps only that kind`() = runTest(dispatcher) {
        val vm = viewModel(listOf(file("a.jpg"), file("b.txt"), file("c.png")))
        vm.start()
        advanceUntilIdle()

        vm.toggleKind(FileKind.IMAGE)

        assertEquals(listOf("a.jpg", "c.png"), vm.state.value.visible.map { it.name })
        assertTrue(vm.state.value.filtering)
    }

    @Test
    fun `folders survive every kind filter`() = runTest(dispatcher) {
        // 筛「图片」时文件夹还在：不然筛完还得先清条件才进得去子目录。
        val vm = viewModel(listOf(dir("相册"), file("a.jpg")))
        vm.start()
        advanceUntilIdle()

        vm.toggleKind(FileKind.IMAGE)

        assertEquals(listOf("相册", "a.jpg"), vm.state.value.visible.map { it.name })
    }

    @Test
    fun `picking the same kind twice turns it off`() = runTest(dispatcher) {
        val vm = viewModel(listOf(file("a.jpg"), file("b.txt")))
        vm.start()
        advanceUntilIdle()

        vm.toggleKind(FileKind.IMAGE)
        vm.toggleKind(FileKind.IMAGE)

        assertNull(vm.state.value.kind)
        assertEquals(2, vm.state.value.visible.size)
    }

    @Test
    fun `picking another kind replaces the current one`() = runTest(dispatcher) {
        // 这是单选：同时筛两种类型等于没筛，逐类看才是用户真正在做的事。
        val vm = viewModel(listOf(file("a.jpg"), file("b.txt"), file("c.mp4")))
        vm.start()
        advanceUntilIdle()

        vm.toggleKind(FileKind.IMAGE)
        vm.toggleKind(FileKind.VIDEO)

        assertEquals(listOf("c.mp4"), vm.state.value.visible.map { it.name })
    }

    @Test
    fun `the keyword and the kind narrow each other`() = runTest(dispatcher) {
        val vm = viewModel(listOf(file("旅行.jpg"), file("旅行.txt"), file("账单.jpg")))
        vm.start()
        advanceUntilIdle()

        vm.toggleKind(FileKind.IMAGE)
        vm.setFilter("旅行")

        assertEquals(listOf("旅行.jpg"), vm.state.value.visible.map { it.name })
    }

    @Test
    fun `walking into a folder drops the kind`() = runTest(dispatcher) {
        val vm = viewModel(listOf(dir("相册"), file("a.jpg")))
        vm.start()
        advanceUntilIdle()
        vm.toggleKind(FileKind.IMAGE)

        vm.enter(vm.state.value.nodes.first { it.isDir })
        advanceUntilIdle()

        assertNull(vm.state.value.kind)
    }

    @Test
    fun `select all in a filtered list only takes the kind`() = runTest(dispatcher) {
        // 全选只选看得见的：筛着图片时「全选」把那两张图选上，不该把文本也带上。
        val vm = viewModel(listOf(dir("相册"), file("a.jpg"), file("b.txt")))
        vm.start()
        advanceUntilIdle()
        vm.toggleKind(FileKind.IMAGE)

        vm.toggleSelectAll()

        assertEquals(setOf(NodeId("相册"), NodeId("a.jpg")), vm.state.value.selected)
    }

    @Test
    fun `clearing the kind leaves the keyword alone`() = runTest(dispatcher) {
        val vm = viewModel(listOf(file("旅行.jpg"), file("账单.jpg")))
        vm.start()
        advanceUntilIdle()
        vm.toggleKind(FileKind.IMAGE)
        vm.setFilter("旅行")

        vm.clearKind()

        assertEquals("旅行", vm.state.value.filter)
        assertEquals(listOf("旅行.jpg"), vm.state.value.visible.map { it.name })
    }
}
