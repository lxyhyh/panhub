package com.panhub.core.network

import java.io.InputStream

/**
 * 从 [offset] 开始、只读 [length] 字节的视图。
 *
 * 分片上传必须有它：每个分片都要重新 openStream()（流的定位能力不保证），
 * 而 OkHttp 会按 contentLength 精确读取，多给一个字节就会破坏分片边界。
 *
 * 放在 core:network 而不是某个服务商模块里，是因为 123 云盘和夸克网盘用同一套
 * 「按范围重新开流」的办法。
 */
fun InputStream.bounded(offset: Long, length: Long): InputStream {
    // 手写跳过而不是 skipNBytes：后者是 Java 12 才加的，Android 上要到 API 33
    // 才存在，minSdk 26 的机器会直接 NoSuchMethodError。
    var skipped = 0L
    while (skipped < offset) {
        val n = skip(offset - skipped)
        if (n <= 0) {
            if (read() == -1) break
            skipped++
        } else {
            skipped += n
        }
    }
    return object : InputStream() {
        private var remaining = length

        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = this@bounded.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val want = minOf(len.toLong(), remaining).toInt()
            val read = this@bounded.read(b, off, want)
            if (read > 0) remaining -= read
            return read
        }

        override fun close() {
            this@bounded.close()
        }
    }
}
