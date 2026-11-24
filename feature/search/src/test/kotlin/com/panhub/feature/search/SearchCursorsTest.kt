package com.panhub.feature.search

import com.panhub.core.model.AccountId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索结果翻页的游标管理。
 *
 * 这里错的后果不响：游标丢了用户只是点一次没反应，或者被「加载更多」
 * 骗着反复点同一个空页——都像网络慢，不像代码错。所以逐条钉住。
 */
class SearchCursorsTest {

    private fun cursor(id: String, name: String, value: String) =
        SearchCursor(AccountId(id), name, value)

    @Test
    fun onlyAccountsWithACursorAreOffered() {
        val all = listOf(cursor("quark:1", "夸克", "2"), cursor("baidu:1", "百度", ""))
        val kept = SearchLogic.cursorsOf(all, setOf("quark:1", "baidu:1"))
        assertEquals(listOf("夸克"), kept.map { it.accountName })
    }

    @Test
    fun aFailedAccountIsNeverOfferedEvenIfItHadACursor() {
        // 这一轮失败说明游标不可信，把它算进来只会让人点进去看错误。
        val all = listOf(cursor("quark:1", "夸克", "2"))
        assertTrue(SearchLogic.cursorsOf(all, emptySet()).isEmpty())
    }

    @Test
    fun nextCursorsDropsAccountsThatRanOut() {
        val current = listOf(cursor("quark:1", "夸克", "2"))
        val refreshed = listOf(cursor("quark:1", "夸克", ""))
        assertTrue(SearchLogic.nextCursors(current, refreshed, setOf("quark:1")).isEmpty())
    }

    @Test
    fun nextCursorsKeepsTheNewCursor() {
        val current = listOf(cursor("quark:1", "夸克", "2"))
        val refreshed = listOf(cursor("quark:1", "夸克", "3"))
        assertEquals(listOf("3"), SearchLogic.nextCursors(current, refreshed, setOf("quark:1")).map { it.cursor })
    }

    @Test
    fun anAccountThatWasNotAskedKeepsItsOldCursor() {
        // 一次超时不该把「还有更多」这个入口整个抹掉。
        val current = listOf(cursor("quark:1", "夸克", "2"), cursor("baidu:1", "百度", "4"))
        val refreshed = listOf(cursor("quark:1", "夸克", ""))
        val kept = SearchLogic.nextCursors(current, refreshed, setOf("quark:1"))
        assertEquals(listOf("百度"), kept.map { it.accountName })
    }

    @Test
    fun anAccountThatVanishedIsDropped() {
        val current = listOf(cursor("gone:1", "已删除", "2"))
        assertTrue(SearchLogic.nextCursors(current, emptyList(), setOf("gone:1")).isEmpty())
    }
}
