package com.panhub.feature.accounts

import com.panhub.core.common.PanError
import com.panhub.core.common.ErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 登录状态检查的判定与措辞。
 *
 * 这两件事合起来决定用户看到什么：把超时说成「失效」会让他去重登一个没问题的账号，
 * 把「没查成」说成「正常」又会让真正过期的那家一直藏着。
 */
class AccountsLoginStateTest {

    @Test
    fun `no error means the login still works`() {
        assertEquals(AccountsLogic.LoginState.OK, AccountsLogic.stateOf(null))
    }

    @Test
    fun `an auth error means the login expired`() {
        val error = PanError.auth("登录已失效")
        assertEquals(AccountsLogic.LoginState.EXPIRED, AccountsLogic.stateOf(error))
    }

    @Test
    fun `a network error leaves the answer unknown`() {
        // 弱网超时和凭据过期在界面上必须分得开，否则一次地铁隧道里的失败
        // 会让人把还能用的账号重登一遍。
        assertNull(AccountsLogic.stateOf(PanError.network("连接超时")))
        assertNull(AccountsLogic.stateOf(PanError.rateLimited("请求太频繁")))
    }

    @Test
    fun `labels name both outcomes`() {
        assertEquals("登录有效", AccountsLogic.stateLabel(AccountsLogic.LoginState.OK))
        assertEquals("登录已失效", AccountsLogic.stateLabel(AccountsLogic.LoginState.EXPIRED))
    }

    @Test
    fun `everything fine says so once`() {
        val message = AccountsLogic.checkMessage(
            listOf(AccountsLogic.LoginState.OK, AccountsLogic.LoginState.OK),
            2,
        )
        assertEquals("2 个账号都还能用", message)
    }

    @Test
    fun `some expired says how many`() {
        val message = AccountsLogic.checkMessage(
            listOf(AccountsLogic.LoginState.OK, AccountsLogic.LoginState.EXPIRED),
            2,
        )
        assertEquals("2 个账号里 1 个需要重新登录", message)
    }

    @Test
    fun `all expired says every one needs attention`() {
        val message = AccountsLogic.checkMessage(
            listOf(AccountsLogic.LoginState.EXPIRED, AccountsLogic.LoginState.EXPIRED),
            2,
        )
        assertEquals("2 个账号都要重新登录", message)
    }

    @Test
    fun `unchecked accounts are counted apart from expired ones`() {
        // 三家账号，只查成一家且它已失效：剩下两家不能算进「都要重新登录」。
        val message = AccountsLogic.checkMessage(
            listOf(AccountsLogic.LoginState.EXPIRED),
            3,
        )
        assertEquals("3 个账号里 1 个需要重新登录，另有 2 个没查成", message)
    }

    @Test
    fun `no checks came back at all says so plainly`() {
        val message = AccountsLogic.checkMessage(emptyList(), 2)
        assertEquals("2 个账号里有 2 个没查成", message)
    }

    @Test
    fun `checking nothing says there is nothing to check`() {
        assertEquals("还没有账号可以检查", AccountsLogic.checkMessage(emptyList(), 0))
    }

    @Test
    fun `expired accounts are picked out of the list`() {
        val state = AccountsUiState(
            accounts = listOf(account("a"), account("b")),
            loginStates = mapOf(
                "a" to AccountsLogic.LoginState.OK,
                "b" to AccountsLogic.LoginState.EXPIRED,
            ),
        )
        assertEquals(listOf("b"), state.expiredAccounts.map { it.id.value })
    }

    private fun account(id: String) = com.panhub.core.session.AccountSummary(
        id = com.panhub.core.model.AccountId(id),
        providerId = com.panhub.core.model.ProviderId("fake"),
        providerName = "Fake",
        displayName = id,
        lastUsedAt = 0L,
        capabilities = com.panhub.core.model.ProviderCapabilities(),
    )
}
