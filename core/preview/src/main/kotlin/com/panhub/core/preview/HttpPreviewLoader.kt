package com.panhub.core.preview

import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.network.HttpEngine
import com.panhub.provider.api.DownloadTarget
import com.panhub.provider.api.ScopedPanProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 真的去网上取预览内容。
 *
 * 三个刻意的选择：
 * 1. 用 Range 只取前一段。预览从不需要整个文件，服务端支持 Range 时能省掉
 *    几十 MB 的流量；不支持时也只是多读了 [PreviewLimits] 那么多，不会被拖死。
 * 2. 读到上限就停，不等服务端把 body 关掉。大文件读到一半断开是正常的，
 *    已经拿到的字节仍然可用，不该被当成失败。
 * 3. 直链失效不重试。预览是一次性的主动动作，失败时用户点一下重来就好，
 *    在这里加重试只会让对话框多转几秒。
 */
class HttpPreviewLoader(
    private val engine: HttpEngine = HttpEngine(),
    private val sessionProvider: suspend (AccountId) -> PanResult<ScopedPanProvider>,
) : PreviewLoader {

    override suspend fun load(
        accountId: AccountId,
        nodeId: NodeId,
        kind: PreviewKind,
        knownSize: Long?,
    ): PanResult<PreviewContent> {
        if (kind == PreviewKind.NONE) {
            return PanResult.Failure(PanError.notSupported("这个文件不支持预览"))
        }
        val scoped = when (val connected = sessionProvider(accountId)) {
            is PanResult.Failure -> return connected
            is PanResult.Success -> connected.value
        }
        val target = when (val url = scoped.downloadUrl(nodeId)) {
            is PanResult.Failure -> return url
            is PanResult.Success -> url.value
        }
        val limit = PreviewLimits.forKind(kind)
        val bytes = readPrefix(target, limit)
            ?: return PanResult.Failure(PanError.network("读取文件内容失败，请稍后再试"))
        // 读满了上限，或者一开始就知道文件比读到的更大，都算截断。
        val truncated = bytes.size.toLong() >= limit || (knownSize != null && knownSize > bytes.size)
        return PreviewAssembler.assemble(kind, bytes, truncated)
    }

    /** 读最多 [limit] 个字节；一个都读不到时返回 null，用来区分「空文件」和「读失败」。 */
    private suspend fun readPrefix(target: DownloadTarget, limit: Long): ByteArray? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(target.url)
                .header("Range", "bytes=0-" + (limit - 1))
                .apply { target.headers.forEach { (name, value) -> header(name, value) } }
                .build()
            // 用 executeRaw：非 2xx 也要先拿到响应才能判，直链失效时服务端
            // 常常回一个 4xx 而不是干脆不给响应。
            val response = when (val result = engine.executeRaw(request)) {
                is PanResult.Failure -> return@withContext null
                is PanResult.Success -> result.value
            }
            response.use { resp ->
                if (resp.code >= 400) return@withContext null
                val body = resp.body ?: return@withContext null
                val out = ByteArrayOutputStream()
                try {
                    body.byteStream().use { stream -> copyLimited(stream, out, limit) }
                } catch (io: IOException) {
                    // 读到一半连接被关掉是预期内的：已经拿到的字节照样能看。
                    if (out.size() == 0) return@withContext null
                }
                out.toByteArray()
            }
        }

    private fun copyLimited(
        stream: java.io.InputStream,
        out: ByteArrayOutputStream,
        limit: Long,
    ) {
        val buffer = ByteArray(READ_CHUNK)
        var remaining = limit
        while (remaining > 0) {
            val want = minOf(remaining, buffer.size.toLong()).toInt()
            val read = stream.read(buffer, 0, want)
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private companion object {
        const val READ_CHUNK = 64 * 1024
    }
}
