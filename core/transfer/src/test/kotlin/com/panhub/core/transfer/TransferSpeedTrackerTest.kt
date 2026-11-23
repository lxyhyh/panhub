package com.panhub.core.transfer

import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferSpeedTrackerTest {

    private fun task(id: String, state: TransferState = TransferState.RUNNING, bytes: Long = 0L) =
        TransferTask(
            id = id,
            accountId = AccountId("acc"),
            nodeId = NodeId("n-" + id),
            name = id + ".bin",
            state = state,
            bytes = bytes,
            totalBytes = 1_000L,
        )

    @Test
    fun runningTaskGetsASpeedAfterTwoSamples() {
        val tracker = TransferSpeedTracker()
        tracker.sample(listOf(task("a", bytes = 0L)), 1_000L)
        val speeds = tracker.sample(listOf(task("a", bytes = 4_096L)), 2_000L)

        assertEquals(4_096L, speeds["a"])
    }

    @Test
    fun firstSampleHasNoSpeedYet() {
        val tracker = TransferSpeedTracker()
        val speeds = tracker.sample(listOf(task("a", bytes = 100L)), 1_000L)

        assertTrue(speeds.isEmpty())
    }

    @Test
    fun finishedTaskIsDroppedAndForgetsItsMeter() {
        val tracker = TransferSpeedTracker()
        tracker.sample(listOf(task("a", bytes = 0L)), 1_000L)
        tracker.sample(listOf(task("a", bytes = 1_000L)), 2_000L)

        val afterFinish = tracker.sample(listOf(task("a", state = TransferState.COMPLETED, bytes = 1_000L)), 3_000L)
        assertTrue(afterFinish.isEmpty())

        // 再回到进行中时是全新计时：旧采样不能参与，否则会立刻报出一个假速率。
        val restarted = tracker.sample(listOf(task("a", bytes = 1_000L)), 4_000L)
        assertFalse(restarted.containsKey("a"))
    }

    @Test
    fun queuedTaskIsNotMeasured() {
        val tracker = TransferSpeedTracker()
        tracker.sample(listOf(task("a", state = TransferState.QUEUED, bytes = 0L)), 1_000L)
        val speeds = tracker.sample(listOf(task("a", state = TransferState.QUEUED, bytes = 0L)), 2_000L)

        assertTrue(speeds.isEmpty())
    }

    @Test
    fun tasksAreMeasuredIndependently() {
        val tracker = TransferSpeedTracker()
        tracker.sample(listOf(task("a", bytes = 0L), task("b", bytes = 0L)), 1_000L)
        val speeds = tracker.sample(listOf(task("a", bytes = 2_000L), task("b", bytes = 500L)), 2_000L)

        assertEquals(2_000L, speeds["a"])
        assertEquals(500L, speeds["b"])
    }
}
