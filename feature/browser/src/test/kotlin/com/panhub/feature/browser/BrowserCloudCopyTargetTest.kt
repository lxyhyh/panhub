package com.panhub.feature.browser

import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.NodePage
import com.panhub.core.model.PageRequest
import com.panhub.core.model.ProviderCapabilities
import com.panhub.core.model.ProviderDescriptor
import com.panhub.core.model.ProviderId
import com.panhub.core.model.RemoteNode
import com.panhub.core.session.AccountSummary
import com.panhub.core.session.SessionManager
import com.panhub.core.transfer.CloudCopyLauncher
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
 * 「复制到其他网盘」里挑目标目录。
 *
 * 以前目标固定是对方根目录，复制完还得去对方账号里挪一次；
 * 现在对话框里就能钻进子目录。这里守的是导航与提交两件事：
 * 换账号要回到根目录，提交要带上用户选到的那个目录。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserCloudCopyTargetTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val account = AccountId("fake:1")
    private val other = AccountId("fake:2")

    /** 记录跨网盘复制调用的目标目录。 */
    private class FakeCopier : CloudCopyLauncher {
        data class Call(val name: String, val toAccountId: AccountId, val toParentId: NodeId?)
        val calls = mutableListOf<Call>()
        override fun copyBetween(
            fromAccountId: AccountId,
            nodeId: NodeId,
            name: String,
            size: Long?,
            toAccountId: AccountId,
            toParentId: NodeId?,
        ): String {
            calls += Call(name, toAccountId, toParentId)
            return "cc-" + calls.size
        }

        override fun copyFolderBetween(
            fromAccountId: AccountId,
            nodeId: NodeId,
            name: String,
            toAccountId: AccountId,
            toParentId: NodeId?,
        ): String {
            calls += Call(name, toAccountId, toParentId)
            return "cc-" + calls.size
        }
    }

    /** 源账号（列出待复制文件）与目标账号（列出可选目录）各一份。 */
    private class TwoAccountProvider(
        private val sourceFiles: List<RemoteNode>,
        private val targetFolders: List<RemoteNode> = emptyList(),
        private val targetAccountId: AccountId,
    ) : PanProvider {
        override val descriptor = ProviderDescriptor(
            id = ProviderId("fake"),
            displayName = "假网盘",
            authHint = "",
            capabilities = ProviderCapabilities(list = true, upload = true, createFolder = true, quota = true),
            authMethod = com.panhub.core.model.AuthMethod.TOKEN,
        )

        override suspend fun list(
            session: ProviderSession,
            parentId: NodeId?,
            page: PageRequest,
        ): PanResult<NodePage> {
            // 源账号列根目录给文件；目标账号按 parentId 过滤自己的目录树。
            if (session.accountId != targetAccountId) return PanResult.Success(NodePage(sourceFiles))
            val items = targetFolders.filter { it.parentId == parentId }
            return PanResult.Success(NodePage(items))
        }

        override suspend fun login(credentials: ProviderCredentials) = error("不该调用")
        override suspend fun verify(session: ProviderSession) = PanResult.Success(Unit)
        override suspend fun stat(session: ProviderSession, nodeId: NodeId) = error("不该调用")
        override suspend fun quota(session: ProviderSession) = PanResult.Success(com.panhub.core.model.Quota(used = 1L, total = 10L))
        override suspend fun createFolder(session: ProviderSession, parentId: NodeId?, name: String) = error("不该调用")
        override suspend fun rename(session: ProviderSession, nodeId: NodeId, newName: String) = error("不该调用")
        override suspend fun move(session: ProviderSession, nodeId: NodeId, newParentId: NodeId?) = error("不该调用")
        override suspend fun delete(session: ProviderSession, nodeId: NodeId) = error("不该调用")
        override suspend fun downloadUrl(session: ProviderSession, nodeId: NodeId) = error("不该调用")
        override suspend fun createShare(session: ProviderSession, nodeIds: List<NodeId>, options: com.panhub.core.model.ShareOptions) = error("不该调用")
        override suspend fun cancelShare(session: ProviderSession, shareId: String) = error("不该调用")
    }

    private class TwoAccountSession(
        private val provider: TwoAccountProvider,
        private val sourceId: AccountId,
        private val targetId: AccountId,
    ) : SessionManager {
        val summaries = listOf(
            AccountSummary(
                id = sourceId,
                providerId = provider.descriptor.id,
                providerName = provider.descriptor.displayName,
                displayName = "源账号",
                lastUsedAt = 0L,
                capabilities = provider.descriptor.capabilities,
            ),
            AccountSummary(
                id = targetId,
                providerId = provider.descriptor.id,
                providerName = provider.descriptor.displayName,
                displayName = "目标账号",
                lastUsedAt = 0L,
                capabilities = provider.descriptor.capabilities,
            ),
        )

        override val accounts: Flow<List<AccountSummary>> = flowOf(summaries)
        override fun descriptors(): List<ProviderDescriptor> = listOf(provider.descriptor)
        override suspend fun login(
            providerId: ProviderId,
            credentials: ProviderCredentials,
        ): PanResult<AccountSummary> = error("不该调用")
        override suspend fun verify(accountId: AccountId) = PanResult.Success(Unit)
        override suspend fun connect(accountId: AccountId): PanResult<ScopedPanProvider> = PanResult.Success(
            ScopedPanProvider(
                provider,
                ProviderSession(accountId, ProviderCredentials.Token("t"), "假"),
            ),
        )
        override suspend fun logout(accountId: AccountId) = error("不该调用")
        override suspend fun rename(accountId: AccountId, alias: String?) = error("不该调用")
        override suspend fun reauthenticate(
            accountId: AccountId,
            credentials: ProviderCredentials,
        ): PanResult<Unit> = error("不该调用")
    }

    private fun viewModel(session: SessionManager, copier: FakeCopier) = BrowserViewModel(
        session = session,
        accountId = account,
        copier = copier,
    )

    private fun session(provider: TwoAccountProvider) =
        TwoAccountSession(provider, sourceId = account, targetId = other)

    private fun file(name: String) =
        RemoteNode(id = NodeId("/" + name), parentId = null, name = name, kind = NodeKind.FILE)

    private fun dir(id: String, name: String, parent: NodeId? = null) =
        RemoteNode(id = NodeId(id), parentId = parent, name = name, kind = NodeKind.DIR)

    @Test
    fun openingTheDialogListsTheTargetAccountsFolders() = runTest(dispatcher) {
        val provider = TwoAccountProvider(
            sourceFiles = listOf(file("a.txt")),
            targetFolders = listOf(dir("/t", "照片")),
            targetAccountId = other,
        )
        val copier = FakeCopier()
        val vm = viewModel(session(provider), copier)
        vm.start()
        advanceUntilIdle()

        vm.openCloudCopy(listOf(file("a.txt")))
        advanceUntilIdle()

        val copy = vm.copy.value
        assertEquals(listOf("照片"), copy.folders.map { it.name })
        // 默认位置是对方根目录。
        assertNull(copy.targetFolderId)
        assertEquals("目标账号", copy.targetName)
    }

    @Test
    fun enteringAFolderDescendsAndListsItsChildren() = runTest(dispatcher) {
        val photos = dir("/t", "照片")
        val child = dir("/t/2024", "2024", parent = photos.id)
        val provider = TwoAccountProvider(
            sourceFiles = listOf(file("a.txt")),
            targetFolders = listOf(photos, child),
            targetAccountId = other,
        )
        val vm = viewModel(session(provider), FakeCopier())
        vm.start()
        advanceUntilIdle()
        vm.openCloudCopy(listOf(file("a.txt")))
        advanceUntilIdle()

        vm.enterCloudCopyFolder(photos)
        advanceUntilIdle()

        val copy = vm.copy.value
        assertEquals(listOf(photos), copy.stack)
        // 子目录列表来自目标账号在这一层的响应。
        assertEquals(listOf("2024"), copy.folders.map { it.name })
    }

    @Test
    fun leavingAFolderGoesBackUpOneLevel() = runTest(dispatcher) {
        val photos = dir("/t", "照片")
        val provider = TwoAccountProvider(
            sourceFiles = listOf(file("a.txt")),
            targetFolders = listOf(photos, dir("/t/2024", "2024", photos.id)),
            targetAccountId = other,
        )
        val vm = viewModel(session(provider), FakeCopier())
        vm.start()
        advanceUntilIdle()
        vm.openCloudCopy(listOf(file("a.txt")))
        advanceUntilIdle()
        vm.enterCloudCopyFolder(photos)
        advanceUntilIdle()

        assertTrue(vm.leaveCloudCopyFolder())
        advanceUntilIdle()

        assertTrue(vm.copy.value.stack.isEmpty())
        // 回到根目录：再退一步无事可做。
        assertFalse(vm.leaveCloudCopyFolder())
    }

    @Test
    fun switchingTargetAccountResetsTheFolderStack() = runTest(dispatcher) {
        val photos = dir("/t", "照片")
        val provider = TwoAccountProvider(
            sourceFiles = listOf(file("a.txt")),
            targetFolders = listOf(photos),
            targetAccountId = other,
        )
        val vm = viewModel(session(provider), FakeCopier())
        vm.start()
        advanceUntilIdle()
        vm.openCloudCopy(listOf(file("a.txt")))
        advanceUntilIdle()
        vm.enterCloudCopyFolder(photos)
        advanceUntilIdle()

        vm.setCloudCopyTarget(other)
        advanceUntilIdle()

        // 旧账号的目录对新账号毫无意义：清栈回根。
        assertTrue(vm.copy.value.stack.isEmpty())
    }

    @Test
    fun submittingUsesTheChosenFolderInsteadOfTheRoot() = runTest(dispatcher) {
        val photos = dir("/t", "照片")
        val provider = TwoAccountProvider(
            sourceFiles = listOf(file("a.txt")),
            targetFolders = listOf(photos),
            targetAccountId = other,
        )
        val copier = FakeCopier()
        val vm = viewModel(session(provider), copier)
        vm.start()
        advanceUntilIdle()
        vm.openCloudCopy(listOf(file("a.txt")))
        advanceUntilIdle()
        vm.enterCloudCopyFolder(photos)
        advanceUntilIdle()

        vm.submitCloudCopy()
        advanceUntilIdle()

        val call = copier.calls.single()
        assertEquals("a.txt", call.name)
        assertEquals(other, call.toAccountId)
        assertEquals(NodeId("/t"), call.toParentId)
    }

    @Test
    fun submittingFromTheRootStillTargetsNull() = runTest(dispatcher) {
        val provider = TwoAccountProvider(sourceFiles = listOf(file("a.txt")), targetAccountId = other)
        val copier = FakeCopier()
        val vm = viewModel(session(provider), copier)
        vm.start()
        advanceUntilIdle()
        vm.openCloudCopy(listOf(file("a.txt")))
        advanceUntilIdle()

        vm.submitCloudCopy()
        advanceUntilIdle()

        // 根目录本来就是 null：不选目录时行为与以前一致。
        assertNull(copier.calls.single().toParentId)
    }
}