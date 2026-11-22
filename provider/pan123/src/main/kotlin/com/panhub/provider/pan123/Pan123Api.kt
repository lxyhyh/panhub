package com.panhub.provider.pan123

import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.network.HttpEngine
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * 123 云盘的 HTTP 层。
 *
 * 这一层只负责「发请求 + 解包 + 把 code!=0 变成 PanError」，
 * 不含任何业务判断，方便单测和替换。
 */
class Pan123Api(
    private val engine: HttpEngine,
    private val baseUrl: String = BASE_URL,
) {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    suspend fun <T> get(
        path: String,
        token: String?,
        decode: (String) -> ApiEnvelope<T>,
    ): PanResult<T> = request("GET", path, token, null, decode)

    suspend fun <T> post(
        path: String,
        token: String?,
        body: String? = null,
        decode: (String) -> ApiEnvelope<T>,
    ): PanResult<T> = request("POST", path, token, body, decode)

    private suspend fun <T> request(
        method: String,
        path: String,
        token: String?,
        body: String?,
        decode: (String) -> ApiEnvelope<T>,
    ): PanResult<T> {
        val builder = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("User-Agent", HttpEngine.USER_AGENT)
            .header("Accept", "application/json")
            .header("Platform", "android")
            .header("App-Version", "3.3.4")
            .header("App-Version-Code", "3244")

        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
            builder.header("Cookie", "authorToken=$token")
        }

        when (method) {
            "GET" -> builder.get()
            else -> builder.method(method, (body ?: "{}").toRequestBody(JSON_MEDIA))
        }

        return when (val result = engine.executeRaw(builder.build())) {
            is PanResult.Failure -> PanResult.Failure(result.error)
            is PanResult.Success -> result.value.use { response -> decodeResponse(response, decode) }
        }
    }

    private fun <T> decodeResponse(
        response: Response,
        decode: (String) -> ApiEnvelope<T>,
    ): PanResult<T> {
        val raw = try {
            response.body?.string().orEmpty()
        } catch (io: IOException) {
            return PanResult.Failure(PanError.network(io.message ?: "读取响应失败", io))
        }

        if (raw.isBlank()) {
            return PanResult.Failure(PanError.unknown("服务端返回空响应"))
        }

        val envelope = runCatching { decode(raw) }.getOrElse { e ->
            return PanResult.Failure(PanError.unknown("响应解析失败", raw.take(300), e))
        }

        if (!envelope.isOk) return PanResult.Failure(envelope.toError())

        @Suppress("UNCHECKED_CAST")
        return PanResult.Success((envelope.data ?: Unit) as T)
    }

    private fun ApiEnvelope<*>.toError(): PanError = when (code) {
        401, 403 -> PanError.auth(message ?: "登录已过期")
        404 -> PanError.notFound(message ?: "文件不存在")
        429 -> PanError.rateLimited(message ?: "操作过于频繁")
        else -> PanError(
            kind = if (code >= 500) ErrorKind.SERVER else ErrorKind.UNKNOWN,
            message = message ?: "请求失败（$code）",
        )
    }

    /**
     * 直接 PUT 到预签名 URL。
     *
     * 注意：**不能**带 Authorization 头。S3 的预签名 URL 把签名写在 query 里，
     * 多加一个鉴权头会因为签名不匹配被拒。
     */
    suspend fun putUrl(url: String, body: RequestBody): PanResult<Unit> {
        val request = Request.Builder().url(url).put(body).build()
        return when (val result = engine.execute(request)) {
            is PanResult.Failure -> result
            is PanResult.Success -> result.value.use { PanResult.Success(Unit) }
        }
    }

    fun encodeLogin(request: LoginRequest): String = json.encodeToString(request)

    companion object {
        const val BASE_URL = "https://api.123278.com"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        // 路径常量：全部来自 APK 内 interface_config.json，已确认。
        const val PATH_SIGN_IN = "/api/user/sign_in"
        const val PATH_USER_INFO = "/api/user/info"
        const val PATH_FILE_LIST = "/api/file/list/new"
        const val PATH_DOWNLOAD_INFO = "/api/file/download_info"
        const val PATH_UPLOAD_REQUEST = "/api/file/upload_request"
        const val PATH_UPLOAD_COMPLETE = "/api/file/upload_complete"
        const val PATH_UPLOAD_COMPLETE_V2 = "/api/file/upload_complete/v2"
        const val PATH_S3_AUTH = "/api/file/s3_upload_object/auth"
        const val PATH_S3_PARTS = "/api/file/s3_repare_upload_parts_batch"
        const val PATH_RENAME = "/api/file/rename"
        const val PATH_MOD_PID = "/api/file/mod_pid"
        const val PATH_DELETE = "/api/file/delete"
        const val PATH_TRASH = "/api/file/trash"
        const val PATH_SHARE_CREATE = "/api/share/create"
        const val PATH_SHARE_LIST = "/api/share/list"
        const val PATH_SHARE_DELETE = "/api/share/delete"
    }
}