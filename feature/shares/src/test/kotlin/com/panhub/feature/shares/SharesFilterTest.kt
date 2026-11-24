package com.panhub.feature.shares

import com.panhub.core.model.AccountId
import com.panhub.core.model.ShareId
import com.panhub.core.model.ShareLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 我的分享页的筛选与空状态。
 *
 * 分享列表通常只有十几条，但一条链接往往要跟别人反复确认「是哪一条」，
 * 所以按标题筛是常用动作；筛没了和无分享必须说成两件事。
 */
class SharesFilterTest {

    private fun link(id: String, title: String? = null, url: String = "https://pan.baidu.com/s/1" + id) =
        ShareLink(id = ShareId(id), url = url, title = title)

    private fun row(id: String, title: String? = null, url: String = "https://pan.baidu.com/s/1" + id) =
        ShareRow(AccountId("a1"), "账号", link(id, title, url))

    private fun state(vararg rows: ShareRow, query: String = "") =
        SharesUiState(rows = rows.toList(), query = query)

    @Test
    fun withoutAKeywordEveryShareIsVisible() {
        val s = state(row("1", "年报.pdf"), row("2", "照片.zip"))
        assertEquals(2, s.visible.size)
        assertTrue(!s.filtering)
        assertNull(s.filterSummary)
    }

    @Test
    fun aKeywordNarrowsTheList() {
        val s = state(row("1", "年报.pdf"), row("2", "照片.zip"), query = "年报")
        assertEquals(1, s.visible.size)
        assertEquals("年报.pdf", s.visible.first().link.title)
    }

    @Test
    fun matchingIgnoresCase() {
        val s = state(row("1", "Report.PDF"), query = "report")
        assertEquals(1, s.visible.size)
    }

    @Test
    fun theLinkTailIsSearchableWhenThereIsNoTitle() {
        // 没有标题时卡片上显示的是链接尾段，用户照着屏幕上看到的字去搜，必须能搜到。
        val s = state(row("1", url = "https://pan.baidu.com/s/1abc"), query = "1abc")
        assertEquals(1, s.visible.size)
    }

    @Test
    fun summaryOnlyCountsWhatTheKeywordHid() {
        val s = state(row("1", "年报.pdf"), row("2", "照片.zip"), row("3", "年报备份.pdf"), query = "年报")
        assertEquals("筛选中 2/3 项", s.filterSummary)
    }

    @Test
    fun filteringEverythingOutIsNotTheSameAsHavingNoShares() {
        val s = state(row("1", "年报.pdf"), query = "不存在的名字")
        assertTrue(s.visible.isEmpty())
        assertEquals("没有名字包含「不存在的名字」的项目", s.emptyTitle)
        assertEquals("换个关键字，或者点上面的清空", s.emptyHint)
    }

    @Test
    fun anEmptyAccountSaysThereIsNothingToLookAt() {
        val s = state()
        assertEquals("还没有分享", s.emptyTitle)
        assertEquals("在文件列表里选中文档，就能生成分享链接", s.emptyHint)
    }

    @Test
    fun filteringKeepsTheValidityOrder() {
        val s = state(
            row("1", "已失效的报告.pdf").let { it.copy(link = it.link.copy(expired = true)) },
            row("2", "报告.pdf"),
            query = "报告",
        )
        assertEquals(ShareId("2"), s.visible.first().link.id)
    }
}
