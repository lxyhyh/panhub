package com.panhub.feature.browser

import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.ProviderDescriptor
import com.panhub.core.model.ProviderId
import com.panhub.core.model.ProviderCapabilities
import com.panhub.core.model.RemoteNode
import com.panhub.core.preview.PreviewContent
import com.panhub.core.preview.PreviewKind
import com.panhub.core.preview.PreviewLoader
import com.panhub.core.session.AccountSummary
import com.panhub.core.session.SessionManager
import com.panhub.provider.api.ProviderCredentials
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

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserPreviewTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** 预览不需要真正连上网盘，这个会话管理器只用来满足构造函数。 */
    private class StubSession : SessionManager {
        override val accounts: Flow<List<AccountSummary>> = flowOf(emptyList())
        override fun descriptors(): List<ProviderDescriptor> = emptyList()
        override suspend fun login(
            providerId: ProviderId,
            credentials: ProviderCredentials,
        ): PanResult<AccountSummary> = error("不该调用")

        override suspend fun verify(accountId: AccountId): PanResult<Unit> = error("不该调用")
        override suspend fun connect(accountId: AccountId): PanResult<ScopedPanProvider> =
            error("不该调用")

        override suspend fun logout(accountId: AccountId) = error("不该调用")
    }

    private class FakePreviewer(
        private val result: PanResult<PreviewContent>,
    ) : PreviewLoader {
        val calls = mutableListOf<Pair<NodeId, PreviewKind>>()

        override suspend fun load(
            accountId: AccountId,
            nodeId: NodeId,
            kind: PreviewKind,
            knownSize: Long?,
        ): PanResult<PreviewContent> {
            calls += nodeId to kind
            return result
        }
    }

    private fun node(name: String, mime: String? = null, kind: NodeKind = NodeKind.FILE) =
        RemoteNode(id = NodeId("n-" + name), parentId = null, name = name, kind = kind, mimeType = mime)

    private fun viewModel(previewer: PreviewLoader?) = BrowserViewModel(
        session = StubSession(),
        accountId = AccountId("fake:1"),
        previewer = previewer,
    )

    @Test
    fun textFileIsPreviewable() {
        val vm = viewModel(FakePreviewer(PanResult.Success(PreviewContent(PreviewKind.TEXT))))
        assertTrue(vm.canPreview(node("readme.md")))
    }

    @Test
    fun folderIsNeverPreviewable() {
        val vm = viewModel(FakePreviewer(PanResult.Success(PreviewContent(PreviewKind.TEXT))))
        assertFalse(vm.canPreview(node("photos", kind = NodeKind.DIR)))
    }

    @Test
    fun unknownTypeIsNotPreviewable() {
        val vm = viewModel(FakePreviewer(PanResult.Success(PreviewContent(PreviewKind.TEXT))))
        assertFalse(vm.canPreview(node("archive.zip")))
    }

    @Test
    fun withoutLoaderNothingIsPreviewable() {
        // 没有加载器时必须老实说"不能预览"，否则界面会显示一个点了没反应的入口。
        assertFalse(viewModel(null).canPreview(node("readme.md")))
    }

    @Test
    fun openPreviewOnUnsupportedNodeDoesNothing() {
        val previewer = FakePreviewer(PanResult.Success(PreviewContent(PreviewKind.TEXT)))
        val vm = viewModel(previewer)

        assertFalse(vm.openPreview(node("archive.zip")))
        assertFalse(vm.preview.value.visible)
        assertEquals(0, previewer.calls.size)
    }

    @Test
    fun openPreviewShowsContentAfterLoading() = runTest(dispatcher) {
        val previewer = FakePreviewer(
            PanResult.Success(PreviewContent(PreviewKind.TEXT, text = "hello")),
        )
        val vm = viewModel(previewer)

        assertTrue(vm.openPreview(node("a.txt")))
        advanceUntilIdle()

        val state = vm.preview.value
        assertTrue(state.visible)
        assertEquals("hello", state.content?.text)
        assertNull(state.error)
        assertFalse(state.loading)
    }

    @Test
    fun failureIsKeptAndRetryIsOffered() = runTest(dispatcher) {
        val previewer = FakePreviewer(PanResult.Failure(PanError.network("断网了")))
        val vm = viewModel(previewer)

        vm.openPreview(node("a.txt"))
        advanceUntilIdle()

        assertEquals("断网了", vm.preview.value.error?.message)
        assertTrue(vm.preview.value.canRetry)
    }

    @Test
    fun retryAsksTheLoaderAgain() = runTest(dispatcher) {
        val previewer = FakePreviewer(PanResult.Failure(PanError.network("断网了")))
        val vm = viewModel(previewer)

        vm.openPreview(node("a.txt"))
        advanceUntilIdle()
        vm.retryPreview()
        advanceUntilIdle()

        assertEquals(2, previewer.calls.size)
    }

    @Test
    fun closingDropsEverything() = runTest(dispatcher) {
        val previewer = FakePreviewer(
            PanResult.Success(PreviewContent(PreviewKind.TEXT, text = "hello")),
        )
        val vm = viewModel(previewer)

        vm.openPreview(node("a.txt"))
        advanceUntilIdle()
        vm.closePreview()

        assertFalse(vm.preview.value.visible)
        assertNull(vm.preview.value.node)
        assertNull(vm.preview.value.content)
    }

    @Test
    fun imageMimeIsPreviewedAsImage() = runTest(dispatcher) {
        val previewer = FakePreviewer(
            PanResult.Success(PreviewContent(PreviewKind.IMAGE, bytes = byteArrayOf(1, 2))),
        )
        val vm = viewModel(previewer)

        vm.openPreview(node("photo", "image/jpeg"))
        advanceUntilIdle()

        assertEquals(PreviewKind.IMAGE, previewer.calls.single().second)
    }
}
