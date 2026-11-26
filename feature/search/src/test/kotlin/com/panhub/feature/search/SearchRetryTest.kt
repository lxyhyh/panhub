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
import kotlinx.coroutines.test.TestScope
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
 * 一家网盘登录过期，不该逼用户把整次搜索重来一遍。
 *
 * 这里钉住的是「重试只补问没搜成的那几家」：已经搜到的结果原地不动，
 * 补问成功的账号从失败名单里摘掉，还是失败的换成新消息继续挂着。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchRetryTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun retryingOnlyAsksTheAccountsThatFailed() = runTest {
        val ok = StubProvider(PanResult.Success(page("a")))
        val broken = StubProvider(PanResult.Failure(PanError.auth("登录已过期")))
        val vm = viewModel(ok, broken)
        search(vm)
        assertEquals(listOf("报告"), ok.queries)
        vm.retryFailed()
        advanceUntilIdle()
        // 已经搜成的那家不该被再问一次：它的结果一直在屏幕上。
        assertEquals(listOf("报告"), ok.queries)
        assertEquals(listOf("报告", "报告"), broken.queries)
    }

    @Test
    fun retryingKeepsTheResultsAlreadyOnScreen() = runTest {
        val ok = StubProvider(PanResult.Success(page("a", "b")))
        val broken = StubProvider(PanResult.Failure(PanError.auth("登录已过期")))
        val vm = viewModel(ok, broken)
        search(vm)
        vm.retryFailed()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), vm.state.value.hits.map { it.node.id.value })
    }

    @Test
    fun aFailureThatHealsDisappearsFromTheList() = runTest {
        val ok = StubProvider(PanResult.Success(page("a")))
        val broken = StubProvider(PanResult.Failure(PanError.auth("登录已过期")))
        val vm = viewModel(ok, broken)
        search(vm)
        assertEquals(1, vm.state.value.failedCount)
        broken.result = PanResult.Success(page("c"))
        vm.retryFailed()
        advanceUntilIdle()
        assertTrue(vm.state.value.failures.isEmpty())
        // 补问回来的东西接在已有结果后面，不插队。
        assertEquals(listOf("a", "c"), vm.state.value.hits.map { it.node.id.value })
    }

    @Test
    fun aFailureThatPersistsStaysWithTheNewMessage() = runTest {
        val ok = StubProvider(PanResult.Success(page("a")))
        val broken = StubProvider(PanResult.Failure(PanError.auth("登录已过期")))
        val vm = viewModel(ok, broken)
        search(vm)
        broken.result = PanResult.Failure(PanError.network("网络不通"))
        vm.retryFailed()
        advanceUntilIdle()
        assertEquals(listOf("网络不通"), vm.state.value.failures.map { it.message })
        // 同一个账号不该在名单里出现两次。
        assertEquals(1, vm.state.value.failedCount)
    }

    @Test
    fun retryingWithNothingFailedTouchesNothing() = runTest {
        val ok = StubProvider(PanResult.Success(page("a")))
        val vm = SearchViewModel(
            StubSession(listOf(summary("quark:1", "夸克")), mapOf("quark:1" to ok)),
        )
        search(vm)
        vm.retryFailed()
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertEquals(listOf("报告"), ok.queries)
    }

    @Test
    fun retryBringsBackTheCursorForTheAccountThatHealed() = runTest {
        val ok = StubProvider(PanResult.Success(NodePage(listOf(file("a")), nextCursor = null)))
        val broken = StubProvider(PanResult.Failure(PanError.auth("登录已过期")))
        val vm = viewModel(ok, broken)
        search(vm)
        assertFalse(vm.state.value.hasMore)
        broken.result = PanResult.Success(NodePage(listOf(file("c")), nextCursor = "2"))
        vm.retryFailed()
        advanceUntilIdle()
        assertTrue(vm.state.value.hasMore)
        assertEquals(listOf("百度"), vm.state.value.moreNames)
    }

    @Test
    fun theButtonIsOnlyOfferedWhileSomethingFailed() = runTest {
        val ok = StubProvider(PanResult.Success(page("a")))
        val quiet = SearchViewModel(StubSession(listOf(summary("quark:1", "夸克")), mapOf("quark:1" to ok)))
        assertFalse(quiet.state.value.canRetry)
        val broken = StubProvider(PanResult.Failure(PanError.auth("登录已过期")))
        val failing = viewModel(StubProvider(PanResult.Success(page("a"))), broken)
        search(failing)
        assertTrue(failing.state.value.canRetry)
    }

    @Test
    fun failuresCarryTheAccountIdSoRetryKnowsWhoToAsk() = runTest {
        val ok = StubProvider(PanResult.Success(page("a")))
        val broken = StubProvider(PanResult.Failure(PanError.auth("登录已过期")))
        val vm = viewModel(ok, broken)
        search(vm)
        assertEquals("baidu:1", vm.state.value.failures.single().accountId?.value)
    }

    @Test
    fun retryTargetsSkipAccountsThatCannotSearch() {
        val accounts = listOf(summary("quark:1", "夸克"), summary("webdav:1", "坚果云", search = false))
        val failures = listOf(SearchFailure("坚果云", "不支持", AccountId("webdav:1")))
        assertTrue(SearchLogic.retryTargets(accounts, failures).isEmpty())
    }

    @Test
    fun failuresWithoutAnAccountIdAreNeverQuietlyDropped() {
        val stale = listOf(SearchFailure("百度", "登录已过期"))
        assertEquals(stale, SearchLogic.dropRetried(stale, setOf("baidu:1")))
    }

    /** 输关键字并等这一轮跑完。写成 TestScope 扩展才拿得到 advanceUntilIdle。 */
    private suspend fun TestScope.search(vm: SearchViewModel) {
        vm.setQuery("报告")
        vm.submit()
        advanceUntilIdle()
    }

    private fun viewModel(ok: StubProvider, broken: StubProvider): SearchViewModel = SearchViewModel(
        StubSession(
            listOf(summary("quark:1", "夸克"), summary("baidu:1", "百度")),
            mapOf("quark:1" to ok, "baidu:1" to broken),
        ),
    )

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

    private class StubProvider(var result: PanResult<NodePage>) : PanProvider {
        val queries = mutableListOf<String>()

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
            return result
        }
    }

    private class StubSession(
        private val known: List<AccountSummary>,
        private val providers: Map<String, PanProvider>,
    ) : SessionManager {
        override val accounts: Flow<List<AccountSummary>> = flowOf(known)

        override fun descriptors(): List<ProviderDescriptor> = providers.values.map { it.descriptor }

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