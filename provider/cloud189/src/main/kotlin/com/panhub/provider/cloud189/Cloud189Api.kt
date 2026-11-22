package com.panhub.provider.cloud189

import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.network.HttpEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * 天翼云盘的 HTTP 层。
 *
 * 鉴权靠网页会话 Cookie：天翼云盘的 open 接口没有可直传的 access token，
 * 必须带上 cloud.189.cn 域的登录 Cookie 才会返回业务数据。
 *
 * 这一层只负责「发请求 + 解包 + 把 res_code/errorCode 变成 PanError」，
 * 不含任何业务判断。
 */
internal class Cloud189Api(
    private val engine: HttpEngine,
    private val baseUrl: String = BASE_URL,
) {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    suspend fun get(
        path: String,
        cookie: String?,
        query: List<Pair<String, String>> = emptyList(),
    ): PanResult<String> {
        val url = buildUrl(path, query)
            ?: return PanResult.Failure(PanError.unknown("请求地址不合法", path))
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", HttpEngine.USER_AGENT)
            .header("Accept", "application/json;charset=UTF-8")
            .header("Referer", "$baseUrl/")
            .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
            .get()
            .build()
        return send(request)
    }

    suspend fun postForm(
        path: String,
        cookie: String?,
        fields: List<Pair<String, String>>,
        query: List<Pair<String, String>> = emptyList(),
    ): PanResult<String> {
        val url = buildUrl(path, query)
            ?: return PanResult.Failure(PanError.unknown("请求地址不合法", path))
        val body = fields.joinToString("&") { (k, v) -> encode(k) + "=" + encode(v) }
            .toRequestBody(FORM_MEDIA)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", HttpEngine.USER_AGENT)
            .header("Accept", "application/json;charset=UTF-8")
            .header("Referer", "$baseUrl/")
            .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
            .post(body)
            .build()
        return send(request)
    }

    private fun buildUrl(path: String, query: List<Pair<String, String>>) = try {
        ("$baseUrl$path").toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
    } catch (e: IllegalArgumentException) {
        null
    }

    private suspend fun send(request: Request): PanResult<String> =
        when (val result = engine.executeRaw(request)) {
            is PanResult.Failure -> result
            is PanResult.Success -> result.value.use { response -> readBody(response) }
        }

    private fun readBody(response: Response): PanResult<String> {
        val raw = try {
            response.body.string()
        } catch (io: IOException) {
            return PanResult.Failure(PanError.network(io.message ?: "读取响应失败", io))
        }
        if (raw.isBlank()) {
            return PanResult.Failure(PanError.unknown("服务端返回空响应"))
        }

        // 天翼云盘鉴权失败时也常返回 400 + JSON，所以先看 body 再看状态码。
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        val errorCode = textOf((element as? JsonObject)?.get("errorCode"))
        if (!errorCode.isNullOrBlank()) {
            return PanResult.Failure(
                if (errorCode == "InvalidSessionKey") {
                    PanError.auth()
                } else {
                    PanError.unknown("天翼云盘拒绝了请求", errorCode)
                },
            )
        }
        if (!response.isSuccessful) {
            return PanResult.Failure(httpError(response.code))
        }
        return PanResult.Success(raw)
    }

    /**
     * 解出信封并统一处理 res_code：0 或缺失视为成功，
     * 其余（数字或字符串）都当失败，InvalidSessionKey 归类成登录过期。
     */
    fun decode(raw: String): PanResult<C189Envelope> {
        val envelope = runCatching { json.decodeFromString(C189Envelope.serializer(), raw) }
            .getOrElse { e ->
                return PanResult.Failure(PanError.unknown("响应解析失败", raw.take(300), e))
            }

        val resCode = textOf(envelope.resCode)
        if (!resCode.isNullOrBlank() && resCode != "0") {
            return PanResult.Failure(
                if (resCode == "InvalidSessionKey") {
                    PanError.auth()
                } else {
                    PanError.unknown(envelope.resMessage ?: "天翼云盘返回错误（$resCode）", resCode)
                },
            )
        }
        return PanResult.Success(envelope)
    }

    private fun httpError(code: Int): PanError = when (code) {
        401, 403 -> PanError.auth()
        404 -> PanError.notFound()
        429 -> PanError.rateLimited()
        in 500..599 -> PanError(ErrorKind.SERVER, "服务器错误（$code）", retryable = true)
        else -> PanError.unknown("请求失败（$code）")
    }

    companion object {
        const val BASE_URL = "https://cloud.189.cn"

        /** 个人云根目录，官方客户端与 OpenList 都用 -11。 */
        const val ROOT_FOLDER_ID = "-11"

        const val PATH_FILE_LIST = "/api/open/file/listFiles.action"
        const val PATH_CREATE_FOLDER = "/api/open/file/createFolder.action"
        const val PATH_RENAME_FILE = "/api/open/file/renameFile.action"
        const val PATH_RENAME_FOLDER = "/api/open/file/renameFolder.action"
        const val PATH_BATCH_TASK = "/api/open/batch/createBatchTask.action"
        const val PATH_FILE_INFO = "/api/portal/getFileInfo.action"
        const val PATH_USER_SIZE = "/api/portal/getUserSizeInfo.action"
        const val PATH_USER_BRIEF = "/v2/getUserBriefInfo.action"

        /** 官方客户端与 OpenList 都用 60 一页。 */
        const val PAGE_SIZE = 60

        private val FORM_MEDIA = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
        private val HEX = "0123456789ABCDEF".toCharArray()

        /** 从 JsonElement 里安全地取字符串，数字也转成字符串。 */
        fun textOf(element: JsonElement?): String? {
            val primitive = element as? JsonPrimitive ?: return null
            return primitive.content
        }

        /** 极简 URL 编码，只作用于参数值，避免依赖 FormBody 造成空 body。 */
        fun encode(value: String): String = buildString {
            value.forEach { c ->
                when {
                    // 必须只放行 ASCII：Character.isLetterOrDigit('中') 是 true，
                    // 直接用会把中文原样写进 form body，服务端会拒。
                    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-._~" -> append(c)
                    c == ' ' -> append("%20")
                    else -> c.toString().toByteArray(Charsets.UTF_8).forEach { b ->
                        append('%')
                        append(HEX[(b.toInt() shr 4) and 0x0F])
                        append(HEX[b.toInt() and 0x0F])
                    }
                }
            }
        }
    }
}
