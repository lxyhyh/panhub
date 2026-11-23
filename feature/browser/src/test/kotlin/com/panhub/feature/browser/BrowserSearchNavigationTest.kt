package com.panhub.feature.browser

import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.ProviderCapabilities
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 从搜索结果点进去之后的导航。
 *
 * 这里钉的是一条只有真点过才会发现的规则：搜索结果是跨目录的，
 * 直接压栈会让返回键从深层目录一步跳回根目录，用户会以为自己走过界了。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserSearchNavigationTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** 导航只改本地状态，不真的连网盘；连一次就会报错，正好证明没发请求。 */
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
    }

    private fun viewModel() = BrowserViewModel(session = StubSession(), accountId = AccountId("fake:1"))

    private fun folder(name: String, parent: String? = null, location: String? = null) = RemoteNode(
        id = NodeId("id-" + name),
        parentId = parent?.let { NodeId(it) },
        name = name,
        kind = NodeKind.DIR,
        providerPath = location,
    )

    private fun file(name: String, parent: String? = null) = RemoteNode(
        id = NodeId("id-" + name),
        parentId = parent?.let { NodeId(it) },
        name = name,
        kind = NodeKind.FILE,
    )

    @Test
    fun folderFromSearchKeepsItsParentAsBreadcrumb() {
        val vm = viewModel()
        vm.closeSearch()
        vm.openFromSearch(folder("2024", parent = "42", location = "财务"))

        val state = vm.state.value
        assertEquals(2, state.stack.size)
        assertEquals("42", state.stack[0].id.value)
        assertEquals("财务", state.stack[0].name)
        assertEquals("2024", state.title)
        assertTrue(state.canGoUp)
    }

    @Test
    fun goUpFromSearchResultLandsInTheParentFolder() {
        val vm = viewModel()
        vm.openFromSearch(folder("2024", parent = "42", location = "财务"))

        assertTrue(vm.goUp())
        // 退到「财务」这一层，再往上才是根目录：正好是用户期望的两步返回。
        assertEquals("财务", vm.state.value.title)
        assertEquals(1, vm.state.value.stack.size)

        assertTrue(vm.goUp())
        assertEquals("再退一步就回到根目录", 0, vm.state.value.stack.size)
    }

    @Test
    fun parentWithoutANameStillGetsAPlaceholder() {
        val vm = viewModel()
        vm.openFromSearch(folder("2024", parent = "42"))

        // 服务端不给目录名时不能空着：面包屑上看不见东西，比占位符更让人困惑。
        assertEquals("上一级目录", vm.state.value.stack.first().name)
    }

    @Test
    fun folderWithoutParentFallsBackToItself() {
        val vm = viewModel()
        vm.openFromSearch(folder("2024"))

        // 没有父目录信息时宁可返回根目录，也不要编一个不存在的层级。
        assertEquals(1, vm.state.value.stack.size)
        assertEquals("2024", vm.state.value.title)
    }

    @Test
    fun fileFromSearchOpensDetailsInsteadOfNavigating() {
        val vm = viewModel()
        vm.openFromSearch(file("报告.pdf", parent = "42"))

        assertTrue(vm.details.value.visible)
        assertEquals("报告.pdf", vm.details.value.node?.name)
        // 文件没有「进去」这回事，目录栈必须原封不动。
        assertTrue(vm.state.value.stack.isEmpty())
    }

    @Test
    fun openingAResultClosesTheSearchPanel() {
        val vm = viewModel()
        vm.openSearch()
        assertTrue(vm.search.value.visible)

        vm.openFromSearch(folder("2024", parent = "42"))
        assertFalse("点结果后搜索面板要收起来", vm.search.value.visible)
        assertNull(vm.search.value.error)
    }
}
