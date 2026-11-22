package com.panhub.provider.webdav

import com.panhub.core.common.PanResult
import com.panhub.core.network.HttpEngine
import com.panhub.provider.api.UploadSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URLEncoder

/**
 * 一个最小但完整的 WebDAV 客户端。
 *
 * 只实现 App 真正需要的 6 个方法（PROPFIND/GET/PUT/MKCOL/MOVE/DELETE），
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
            "MOVE" -> builder.method(
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
        return engine.executeRaw(req).mapResponse { it.body?.string().orEmpty() }
    }

    suspend fun mkcol(path: String): PanResult<Unit> {
        val req = request("MKCOL", path).build()
        return engine.executeRaw(req).mapResponse { }
    }

    suspend fun delete(path: String): PanResult<Unit> {
        val req = request("DELETE", path).build()
        return engine.executeRaw(req).mapResponse { }
    }

    suspend fun move(from: String, to: String): PanResult<Unit> {
        val req = request("MOVE", from, to).build()
        return engine.executeRaw(req).mapResponse { }
    }

    suspend fun put(path: String, content: ByteArray): PanResult<Unit> {
        val req = Request.Builder()
            .url(resolve(path))
            .header("Authorization", authHeader)
            .put(content.toRequestBody(BYTES))
            .build()
        return engine.executeRaw(req).mapResponse { }
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
        return when (val result = engine.execute(req)) {
            is PanResult.Failure -> result
            is PanResult.Success -> result.value.use { PanResult.Success(Unit) }
        }
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

private inline fun <T> PanResult<Response>.mapResponse(transform: (Response) -> T): PanResult<T> =
    when (this) {
        is PanResult.Failure -> this
        is PanResult.Success -> {
            value.use { PanResult.Success(transform(it)) }
        }
    }
