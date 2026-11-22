package com.panhub.provider.pan123

import com.panhub.core.network.bounded
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class Pan123SlidingStreamTest {

    private fun read(offset: Long, length: Long, data: ByteArray): ByteArray {
        val stream = ByteArrayInputStream(data).bounded(offset, length)
        return stream.use { it.readBytes() }
    }

    @Test
    fun readsWholeStreamWhenOffsetIsZero() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        assertArrayEquals(data, read(0, 5, data))
    }

    @Test
    fun readsOnlyRequestedRange() {
        val data = ByteArray(100) { it.toByte() }
        assertArrayEquals(byteArrayOf(10, 11, 12), read(10, 3, data))
    }

    @Test
    fun rangeAtEndOfStreamStopsAtEnd() {
        val data = ByteArray(10) { it.toByte() }
        // 要求 5 字节但只剩 2 字节，必须老实返回 2 字节，
        // 否则 OkHttp 的 contentLength 与实际写入不一致，连接会被重置。
        assertEquals(2, read(8, 5, data).size)
    }

    @Test
    fun emptyRangeReadsNothing() {
        val data = ByteArray(10)
        assertEquals(0, read(0, 0, data).size)
    }

    @Test
    fun offsetPastEndReadsNothing() {
        val data = ByteArray(4)
        assertEquals(0, read(100, 2, data).size)
    }

    @Test
    fun offsetBeyondSkipLimitStillWorks() {
        // 有些 InputStream 的 skip 会提前返回 0（网络流常见），
        // 这时必须退化成 read 逐个跳过，不能少跳。
        val data = ByteArray(50) { (it + 1).toByte() }
        val lazy = object : java.io.InputStream() {
            private var index = 0
            override fun read(): Int =
                if (index >= data.size) -1 else data[index++].toInt() and 0xFF

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (index >= data.size) return -1
                val n = minOf(len, data.size - index)
                System.arraycopy(data, index, b, off, n)
                index += n
                return n
            }

            override fun skip(n: Long): Long = 0
        }
        val bytes = lazy.bounded(45, 5).use { it.readBytes() }
        assertArrayEquals(byteArrayOf(46, 47, 48, 49, 50), bytes)
    }
}
