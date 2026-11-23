package com.panhub.feature.search

import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.ProviderCapabilities
import com.panhub.core.model.ProviderId
import com.panhub.core.model.RemoteNode
import com.panhub.core.session.AccountSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨账号搜索的纯逻辑。
 *
 * 这些规则决定了「结果里出现什么」，一旦错了用户看到的是错的东西而不是
 * 报错，所以逐条钉住。
 */
class SearchLogicTest {

    private fun account(
        id: String = "quark:1",
        name: String = "夸克网盘",
        search: Boolean = true,
        lastUsedAt: Long = 0L,
    ) = AccountSummary(
        id = AccountId(id),
        providerId = ProviderId("quark"),
        providerName = "夸克网盘",
        displayName = name,
        lastUsedAt = lastUsedAt,
        capabilities = ProviderCapabilities(search = search),
    )

    private fun node(id: String, name: String = id, dir: Boolean = false) = RemoteNode(
        id = NodeId(id),
        parentId = null,
        name = name,
        kind = if (dir) NodeKind.DIR else NodeKind.FILE,
    )

    private fun hit(accountId: String = "quark:1", accountName: String = "夸克网盘", nodeId: String = "1") =
        SearchHit(AccountId(accountId), accountName, node(nodeId))

    @Test
    fun onlyAccountsDeclaringSearchAreQueried() {
        val accounts = listOf(
            account(id = "quark:1", name = "夸克", search = true),
            account(id = "webdav:1", name = "坚果云", search = false),
        )
        assertEquals(listOf("夸克"), SearchLogic.targets(accounts).map { it.displayName })
    }

    @Test
    fun accountsWithoutSearchAreReportedInsteadOfSilentlyMissing() {
        val accounts = listOf(
            account(id = "quark:1", name = "夸克", search = true),
            account(id = "webdav:1", name = "坚果云", search = false),
            account(id = "lanzou:1", name = "蓝奏云", search = false),
        )
        assertEquals(
            listOf("坚果云", "蓝奏云"),
            SearchLogic.skipped(accounts).map { it.displayName },
        )
    }

    @Test
    fun resultsAreGroupedByAccountKeepingProviderOrder() {
        val hits = listOf(
            hit(accountId = "quark:1", accountName = "夸克", nodeId = "a"),
            hit(accountId = "quark:1", accountName = "夸克", nodeId = "b"),
            hit(accountId = "baidu:1", accountName = "百度", nodeId = "c"),
        )
        val groups = SearchLogic.grouped(hits)
        assertEquals(listOf("夸克", "百度"), groups.map { it.first })
        assertEquals(listOf("a", "b"), groups[0].second.map { it.node.id.value })
        assertEquals(listOf("c"), groups[1].second.map { it.node.id.value })
    }

    @Test
    fun mergingDropsDuplicatesSoLazyKeysStayUnique() {
        val first = listOf(hit(nodeId = "a"), hit(nodeId = "b"))
        val merged = SearchLogic.merge(first, listOf(hit(nodeId = "b"), hit(nodeId = "c")))
        assertEquals(listOf("a", "b", "c"), merged.map { it.node.id.value })
    }

    @Test
    fun sameNodeIdInTwoAccountsIsNotADuplicate() {
        val merged = SearchLogic.merge(
            listOf(hit(accountId = "quark:1", accountName = "夸克", nodeId = "a")),
            listOf(hit(accountId = "baidu:1", accountName = "百度", nodeId = "a")),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun summaryIsEmptyUntilSomethingIsSearched() {
        assertEquals("", SearchLogic.summary(SearchUiState()))
    }

    @Test
    fun summarySaysHowManyAccountsProducedHits() {
        val state = SearchUiState(
            searched = true,
            hits = listOf(
                hit(accountId = "quark:1", accountName = "夸克", nodeId = "a"),
                hit(accountId = "baidu:1", accountName = "百度", nodeId = "b"),
            ),
        )
        assertEquals("在 2 个网盘里找到 2 项", SearchLogic.summary(state))
    }

    @Test
    fun emptySearchWithoutFailuresSaysNothingMatched() {
        val state = SearchUiState(searched = true)
        assertEquals("没有找到匹配的文件", SearchLogic.summary(state))
    }

    @Test
    fun emptySearchWithFailuresSaysResultsMayBeIncomplete() {
        val state = SearchUiState(
            searched = true,
            failures = listOf(SearchFailure("百度", "登录已过期")),
        )
        assertEquals("没有找到匹配的文件（有账号没搜成）", SearchLogic.summary(state))
    }

    @Test
    fun busySearchSaysItIsStillWorking() {
        val state = SearchUiState(searched = true, busy = true)
        assertEquals("正在搜索…", SearchLogic.summary(state))
    }

    @Test
    fun perAccountLimitIsSmallEnoughToStayReadable() {
        assertTrue(SearchLogic.PER_ACCOUNT_LIMIT in 20..500)
    }

    @Test
    fun focusCarriesTheFolderTheHitLivesIn() {
        val node = RemoteNode(
            id = NodeId("f1"),
            parentId = NodeId("p1"),
            name = "报告.pdf",
            kind = NodeKind.FILE,
            providerPath = "资料",
        )
        val focus = SearchHit(AccountId("quark:1"), "夸克", node).focus
        assertEquals("f1", focus.id.value)
        assertEquals("p1", focus.parentId?.value)
        assertEquals("资料", focus.parentName)
        assertEquals(false, focus.dir)
    }

    @Test
    fun blankProviderPathDoesNotBecomeAFolderName() {
        val node = RemoteNode(
            id = NodeId("f1"),
            parentId = null,
            name = "报告.pdf",
            kind = NodeKind.FILE,
            providerPath = "  ",
        )
        assertEquals(null, SearchHit(AccountId("a"), "A", node).focus.parentName)
    }
}
