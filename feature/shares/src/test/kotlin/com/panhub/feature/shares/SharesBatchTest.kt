package com.panhub.feature.shares

import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanError
import com.panhub.core.model.AccountId
import com.panhub.core.model.ShareId
import com.panhub.core.model.ShareLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 我的分享页的批量取消。
 *
 * 这里钉的是「点一次取消之后，屏幕上剩下什么、用户看到哪句话」——
 * 部分失败是最容易做错、也最容易让用户误以为全成功的那条路径。
 */
class SharesBatchTest {

    private val account = AccountId("a1")

    private fun link(id: String, title: String? = null, expired: Boolean = false) =
        ShareLink(id = ShareId(id), url = "https://pan.baidu.com/s/1" + id, title = title, expired = expired)

    private fun row(id: String, title: String? = null) =
        ShareRow(account, "账号", link(id, title))

    private fun state(vararg rows: ShareRow, selected: Set<String> = emptySet(), query: String = "") =
        SharesUiState(rows = rows.toList(), selected = selected, query = query)

    private val authError = PanError(ErrorKind.AUTH, "登录已失效")

    @Test
    fun cancellingOneShareSaysSoOnce() {
        assertEquals("已取消分享", SharesLogic.cancelledMessage(1, 1, null))
    }

    @Test
    fun cancellingSeveralSharesCountsThem() {
        assertEquals("已取消 3 条分享", SharesLogic.cancelledMessage(3, 3, null))
    }

    @Test
    fun aPartialFailureSaysHowManyWentThrough() {
        // 用户点了一次「取消」，屏幕上少了 5 条、还剩 2 条，必须当场讲明白。
        assertEquals("已取消 5/7 项", SharesLogic.cancelledMessage(5, 7, authError))
    }

    @Test
    fun whenNothingWasCancelledTheServerMessageIsUsed() {
        assertEquals("登录已失效", SharesLogic.cancelledMessage(0, 3, authError))
    }

    @Test
    fun cancellingNothingAsksForNothing() {
        assertEquals("没有要取消的分享", SharesLogic.cancelledMessage(0, 0, null))
    }

    @Test
    fun onlyTheFailedOnesStayInTheList() {
        val rows = listOf(row("1"), row("2"), row("3"))
        val left = SharesLogic.withoutCancelled(rows, setOf(rows[0].key, rows[2].key))
        assertEquals(1, left.size)
        assertEquals(ShareId("2"), left.first().link.id)
    }

    @Test
    fun cancellingEverythingLeavesAnEmptyList() {
        val rows = listOf(row("1"), row("2"))
        assertTrue(SharesLogic.withoutCancelled(rows, rows.map { it.key }.toSet()).isEmpty())
    }

    @Test
    fun confirmTitleNamesTheSingleShare() {
        assertEquals("取消「年报.pdf」？", SharesLogic.cancelTitle(listOf(row("1", "年报.pdf"))))
    }

    @Test
    fun confirmTitleCountsABatch() {
        assertEquals("取消这 2 条分享？", SharesLogic.cancelTitle(listOf(row("1"), row("2"))))
    }

    @Test
    fun batchQuestionWarnsThatAllLinksDieTogether() {
        assertTrue(SharesLogic.cancelQuestion(3).contains("3 条链接会一起失效"))
        assertTrue(SharesLogic.cancelQuestion(1).contains("链接立刻失效"))
    }

    @Test
    fun confirmButtonSaysHowManyWillGo() {
        assertEquals("取消分享", SharesLogic.cancelConfirmLabel(1))
        assertEquals("取消 4 条", SharesLogic.cancelConfirmLabel(4))
    }

    @Test
    fun selectAllOnlyLooksAtWhatIsOnScreen() {
        // 补了关键字之后屏幕上只剩一条，全选框必须认为「已经全选了」，
        // 否则用户会以为刚才那一下没生效。
        val rows = listOf(row("1", "年报.pdf"), row("2", "照片.zip"))
        val s = state(*rows.toTypedArray(), selected = setOf(rows[0].key), query = "年报")
        assertTrue(s.allSelected)
    }

    @Test
    fun nothingOnScreenIsNotAllSelected() {
        assertFalse(SharesLogic.allSelected(emptyList(), emptySet()))
    }

    @Test
    fun togglingAllPicksTheVisibleOnes() {
        val rows = listOf(row("1", "年报.pdf"), row("2", "照片.zip"))
        val picked = SharesLogic.toggleAll(rows, emptySet())
        assertEquals(rows.map { it.key }.toSet(), picked)
    }

    @Test
    fun togglingAllAgainDropsThem() {
        val rows = listOf(row("1"), row("2"))
        val all = SharesLogic.toggleAll(rows, emptySet())
        assertTrue(SharesLogic.toggleAll(rows, all).isEmpty())
    }

    @Test
    fun hiddenSelectionsSurviveAToggle() {
        // 先勾了一条在别的关键字下看得见的，再全选当前这批：
        // 取消全选只该撤销当前这批，之前那条不该被顺手清掉。
        val visible = listOf(row("1"), row("2"))
        val earlier = row("9")
        val picked = SharesLogic.toggleAll(visible, setOf(earlier.key))
        assertTrue(earlier.key in picked)
        val cleared = SharesLogic.toggleAll(visible, picked)
        assertEquals(setOf(earlier.key), cleared)
    }

    @Test
    fun selectedRowsFollowTheListOrder() {
        val rows = listOf(row("1"), row("2"), row("3"))
        val picked = SharesLogic.selectedRows(rows, setOf(rows[2].key, rows[0].key))
        assertEquals(listOf(ShareId("1"), ShareId("3")), picked.map { it.link.id })
    }

    @Test
    fun selectionSummaryCountsTheShares() {
        assertEquals("已选 5 条", SharesLogic.selectionSummary(5))
    }
}
