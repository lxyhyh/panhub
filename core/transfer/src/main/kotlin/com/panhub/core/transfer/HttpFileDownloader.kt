package com.panhub.core.transfer

import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.network.HttpEngine
import com.panhub.provider.api.DownloadTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 用 OkHttp 把 [DownloadTarget] 写到本地。
 *
 * 三条硬性约束在实现里体现：
 * 1. 先写 .part 临时文件，成功后才 rename——否则进度条走到 99% 断网，
 *    用户会在目录里看到一个看似完整但已损坏的文件；
 * 2. 每个 chunk 都检查协程是否已取消，取消时立刻删掉 .part，
 *    不然取消只是"停了"，磁盘上留一堆半截文件；
 * 3. 网络中断**不**删 .part，下次从断点续传。一个 3 GB 的文件传到 2.9 GB 掉线，
 *    从头再来是这个功能最让人恼火的地方；服务端支持 Range 时只补最后一小段。
 */
class HttpFileDownloader(
    private val engine: HttpEngine = HttpEngine(),
) : FileDownloader {

    override suspend fun download(
        target: DownloadTarget,
        destination: File,
        listener: ProgressListener,
    ): PanResult<Unit> = withContext(Dispatchers.IO) {
        val temp = File(destination.parentFile, destination.name + PART_SUFFIX)
        try {
            downloadToFile(target, temp, listener)
            // rename 在多数文件系统上是原子的，失败说明目标目录不可写。
            if (destination.exists()) destination.delete()
            if (!temp.renameTo(destination)) {
                PanResult.Failure(
                    PanError.unknown("无法写入目标文件", destination.absolutePath),
                )
            } else {
                PanResult.Success(Unit)
            }
        } catch (cancel: CancellationException) {
            // 暂停要留着残片：它就是下次续传的凭据。取消才是真的不要了。
            if (!keepsPartialOnPause(cancel)) temp.delete()
            throw cancel
        } catch (stale: RangeNotSatisfiableException) {
            // 服务端说这个区间不存在，说明 .part 已经不能当续传依据，丢掉重来。
            temp.delete()
            PanResult.Failure(PanError.network("下载链接已失效，正在重新下载", stale))
        } catch (io: IOException) {
            // 保留 .part：这正是断点续传的凭据。
            PanResult.Failure(PanError.network(io.message ?: "下载中断", io))
        } catch (error: Throwable) {
            temp.delete()
            PanResult.Failure(PanError.unknown(error.message ?: "下载失败", cause = error))
        }
    }

    private suspend fun downloadToFile(
        target: DownloadTarget,
        file: File,
        listener: ProgressListener,
    ) {
        val offset = resumeOffset(target, file)

        val request = Request.Builder()
            .url(target.url)
            .apply { target.headers.forEach { (k, v) -> header(k, v) } }
            .apply { if (offset > 0L) header("Range", "bytes=" + offset + "-") }
            .build()

        val result = engine.executeRaw(request)
        if (result is PanResult.Failure) throw IOException(result.error.message)

        val response = (result as PanResult.Success).value
        response.use { resp ->
            if (resp.code == HTTP_RANGE_NOT_SATISFIABLE) throw RangeNotSatisfiableException()
            if (!resp.isSuccessful) {
                throw IOException("download failed: HTTP " + resp.code)
            }
            // 只有 206 才是真的续传；服务端忽略 Range 回 200 时必须从头写，
            // 否则会把整个文件追加到旧的半截文件后面。
            val resuming = offset > 0L && resp.code == HTTP_PARTIAL_CONTENT
            val start = if (resuming) offset else 0L

            val body = resp.body
            val contentLength = body.contentLength().takeIf { it > 0L }
            val total = when {
                contentLength == null -> target.size
                resuming -> start + contentLength
                else -> contentLength
            }
            body.byteStream().use { input ->
                FileOutputStream(file, resuming).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = start
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        listener.onProgress(written, total)
                    }
                    output.flush()
                }
            }
        }
    }

    /**
     * 该从第几个字节开始下。
     *
     * 两种情况不能续传，直接从头来：
     * - 没有 .part；
     * - .part 已经不小于服务端报的大小——上次其实已经写完（只差 rename），
     *   或者这是个同名旧残片，再续下去只会写出更长的坏文件。
     * 服务端没报大小时只能信任 .part，续错了由 416 分支兜底重来。
     */
    private fun resumeOffset(target: DownloadTarget, file: File): Long {
        if (!file.isFile) return 0L
        val existing = file.length()
        val expected = target.size
        val usable = existing > 0L && (expected == null || existing < expected)
        if (!usable) {
            file.delete()
            return 0L
        }
        return existing
    }

    private class RangeNotSatisfiableException : IOException("range not satisfiable")

    /**
     * 这次取消要不要保留 .part。
     *
     * 单独一个函数而不是直接写一句 is 判断：这是整个暂停功能里
     * 唯一会毁掉用户已下载数据的地方，值得被单独测到。
     */
    internal fun keepsPartialOnPause(cause: CancellationException): Boolean =
        cause is TransferPause

    companion object {
        const val PART_SUFFIX = ".part"
        private const val BUFFER_SIZE = 64 * 1024
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}
