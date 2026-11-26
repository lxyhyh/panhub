package com.panhub.provider.webdav

import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.network.HttpEngine
import com.panhub.core.network.HttpText
import com.panhub.core.network.StreamRequestBody
import com.panhub.provider.api.UploadSource
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * 一个最小但完整的 WebDAV 客户端。
 *
 * 只实现 App 真正需要的 7 个方法（PROPFIND/GET/PUT/MKCOL/MOVE/COPY/DELETE），
 * 不做 LOCK/UNLOCK（坚果云不需要协作锁）。
 */
class WebDavClient(
    private val engine: HttpEngine,
    private val baseUrl: String,
    private val username: String,
    private val password: String,
) {

    private val authHeader: String = Credentials.basic(username, password, Charsets.UTF_8)

    private fun request(method: String, path: String, body: String? = null): Request.Builder {
        val url = resolve(path)
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .header("User-Agent", HttpEngine.USER_AGENT)
            .header("Accept", "*/*")
        return when (method) {
            "PROPFIND" -> builder
                .method(method, propfindBody(body).toRequestBody(XML))
                .header("Depth", "1")
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "MKCOL" -> builder.method(method, EMPTY_BODY)
            "MOVE", "COPY" -> builder.method(
                method,
                EMPTY_BODY,
            ).apply { if (body != null) header("Destination", resolve(body)) }
            "PUT" -> builder.put(
                (body ?: "").toRequestBody(BYTES)
            )
            else -> builder.method(method, body?.toRequestBody(XML) ?: EMPTY_BODY)
        }
    }

    /** PROPFIND，返回原始 XML 文本。 */
    suspend fun propfind(path: String, depth: String = "1"): PanResult<String> {
        val req = request("PROPFIND", path)
            .header("Depth", depth)
            .build()
        return engine.fetchText(req).mapResponse { it.body }
    }

    suspend fun mkcol(path: String): PanResult<Unit> {
        val req = request("MKCOL", path).build()
        return engine.fetchText(req).mapResponse { }
    }

    suspend fun delete(path: String): PanResult<Unit> {
        val req = request("DELETE", path).build()
        return engine.fetchText(req).mapResponse { }
    }

    suspend fun move(from: String, to: String): PanResult<Unit> {
        val req = request("MOVE", from, to).build()
        return engine.fetchText(req).mapResponse { }
    }

    /**
     * COPY。与 MOVE 的区别只在动词：同样是 Destination 头指向新路径。
     *
     * 两个头都必须显式写：
     * - Depth: infinity —— 省略时按 RFC 4918 默认就是 infinity，但目录复制
     *   会看这个头，写出来省得依赖默认值。
     * - Overwrite: F —— **这个默认是 T**，也就是说目标已存在时服务端会
     *   直接覆盖。复制一个同名文件到已经放着同名文件的目录里，会静默毁掉
     *   原来那份。宁可让服务端回 412 让用户自己改名。
     */
    suspend fun copy(from: String, to: String): PanResult<Unit> {
        val req = request("COPY", from, to)
            .header("Depth", "infinity")
            .header("Overwrite", "F")
            .build()
        return engine.fetchText(req).mapResponse { }
    }

    suspend fun put(path: String, content: ByteArray): PanResult<Unit> {
        val req = Request.Builder()
            .url(resolve(path))
            .header("Authorization", authHeader)
            .put(content.toRequestBody(BYTES))
            .build()
        return engine.fetchText(req).mapResponse { }
    }

    /**
     * 流式上传。
     *
     * [source] 每次 openStream() 都要能从头读，OkHttp 在认证重试时可能写两遍。
     * [onBytes] 在写线程里被调用，调用方自己负责线程安全。
     */
    suspend fun putStream(
        path: String,
        source: UploadSource,
        onBytes: ((Long) -> Unit)? = null,
    ): PanResult<Unit> {
        val body = StreamRequestBody(
            openStream = source::openStream,
            length = source.size,
            contentType = source.mimeType?.let { it.toMediaType() } ?: BYTES,
            onBytes = onBytes,
        )
        val req = Request.Builder()
            .url(resolve(path))
            .header("Authorization", authHeader)
            .header("User-Agent", HttpEngine.USER_AGENT)
            .put(body)
            .build()
        return engine.fetchTextOk(req).map { }
    }

    /** 获取下载用的请求（调用方自己流式消费）。 */
    fun downloadRequest(path: String): Request = Request.Builder()
        .url(resolve(path))
        .header("Authorization", authHeader)
        .header("User-Agent", HttpEngine.USER_AGENT)
        .get()
        .build()

    private fun resolve(path: String): String {
        val base = baseUrl.trimEnd('/')
        if (path.isBlank()) return "$base/"
        val encoded = path.split('/').filter { it.isNotEmpty() }
            .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "$base/$encoded"
    }

    private fun propfindBody(depth: String?): String = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:displayname/>
    <D:getcontentlength/>
    <D:getcontenttype/>
    <D:getlastmodified/>
    <D:creationdate/>
    <D:resourcetype/>
    <D:quota-available-bytes/>
    <D:quota-used-bytes/>
  </D:prop>
</D:propfind>"""

    private companion object {
        val XML = "application/xml; charset=utf-8".toMediaType()
        val BYTES = "application/octet-stream".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(null, 0, 0)
    }
}

/**
 * 把 HTTP 响应转成结果，并且**检查状态码**。
 *
 * 之前这里不判状态码，于是 COPY/DELETE/MKCOL 失败时也会返回成功——
 * WebDAV 的错误只有状态码这一个信号，不看它就等于假装操作成功了。
 *
 * 响应体已经在 HttpEngine.fetchText 的 IO 线程上读完并关掉，这里只做判定。
 */
private fun <T> PanResult<HttpText>.mapResponse(transform: (HttpText) -> T): PanResult<T> =
    when (this) {
        is PanResult.Failure -> this
        is PanResult.Success -> {
            val text = value
            if (text.code in 200..299) {
                PanResult.Success(transform(text))
            } else {
                PanResult.Failure(webdavError(text.code, text.message))
            }
        }
    }

private fun webdavError(code: Int, message: String): PanError = when (code) {
    401, 403 -> PanError.auth("坚果云拒绝了这次操作（$code），请检查应用密码")
    404 -> PanError.notFound()
    409 -> PanError.unknown("目标目录不存在（409）")
    412 -> PanError.unknown("目标目录里已经有同名的文件")
    423 -> PanError.unknown("文件被锁定（423）")
    429 -> PanError.rateLimited()
    in 500..599 -> PanError(kind = ErrorKind.SERVER, message = "服务器错误（" + code + "）", retryable = true)
    else -> PanError.unknown("请求失败（" + code + " " + message + "）")
}
