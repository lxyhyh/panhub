package com.panhub.feature.transfers

import com.panhub.core.model.AccountId
import com.panhub.core.transfer.TransferDirection
import com.panhub.core.transfer.TransferTask
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 传输卡片上的账号归属。
 *
 * 传输页是唯一把不同账号的任务混排在一起的地方，
 * 所以这里守两件事：只有一个账号时不要凑字数，跨网盘时两头都要说清楚。
 */
class TransferAccountNoteTest {

    private val labels = mapOf("a" to "我的 123", "b" to "公司夸克")

    private fun task(
        account: String = "a",
        target: String? = null,
    ) = TransferTask(
        id = "t",
        accountId = AccountId(account),
        name = "a.txt",
        direction = TransferDirection.DOWNLOAD,
        targetAccountId = target?.let { AccountId(it) },
    )

    @Test
    fun aSingleAccountNeedsNoLabel() {
        assertEquals(
            "",
            TransferAccountNote.note(task(), mapOf("a" to "我的 123")),
        )
    }

    @Test
    fun severalAccountsNameTheOwner() {
        assertEquals(" · 我的 123", TransferAccountNote.note(task(), labels))
    }

    @Test
    fun aCloudCopyNamesBothEnds() {
        assertEquals(
            " · 我的 123 → 公司夸克",
            TransferAccountNote.note(task(target = "b"), labels),
        )
    }

    @Test
    fun anUnknownAccountSaysSoInsteadOfShowingNothing() {
        assertEquals(" · 未知账号", TransferAccountNote.note(task("gone"), labels))
    }

    @Test
    fun anUnknownTargetIsAlsoNamed() {
        assertEquals(
            " · 我的 123 → 未知账号",
            TransferAccountNote.note(task(target = "gone"), labels),
        )
    }

    @Test
    fun aBlankLabelCountsAsUnknown() {
        assertEquals(
            " · 未知账号",
            TransferAccountNote.note(task(), mapOf("a" to "  ", "b" to "公司夸克")),
        )
    }
}
