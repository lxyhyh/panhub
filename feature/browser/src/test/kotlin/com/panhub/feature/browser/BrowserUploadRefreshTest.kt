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
import com.panhub.core.session.AccountSummary
import com.panhub.core.session.SessionManager
import com.panhub.core.transfer.UploadLauncher
import com.panhub.core.transfer.UploadedFile
import com.panhub.provider.api.DownloadTarget
import com.panhub.provider.api.PanProvider
import com.panhub.provider.api.ProviderCredentials
import com.panhub.provider.api.ProviderSession
import com.panhub.provider.api.ScopedPanProvider
import com.panhub.provider.api.UploadSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
 * 传完自己刷新列表。
 *
 * 守的是「传上去的文件当场就能看见」：以前要用户自己点刷新，或者退出去再进来，
 * 而多数人传完的第一件事就是找一眼自己的文件在不在。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserUploadRefreshTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val account = AccountId("fake:1")

    private class FakeProvider(var nodes: List<RemoteNode>) : PanProvider {
        var listCalls = 0

        override val descriptor = ProviderDescriptor(
            id = ProviderId("fake"),
            displayName = "假网盘",
            authHint = "",
            capabilities = ProviderCapabilities(list = true, upload = true),
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
        ): PanResult<NodePage> {
            listCalls++
            return PanResult.Success(NodePage(nodes, null))
        }

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

    /** 只负责「报一条传完了」；真正的入队不在这条测试的关心范围内。 */
    private class FakeUploader : UploadLauncher {
        private val events = MutableSharedFlow<UploadedFile>(extraBufferCapacity = 16)

        override val uploaded: Flow<UploadedFile> get() = events

        override fun upload(
            accountId: AccountId,
            parentId: NodeId?,
            localPath: String,
            name: String,
            size: Long,
        ): String = "u1"

        override fun uploadFolder(
            accountId: AccountId,
            parentId: NodeId?,
            localDir: String,
            rootName: String,
        ): String = "f1"

        fun announce(event: UploadedFile) {
            events.tryEmit(event)
        }
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

    private fun viewModel(provider: PanProvider, uploader: UploadLauncher) = BrowserViewModel(
        session = FakeSession(provider, account),
        accountId = account,
        uploader = uploader,
    )

    private fun file(name: String) = RemoteNode(
        id = NodeId("/" + name),
        parentId = null,
        name = name,
        kind = NodeKind.FILE,
    )

    @Test
    fun aFinishedUploadMakesTheNewFileShowUp() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("old.txt")))
        val uploader = FakeUploader()
        val vm = viewModel(provider, uploader)
        vm.start()
        advanceUntilIdle()
        assertEquals(1, provider.listCalls)

        // 服务端此刻已经有了新文件，只是还没人问过它。
        provider.nodes = listOf(file("old.txt"), file("new.txt"))
        uploader.announce(UploadedFile(account, null, "new.txt"))
        advanceUntilIdle()

        assertEquals(2, provider.listCalls)
        assertTrue(vm.state.value.nodes.any { it.name == "new.txt" })
    }

    @Test
    fun anUploadIntoAnotherFolderLeavesThisListAlone() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("old.txt")))
        val uploader = FakeUploader()
        val vm = viewModel(provider, uploader)
        vm.start()
        advanceUntilIdle()

        uploader.announce(UploadedFile(account, NodeId("/别的目录"), "x.txt"))
        advanceUntilIdle()

        // 别的目录传完跟着刷新，用户会看到列表闪一下，而他要找的文件根本不在这儿。
        assertEquals(1, provider.listCalls)
    }

    @Test
    fun anUploadOnAnotherAccountLeavesThisListAlone() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("old.txt")))
        val uploader = FakeUploader()
        val vm = viewModel(provider, uploader)
        vm.start()
        advanceUntilIdle()

        uploader.announce(UploadedFile(AccountId("fake:2"), null, "x.txt"))
        advanceUntilIdle()

        assertEquals(1, provider.listCalls)
    }

    @Test
    fun backToBackUploadsShareOneRefresh() = runTest(dispatcher) {
        val provider = FakeProvider(listOf(file("old.txt")))
        val uploader = FakeUploader()
        val vm = viewModel(provider, uploader)
        vm.start()
        advanceUntilIdle()
        assertEquals(1, provider.listCalls)

        uploader.announce(UploadedFile(account, null, "a.txt"))
        uploader.announce(UploadedFile(account, null, "b.txt"))
        advanceUntilIdle()

        // 两次事件挨得足够近时，后一次会把前一次还没回来的请求取消掉，
        // 真正发出去的只有一次。这不影响正确性：列表是一份完整快照，
        // b.txt 传完的那一刻 a.txt 早就在里面了。
        assertEquals(2, provider.listCalls)
    }
}
