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
 * 目录选择器里的「新建文件夹」。
 *
 * 守两件事：新建是建在**选择器当前所在的目录**、而不是浏览页那个目录；
 * 以及名字不合法时一个请求都不发出去。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserPickerCreateTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val account = AccountId("fake:1")

    private class FakeProvider(
        private val folders: MutableList<RemoteNode> = mutableListOf(),
    ) : PanProvider {
        val created = mutableListOf<Pair<NodeId?, String>>()

        override val descriptor = ProviderDescriptor(
            id = ProviderId("fake"),
            displayName = "假网盘",
            authHint = "",
            capabilities = ProviderCapabilities(
                list = true,
                quota = true,
                createFolder = true,
                move = true,
            ),
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
        ): PanResult<NodePage> = PanResult.Success(NodePage(folders.toList(), null))

        override suspend fun stat(session: ProviderSession, nodeId: NodeId): PanResult<RemoteNode> =
            PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun quota(session: ProviderSession): PanResult<Quota> =
            PanResult.Success(Quota(used = 0L, total = 100L))

        override suspend fun createFolder(
            session: ProviderSession,
            parentId: NodeId?,
            name: String,
        ): PanResult<RemoteNode> {
            created += parentId to name
            // 真服务商建完会出现在下一份列表里，这里照着做，
            // 才能验证「建完刷新」确实把新目录带了回来。
            val node = RemoteNode(
                id = NodeId((parentId?.value ?: "") + "/" + name),
                parentId = parentId,
                name = name,
                kind = NodeKind.DIR,
            )
            folders += node
            return PanResult.Success(node)
        }

        override suspend fun rename(
            session: ProviderSession,
            nodeId: NodeId,
            newName: String,
        ): PanResult<RemoteNode> = PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun move(
            session: ProviderSession,
            nodeId: NodeId,
            newParentId: NodeId?,
        ): PanResult<Unit> = PanResult.Success(Unit)

        override suspend fun delete(session: ProviderSession, nodeId: NodeId): PanResult<Unit> =
            PanResult.Success(Unit)

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

    private fun viewModel(provider: PanProvider) = BrowserViewModel(
        session = FakeSession(provider, account),
        accountId = account,
    )

    private fun file(name: String) =
        RemoteNode(id = NodeId("/" + name), parentId = null, name = name, kind = NodeKind.FILE)

    private fun folder(id: String, name: String) =
        RemoteNode(id = NodeId(id), parentId = null, name = name, kind = NodeKind.DIR)

    @Test
    fun aNewFolderIsCreatedInTheFolderThePickerShows() = runTest(dispatcher) {
        val provider = FakeProvider(mutableListOf(folder("/docs", "docs")))
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        vm.openFolderPicker(listOf(file("a.txt")))
        advanceUntilIdle()
        vm.enterPickerFolder(folder("/docs", "docs"))
        advanceUntilIdle()

        vm.createPickerFolder("归档")
        advanceUntilIdle()

        // 建在选择器当前所在的 docs 里，而不是浏览页根目录。
        assertEquals(NodeId("/docs") to "归档", provider.created.single())
        assertNull(vm.picker.value.error)
    }

    @Test
    fun theNewFolderShowsUpInThePickerRightAway() = runTest(dispatcher) {
        val provider = FakeProvider()
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        vm.openFolderPicker(listOf(file("a.txt")))
        advanceUntilIdle()
        vm.createPickerFolder("相册")
        advanceUntilIdle()

        assertTrue(vm.picker.value.folders.any { it.name == "相册" })
        // 建完停在这一层：用户可能接着再建一个，也可能只是想确认它出现了。
        assertTrue(vm.picker.value.stack.isEmpty())
    }

    @Test
    fun anIllegalNameIsRefusedBeforeAnyRequest() = runTest(dispatcher) {
        val provider = FakeProvider()
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        vm.openFolderPicker(listOf(file("a.txt")))
        advanceUntilIdle()
        vm.createPickerFolder("a/b")
        advanceUntilIdle()

        assertTrue(provider.created.isEmpty())
        assertEquals("名字里不能有斜杠，那是目录分隔符", vm.picker.value.error)
    }

    @Test
    fun creatingIsRefusedWhileAnotherOneIsRunning() = runTest(dispatcher) {
        val provider = FakeProvider()
        val vm = viewModel(provider)
        vm.start()
        advanceUntilIdle()

        vm.openFolderPicker(listOf(file("a.txt")))
        advanceUntilIdle()
        vm.createPickerFolder("一")
        vm.createPickerFolder("二")
        advanceUntilIdle()

        assertEquals(listOf("一"), provider.created.map { it.second })
    }
}