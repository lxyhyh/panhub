package com.panhub.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedMeterTest {

    @Test
    fun singleSampleHasNoSpeed() {
        val meter = SpeedMeter()
        meter.record(1_000L, 100L)

        assertNull(meter.bytesPerSecond())
    }

    @Test
    fun twoSamplesOverASecondGiveBytesPerSecond() {
        val meter = SpeedMeter()
        meter.record(1_000L, 0L)
        meter.record(2_000L, 2_048L)

        assertEquals(2_048L, meter.bytesPerSecond())
    }

    @Test
    fun veryShortSpanIsNotMeasured() {
        // 两次采样只隔 10 毫秒：除数太小，速率会飙到天上，宁可不显示。
        val meter = SpeedMeter()
        meter.record(1_000L, 0L)
        meter.record(1_010L, 10L)

        assertNull(meter.bytesPerSecond())
    }

    @Test
    fun stalledTransferReportsZeroNotNull() {
        val meter = SpeedMeter()
        meter.record(1_000L, 500L)
        meter.record(3_000L, 500L)

        assertEquals(0L, meter.bytesPerSecond())
    }

    @Test
    fun oldSamplesFallOutOfTheWindow() {
        val meter = SpeedMeter(windowMillis = 2_000L)
        // 第一段很快，随后停住：窗口外的采样必须丢掉，否则会一直报着旧的高速率。
        meter.record(0L, 0L)
        meter.record(1_000L, 1_000L)
        meter.record(5_000L, 1_000L)
        meter.record(6_000L, 1_000L)

        assertEquals(0L, meter.bytesPerSecond())
    }

    @Test
    fun clockGoingBackwardsRestartsInsteadOfReportingNegative() {
        val meter = SpeedMeter()
        meter.record(10_000L, 5_000L)
        meter.record(9_000L, 5_100L)

        // 旧采样作废后只剩一个，测不出来；关键是别给出负数。
        assertNull(meter.bytesPerSecond())
    }

    @Test
    fun resetDropsEverything() {
        val meter = SpeedMeter()
        meter.record(1_000L, 0L)
        meter.record(2_000L, 1_000L)
        meter.reset()

        assertNull(meter.bytesPerSecond())
    }
}
