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
import java.io.IOException

/**
 * 用 OkHttp 把 [DownloadTarget] 写到本地。
 *
 * 两条硬性约束在实现里体现：
 * 1. 先写 .part 临时文件，成功后才 rename——否则进度条走到 99% 断网，
 *    用户会在目录里看到一个看似完整但已损坏的文件；
 * 2. 每个 chunk 都检查协程是否已取消，取消时立刻删掉 .part，
 *    不然取消只是"停了"，磁盘上留一堆半截文件。
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
            temp.delete()
            throw cancel
        } catch (io: IOException) {
            temp.delete()
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
        val request = Request.Builder()
            .url(target.url)
            .apply { target.headers.forEach { (k, v) -> header(k, v) } }
            .build()

        val result = engine.executeRaw(request)
        if (result is PanResult.Failure) throw IOException(result.error.message)

        val response = (result as PanResult.Success).value
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("download failed: HTTP " + resp.code)
            }
            val body = resp.body
            val total = body.contentLength().takeIf { it > 0L }
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = 0L
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

    companion object {
        const val PART_SUFFIX = ".part"
        private const val BUFFER_SIZE = 64 * 1024
    }
}
