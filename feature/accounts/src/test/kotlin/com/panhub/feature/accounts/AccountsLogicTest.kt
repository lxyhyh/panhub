package com.panhub.feature.accounts

import com.panhub.core.model.AccountId
import com.panhub.core.model.ProviderCapabilities
import com.panhub.core.model.ProviderId
import com.panhub.core.session.AccountSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountsLogicTest {

    private fun account(id: String, quota: Boolean): AccountSummary = AccountSummary(
        id = AccountId(id),
        providerId = ProviderId("fake"),
        providerName = "Fake",
        displayName = id,
        lastUsedAt = 0L,
        capabilities = ProviderCapabilities(quota = quota),
    )

    @Test
    fun onlyAccountsDeclaringQuotaAreQueried() {
        val accounts = listOf(account("a", quota = true), account("b", quota = false))

        val targets = AccountsLogic.quotaTargets(accounts, emptySet())

        assertEquals(listOf("a"), targets.map { it.id.value })
    }

    @Test
    fun alreadyAttemptedAccountsAreSkipped() {
        val accounts = listOf(account("a", quota = true), account("b", quota = true))

        val targets = AccountsLogic.quotaTargets(accounts, setOf("a"))

        assertEquals(listOf("b"), targets.map { it.id.value })
    }

    @Test
    fun failedAttemptsAreNotRetriedEveryReload() {
        // 上一次查失败了，账号仍然出现在列表里；不记住「试过」就会每次刷新都再打一次。
        val accounts = listOf(account("a", quota = true))

        val targets = AccountsLogic.quotaTargets(accounts, setOf("a"))

        assertTrue(targets.isEmpty())
    }

    @Test
    fun orderOfAccountsIsKept() {
        val accounts = listOf(
            account("c", quota = true),
            account("a", quota = true),
            account("b", quota = true),
        )

        val targets = AccountsLogic.quotaTargets(accounts, emptySet())

        assertEquals(listOf("c", "a", "b"), targets.map { it.id.value })
    }

    @Test
    fun emptyListNeedsNothing() {
        assertTrue(AccountsLogic.quotaTargets(emptyList(), emptySet()).isEmpty())
    }
}
