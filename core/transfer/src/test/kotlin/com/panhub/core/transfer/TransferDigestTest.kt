package com.panhub.core.transfer

import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferDigestTest {

    private fun task(
        id: String,
        state: TransferState = TransferState.QUEUED,
        bytes: Long = 0L,
        total: Long? = 10L,
    ) = TransferTask(
        id = id,
        accountId = AccountId("acc"),
        nodeId = NodeId("n-" + id),
        name = id + ".bin",
        state = state,
        bytes = bytes,
        totalBytes = total,
    )

    @Test
    fun emptyQueueHasNothingActive() {
        val digest = TransferDigest.of(emptyList())

        assertFalse(digest.hasActive)
        assertEquals(0, digest.active)
        assertNull(digest.running)
        assertNull(digest.percent)
    }

    @Test
    fun finishedTasksAreNotCounted() {
        val digest = TransferDigest.of(
            listOf(
                task("a", state = TransferState.COMPLETED, bytes = 10L),
                task("b", state = TransferState.FAILED),
                task("c", state = TransferState.CANCELED),
            ),
        )

        assertFalse(digest.hasActive)
        assertEquals(0L, digest.doneBytes)
    }

    @Test
    fun runningAndQueuedAreSplit() {
        val digest = TransferDigest.of(
            listOf(
                task("a", state = TransferState.RUNNING, bytes = 4L),
                task("b"),
                task("c"),
            ),
        )

        assertTrue(digest.hasActive)
        assertEquals(3, digest.active)
        assertEquals(2, digest.queued)
        assertEquals("a", digest.running?.id)
    }

    @Test
    fun bytesAddUpAcrossActiveTasks() {
        val digest = TransferDigest.of(
            listOf(
                task("a", state = TransferState.RUNNING, bytes = 4L, total = 10L),
                task("b", bytes = 5L, total = 5L),
            ),
        )

        assertEquals(9L, digest.doneBytes)
        assertEquals(15L, digest.totalBytes)
        assertEquals(60, digest.percent)
    }

    @Test
    fun unknownSizeMakesProgressIndeterminate() {
        val digest = TransferDigest.of(
            listOf(
                task("a", state = TransferState.RUNNING, bytes = 4L, total = 10L),
                task("b", total = null),
            ),
        )

        assertNull(digest.totalBytes)
        assertNull(digest.percent)
    }

    @Test
    fun zeroTotalDoesNotDivideByZero() {
        val digest = TransferDigest.of(
            listOf(task("a", state = TransferState.RUNNING, bytes = 0L, total = 0L)),
        )

        assertNull(digest.percent)
    }

    @Test
    fun percentIsClampedToHundred() {
        val digest = TransferDigest.of(
            listOf(task("a", state = TransferState.RUNNING, bytes = 12L, total = 10L)),
        )

        assertEquals(100, digest.percent)
    }
}
