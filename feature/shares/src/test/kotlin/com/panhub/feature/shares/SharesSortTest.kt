package com.panhub.feature.shares

import com.panhub.core.model.AccountId
import com.panhub.core.model.ShareId
import com.panhub.core.model.ShareLink
import com.panhub.core.model.SortOrder
import com.panhub.core.model.SortSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 我的分享页的排序。
 *
 * 这里钉住两件事：「失效的永远沉底」是外层分界，不跟着排序方向翻；
 * 以及方向反转只影响层内的那个依据。
 */
class SharesSortTest {

    private val account = AccountId("a1")

    private fun row(
        id: String,
        title: String? = null,
        createdAt: Long? = null,
        expired: Boolean = false,
    ) = ShareRow(
        accountId = account,
        accountName = "账号",
        link = ShareLink(
            id = ShareId(id),
            url = "https://pan.baidu.com/s/1" + id,
            title = title,
            createdAt = createdAt,
            expired = expired,
        ),
    )

    private val byName = SortSpec(order = SortOrder.NAME, descending = false)
    private val byTime = SortSpec(order = SortOrder.TIME, descending = true)

    @Test
    fun sortingByNamePutsTheSmallLettersFirst() {
        val rows = listOf(row("1", "zeta"), row("2", "alpha"), row("3", "Beta"))
        assertEquals(listOf("alpha", "Beta", "zeta"), rows.sortedFor(byName).map { it.link.title })
    }

    @Test
    fun reversingPutsTheLastLetterFirst() {
        val rows = listOf(row("1", "alpha"), row("2", "zeta"))
        val desc = rows.sortedFor(byName.copy(descending = true))
        assertEquals(listOf("zeta", "alpha"), desc.map { it.link.title })
    }

    @Test
    fun newestFirstIsTheDefault() {
        val rows = listOf(row("1", "a", createdAt = 100L), row("2", "b", createdAt = 900L))
        assertEquals(listOf("b", "a"), rows.sortedFor(byTime).map { it.link.title })
    }

    @Test
    fun sharesWithoutACreationTimeSinkToTheBottom() {
        val rows = listOf(
            row("1", "没有时间"),
            row("2", "新", createdAt = 900L),
            row("3", "旧", createdAt = 100L),
        )
        assertEquals(listOf("新", "旧", "没有时间"), rows.sortedFor(byTime).map { it.link.title })
    }

    @Test
    fun expiredSharesStayAtTheBottomEvenWhenReversed() {
        // 「失效的排后面」是外层分界：按名字倒序时它也不该翻上来，
        // 否则用户会以为失效链接又活了。
        val rows = listOf(row("1", "aaa", expired = true), row("2", "zzz"))
        val desc = rows.sortedFor(byName.copy(descending = true))
        assertEquals(listOf("zzz", "aaa"), desc.map { it.link.title })
    }

    @Test
    fun expiredSharesAlsoSinkWhenSortingByTime() {
        val rows = listOf(
            row("1", "失效但很新", createdAt = 9_000L, expired = true),
            row("2", "还能用但很旧", createdAt = 10L),
        )
        assertEquals(listOf("还能用但很旧", "失效但很新"), rows.sortedFor(byTime).map { it.link.title })
    }

    @Test
    fun onlyNameAndTimeAreOffered() {
        // 分享记录里没有「大小」可用：节点 id 列表的长度是条目数不是字节数，
        // 摆一个按大小排只会排出一堆看不出规律的东西。
        assertEquals(listOf(SortOrder.NAME, SortOrder.TIME), SharesLogic.SORT_ORDERS)
    }

    @Test
    fun theDefaultDirectionMatchesTheOldHardCodedOrder() {
        // 没动排序之前列表就是「失效沉底、新的在上」，默认值必须和那时一样，
        // 否则升级之后所有人的列表顺序会莫名其妙地变一次。
        val s = SharesUiState()
        assertEquals(SortOrder.TIME, s.sort.order)
        assertTrue(s.sort.descending)
    }
}
