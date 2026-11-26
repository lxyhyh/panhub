package com.panhub.feature.search

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 搜索历史接进搜索页之后的行为。
 *
 * 钉住的是三件最容易退化的：搜过才记、点历史等于直接搜、清空之后不再出现。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchHistoryTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun anAccountlessSearchStillRecordsWhatWasTyped() = runTest {
        val history = FakeHistory()
        val vm = SearchViewModel(StubSession(emptyList(), emptyMap()), history)
        vm.start()
        vm.setQuery("  报告  ")
        vm.submit()
        advanceUntilIdle()

        assertEquals(listOf("报告"), history.remembered)
    }

    @Test
    fun aBlankKeywordIsNotRecorded() = runTest {
        val history = FakeHistory()
        val vm = SearchViewModel(StubSession(emptyList(), emptyMap()), history)
        vm.start()
        vm.setQuery("   ")
        vm.submit()
        advanceUntilIdle()

        assertTrue(history.remembered.isEmpty())
    }

    @Test
    fun theScreenShowsWhateverTheHistoryHolds() = runTest {
        val history = FakeHistory(listOf("照片", "报告"))
        val vm = SearchViewModel(StubSession(emptyList(), emptyMap()), history)
        vm.start()
        advanceUntilIdle()

        assertEquals(listOf("照片", "报告"), vm.state.value.history)
    }

    @Test
    fun pickingAHistoryEntrySearchesItRightAway() = runTest {
        val provider = StubProvider(PanResult.Success(page("hit")))
        val history = FakeHistory(listOf("报告"))
        val vm = SearchViewModel(
            StubSession(listOf(summary("quark:1", "夸克")), mapOf("quark:1" to provider)),
            history,
        )
        vm.start()
        vm.useHistory("报告")
        advanceUntilIdle()

        assertEquals("报告", vm.state.value.query)
        assertEquals(listOf("hit"), vm.state.value.hits.map { it.node.id.value })
        assertEquals(listOf("报告"), provider.keywords)
    }

    @Test
    fun clearingEmptiesTheListOnScreen() = runTest {
        val history = FakeHistory(listOf("照片"))
        val vm = SearchViewModel(StubSession(emptyList(), emptyMap()), history)
        vm.start()
        advanceUntilIdle()

        vm.clearHistory()
        advanceUntilIdle()

        assertTrue(vm.state.value.history.isEmpty())
        assertTrue(history.cleared)
    }

    @Test
    fun withoutAnyHistorySourceNothingBreaks() = runTest {
        val vm = SearchViewModel(StubSession(emptyList(), emptyMap()))
        vm.start()
        vm.useHistory("报告")
        vm.clearHistory()
        advanceUntilIdle()

        assertTrue(vm.state.value.history.isEmpty())
    }

    private fun page(vararg ids: String) = NodePage(ids.map { file(it) }, nextCursor = null)

    private fun file(id: String) =
        RemoteNode(NodeId(id), null, id + ".txt", NodeKind.FILE, size = 10L)

    private fun summary(
        id: String,
        name: String,
        search: Boolean = true,
    ) = AccountSummary(
        id = AccountId(id),
        providerId = ProviderId(id.substringBefore(':')),
        providerName = name,
        displayName = name,
        lastUsedAt = 0L,
        capabilities = ProviderCapabilities(list = true, search = search),
    )

    private class FakeHistory(
        initial: List<String> = emptyList(),
    ) : SearchHistorySource {
        private val flow = MutableStateFlow(initial)

        override val entries: StateFlow<List<String>> = flow.asStateFlow()

        val remembered = mutableListOf<String>()
        val forgotten = mutableListOf<String>()
        var cleared = false

        override suspend fun remember(query: String) {
            remembered += query
            flow.value = (listOf(query) + flow.value.filterNot { it == query }).take(10)
        }

        override suspend fun forget(query: String) {
            forgotten += query
            flow.value = flow.value.filterNot { it == query }
        }

        override suspend fun clear() {
            cleared = true
            flow.value = emptyList()
        }
    }

    private class StubProvider(private val result: PanResult<NodePage>) : PanProvider {
        val keywords = mutableListOf<String>()

        override val descriptor = ProviderDescriptor(
            id = ProviderId("quark"),
            displayName = "夸克",
            capabilities = ProviderCapabilities(list = true, search = true),
        )

        override suspend fun login(credentials: ProviderCredentials): PanResult<ProviderSession> =
            error("不该调用")

        override suspend fun verify(session: ProviderSession): PanResult<Unit> = PanResult.Success(Unit)

        override suspend fun list(
            session: ProviderSession,
            parentId: NodeId?,
            page: PageRequest,
        ): PanResult<NodePage> = error("不该调用")

        override suspend fun stat(session: ProviderSession, nodeId: NodeId): PanResult<RemoteNode> =
            error("不该调用")

        override suspend fun quota(session: ProviderSession): PanResult<Quota> = error("不该调用")

        override suspend fun createFolder(
            session: ProviderSession,
            parentId: NodeId?,
            name: String,
        ): PanResult<RemoteNode> = error("不该调用")

        override suspend fun rename(
            session: ProviderSession,
            nodeId: NodeId,
            newName: String,
        ): PanResult<RemoteNode> = error("不该调用")

        override suspend fun move(
            session: ProviderSession,
            nodeId: NodeId,
            newParentId: NodeId?,
        ): PanResult<Unit> = error("不该调用")

        override suspend fun delete(session: ProviderSession, nodeId: NodeId): PanResult<Unit> =
            error("不该调用")

        override suspend fun downloadUrl(
            session: ProviderSession,
            nodeId: NodeId,
        ): PanResult<DownloadTarget> = error("不该调用")

        override suspend fun createShare(
            session: ProviderSession,
            nodeIds: List<NodeId>,
            options: ShareOptions,
        ): PanResult<ShareLink> = error("不该调用")

        override suspend fun cancelShare(session: ProviderSession, shareId: String): PanResult<Unit> =
            error("不该调用")

        override suspend fun listShares(
            session: ProviderSession,
            page: PageRequest,
        ): PanResult<SharePage> = error("不该调用")

        override suspend fun search(
            session: ProviderSession,
            query: String,
            page: PageRequest,
        ): PanResult<NodePage> {
            keywords += query
            return result
        }
    }

    private class StubSession(
        private val known: List<AccountSummary>,
        private val providers: Map<String, PanProvider>,
    ) : SessionManager {
        override val accounts: Flow<List<AccountSummary>> = flowOf(known)

        override fun descriptors(): List<ProviderDescriptor> =
            providers.values.map { it.descriptor }

        override suspend fun login(
            providerId: ProviderId,
            credentials: ProviderCredentials,
        ): PanResult<AccountSummary> = error("不该调用")

        override suspend fun verify(accountId: AccountId): PanResult<Unit> = error("不该调用")

        override suspend fun connect(accountId: AccountId): PanResult<ScopedPanProvider> {
            val provider = providers[accountId.value]
                ?: return PanResult.Failure(PanError.notFound())
            return PanResult.Success(
                ScopedPanProvider(provider, ProviderSession(accountId, ProviderCredentials.Token("c"))),
            )
        }

        override suspend fun logout(accountId: AccountId) = error("不该调用")

        override suspend fun rename(accountId: AccountId, alias: String?) = error("不该调用")

        override suspend fun reauthenticate(
            accountId: AccountId,
            credentials: ProviderCredentials,
        ): PanResult<Unit> = error("不该调用")
    }
}
