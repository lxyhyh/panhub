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
import com.panhub.core.model.SelectionSizes
import com.panhub.core.model.ShareLink
import com.panhub.core.model.ShareOptions
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 多选时顶栏那一行体积。
 *
 * 算术本身在 core:model 里测过，这里守的是接线：勾选状态变了之后
 * 报出来的数字跟不跟着变，以及不该报的时候真的没有。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserSelectionSizeTest {

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

    private fun viewModel(nodes: List<RemoteNode>) = BrowserViewModel(
        session = FakeSession(FakeProvider(nodes), account),
        accountId = account,
    )

    private fun file(name: String, size: Long) = RemoteNode(
        id = NodeId(name),
        parentId = null,
        name = name,
        kind = NodeKind.FILE,
        size = size,
    )

    private fun dir(name: String) = RemoteNode(
        id = NodeId(name),
        parentId = null,
        name = name,
        kind = NodeKind.DIR,
    )

    /**
     * 顶栏那一行。格式化交给一个直白的替身：真正给人看的写法在 core:model
     * 里另有用例钉着，这里只关心数字有没有算对、该不该出现。
     */
    private fun line(vm: BrowserViewModel): String? =
        SelectionSizes.line(vm.state.value.selectionSize) { size -> size.toString() + " B" }

    @Test
    fun nothingSelectedMeansNoSizeLine() = runTest(dispatcher) {
        val big = file("影片.mkv", 2048L * 1024L * 1024L)
        val vm = viewModel(listOf(big))
        vm.start()
        advanceUntilIdle()
        assertFalse(vm.state.value.selectionMode)
        assertNull(line(vm))
    }

    @Test
    fun selectingAFileShowsItsSize() = runTest(dispatcher) {
        val big = file("影片.mkv", 2048L * 1024L * 1024L)
        val vm = viewModel(listOf(big))
        vm.start()
        advanceUntilIdle()
        vm.toggleSelect(big)
        assertEquals("2147483648 B", line(vm))
    }

    @Test
    fun selectingTwoFilesAddsThemUp() = runTest(dispatcher) {
        val a = file("a.bin", 1024L * 1024L)
        val b = file("b.bin", 1024L * 1024L)
        val vm = viewModel(listOf(a, b))
        vm.start()
        advanceUntilIdle()
        vm.toggleSelect(a)
        vm.toggleSelect(b)
        assertEquals("2097152 B", line(vm))
    }

    @Test
    fun aFolderIsMentionedButDoesNotAddBytes() = runTest(dispatcher) {
        val a = file("a.bin", 1024L * 1024L)
        val folder = dir("照片")
        val vm = viewModel(listOf(a, folder))
        vm.start()
        advanceUntilIdle()
        vm.toggleSelect(a)
        vm.toggleSelect(folder)
        assertEquals("1048576 B · 含 1 个文件夹", line(vm))
    }

    @Test
    fun unselectingRecalculates() = runTest(dispatcher) {
        val a = file("a.bin", 1024L * 1024L)
        val b = file("b.bin", 1024L * 1024L)
        val vm = viewModel(listOf(a, b))
        vm.start()
        advanceUntilIdle()
        vm.toggleSelect(a)
        vm.toggleSelect(b)
        vm.toggleSelect(a)
        assertEquals("1048576 B", line(vm))
    }

    @Test
    fun clearingTheSelectionDropsTheLine() = runTest(dispatcher) {
        val a = file("a.bin", 1024L * 1024L)
        val vm = viewModel(listOf(a))
        vm.start()
        advanceUntilIdle()
        vm.toggleSelect(a)
        vm.clearSelection()
        assertNull(line(vm))
    }

    @Test
    fun selectingOnlyFoldersKeepsTheLineEmpty() = runTest(dispatcher) {
        val folder = dir("照片")
        val vm = viewModel(listOf(folder))
        vm.start()
        advanceUntilIdle()
        vm.toggleSelect(folder)
        assertEquals(1, vm.state.value.selectionSize.folders)
        assertNull(line(vm))
    }

    @Test
    fun selectingEverythingCountsTheWholeFolder() = runTest(dispatcher) {
        val a = file("a.bin", 1024L * 1024L)
        val b = file("b.bin", 1024L * 1024L)
        val vm = viewModel(listOf(a, b))
        vm.start()
        advanceUntilIdle()
        vm.toggleSelectAll()
        assertEquals(2, vm.state.value.selectionSize.items)
        assertEquals("2097152 B", line(vm))
    }

    @Test
    fun filesWithoutAReportedSizeAreFlagged() = runTest(dispatcher) {
        val a = file("a.bin", 1024L * 1024L)
        val unknown = file("b.bin", 0L)
        val vm = viewModel(listOf(a, unknown))
        vm.start()
        advanceUntilIdle()
        vm.toggleSelect(a)
        vm.toggleSelect(unknown)
        assertEquals("1048576 B · 1 个文件未报大小", line(vm))
    }
}
