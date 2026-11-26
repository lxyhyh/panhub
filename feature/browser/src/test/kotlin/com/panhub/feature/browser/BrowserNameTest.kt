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
import com.panhub.core.session.AccountSummary
import com.panhub.core.session.SessionManager
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 新建文件夹与重命名之前的那道名字检查。
 *
 * 规则本身在 core:model 的 NodeNamesTest 里测；这里守的是接线：
 * 不合法的名字一次请求都不该发出去，合法的名字要原样送到服务端。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserNameTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val account = AccountId("fake:1")

    /** 记录发出去的名字，不真的动网盘。 */
    private class Recorder : PanProvider {
        val created = mutableListOf<String>()
        val renamed = mutableListOf<Pair<String, String>>()

        override val descriptor = ProviderDescriptor(
            id = ProviderId("fake"),
            displayName = "假网盘",
            authHint = "",
            capabilities = ProviderCapabilities(list = true, createFolder = true, rename = true),
            authMethod = AuthMethod.TOKEN,
        )

        override suspend fun login(credentials: ProviderCredentials): PanResult<ProviderSession> =
            error("不该调用")

        override suspend fun verify(session: ProviderSession): PanResult<Unit> = PanResult.Success(Unit)

        override suspend fun list(
            session: ProviderSession,
            parentId: NodeId?,
            page: PageRequest,
        ): PanResult<NodePage> = PanResult.Success(NodePage(emptyList(), null))

        override suspend fun stat(session: ProviderSession, nodeId: NodeId): PanResult<RemoteNode> =
            PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun quota(session: ProviderSession): PanResult<Quota> =
            PanResult.Success(Quota(used = 0L, total = 100L))

        override suspend fun createFolder(
            session: ProviderSession,
            parentId: NodeId?,
            name: String,
        ): PanResult<RemoteNode> {
            created += name
            return PanResult.Success(RemoteNode(NodeId(name), parentId, name, NodeKind.DIR))
        }

        override suspend fun rename(
            session: ProviderSession,
            nodeId: NodeId,
            newName: String,
        ): PanResult<RemoteNode> {
            renamed += nodeId.value to newName
            return PanResult.Success(RemoteNode(nodeId, null, newName, NodeKind.FILE))
        }

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
        ): PanResult<com.panhub.provider.api.DownloadTarget> =
            PanResult.Failure(PanError.notSupported("不支持"))

        override suspend fun createShare(
            session: ProviderSession,
            nodeIds: List<NodeId>,
            options: com.panhub.core.model.ShareOptions,
        ): PanResult<com.panhub.core.model.ShareLink> =
            PanResult.Failure(PanError.notSupported("不支持"))

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

    private suspend fun ready(provider: PanProvider): BrowserViewModel {
        val vm = BrowserViewModel(session = FakeSession(provider, account), accountId = account)
        vm.start()
        return vm
    }

    private fun file(name: String) = RemoteNode(
        id = NodeId(name),
        parentId = null,
        name = name,
        kind = NodeKind.FILE,
    )

    @Test
    fun `a clean folder name reaches the provider`() = runTest(dispatcher) {
        val provider = Recorder()
        val vm = ready(provider)
        advanceUntilIdle()

        vm.createFolder("相册");
        advanceUntilIdle()

        assertEquals(listOf("相册"), provider.created)
        assertEquals("已创建 相册", vm.state.value.message)
    }

    @Test
    fun `a name with spaces around it is refused rather than silently trimmed`() = runTest(dispatcher) {
        val provider = Recorder()
        val vm = ready(provider)
        advanceUntilIdle()

        // 自动去掉也行，但那样「相册」和「相册 」会变成两个看起来一样的东西。
        // 与其猜用户想要哪个，不如让他自己删掉那个空格。
        vm.createFolder(" 相册 ");
        advanceUntilIdle()

        assertTrue(provider.created.isEmpty())
        assertEquals("名字前后不能有空格", vm.state.value.message)
    }

    @Test
    fun `a name with a slash never leaves the app`() = runTest(dispatcher) {
        val provider = Recorder()
        val vm = ready(provider)
        advanceUntilIdle()

        vm.createFolder("a/b");
        advanceUntilIdle()

        assertTrue(provider.created.isEmpty())
        assertEquals("名字里不能有斜杠，那是目录分隔符", vm.state.value.message)
    }

    @Test
    fun `an empty folder name is refused before the request`() = runTest(dispatcher) {
        val provider = Recorder()
        val vm = ready(provider)
        advanceUntilIdle()

        vm.createFolder("   ");
        advanceUntilIdle()

        assertTrue(provider.created.isEmpty())
        assertEquals("名字不能只有空格", vm.state.value.message)
    }

    @Test
    fun `renaming to a reserved character is refused`() = runTest(dispatcher) {
        val provider = Recorder()
        val vm = ready(provider)
        advanceUntilIdle()

        vm.rename(file("a.txt"), "a:b.txt");
        advanceUntilIdle()

        assertTrue(provider.renamed.isEmpty())
        assertEquals("名字里不能有 : * ? \" < > |", vm.state.value.message)
    }

    @Test
    fun `renaming to the same name sends nothing`() = runTest(dispatcher) {
        val provider = Recorder()
        val vm = ready(provider)
        advanceUntilIdle()

        vm.rename(file("a.txt"), "a.txt");
        advanceUntilIdle()

        // 名字和原来一样，等于什么都没改，不该白跑一次。
        assertTrue(provider.renamed.isEmpty())
    }

    @Test
    fun `a normal rename goes through`() = runTest(dispatcher) {
        val provider = Recorder()
        val vm = ready(provider)
        advanceUntilIdle()

        vm.rename(file("a.txt"), "b.txt");
        advanceUntilIdle()

        assertEquals(listOf("a.txt" to "b.txt"), provider.renamed)
        assertEquals("已重命名", vm.state.value.message)
    }
}
