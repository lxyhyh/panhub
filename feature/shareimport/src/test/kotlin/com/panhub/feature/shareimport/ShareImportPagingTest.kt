package com.panhub.feature.shareimport

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
import com.panhub.core.model.ShareEntry
import com.panhub.core.model.ShareEntryPage
import com.panhub.core.model.ShareLink
import com.panhub.core.model.ShareOptions
import com.panhub.core.model.SharePage
import com.panhub.core.model.ShareSource
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 分享里超过一页时，后面那些项必须能被要走。
 *
 * 没有这一步的表现最容易被忽略：页面上只有前一百条，用户以为分享就这么多，
 * 于是转存了一份残缺的副本还不自知。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareImportPagingTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun summary() = AccountSummary(
        id = AccountId("quark:1"),
        providerId = ProviderId("quark"),
        providerName = "夸克网盘",
        displayName = "我的夸克",
        lastUsedAt = 0L,
        capabilities = ProviderCapabilities(share = true, shareImport = true),
    )

    /** 两页：第一页 a,b 且带游标，第二页 c 且没有游标。 */
    private class PagingProvider : PanProvider {
        val asked = mutableListOf<String?>()

        override val descriptor = ProviderDescriptor(
            id = ProviderId("quark"),
            displayName = "夸克网盘",
            capabilities = ProviderCapabilities(shareImport = true),
        )

        override suspend fun login(credentials: ProviderCredentials): PanResult<ProviderSession> =
            error("不该调用")

        override suspend fun verify(session: ProviderSession): PanResult<Unit> = error("不该调用")

        override suspend fun list(
            session: ProviderSession,
            parentId: NodeId?,
            page: PageRequest,
        ): PanResult<NodePage> = PanResult.Success(NodePage(emptyList()))

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

        override suspend fun upload(
            session: ProviderSession,
            parentId: NodeId?,
            source: UploadSource,
            listener: PanProvider.UploadProgress,
        ): PanResult<RemoteNode> = error("不该调用")

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

        override suspend fun openShare(
            session: ProviderSession,
            shareUrl: String,
            passcode: String?,
        ): PanResult<ShareSource> =
            PanResult.Success(ShareSource(linkId = "abc", token = "stoken", title = "报告"))

        override suspend fun listShare(
            session: ProviderSession,
            source: ShareSource,
            parentId: NodeId?,
            page: PageRequest,
        ): PanResult<ShareEntryPage> {
            asked += page.cursor
            return when (page.cursor) {
                null -> PanResult.Success(
                    ShareEntryPage(
                        items = listOf(
                            ShareEntry(NodeId("a"), name = "a", kind = NodeKind.FILE),
                            ShareEntry(NodeId("b"), name = "b", kind = NodeKind.FILE),
                        ),
                        nextCursor = "2",
                    ),
                )
                else -> PanResult.Success(
                    ShareEntryPage(
                        items = listOf(ShareEntry(NodeId("c"), name = "c", kind = NodeKind.FILE)),
                        nextCursor = null,
                    ),
                )
            }
        }
    }

    private class StubSession(
        private val known: List<AccountSummary>,
        private val provider: PanProvider,
    ) : SessionManager {
        override val accounts: Flow<List<AccountSummary>> = flowOf(known)

        override fun descriptors(): List<ProviderDescriptor> = listOf(provider.descriptor)

        override suspend fun login(
            providerId: ProviderId,
            credentials: ProviderCredentials,
        ): PanResult<AccountSummary> = error("不该调用")

        override suspend fun verify(accountId: AccountId): PanResult<Unit> = error("不该调用")

        override suspend fun connect(accountId: AccountId): PanResult<ScopedPanProvider> =
            PanResult.Success(
                ScopedPanProvider(
                    provider,
                    ProviderSession(accountId, ProviderCredentials.Token("cookie")),
                ),
            )

        override suspend fun logout(accountId: AccountId) = error("不该调用")

        override suspend fun rename(accountId: AccountId, alias: String?) = error("不该调用")
        override suspend fun reauthenticate(
            accountId: AccountId,
            credentials: ProviderCredentials,
        ): PanResult<Unit> = error("不该调用")
    }

    private suspend fun TestScope.opened(provider: PagingProvider): ShareImportViewModel {
        val vm = ShareImportViewModel(
            session = StubSession(listOf(summary()), provider),
            initialUrl = "https://pan.quark.cn/s/abc",
        )
        vm.start()
        advanceUntilIdle()
        return vm
    }

    @Test
    fun theFirstPageArrivesWithACursorForTheRest() = runTest {
        val provider = PagingProvider()
        val vm = opened(provider)
        assertEquals(listOf("a", "b"), vm.state.value.entries.map { it.id.value })
        assertTrue(vm.state.value.hasMore)
    }

    @Test
    fun askingForMoreAppendsInsteadOfReplacing() = runTest {
        val provider = PagingProvider()
        val vm = opened(provider)
        vm.loadMore()
        advanceUntilIdle()
        // 追加而不是替换：替换会把用户已经勾好的前两页从界面上抹掉。
        assertEquals(listOf("a", "b", "c"), vm.state.value.entries.map { it.id.value })
        assertEquals(listOf(null, "2"), provider.asked)
    }

    @Test
    fun theLastPageEndsThePaging() = runTest {
        val provider = PagingProvider()
        val vm = opened(provider)
        vm.loadMore()
        advanceUntilIdle()
        assertNull(vm.state.value.nextCursor)
        // 到底之后再叫一次不该再发请求。
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(2, provider.asked.size)
    }

    @Test
    fun aRepeatedEntryIsNotListedTwice() = runTest {
        val provider = PagingProvider()
        val vm = opened(provider)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(3, vm.state.value.entries.map { it.id.value }.distinct().size)
    }

    @Test
    fun loadingMoreDoesNotClearTheSelection() = runTest {
        val provider = PagingProvider()
        val vm = opened(provider)
        vm.toggle(NodeId("a"))
        vm.loadMore()
        advanceUntilIdle()
        // 翻页不该动选择：用户勾好前两页再往下翻是正常顺序。
        assertEquals(setOf(NodeId("a")), vm.state.value.selected)
    }
}