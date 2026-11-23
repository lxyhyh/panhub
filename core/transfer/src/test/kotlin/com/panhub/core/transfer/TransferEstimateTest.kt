package com.panhub.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferEstimateTest {

    @Test
    fun unknownTotalHasNoEstimate() {
        assertNull(TransferEstimate.remainingMillis(bytes = 10L, totalBytes = null, bytesPerSecond = 100L))
    }

    @Test
    fun unknownSpeedHasNoEstimate() {
        assertNull(TransferEstimate.remainingMillis(bytes = 10L, totalBytes = 100L, bytesPerSecond = null))
    }

    @Test
    fun stalledSpeedHasNoEstimate() {
        // 速率为 0 时说「还剩 1 小时」是在骗人，只能说不好。
        assertNull(TransferEstimate.remainingMillis(bytes = 10L, totalBytes = 100L, bytesPerSecond = 0L))
    }

    @Test
    fun alreadyFinishedIsZero() {
        assertEquals(0L, TransferEstimate.remainingMillis(bytes = 100L, totalBytes = 100L, bytesPerSecond = 50L))
    }

    @Test
    fun remainingBytesDividedBySpeed() {
        assertEquals(2_000L, TransferEstimate.remainingMillis(bytes = 0L, totalBytes = 100L, bytesPerSecond = 50L))
    }

    @Test
    fun describesSecondsMinutesAndHours() {
        assertEquals("不到 1 秒", TransferEstimate.describe(400L))
        assertEquals("42 秒", TransferEstimate.describe(42_000L))
        assertEquals("1 分钟", TransferEstimate.describe(60_000L))
        assertEquals("2 分 5 秒", TransferEstimate.describe(125_000L))
        assertEquals("1 小时", TransferEstimate.describe(3_600_000L))
        assertEquals("1 小时 2 分", TransferEstimate.describe(3_720_000L))
    }

    @Test
    fun negativeMillisIsTreatedAsZero() {
        assertEquals("不到 1 秒", TransferEstimate.describe(-5_000L))
    }

    @Test
    fun taskOverloadReadsTheFields() {
        val task = TransferTask(
            id = "a",
            accountId = com.panhub.core.model.AccountId("acc"),
            name = "a.bin",
            bytes = 500L,
            totalBytes = 1_500L,
        )

        assertEquals(1_000L, TransferEstimate.remainingMillis(task, bytesPerSecond = 1_000L))
    }
}
