package com.panhub.feature.accounts

import com.panhub.core.model.AccountId
import com.panhub.core.model.ProviderCapabilities
import com.panhub.core.model.ProviderId
import com.panhub.core.session.AccountSummary
import com.panhub.core.model.Quota
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun nothingKnownMeansNoSummaryAtAll() {
        assertNull(AccountsLogic.quotaSummary(emptyList()))
    }

    @Test
    fun knownTotalsAreAddedUp() {
        val summary = AccountsLogic.quotaSummary(
            listOf(Quota(used = 100L, total = 1_000L), Quota(used = 400L, total = 9_000L)),
        )
        assertEquals(500L, summary?.used)
        assertEquals(10_000L, summary?.total)
        assertEquals(2, summary?.accounts)
        assertEquals(0, summary?.withoutTotal)
        assertEquals(5, summary?.percent)
    }

    @Test
    fun aProviderWithoutATotalDoesNotShrinkTheWhole() {
        // 把「不知道总量」当成 0 会让总和凭空变小，看起来像空间被吃掉了一块。
        val summary = AccountsLogic.quotaSummary(
            listOf(Quota(used = 100L, total = 1_000L), Quota(used = 50L, total = null)),
        )
        assertEquals(150L, summary?.used)
        assertEquals(1_000L, summary?.total)
        assertEquals(1, summary?.withoutTotal)
    }

    @Test
    fun noProviderKnowsItsTotalSoOnlyUsedIsReported() {
        val summary = AccountsLogic.quotaSummary(
            listOf(Quota(used = 100L, total = null), Quota(used = 50L, total = null)),
        )
        assertNull(summary?.total)
        assertNull(summary?.percent)
        assertEquals(150L, summary?.used)
        assertEquals(2, summary?.withoutTotal)
    }

    @Test
    fun negativeUsageIsNotSubtractedFromTheTotal() {
        // 个别服务商在算账时会给负数（比如退还空间时的中间态），照加会得到一个假的总和。
        val summary = AccountsLogic.quotaSummary(listOf(Quota(used = -10L, total = 100L)))
        assertEquals(0L, summary?.used)
        assertEquals(100L, summary?.free)
    }

    @Test
    fun freeSpaceNeverGoesNegative() {
        val summary = AccountsLogic.quotaSummary(listOf(Quota(used = 200L, total = 100L)))
        assertEquals(0L, summary?.free)
        assertEquals(100, summary?.percent)
    }

    @Test
    fun summaryLineSaysHowManyAndHowMuch() {
        val summary = AccountsLogic.quotaSummary(
            listOf(Quota(used = 512L * 1024 * 1024, total = 1024L * 1024 * 1024)),
        )
        assertEquals("1 个网盘 · 已用 512 MB / 1.0 GB（50%）", AccountsLogic.summaryLine(summary!!))
    }

    @Test
    fun summaryLineMentionsWhoDidNotReportATotal() {
        val summary = AccountsLogic.quotaSummary(
            listOf(Quota(used = 1024L, total = 2048L), Quota(used = 1024L, total = null)),
        )
        assertEquals(
            "2 个网盘 · 已用 2.0 KB / 2.0 KB（100%），另有 1 家没给总容量",
            AccountsLogic.summaryLine(summary!!),
        )
    }

    @Test
    fun summaryLineWithoutAnyTotalStillReadsNaturally() {
        val summary = AccountsLogic.quotaSummary(listOf(Quota(used = 1024L, total = null)))
        assertEquals("1 个网盘 · 已用 1.0 KB，另有 1 家没给总容量", AccountsLogic.summaryLine(summary!!))
    }
}
