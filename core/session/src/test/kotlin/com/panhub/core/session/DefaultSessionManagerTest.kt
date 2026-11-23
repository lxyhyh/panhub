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
import com.panhub.core.storage.AccountMapper
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
    fun `rename stores a trimmed alias and exposes it in the summary`() = runTest {
        val m = manager()
        m.login(ProviderId("fake"), ProviderCredentials.Token("tok"))
        val id = m.accounts.first().single().id

        m.rename(id, "  公司备份盘  ")

        val account = m.accounts.first().single()
        assertEquals("公司备份盘", account.alias)
        assertEquals("公司备份盘", account.label)
        assertEquals("测试用户", account.displayName)
    }

    @Test
    fun `blank alias means no alias at all`() = runTest {
        val m = manager()
        m.login(ProviderId("fake"), ProviderCredentials.Token("tok"))
        val id = m.accounts.first().single().id
        m.rename(id, "公司备份盘")

        m.rename(id, "   ")

        val account = m.accounts.first().single()
        assertNull(account.alias)
        // 没有备注名时 label 要退回服务商给的名字，界面不能显示空白。
        assertEquals("测试用户", account.label)
    }

    @Test
    fun `logging in again keeps the alias the user set`() = runTest {
        val m = manager()
        m.login(ProviderId("fake"), ProviderCredentials.Token("tok"))
        val id = m.accounts.first().single().id
        m.rename(id, "公司备份盘")

        // 凭据过期后重新登录：记录会被整条覆盖，备注名不该跟着消失。
        m.login(ProviderId("fake"), ProviderCredentials.Token("tok"))

        assertEquals("公司备份盘", m.accounts.first().single().alias)
    }

    @Test
    fun `reauthenticate swaps the secret and keeps the identity`() = runTest {
        val s = store()
        val m = manager(store = s)
        m.login(ProviderId("fake"), ProviderCredentials.Token("old-cookie"))
        m.rename(AccountId("fake:me"), "公司备份盘")

        val result = m.reauthenticate(AccountId("fake:me"), ProviderCredentials.Token("new-cookie"))

        assertTrue(result is PanResult.Success)
        val account = m.accounts.first().single()
        // 身份不变：传输记录、收藏入口等挂在旧 id 上的东西不会失联。
        assertEquals("fake:me", account.id.value)
        assertEquals("公司备份盘", account.alias)
        val record = s.get("fake:me")!!
        assertEquals("new-cookie", record.secret)
    }

    @Test
    fun `reauthenticate with bad credentials leaves the account untouched`() = runTest {
        val s = store()
        // failLogin 的服务商连 login 都进不来，所以先直接往店里存一份旧凭据，
        // 模拟「账号本来就在，只是这次换新凭据没换成」。
        s.save(AccountMapper.toRecord(ProviderSession(AccountId("fake:me"), ProviderCredentials.Token("old-cookie")), now = 1L))
        val m = manager(store = s, provider = FakeProvider(failLogin = true))

        val result = m.reauthenticate(AccountId("fake:me"), ProviderCredentials.Token("new-cookie"))

        assertTrue(result is PanResult.Failure)
        // 验证失败的凭据不能落盘，不然下次启动连旧账号都登不上。
        assertEquals("old-cookie", s.get("fake:me")!!.secret)
    }

    @Test
    fun `reauthenticate rejects credentials of a different account`() = runTest {
        val s = store()
        val m = manager(store = s)
        m.login(ProviderId("fake"), ProviderCredentials.Token("old-cookie"))
        // 服务商验出的账号不是原账号：说明新凭据属于另一个人，
        // 直接覆盖会把两个账号搅在一起。
        val provider = object : PanProvider by FakeProvider() {
            override suspend fun login(credentials: ProviderCredentials) = PanResult.Success(
                ProviderSession(AccountId("fake:other"), credentials, "别人"),
            )
        }
        val m2 = DefaultSessionManager(
            registry = ProviderRegistry(listOf(provider)),
            store = s,
            clock = Clock { 1000L },
        )

        val result = m2.reauthenticate(AccountId("fake:me"), ProviderCredentials.Token("other-cookie"))

        assertTrue(result is PanResult.Failure)
        assertEquals("old-cookie", s.get("fake:me")!!.secret)
    }

    @Test
    fun `reauthenticate on a missing account is NOT_FOUND`() = runTest {
        val result = manager().reauthenticate(AccountId("fake:ghost"), ProviderCredentials.Token("t"))
        assertEquals(ErrorKind.NOT_FOUND, (result as PanResult.Failure).error.kind)
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