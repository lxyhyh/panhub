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
import com.panhub.core.model.SortOrder
import com.panhub.core.model.SortSpec
import com.panhub.core.session.AccountSummary
import com.panhub.core.session.SessionManager
import com.panhub.provider.api.DownloadTarget
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 记住上次挑的排序。
 *
 * 排序本身在 core:model 里测过，这里守的是接线：进页面时用不用记住的那个、
 * 改排序时有没有写回去、写不进去会不会把这一整次浏览搞坏。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserSortPreferenceTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val account = AccountId("fake:1")

    /** 只记在内存里的排序偏好，够验证接线了。 */
    private class RecordingSort(
        private var remembered: SortSpec? = null,
        private val failing: Boolean = false,
    ) : SortSettingsSource {

        val saved = mutableListOf<SortSpec>()

        override fun rememberedSort(): SortSpec? = remembered

        override suspend fun rememberSort(spec: SortSpec) {
            if (failing) error("写盘失败")
            saved += spec
            remembered = spec
        }
    }

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

        override suspend fun verify(session: ProviderSession): PanResult<Unit> = PanResult.Success(Unit)

        override suspend fun list(
            session: ProviderSession,
            parentId: NodeId?,
            page: PageRequest,
        ): PanResult<NodePage> = PanResult.Success(NodePage(nodes, null))

        override suspend fun stat(session: ProviderSession, nodeId: NodeId): PanResult<RemoteNode> =
            PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun quota(session: ProviderSession): PanResult<Quota> =
            PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun createFolder(
            session: ProviderSession,
            parentId: NodeId?,
            name: String,
        ): PanResult<NodeId> = PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun rename(
            session: ProviderSession,
            nodeId: NodeId,
            newName: String,
        ): PanResult<Unit> = PanResult.Failure(PanError.notSupported("不支持"))

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

        override suspend fun createShare(
            session: ProviderSession,
            nodeIds: List<NodeId>,
            options: ShareOptions,
        ): PanResult<ShareLink> = PanResult.Failure(PanError.notSupported("不支持"))

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

        override suspend fun reauthenticate(
            accountId: AccountId,
            credentials: ProviderCredentials,
        ): PanResult<Unit> = error("不该调用")
    }

    private fun viewModel(provider: PanProvider, sorts: SortSettingsSource?) = BrowserViewModel(
        session = FakeSession(provider, account),
        accountId = account,
        sortSettings = sorts,
    )

    private fun file(name: String) = RemoteNode(
        id = NodeId(name),
        parentId = null,
        name = name,
        kind = NodeKind.FILE,
    )

    @Test
    fun aRememberedSortIsAppliedBeforeTheListLoads() = runTest(dispatcher) {
        val sorts = RecordingSort(remembered = SortSpec(SortOrder.SIZE, descending = true))
        val vm = viewModel(FakeProvider(listOf(file("a.txt"))), sorts)

        vm.start()
        advanceUntilIdle()

        assertEquals(SortSpec(SortOrder.SIZE, descending = true), vm.state.value.sort)
    }

    @Test
    fun withoutAPreferenceTheDefaultSortStays() = runTest(dispatcher) {
        val vm = viewModel(FakeProvider(listOf(file("a.txt"))), RecordingSort())

        vm.start()
        advanceUntilIdle()

        assertEquals(SortSpec(), vm.state.value.sort)
    }

    @Test
    fun pickingANewSortIsWrittenBack() = runTest(dispatcher) {
        val sorts = RecordingSort()
        val vm = viewModel(FakeProvider(listOf(file("a.txt"))), sorts)
        vm.start()
        advanceUntilIdle()

        vm.setSortOrder(SortOrder.TIME)
        advanceUntilIdle()

        assertEquals(listOf(SortSpec(SortOrder.TIME, descending = true)), sorts.saved)
    }

    @Test
    fun flippingTheDirectionIsWrittenBackToo() = runTest(dispatcher) {
        val sorts = RecordingSort()
        val vm = viewModel(FakeProvider(listOf(file("a.txt"))), sorts)
        vm.start()
        advanceUntilIdle()

        vm.toggleSortDirection()
        advanceUntilIdle()

        assertEquals(SortSpec(SortOrder.NAME, descending = true), sorts.saved.last())
    }

    @Test
    fun pickingTheSameSortTwiceFlipsItAndSavesBoth() = runTest(dispatcher) {
        val sorts = RecordingSort()
        val vm = viewModel(FakeProvider(listOf(file("a.txt"))), sorts)
        vm.start()
        advanceUntilIdle()

        vm.setSortOrder(SortOrder.TIME)
        advanceUntilIdle()
        vm.setSortOrder(SortOrder.TIME)
        advanceUntilIdle()

        assertEquals(
            listOf(
                SortSpec(SortOrder.TIME, descending = true),
                SortSpec(SortOrder.TIME, descending = false),
            ),
            sorts.saved,
        )
    }

    @Test
    fun withoutAnyPreferenceSourceNothingBreaks() = runTest(dispatcher) {
        val vm = viewModel(FakeProvider(listOf(file("a.txt"))), null)
        vm.start()
        advanceUntilIdle()

        vm.setSortOrder(SortOrder.SIZE)
        advanceUntilIdle()

        assertEquals(SortOrder.SIZE, vm.state.value.sort.order)
    }

    @Test
    fun aFailingWriteDoesNotBreakThisBrowsing() = runTest(dispatcher) {
        // 写不进去最坏就是下次进来排序没记住，不该让当前这次浏览报错。
        val vm = viewModel(FakeProvider(listOf(file("a.txt"))), RecordingSort(failing = true))
        vm.start()
        advanceUntilIdle()

        vm.setSortOrder(SortOrder.SIZE)
        advanceUntilIdle()

        assertEquals(SortOrder.SIZE, vm.state.value.sort.order)
        assertNull(vm.state.value.error)
    }
}
