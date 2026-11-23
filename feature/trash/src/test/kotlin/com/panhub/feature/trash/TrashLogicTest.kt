package com.panhub.feature.trash

import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.RemoteNode
import com.panhub.core.model.TrashNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回收站里那几句会被用户当真话看的文案。
 *
 * 过期时间算错、删除时间编出来，都不会崩，只会让人晚一天想起来去救文件，
 * 所以这里的期望值都是手算出来的具体数字。
 */
class TrashLogicTest {

    private val now = 1_791_000_000_000L

    private fun node(
        name: String = "a.txt",
        dir: Boolean = false,
        size: Long = 10L,
        deletedAt: Long? = now - 3_600_000L,
        expiresAt: Long? = null,
    ) = TrashNode(
        node = RemoteNode(
            id = NodeId("7"),
            parentId = null,
            name = name,
            kind = if (dir) NodeKind.DIR else NodeKind.FILE,
            size = size,
        ),
        deletedAt = deletedAt,
        expiresAt = expiresAt,
    )

    @Test
    fun subtitleNamesTheDeleteTimeWhenItIsKnown() {
        // 删除时间按本地时区格式化，这一条只钉住格式与拼接，不钉日期本身。
        val line = TrashLogic.subtitle(node(), now)
        assertTrue(line.startsWith("删除于 "))
        assertTrue(line.contains("·").not())
    }

    @Test
    fun subtitleDoesNotInventADeleteTime() {
        val line = TrashLogic.subtitle(node(deletedAt = null), now)
        // 老条目没有 TrashedAt，宁可说不知道，也别编一个时间。
        assertEquals("删除时间未知", line)
    }

    @Test
    fun subtitleSaysTodayWhenItExpiresWithinADay() {
        val line = TrashLogic.subtitle(node(expiresAt = now + 3_600_000L), now)
        assertTrue(line, line.endsWith(" · 今天过期"))
    }

    @Test
    fun subtitleCountsTheWholeDaysLeft() {
        val line = TrashLogic.subtitle(node(expiresAt = now + 3L * 86_400_000L), now)
        assertTrue(line, line.endsWith(" · 3 天后过期"))
    }

    @Test
    fun subtitleMarksAlreadyExpiredEntries() {
        val line = TrashLogic.subtitle(node(expiresAt = now - 1L), now)
        assertTrue(line, line.endsWith(" · 已过期"))
    }

    @Test
    fun daysLeftRoundsUpSoTheLastPartialDayIsNotZero() {
        // 差 3 小时到期：说「还有 0 天」跟说「已经没了」一样糟，向上取整成 1。
        assertEquals(1L, TrashLogic.daysLeft(now + 3 * 3_600_000L, now))
        assertEquals(2L, TrashLogic.daysLeft(now + 25L * 3_600_000L, now))
    }

    @Test
    fun daysLeftIsExactWhenTheDeadlineFallsOnAWholeDay() {
        assertEquals(1L, TrashLogic.daysLeft(now + 86_400_000L, now))
        assertEquals(0L, TrashLogic.daysLeft(now, now))
        assertEquals(0L, TrashLogic.daysLeft(now - 1L, now))
    }

    @Test
    fun unknownExpiryHasNoDaysLeft() {
        assertNull(TrashLogic.daysLeft(null, now))
    }

    @Test
    fun allSelectedNeedsANonEmptyList() {
        // 空列表上的「全选」不能被当成已全选，否则按钮文案会反过来。
        assertFalse(TrashLogic.allSelected(emptyList(), emptySet()))
        assertFalse(TrashLogic.allSelected(listOf(node()), emptySet()))
        assertTrue(TrashLogic.allSelected(listOf(node()), setOf("7")))
    }

    @Test
    fun resultMessageCountsOnlyWhenEverythingWorked() {
        assertEquals("已还原 3 项", TrashLogic.resultMessage(3, 3, "还原", null))
        assertEquals("已彻底删除 1 项", TrashLogic.resultMessage(1, 1, "彻底删除", null))
    }

    @Test
    fun resultMessageCarriesTheReasonWhenNothingWorked() {
        assertEquals("登录状态已失效", TrashLogic.resultMessage(0, 2, "还原", "登录状态已失效"))
        assertEquals("没能还原这 2 项", TrashLogic.resultMessage(0, 2, "还原", null))
    }

    @Test
    fun resultMessageShowsThePartialCountAndTheReason() {
        assertEquals(
            "已还原 1/3 项 · 有 2 项没能还原，原目录可能已经不在了",
            TrashLogic.resultMessage(1, 3, "还原", "有 2 项没能还原，原目录可能已经不在了"),
        )
    }

    @Test
    fun folderOnlyDropsFiles() {
        val items = listOf(
            folder("photos"),
            file("a.txt"),
            folder("docs"),
        )

        assertEquals(listOf("photos", "docs"), TrashLogic.folderOnly(items).map { it.name })
    }

    @Test
    fun folderOnlyOnAnEmptyPageStaysEmpty() {
        assertTrue(TrashLogic.folderOnly(emptyList()).isEmpty())
    }

    @Test
    fun restoreTargetLabelJoinsTheStack() {
        val stack = listOf(folder("photos"), folder("2026"))

        // 目标的层级是选目录时唯一能确认「我现在在哪」的东西，
        // 只显示最后一层会在同名目录上骗人。
        assertEquals("photos / 2026", TrashLogic.restoreTargetLabel(stack))
    }

    @Test
    fun restoreTargetLabelFallsBackToTheRoot() {
        assertEquals("我的网盘", TrashLogic.restoreTargetLabel(emptyList()))
    }

    @Test
    fun restoreTargetLabelIgnoresBlankNames() {
        val stack = listOf(folder("photos"), folder("   "))

        assertEquals("photos", TrashLogic.restoreTargetLabel(stack))
    }
}

private fun folder(name: String) = RemoteNode(
    id = NodeId("/" + name),
    parentId = null,
    name = name,
    kind = NodeKind.DIR,
)

private fun file(name: String) = RemoteNode(
    id = NodeId("/" + name),
    parentId = null,
    name = name,
    kind = NodeKind.FILE,
)
