package com.panhub.feature.search

import com.panhub.core.model.AccountId
import com.panhub.core.model.FileKind
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.RemoteNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索结果按文件类型筛。
 *
 * 这里全部是纯函数，所以不拖 ViewModel：真正要钉住的是「筛完之后说什么」——
 * 一句话说错，用户就会以为搜索本身出了毛病，而不是被自己的条件筛掉了。
 */
class SearchKindFilterTest {

    private fun node(name: String, dir: Boolean = false) = RemoteNode(
        id = NodeId(name),
        parentId = null,
        name = name,
        kind = if (dir) NodeKind.DIR else NodeKind.FILE,
    )

    private fun hit(name: String, account: String = "quark:1", dir: Boolean = false) =
        SearchHit(AccountId(account), account, node(name, dir))

    @Test
    fun withoutAKindEveryHitStays() {
        val hits = listOf(hit("a.png"), hit("b.txt"))
        assertTrue(SearchLogic.visibleHits(hits, null) === hits)
    }

    @Test
    fun onlyFilesOfThatKindSurvive() {
        val hits = listOf(hit("a.png"), hit("b.txt"), hit("c.mp4"))
        assertEquals(
            listOf("a.png"),
            SearchLogic.visibleHits(hits, FileKind.IMAGE).map { it.node.name },
        )
    }

    @Test
    fun foldersAreDroppedByAKindFilter() {
        // 浏览页留文件夹是「还能进去」，搜索结果里的文件夹只是名字撞上了关键字。
        val hits = listOf(hit("图片"), hit("a.png"))
        assertEquals(
            listOf("a.png"),
            SearchLogic.visibleHits(hits, FileKind.IMAGE).map { it.node.name },
        )
    }

    @Test
    fun filteringKeepsTheServiceOrder() {
        val hits = listOf(hit("c.jpg"), hit("x.txt"), hit("a.png"))
        assertEquals(
            listOf("c.jpg", "a.png"),
            SearchLogic.visibleHits(hits, FileKind.IMAGE).map { it.node.name },
        )
    }

    @Test
    fun summaryCountsWhatIsOnScreenNow() {
        val state = SearchUiState(
            searched = true,
            hits = listOf(hit("a.png"), hit("b.txt")),
            kind = FileKind.IMAGE,
        )
        assertEquals("在 1 个网盘里找到 1 项", SearchLogic.summary(state))
    }

    @Test
    fun aKindThatFiltersEverythingOutSaysSoInsteadOfNoResults() {
        // 说「没有找到匹配的文件」会让用户回去改那个根本没填错的关键字。
        val state = SearchUiState(
            searched = true,
            hits = listOf(hit("b.txt")),
            kind = FileKind.IMAGE,
        )
        assertEquals("搜到的结果里没有图片", SearchLogic.summary(state))
    }

    @Test
    fun aFailedAccountIsStillMentionedWhenAKindFilteredEverythingOut() {
        val state = SearchUiState(
            searched = true,
            hits = listOf(hit("b.txt")),
            failures = listOf(SearchFailure("百度", "登录已过期")),
            kind = FileKind.IMAGE,
        )
        assertEquals("搜到的结果里没有图片（有账号没搜成）", SearchLogic.summary(state))
    }

    @Test
    fun anEmptySearchIsStillAnEmptySearch() {
        val state = SearchUiState(searched = true, kind = FileKind.IMAGE)
        assertEquals("没有找到匹配的文件", SearchLogic.summary(state))
    }

    @Test
    fun theMiddleOfTheScreenSaysWhichKindWasMissing() {
        assertEquals("搜到的结果里没有图片", SearchLogic.emptyKindMessage(FileKind.IMAGE))
        assertEquals("没有找到匹配的文件", SearchLogic.emptyKindMessage(null))
    }

    @Test
    fun filterSummaryOnlyShowsUpWhenSomethingWentAway() {
        assertNull(SearchLogic.filterSummary(3, 3, FileKind.IMAGE))
        assertEquals("筛选中 1/3 项", SearchLogic.filterSummary(1, 3, FileKind.IMAGE))
    }

    @Test
    fun filterSummaryIsSilentWithoutAKind() {
        assertNull(SearchLogic.filterSummary(1, 3, null))
    }
}
