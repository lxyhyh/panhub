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
 * 分享里的文件一多，转存就成了「在一堆东西里找那一份」。
 *
 * 这条路径最容易错的地方不是筛不出来，而是筛出来之后「全选」
 * 把看不见的那些也一起勾上 —— 用户按一下「转存 3 项」，
 * 自己的网盘里却多出十几个文件。所以每条规则都钉住。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareImportFilterTest {

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

    private fun file(id: String, name: String = id) = ShareEntry(
        id = NodeId(id),
        name = name,
        kind = NodeKind.FILE,
    )

    private fun dir(id: String, name: String = id) = ShareEntry(
        id = NodeId(id),
        name = name,
        kind = NodeKind.DIR,
    )

    /** 根目录返回 [root]，任何子目录返回空，够用来验证「进目录会不会清关键字」。 */
    private class StubProvider(
        private val root: List<ShareEntry>,
    ) : PanProvider {
        val listed = mutableListOf<NodeId?>()

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
            listed += parentId
            return PanResult.Success(ShareEntryPage(if (parentId == null) root else emptyList()))
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

    /** 打开一份有三份文件、一个文件夹的分享，等它读完。 */
    private suspend fun open(
        scope: kotlinx.coroutines.test.TestScope,
        provider: StubProvider,
    ): ShareImportViewModel {
        val vm = ShareImportViewModel(
            session = StubSession(listOf(summary()), provider),
            initialUrl = "https://pan.quark.cn/s/abc",
        )
        vm.start()
        scope.advanceUntilIdle()
        return vm
    }

    private fun provider() = StubProvider(
        listOf(
            file("a", "年度报告.pdf"),
            file("b", "报销单.xlsx"),
            dir("c", "报告归档"),
        ),
    )

    @Test
    fun everythingIsVisibleWithoutAKeyword() = runTest {
        val vm = open(this, provider())
        assertEquals(listOf("a", "b", "c"), vm.state.value.visibleEntries.map { it.id.value })
        assertFalse(vm.state.value.filtering)
        assertEquals(null, vm.state.value.filterSummary)
    }

    @Test
    fun aKeywordNarrowsDirectoriesAndFilesAlike() = runTest {
        val vm = open(this, provider())
        vm.setQuery("报告")
        // 文件夹也按名字参与筛选：用户找「报告归档」时不该因为它是文件夹就藏起来。
        assertEquals(listOf("a", "c"), vm.state.value.visibleEntries.map { it.id.value })
        assertTrue(vm.state.value.filtering)
        assertEquals("筛选中 2/3 项", vm.state.value.filterSummary)
    }

    @Test
    fun matchingIgnoresCase() = runTest {
        val vm = open(this, StubProvider(listOf(file("x", "Budget.XLSX"), file("y", "notes.txt"))))
        vm.setQuery("budget")
        assertEquals(listOf("x"), vm.state.value.visibleEntries.map { it.id.value })
    }

    @Test
    fun nothingMatchingIsNotTheSameAsAnEmptyFolder() = runTest {
        val vm = open(this, provider())
        vm.setQuery("没有这个东西")
        assertTrue(vm.state.value.visibleEntries.isEmpty())
        assertTrue(vm.state.value.filteredOut)
        // 空态要说「没匹配上」，而不是「这个文件夹是空的」——后者会让人以为分享坏了。
        assertEquals(
            com.panhub.core.model.NodeFilter.emptyMessage("没有这个东西"),
            vm.state.value.emptyFilterMessage,
        )
    }

    @Test
    fun anActuallyEmptyFolderIsNotAFilteringProblem() = runTest {
        val vm = open(this, StubProvider(emptyList()))
        assertFalse(vm.state.value.filteredOut)
        vm.setQuery("随便")
        assertFalse(vm.state.value.filteredOut)
    }

    @Test
    fun selectAllOnlyTakesWhatIsOnScreen() = runTest {
        val vm = open(this, provider())
        vm.setQuery("报告")
        vm.toggleAll()
        // 只勾看得见的两项；「报销单」被筛掉了，不该被顺手带上。
        assertEquals(setOf(NodeId("a"), NodeId("c")), vm.state.value.selected)
    }

    @Test
    fun selectAllIsJudgedAgainstWhatIsOnScreen() = runTest {
        val vm = open(this, provider())
        vm.toggle(NodeId("a"))
        vm.toggle(NodeId("c"))
        vm.setQuery("报告")
        // 筛出来的两项都勾了，按钮就该说「取消全选」。
        assertTrue(vm.state.value.allSelected)
    }

    @Test
    fun selectionSurvivesKeywordChanges() = runTest {
        val vm = open(this, provider())
        vm.toggle(NodeId("b"))
        vm.setQuery("报告")
        // 换关键字不清选择：用户先勾一份、再去找另一份是常见顺序。
        assertEquals(setOf(NodeId("b")), vm.state.value.selected)
    }

    @Test
    fun clearingTheKeywordBringsEverythingBack() = runTest {
        val vm = open(this, provider())
        vm.setQuery("报告")
        vm.clearQuery()
        assertEquals(3, vm.state.value.visibleEntries.size)
        assertFalse(vm.state.value.filtering)
    }

    @Test
    fun enteringAFolderDropsTheKeyword() = runTest {
        val vm = open(this, provider())
        vm.setQuery("报告")
        vm.enter(vm.state.value.entries.first { it.isDir })
        // 上一层的命中名字多半跟这一层没关系，留着会让人以为新目录是空的。
        assertEquals("", vm.state.value.query)
        assertFalse(vm.state.value.filtering)
    }

    @Test
    fun goingUpAlsoDropsTheKeyword() = runTest {
        val vm = open(this, provider())
        vm.enter(vm.state.value.entries.first { it.isDir })
        vm.setQuery("x")
        vm.goUp()
        assertEquals("", vm.state.value.query)
    }

}