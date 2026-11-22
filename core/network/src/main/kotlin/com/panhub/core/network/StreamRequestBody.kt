package com.panhub.core.network

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream

/**
 * 从 [InputStream] 流式读取的请求体。
 *
 * 为什么不用 InputStream.toRequestBody()：那会把整个文件读进内存，
 * 手机上放一个 2GB 的视频直接 OOM。这里边读边写，内存占用只有一个缓冲块。
 *
 * [onBytes] 是**非挂起**回调：它在 OkHttp 的写线程里被调用，
 * 调用方负责把它桥接到自己的协程里。
 */
class StreamRequestBody(
    private val openStream: () -> InputStream,
    private val length: Long,
    private val contentType: MediaType? = null,
    private val onBytes: ((Long) -> Unit)? = null,
) : RequestBody() {

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = length

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        openStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var sent = 0L
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
                sent += read
                onBytes?.invoke(sent)
            }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
