package com.panhub.core.session

import com.panhub.core.common.Clock
import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanResult
import com.panhub.core.common.getOrNull
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodePage
import com.panhub.core.model.ProviderCapabilities
import com.panhub.core.model.ProviderDescriptor
import com.panhub.core.model.ProviderId
import com.panhub.core.model.Quota
import com.panhub.core.model.RemoteNode
import com.panhub.core.model.ShareLink
import com.panhub.core.model.ShareOptions
import com.panhub.core.storage.AccountStore
import com.panhub.core.storage.JsonAccountStore
import com.panhub.core.storage.InsecurePlainCipher
import com.panhub.provider.api.DownloadTarget
import com.panhub.provider.api.PanProvider
import com.panhub.provider.api.ProviderCredentials
import com.panhub.provider.api.ProviderRegistry
import com.panhub.provider.api.ProviderSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DefaultSessionManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): AccountStore = JsonAccountStore(File(tmp.root, "accounts.json"), InsecurePlainCipher)

    private fun manager(
        store: AccountStore = store(),
        provider: PanProvider = FakeProvider(),
    ) = DefaultSessionManager(
        registry = ProviderRegistry(listOf(provider)),
        store = store,
        clock = Clock { 1000L },
    )

    @Test
    fun `login persists and exposes an account summary`() = runTest {
        val m = manager()

        val result = m.login(ProviderId("fake"), ProviderCredentials.Token("tok"))
        assertTrue(result is PanResult.Success)

        val accounts: List<AccountSummary> = m.accounts.first()
        assertEquals(1, accounts.size)
        assertEquals("fake:me", accounts.single().id.value)
        assertEquals("假网盘", accounts.single().providerName)
        assertEquals("测试用户", accounts.single().displayName)
    }

    @Test
    fun `login to an unregistered provider fails without writing`() = runTest {
        val s = store()
        val m = manager(store = s)

        val result = m.login(ProviderId("nope"), ProviderCredentials.Token("t"))

        assertTrue(result is PanResult.Failure)
        assertEquals(ErrorKind.UNSUPPORTED, (result as PanResult.Failure).error.kind)
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun `failed provider login does not persist`() = runTest {
        val s = store()
        val m = manager(store = s, provider = FakeProvider(failLogin = true))

        val result = m.login(ProviderId("fake"), ProviderCredentials.Token("bad"))

        assertTrue(result is PanResult.Failure)
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun `connect returns a scoped provider bound to the stored session`() = runTest {
        val m = manager()
        m.login(ProviderId("fake"), ProviderCredentials.Token("tok"))

        val scoped = m.connect(AccountId("fake:me"))
        assertTrue(scoped is PanResult.Success)
        val page = (scoped as PanResult.Success).value.list(null)
        assertTrue(page is PanResult.Success)
        assertEquals(1, (page as PanResult.Success).value.items.size)
    }

    @Test
    fun `connect to unknown account is NOT_FOUND`() = runTest {
        val result = manager().connect(AccountId("fake:ghost"))
        assertEquals(ErrorKind.NOT_FOUND, (result as PanResult.Failure).error.kind)
    }

    @Test
    fun `logout removes the account`() = runTest {
        val m = manager()
        m.login(ProviderId("fake"), ProviderCredentials.Token("tok"))

        m.logout(AccountId("fake:me"))

        assertTrue(m.accounts.first().isEmpty())
        assertNull(m.connect(AccountId("fake:me")).getOrNull())
    }

    @Test
    fun `verify runs the provider check`() = runTest {
        val provider = FakeProvider()
        val m = manager(provider = provider)
        m.login(ProviderId("fake"), ProviderCredentials.Token("tok"))

        assertTrue(m.verify(AccountId("fake:me")) is PanResult.Success)
        assertEquals(1, provider.verifyCalls)
    }

    @Test
    fun `descriptors expose registered providers`() {
        val m = manager()
        assertEquals(listOf("fake"), m.descriptors().map { it.id.value })
    }

    @Test
    fun `accounts from an unregistered provider are filtered out`() = runTest {
        val s = store()
        s.save(
            com.panhub.core.storage.AccountRecord(
                id = "ghost:1",
                providerId = "ghost",
                secret = "tok",
                lastUsedAt = 1L,
            ),
        )
        assertTrue(manager(store = s).accounts.first().isEmpty())
    }
}

/** 最小的 provider，用来测 SessionManager 而不牵扯真实网络。 */
private class FakeProvider(private val failLogin: Boolean = false) : PanProvider {

    var verifyCalls = 0
        private set

    override val descriptor = ProviderDescriptor(
        id = ProviderId("fake"),
        displayName = "假网盘",
        capabilities = ProviderCapabilities(),
    )

    override suspend fun login(credentials: ProviderCredentials): PanResult<ProviderSession> =
        if (failLogin) {
            PanResult.Failure(com.panhub.core.common.PanError.auth("bad credentials"))
        } else {
            PanResult.Success(
                ProviderSession(
                    accountId = AccountId("fake:me"),
                    credentials = credentials,
                    displayName = "测试用户",
                ),
            )
        }

    override suspend fun verify(session: ProviderSession): PanResult<Unit> {
        verifyCalls++
        return PanResult.Success(Unit)
    }

    override suspend fun list(
        session: ProviderSession,
        parentId: NodeId?,
        page: com.panhub.core.model.PageRequest,
    ): PanResult<NodePage> = PanResult.Success(
        NodePage(
            items = listOf(
                RemoteNode(
                    id = NodeId("n1"),
                    parentId = null,
                    name = "a.txt",
                    kind = com.panhub.core.model.NodeKind.FILE,
                ),
            ),
        ),
    )

    override suspend fun stat(session: ProviderSession, nodeId: NodeId): PanResult<RemoteNode> =
        PanResult.Failure(com.panhub.core.common.PanError.notFound())

    override suspend fun quota(session: ProviderSession): PanResult<Quota> =
        PanResult.Failure(com.panhub.core.common.PanError.notSupported())

    override suspend fun createFolder(
        session: ProviderSession,
        parentId: NodeId?,
        name: String,
    ): PanResult<RemoteNode> = PanResult.Failure(com.panhub.core.common.PanError.notSupported())

    override suspend fun rename(
        session: ProviderSession,
        nodeId: NodeId,
        newName: String,
    ): PanResult<RemoteNode> = PanResult.Failure(com.panhub.core.common.PanError.notSupported())

    override suspend fun move(
        session: ProviderSession,
        nodeId: NodeId,
        newParentId: NodeId?,
    ): PanResult<Unit> = PanResult.Failure(com.panhub.core.common.PanError.notSupported())

    override suspend fun delete(session: ProviderSession, nodeId: NodeId): PanResult<Unit> =
        PanResult.Failure(com.panhub.core.common.PanError.notSupported())

    override suspend fun downloadUrl(
        session: ProviderSession,
        nodeId: NodeId,
    ): PanResult<DownloadTarget> = PanResult.Failure(com.panhub.core.common.PanError.notSupported())

    override suspend fun createShare(
        session: ProviderSession,
        nodeIds: List<NodeId>,
        options: ShareOptions,
    ): PanResult<ShareLink> = PanResult.Failure(com.panhub.core.common.PanError.notSupported())

    override suspend fun cancelShare(session: ProviderSession, shareId: String): PanResult<Unit> =
        PanResult.Failure(com.panhub.core.common.PanError.notSupported())
}