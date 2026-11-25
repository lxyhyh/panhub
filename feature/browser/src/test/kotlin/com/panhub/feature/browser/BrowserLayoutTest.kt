package com.panhub.feature.browser

import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.AuthMethod
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.NodePage
import com.panhub.core.model.PageRequest
import com.panhub.core.model.Quota
import com.panhub.core.model.ProviderCapabilities
import com.panhub.core.model.ProviderDescriptor
import com.panhub.core.model.ProviderId
import com.panhub.core.model.RemoteNode
import com.panhub.core.model.SortSpec
import com.panhub.core.session.AccountSummary
import com.panhub.core.session.SessionManager
import com.panhub.provider.api.PanProvider
import com.panhub.provider.api.ProviderCredentials
import com.panhub.provider.api.ProviderSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserLayoutTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val account = AccountId("fake:1")

    /** 只记在内存里的显示偏好，够验证接线了。 */
    private class RecordingPrefs(
        private var sort: SortSpec? = null,
        private var layout: FileLayout? = null,
    ) : SortSettingsSource {

        val savedLayouts = mutableListOf<FileLayout>()

        override fun rememberedSort(): SortSpec? = sort

        override suspend fun rememberSort(spec: SortSpec) {
            sort = spec
        }

        override fun rememberedLayout(): FileLayout? = layout

        override suspend fun rememberLayout(layout: FileLayout) {
            savedLayouts += layout
            this.layout = layout
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
            PanResult.Failure(PanError.notSupported("不支持"))

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

        override suspend fun cancelShare(
            session: ProviderSession,
            shareId: String,
        ): PanResult<Unit> = PanResult.Failure(PanError.notSupported("不支持"))
    }

    private class FakeSession(
        private val provider: PanProvider,
        private val account: AccountId,
    ) : SessionManager {

        override val accounts = flowOf(
            listOf(
                AccountSummary(
                    id = account,
                    providerId = ProviderId("fake"),
                    providerName = "假网盘",
                    displayName = "账号",
                    lastUsedAt = 0L,
                    capabilities = ProviderCapabilities(list = true),
                ),
            ),
        )

        override fun descriptors() = listOf(provider.descriptor)

        override suspend fun login(
            providerId: ProviderId,
            credentials: ProviderCredentials,
        ): PanResult<AccountSummary> = error("不该调用")

        override suspend fun verify(accountId: AccountId): PanResult<Unit> = PanResult.Success(Unit)

        override suspend fun connect(
            accountId: AccountId,
        ): PanResult<com.panhub.provider.api.ScopedPanProvider> =
            PanResult.Success(
                com.panhub.provider.api.ScopedPanProvider(
                    provider,
                    ProviderSession(accountId = accountId, credentials = ProviderCredentials.Token("cookie")),
                ),
            )

        override suspend fun logout(accountId: AccountId) = error("不该调用")

        override suspend fun rename(accountId: AccountId, alias: String?) = error("不该调用")

        override suspend fun reauthenticate(
            accountId: AccountId,
            credentials: ProviderCredentials,
        ): PanResult<Unit> = error("不该调用")
    }

    private fun viewModel(prefs: SortSettingsSource?) = BrowserViewModel(
        session = FakeSession(FakeProvider(listOf(file("a.txt"))), account),
        accountId = account,
        sortSettings = prefs,
    )

    private fun file(name: String) = RemoteNode(
        id = NodeId(name),
        parentId = null,
        name = name,
        kind = NodeKind.FILE,
    )

    @Test
    fun theListIsTheDefaultLayout() = runTest(dispatcher) {
        val vm = viewModel(RecordingPrefs())

        vm.start()
        advanceUntilIdle()

        assertEquals(FileLayout.LIST, vm.state.value.layout)
    }

    @Test
    fun aRememberedGridIsAppliedBeforeTheListLoads() = runTest(dispatcher) {
        val vm = viewModel(RecordingPrefs(layout = FileLayout.GRID))

        vm.start()
        advanceUntilIdle()

        assertEquals(FileLayout.GRID, vm.state.value.layout)
    }

    @Test
    fun togglingFlipsBetweenListAndGrid() = runTest(dispatcher) {
        val vm = viewModel(RecordingPrefs())
        vm.start()
        advanceUntilIdle()

        vm.toggleLayout()
        assertEquals(FileLayout.GRID, vm.state.value.layout)

        vm.toggleLayout()
        assertEquals(FileLayout.LIST, vm.state.value.layout)
    }

    @Test
    fun everySwitchIsWrittenBack() = runTest(dispatcher) {
        val prefs = RecordingPrefs()
        val vm = viewModel(prefs)
        vm.start()
        advanceUntilIdle()

        vm.toggleLayout()
        advanceUntilIdle()

        assertEquals(listOf(FileLayout.GRID), prefs.savedLayouts)
    }

    @Test
    fun selectingSomethingBlocksSwitchingLayout() = runTest(dispatcher) {
        // 勾选之后多半正要去点底部的批量动作，这时候换布局等于把目标挪走。
        val vm = viewModel(RecordingPrefs())
        vm.start()
        advanceUntilIdle()
        vm.toggleSelect(vm.state.value.nodes.first())

        vm.toggleLayout()

        assertEquals(FileLayout.LIST, vm.state.value.layout)
        assertTrue(vm.state.value.selectionMode)
    }

    @Test
    fun withoutAnyPreferenceSourceSwitchingStillWorks() = runTest(dispatcher) {
        val vm = viewModel(null)
        vm.start()
        advanceUntilIdle()

        vm.toggleLayout()

        assertEquals(FileLayout.GRID, vm.state.value.layout)
    }
}
