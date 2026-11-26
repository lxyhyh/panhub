package com.panhub.provider.cloud189

import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.network.HttpEngine
import com.panhub.core.network.HttpText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
    /** 解析器与三条通道共用，避免判定规则各写一份。 */
    val json: Json = Cloud189Decoding.json

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
        when (val result = engine.fetchText(request)) {
            is PanResult.Failure -> result
            is PanResult.Success -> readBody(result.value)
        }

    private fun readBody(text: HttpText): PanResult<String> {
        val raw = text.body
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
        if (text.code !in 200..299) {
            return PanResult.Failure(Cloud189Decoding.httpError(text.code))
        }
        return PanResult.Success(raw)
    }

    /** 解出信封并统一处理 res_code，规则见 [Cloud189Decoding.decode]。 */
    fun decode(raw: String): PanResult<C189Envelope> = Cloud189Decoding.decode(raw)

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

        /** 上传通道用的一次性 RSA 公钥，pubKey 拿来加密随机密钥。 */
        const val PATH_RSA_KEY = "/api/security/generateRsaKey.action"

        /** 官方客户端与 OpenList 都用 60 一页。 */
        const val PAGE_SIZE = 60

        private val FORM_MEDIA = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
        private val HEX = "0123456789ABCDEF".toCharArray()

        /** 从 JsonElement 里安全地取字符串，数字也转成字符串。 */
        fun textOf(element: JsonElement?): String? = Cloud189Decoding.textOf(element)

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
