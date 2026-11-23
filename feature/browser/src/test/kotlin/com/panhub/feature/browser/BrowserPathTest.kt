package com.panhub.feature.browser

import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.ProviderDescriptor
import com.panhub.core.model.ProviderId
import com.panhub.core.model.RemoteNode
import com.panhub.core.session.AccountSummary
import com.panhub.core.session.SessionManager
import com.panhub.provider.api.ProviderCredentials
import com.panhub.provider.api.ScopedPanProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 顶栏路径栏。
 *
 * 这里钉的是「点了某一格之后栈变成什么」：路径栏存在的意义是一次跳到祖先，
 * 而不是像返回键那样一级一级退。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserPathTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

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

        override suspend fun rename(accountId: AccountId, alias: String?) = error("不该调用")
        override suspend fun reauthenticate(
            accountId: AccountId,
            credentials: ProviderCredentials,
        ): PanResult<Unit> = error("不该调用")
    }

    private fun viewModel() = BrowserViewModel(session = StubSession(), accountId = AccountId("fake:1"))

    private fun folder(name: String) = RemoteNode(
        id = NodeId("id-" + name),
        parentId = null,
        name = name,
        kind = NodeKind.DIR,
    )

    @Test
    fun rootCrumbIsTheAccountName() {
        val crumbs = BrowserPath.crumbs("我的 123", emptyList())

        assertEquals(1, crumbs.size)
        assertEquals("我的 123", crumbs[0].label)
        assertEquals(BrowserPath.ROOT_DEPTH, crumbs[0].depth)
    }

    @Test
    fun everyFolderInTheStackGetsItsOwnCrumb() {
        val crumbs = BrowserPath.crumbs("账号", listOf(folder("财务"), folder("2024")))

        assertEquals(listOf("账号", "财务", "2024"), crumbs.map { it.label })
        assertEquals(listOf(-1, 0, 1), crumbs.map { it.depth })
    }

    @Test
    fun blankAccountNameFallsBackToAPlainWord() {
        val crumbs = BrowserPath.crumbs("   ", listOf(folder("照片")))

        assertEquals("我的网盘", crumbs[0].label)
    }

    @Test
    fun currentDepthIsTheLastFolder() {
        assertEquals(-1, BrowserPath.currentDepth(emptyList()))
        assertEquals(1, BrowserPath.currentDepth(listOf(folder("a"), folder("b"))))
    }

    @Test
    fun rootAloneIsNotWorthShowing() {
        assertFalse(BrowserPath.isJumpable("账号", emptyList()))
        assertTrue(BrowserPath.isJumpable("账号", listOf(folder("a"))))
    }

    @Test
    fun jumpingToRootEmptiesTheStack() {
        val vm = viewModel()
        vm.enter(folder("a"))
        vm.enter(folder("b"))

        assertTrue(vm.jumpTo(BrowserPath.ROOT_DEPTH))
        assertTrue(vm.state.value.stack.isEmpty())
    }

    @Test
    fun jumpingToAnAncestorKeepsThatPrefix() {
        val vm = viewModel()
        vm.enter(folder("a"))
        vm.enter(folder("b"))
        vm.enter(folder("c"))

        assertTrue(vm.jumpTo(0))
        assertEquals(listOf("a"), vm.state.value.stack.map { it.name })
    }

    @Test
    fun jumpingToWhereWeAlreadyAreDoesNothing() {
        val vm = viewModel()
        vm.enter(folder("a"))

        assertFalse(vm.jumpTo(0))
        assertEquals(1, vm.state.value.stack.size)
    }

    @Test
    fun jumpingOnTheRootIsANoOp() {
        val vm = viewModel()

        assertFalse(vm.jumpTo(BrowserPath.ROOT_DEPTH))
        assertTrue(vm.state.value.stack.isEmpty())
    }
}
