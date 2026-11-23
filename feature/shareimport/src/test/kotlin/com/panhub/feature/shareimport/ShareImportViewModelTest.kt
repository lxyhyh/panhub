package com.panhub.feature.shareimport

import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodePage
import com.panhub.core.model.PageRequest
import com.panhub.core.model.ProviderCapabilities
import com.panhub.core.model.ProviderDescriptor
import com.panhub.core.model.ProviderId
import com.panhub.core.model.Quota
import com.panhub.core.model.RemoteNode
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 从系统分享菜单进来时，链接应该已经填好并直接打开。
 *
 * 这条路径用户看不见输入框，如果它坏了，表现是「分享过来什么也没发生」，
 * 比在页面上点错更难发现，所以钉住。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareImportViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun summary(id: String = "quark:1") = AccountSummary(
        id = AccountId(id),
        providerId = ProviderId("quark"),
        providerName = "夸克网盘",
        displayName = "我的夸克",
        lastUsedAt = 0L,
        capabilities = ProviderCapabilities(share = true, shareImport = true),
    )

    private class StubProvider(
        private val opened: PanResult<ShareSource>,
    ) : PanProvider {
        val openCalls = mutableListOf<Pair<String, String?>>()

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
        ): PanResult<ShareSource> {
            openCalls += shareUrl to passcode
            return opened
        }

        override suspend fun listShare(
            session: ProviderSession,
            source: ShareSource,
            parentId: NodeId?,
            page: PageRequest,
        ): PanResult<ShareEntryPage> = PanResult.Success(ShareEntryPage(emptyList()))
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
    }

    private fun source() = ShareSource(linkId = "abc", token = "stoken", title = "报告")

    @Test
    fun sharedLinkIsFilledInBeforeAnythingIsTapped() = runTest {
        val provider = StubProvider(PanResult.Success(source()))
        val vm = ShareImportViewModel(
            session = StubSession(listOf(summary()), provider),
            initialUrl = "https://pan.quark.cn/s/abc",
            initialPasscode = "a1b2",
        )
        // 还没 start() 就该填好：分享进来的链接不能让用户再填一次。
        assertEquals("https://pan.quark.cn/s/abc", vm.state.value.url)
        assertEquals("a1b2", vm.state.value.passcode)
    }

    @Test
    fun sharedLinkIsOpenedWithoutTappingOpen() = runTest {
        val provider = StubProvider(PanResult.Success(source()))
        val vm = ShareImportViewModel(
            session = StubSession(listOf(summary()), provider),
            initialUrl = "https://pan.quark.cn/s/abc",
            initialPasscode = "a1b2",
        )
        vm.start()
        advanceUntilIdle()
        assertEquals(listOf("https://pan.quark.cn/s/abc" to "a1b2"), provider.openCalls)
        assertEquals("报告", vm.state.value.source?.title)
    }

    @Test
    fun linkWithoutPasscodeIsOpenedWithNull() = runTest {
        val provider = StubProvider(PanResult.Success(source()))
        val vm = ShareImportViewModel(
            session = StubSession(listOf(summary()), provider),
            initialUrl = "https://pan.quark.cn/s/abc",
        )
        vm.start()
        advanceUntilIdle()
        assertNull(provider.openCalls.single().second)
    }

    @Test
    fun manualEntryDoesNotOpenOnItsOwn() = runTest {
        // 空着进来（从账号页点进转存页）时不该自动发请求：
        // 那时候还没有链接，自动打开只会报一个「链接不能为空」。
        val provider = StubProvider(PanResult.Success(source()))
        val vm = ShareImportViewModel(session = StubSession(listOf(summary()), provider))
        vm.start()
        advanceUntilIdle()
        assertTrue(provider.openCalls.isEmpty())
        assertEquals("", vm.state.value.url)
    }

    @Test
    fun failedOpenKeepsTheLinkSoItCanBeRetried() = runTest {
        val provider = StubProvider(PanResult.Failure(PanError.notFound("分享不存在")))
        val vm = ShareImportViewModel(
            session = StubSession(listOf(summary()), provider),
            initialUrl = "https://pan.quark.cn/s/abc",
        )
        vm.start()
        advanceUntilIdle()
        assertEquals("分享不存在", vm.state.value.error?.message)
        // 链接留在输入框里，用户改一下提取码就能再试。
        assertEquals("https://pan.quark.cn/s/abc", vm.state.value.url)
    }
}