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
import com.panhub.core.model.SharePage
import com.panhub.core.preview.PreviewContent
import com.panhub.core.preview.PreviewKind
import com.panhub.core.preview.PreviewLoader
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 预览里翻上一张/下一张。
 *
 * 重点是「翻的是同一批同类文件」而不是「翻整个目录」：照片堆里那个 README
 * 不该出现在翻页序列里，翻到它再翻回来会让人以为漏看了东西。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserPreviewPagingTest {

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
            capabilities = ProviderCapabilities(list = true, download = true),
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
        ): PanResult<DownloadTarget> = PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun upload(
            session: ProviderSession,
            parentId: NodeId?,
            source: UploadSource,
            listener: PanProvider.UploadProgress,
        ): PanResult<RemoteNode> = PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun createShare(
            session: ProviderSession,
            nodeIds: List<NodeId>,
            options: ShareOptions,
        ): PanResult<ShareLink> = PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun cancelShare(
            session: ProviderSession,
            shareId: String,
        ): PanResult<Unit> = PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun listShares(
            session: ProviderSession,
            page: PageRequest,
        ): PanResult<SharePage> = PanResult.Failure(PanError.notSupported("不支持"))
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

    private class FakePreviewer : PreviewLoader {
        val calls = mutableListOf<NodeId>()

        override suspend fun load(
            accountId: AccountId,
            nodeId: NodeId,
            kind: PreviewKind,
            knownSize: Long?,
        ): PanResult<PreviewContent> {
            calls += nodeId
            return when (kind) {
                PreviewKind.IMAGE -> PanResult.Success(PreviewContent(kind, bytes = byteArrayOf(1, 2)))
                PreviewKind.TEXT -> PanResult.Success(PreviewContent(kind, text = "hi"))
                PreviewKind.NONE -> PanResult.Failure(PanError.notSupported("不支持"))
            }
        }
    }

    private fun viewModel(nodes: List<RemoteNode>, previewer: PreviewLoader) = BrowserViewModel(
        session = FakeSession(FakeProvider(nodes), account),
        accountId = account,
        previewer = previewer,
    )

    private fun file(name: String) = RemoteNode(
        id = NodeId("/" + name),
        parentId = null,
        name = name,
        kind = NodeKind.FILE,
    )

    @Test
    fun picturesFlipInTheOrderTheyAppear() = runTest(dispatcher) {
        val previewer = FakePreviewer()
        val vm = viewModel(listOf(file("a.png"), file("b.png"), file("c.png")), previewer)
        vm.start()
        advanceUntilIdle()

        vm.openPreview(file("b.png"))
        advanceUntilIdle()

        assertEquals("2 / 3", vm.preview.value.cursors.position)

        vm.previewNext()
        advanceUntilIdle()

        assertEquals("c.png", vm.preview.value.node?.name)
        assertNull("到最后一个就没有下一张了", vm.preview.value.cursors.next)
    }

    @Test
    fun goingBackLandsOnThePreviousPicture() = runTest(dispatcher) {
        val previewer = FakePreviewer()
        val vm = viewModel(listOf(file("a.png"), file("b.png")), previewer)
        vm.start()
        advanceUntilIdle()

        vm.openPreview(file("b.png"))
        advanceUntilIdle()
        vm.previewPrevious()
        advanceUntilIdle()

        assertEquals("a.png", vm.preview.value.node?.name)
        assertEquals("1 / 2", vm.preview.value.cursors.position)
    }

    @Test
    fun flippingActuallyLoadsTheNewFile() = runTest(dispatcher) {
        val previewer = FakePreviewer()
        val vm = viewModel(listOf(file("a.png"), file("b.png")), previewer)
        vm.start()
        advanceUntilIdle()

        vm.openPreview(file("a.png"))
        advanceUntilIdle()
        vm.previewNext()
        advanceUntilIdle()

        // 翻页要重新取内容，而不是把上一张的画面留在那里。
        assertEquals(listOf(NodeId("/a.png"), NodeId("/b.png")), previewer.calls)
    }

    @Test
    fun aTextFileDoesNotCountPicturesAsNeighbours() = runTest(dispatcher) {
        val previewer = FakePreviewer()
        val vm = viewModel(
            listOf(file("a.png"), file("notes.md"), file("b.png")),
            previewer,
        )
        vm.start()
        advanceUntilIdle()

        vm.openPreview(file("notes.md"))
        advanceUntilIdle()

        // 目录里只有这一份文本，翻页按钮就不该出现。
        assertFalse(vm.preview.value.cursors.canBrowse)
        assertNull(vm.preview.value.cursors.next)
        assertNull(vm.preview.value.cursors.previous)
    }

    @Test
    fun picturesSkipOverTheTextFileInBetween() = runTest(dispatcher) {
        val previewer = FakePreviewer()
        val vm = viewModel(
            listOf(file("a.png"), file("notes.md"), file("b.png")),
            previewer,
        )
        vm.start()
        advanceUntilIdle()

        vm.openPreview(file("a.png"))
        advanceUntilIdle()

        assertEquals("b.png", vm.preview.value.cursors.next?.name)
    }

    @Test
    fun aSingleFileHasNoPager() = runTest(dispatcher) {
        val previewer = FakePreviewer()
        val vm = viewModel(listOf(file("only.png")), previewer)
        vm.start()
        advanceUntilIdle()

        vm.openPreview(file("only.png"))
        advanceUntilIdle()

        assertFalse(vm.preview.value.cursors.canBrowse)
        assertNull(vm.preview.value.cursors.position)
    }

    @Test
    fun closingDropsTheCursors() = runTest(dispatcher) {
        val previewer = FakePreviewer()
        val vm = viewModel(listOf(file("a.png"), file("b.png")), previewer)
        vm.start()
        advanceUntilIdle()

        vm.openPreview(file("a.png"))
        advanceUntilIdle()
        assertTrue(vm.preview.value.cursors.canBrowse)

        vm.closePreview()

        assertFalse(vm.preview.value.cursors.canBrowse)
    }
}
