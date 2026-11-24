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
 * 一次搜索要同时打几个网盘，任何一家出问题都不能让整次搜索看起来像空的。
 * 这里钉住的正是这条：一个账号失败，其它账号的结果照常出现。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun oneFailingAccountDoesNotHideTheOthersResults() = runTest {
        val ok = StubProvider(PanResult.Success(page("a", "b")))
        val broken = StubProvider(PanResult.Failure(PanError.auth("登录已过期")))
        val vm = SearchViewModel(
            StubSession(
                listOf(summary("quark:1", "夸克"), summary("baidu:1", "百度")),
                mapOf("quark:1" to ok, "baidu:1" to broken),
            ),
        )
        vm.setQuery("报告")
        vm.submit()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), vm.state.value.hits.map { it.node.id.value })
        assertEquals(listOf("百度"), vm.state.value.failures.map { it.accountName })
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun accountsWithoutSearchAreNeverAsked() = runTest {
        val provider = StubProvider(PanResult.Success(page("a")))
        val vm = SearchViewModel(
            StubSession(
                listOf(summary("quark:1", "夸克"), summary("webdav:1", "坚果云", search = false)),
                mapOf("quark:1" to provider),
            ),
        )
        vm.start()
        vm.setQuery("报告")
        vm.submit()
        advanceUntilIdle()
        assertEquals(listOf("报告"), provider.queries)
        assertEquals(listOf("坚果云"), vm.state.value.skipped)
    }

    @Test
    fun blankQueryDoesNotTouchTheNetwork() = runTest {
        val provider = StubProvider(PanResult.Success(page("a")))
        val vm = SearchViewModel(StubSession(listOf(summary("quark:1", "夸克")), mapOf("quark:1" to provider)))
        vm.setQuery("   ")
        vm.submit()
        advanceUntilIdle()
        assertTrue(provider.queries.isEmpty())
        assertFalse(vm.state.value.searched)
    }

    @Test
    fun hitsCarryTheAccountTheyCameFrom() = runTest {
        val provider = StubProvider(PanResult.Success(page("a")))
        val vm = SearchViewModel(StubSession(listOf(summary("quark:1", "夸克")), mapOf("quark:1" to provider)))
        vm.setQuery("报告")
        vm.submit()
        advanceUntilIdle()
        assertEquals("quark:1", vm.state.value.hits.single().accountId.value)
        assertEquals("夸克", vm.state.value.hits.single().accountName)
    }

    @Test
    fun theKeywordIsTrimmedBeforeItIsSent() = runTest {
        val provider = StubProvider(PanResult.Success(page("a")))
        val vm = SearchViewModel(StubSession(listOf(summary("quark:1", "夸克")), mapOf("quark:1" to provider)))
        vm.setQuery("  报告 ")
        vm.submit()
        advanceUntilIdle()
        assertEquals(listOf("报告"), provider.queries)
    }

    @Test
    fun eachAccountIsAskedOnlyOncePerSubmit() = runTest {
        val provider = StubProvider(PanResult.Success(page("a")))
        val vm = SearchViewModel(StubSession(listOf(summary("quark:1", "夸克")), mapOf("quark:1" to provider)))
        vm.setQuery("报告")
        vm.submit()
        advanceUntilIdle()
        vm.submit()
        advanceUntilIdle()
        assertEquals(listOf("报告", "报告"), provider.queries)
    }

    @Test
    fun groupsComeBackInAccountOrder() = runTest {
        val quark = StubProvider(PanResult.Success(page("a")))
        val baidu = StubProvider(PanResult.Success(page("b")))
        val vm = SearchViewModel(
            StubSession(
                listOf(summary("quark:1", "夸克"), summary("baidu:1", "百度")),
                mapOf("quark:1" to quark, "baidu:1" to baidu),
            ),
        )
        vm.setQuery("报告")
        vm.submit()
        advanceUntilIdle()
        assertEquals(listOf("夸克", "百度"), vm.state.value.groups.map { it.first })
    }

    @Test
    fun noAccountsCapableOfSearchingEndsWithoutAnError() = runTest {
        val vm = SearchViewModel(
            StubSession(listOf(summary("webdav:1", "坚果云", search = false)), emptyMap()),
        )
        vm.setQuery("报告")
        vm.submit()
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertTrue(vm.state.value.hits.isEmpty())
        assertEquals(listOf("坚果云"), vm.state.value.skipped)
    }

    @Test
    fun aProviderWithAnotherPageOffersToLoadMore() = runTest {
        val provider = StubProvider(PanResult.Success(NodePage(listOf(file("a")), nextCursor = "2")))
        val vm = SearchViewModel(StubSession(listOf(summary("quark:1", "夸克")), mapOf("quark:1" to provider)))
        vm.setQuery("报告")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.state.value.hasMore)
        assertEquals(listOf("夸克"), vm.state.value.moreNames)
    }

    @Test
    fun loadingMoreAppendsTheSecondPageWithItsCursor() = runTest {
        val provider = StubProvider(PanResult.Success(NodePage(listOf(file("a")), nextCursor = "2")))
        provider.followUp = NodePage(listOf(file("b")), nextCursor = null)
        val vm = SearchViewModel(StubSession(listOf(summary("quark:1", "夸克")), mapOf("quark:1" to provider)))
        vm.setQuery("报告")
        vm.submit()
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), vm.state.value.hits.map { it.node.id.value })
        assertEquals(listOf(null, "2"), provider.cursors)
        assertFalse(vm.state.value.hasMore)
    }

    @Test
    fun loadingMoreKeepsTheOldResultsInPlace() = runTest {
        val provider = StubProvider(PanResult.Success(NodePage(listOf(file("a"), file("b")), nextCursor = "2")))
        provider.followUp = NodePage(listOf(file("c")), nextCursor = null)
        val vm = SearchViewModel(StubSession(listOf(summary("quark:1", "夸克")), mapOf("quark:1" to provider)))
        vm.setQuery("报告")
        vm.submit()
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        // 已经看过的那批不换位置，否则用户会以为自己点错了。
        assertEquals(listOf("a", "b", "c"), vm.state.value.hits.map { it.node.id.value })
    }

    @Test
    fun aFailedAccountNeverAdvertisesMore() = runTest {
        val broken = StubProvider(PanResult.Failure(PanError.network("网络不通")))
        val vm = SearchViewModel(StubSession(listOf(summary("quark:1", "夸克")), mapOf("quark:1" to broken)))
        vm.setQuery("报告")
        vm.submit()
        advanceUntilIdle()
        assertFalse(vm.state.value.hasMore)
    }

    @Test
    fun theLimitIsPassedToTheProvider() = runTest {
        val provider = StubProvider(PanResult.Success(page("a")))
        val vm = SearchViewModel(StubSession(listOf(summary("quark:1", "夸克")), mapOf("quark:1" to provider)))
        vm.setQuery("报告")
        vm.submit()
        advanceUntilIdle()
        assertEquals(listOf(SearchLogic.PER_ACCOUNT_LIMIT), provider.limits)
    }

    private fun file(id: String) = RemoteNode(NodeId(id), null, id + ".txt", NodeKind.FILE)

    private fun page(vararg ids: String) = NodePage(
        ids.map { RemoteNode(NodeId(it), null, it + ".txt", NodeKind.FILE) },
        null,
    )

    private fun summary(id: String, name: String, search: Boolean = true) = AccountSummary(
        id = AccountId(id),
        providerId = ProviderId("p"),
        providerName = name,
        displayName = name,
        lastUsedAt = 0L,
        capabilities = ProviderCapabilities(search = search),
    )

    private class StubProvider(private val result: PanResult<NodePage>) : PanProvider {
        val queries = mutableListOf<String>()
        val limits = mutableListOf<Int>()
        val cursors = mutableListOf<String?>()

        /** 第二页及之后给什么；null 表示和第一页一样。 */
        var followUp: NodePage? = null

        override val descriptor = ProviderDescriptor(
            id = ProviderId("p"),
            displayName = "测试网盘",
            capabilities = ProviderCapabilities(search = true),
        )

        override suspend fun login(credentials: ProviderCredentials): PanResult<ProviderSession> =
            error("不该调用")

        override suspend fun verify(session: ProviderSession): PanResult<Unit> = error("不该调用")

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

        override suspend fun upload(
            session: ProviderSession,
            parentId: NodeId?,
            source: UploadSource,
            listener: PanProvider.UploadProgress,
        ): PanResult<RemoteNode> = error("不该调用")

        override suspend fun search(
            session: ProviderSession,
            query: String,
            page: PageRequest,
        ): PanResult<NodePage> {
            queries += query
            limits += page.limit
            cursors += page.cursor
            // 第一页用构造时给的，之后的页用 followUp 覆盖。
            return if (page.cursor == null) result else followUp?.let { PanResult.Success(it) } ?: result
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
            val provider = providers[accountId.value] ?: return PanResult.Failure(PanError.notFound())
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
