package com.panhub.feature.shares

import com.panhub.core.model.AccountId
import com.panhub.core.model.ShareId
import com.panhub.core.model.ShareLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分享列表的纯展示逻辑。
 *
 * 这些函数决定了用户看到的「有没有密码」「还能不能用」，
 * 服务商给的字段又各不相同，所以固定在这里。
 */
class SharesTest {

    private val account = AccountId("a1")

    private fun link(
        id: String,
        url: String = "https://pan.baidu.com/s/1abc",
        password: String? = null,
        title: String? = null,
        createdAt: Long? = null,
        expiresAt: Long? = null,
        expired: Boolean = false,
    ) = ShareLink(
        id = ShareId(id),
        url = url,
        password = password,
        title = title,
        createdAt = createdAt,
        expiresAt = expiresAt,
        expired = expired,
    )

    @Test
    fun passwordRidesAlongWithTheLink() {
        assertEquals(
            "https://pan.baidu.com/s/1abc  提取码：8x8x",
            shareTextOf(link("1", password = "8x8x")),
        )
    }

    @Test
    fun blankPasswordIsOmitted() {
        assertEquals("https://pan.baidu.com/s/1abc", shareTextOf(link("1", password = "")))
    }

    @Test
    fun titleFallsBackToUrlTail() {
        assertEquals("1abc", shareTitleOf(link("1")))
        assertEquals("合同.pdf", shareTitleOf(link("1", title = "合同.pdf")))
    }

    @Test
    fun expiredWinsOverTimestamp() {
        val expired = link("1", expiresAt = 4_000_000_000_000L, expired = true)
        assertEquals("已失效", statusLine(expired))
    }

    @Test
    fun noExpiryMeansLongLived() {
        assertEquals("长期有效", statusLine(link("1")))
    }

    @Test
    fun liveSharesComeBeforeExpiredOnes() {
        val state = SharesUiState(
            rows = listOf(
                ShareRow(account, "a", link("1", expired = true)),
                ShareRow(account, "a", link("2")),
            ),
        )
        assertEquals(ShareId("2"), state.sorted.first().link.id)
    }

    @Test
    fun newestShareComesFirstWithinSameValidity() {
        val state = SharesUiState(
            rows = listOf(
                ShareRow(account, "a", link("1", createdAt = 100L)),
                ShareRow(account, "a", link("2", createdAt = 900L)),
            ),
        )
        assertEquals(ShareId("2"), state.sorted.first().link.id)
    }

    @Test
    fun rowKeyIsUniqueAcrossAccounts() {
        val x = ShareRow(AccountId("a1"), "a", link("7"))
        val y = ShareRow(AccountId("a2"), "b", link("7"))
        assertTrue("同一 shareId 在不同账号下必须能区分", x.key != y.key)
    }
}
