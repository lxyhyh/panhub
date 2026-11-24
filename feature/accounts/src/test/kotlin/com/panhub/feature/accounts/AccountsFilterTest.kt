package com.panhub.feature.accounts

import com.panhub.core.model.AccountId
import com.panhub.core.model.ProviderCapabilities
import com.panhub.core.model.ProviderId
import com.panhub.core.session.AccountSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountsFilterTest {

    private fun account(
        id: String,
        provider: String = "坚果云",
        display: String = id,
        alias: String? = null,
    ): AccountSummary = AccountSummary(
        id = AccountId(id),
        providerId = ProviderId("fake"),
        providerName = provider,
        displayName = display,
        lastUsedAt = 0L,
        capabilities = ProviderCapabilities(),
        alias = alias,
    )

    private fun state(vararg accounts: AccountSummary, query: String = "") =
        AccountsUiState(accounts = accounts.toList(), loading = false, query = query)

    @Test
    fun withoutAKeywordEveryAccountIsVisible() {
        val state = state(account("a"), account("b"))

        assertEquals(listOf("a", "b"), state.visible.map { it.id.value })
        assertFalse(state.filtering)
        assertNull(state.filterSummary)
    }

    @Test
    fun aKeywordNarrowsTheList() {
        val state = state(
            account("a", provider = "坚果云"),
            account("b", provider = "百度网盘"),
            query = "百度",
        )

        assertEquals(listOf("b"), state.visible.map { it.id.value })
    }

    @Test
    fun theAliasIsSearchable() {
        // 备注名是用来区分同名账号的，记不住服务商给的那串名字很正常。
        val state = state(account("a", display = "me@example.com", alias = "工作号"), query = "工作")

        assertEquals(listOf("a"), state.visible.map { it.id.value })
    }

    @Test
    fun theDisplayNameIsSearchableEvenWhenAnAliasExists() {
        val state = state(account("a", display = "me@example.com", alias = "工作号"), query = "example")

        assertEquals(listOf("a"), state.visible.map { it.id.value })
    }

    @Test
    fun matchingIgnoresCase() {
        val state = state(account("a", display = "Report@Example.com"), query = "report")

        assertEquals(listOf("a"), state.visible.map { it.id.value })
    }

    @Test
    fun theSummaryOnlyShowsUpWhenSomethingWasHidden() {
        val state = state(
            account("a", provider = "坚果云"),
            account("b", provider = "坚果云"),
            account("c", provider = "百度网盘"),
            query = "坚果",
        )

        assertEquals("筛选中 2/3 项", state.filterSummary)
    }

    @Test
    fun filteringEverythingOutIsNotTheSameAsHavingNoAccounts() {
        val state = state(account("a", provider = "坚果云"), query = "不存在的名字")

        assertTrue(state.visible.isEmpty())
        assertFalse(state.accounts.isEmpty())
        assertEquals("没有名字包含「不存在的名字」的项目", state.emptyTitle)
        assertEquals("换个关键字，或者点上面的清空", state.emptyHint)
    }

    @Test
    fun noAccountsAtAllKeepsTheOriginalWording() {
        val state = state(query = "随便")

        assertEquals("还没有账号", state.emptyTitle)
        assertEquals("点右下角的加号，接入第一个网盘", state.emptyHint)
    }

    @Test
    fun orderOfAccountsIsKeptWhileFiltering() {
        val state = state(
            account("c", provider = "坚果云"),
            account("a", provider = "坚果云"),
            account("b", provider = "百度网盘"),
            query = "坚果",
        )

        assertEquals(listOf("c", "a"), state.visible.map { it.id.value })
    }
}
