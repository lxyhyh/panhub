package com.panhub.provider.quark

import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.network.HttpEngine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 夸克网盘的 HTTP 层。
 *
 * 端点与参数来自 OpenList drivers/quark_uc（夸克与 UC 是同一套云盘的两个品牌，
 * 只是 host / UA / pr 不同，本项目只接夸克）。
 *
 * 这一层**不做泛型反序列化**：运行时拿不到类型参数，硬塞只会写出自相矛盾的代码。
 * 它只负责发请求、统一判成功、抽取被轮换的 Cookie，然后把原始 JSON 交给上层用
 * 具体序列化器解析。成功判据只在这里出现一次，避免各调用点各写一份。
 */
internal class QuarkApi(
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
        cookie: String,
        query: List<Pair<String, String>> = emptyList(),
    ): PanResult<QuarkRaw> = send(path, cookie, query, body = null)

    suspend fun postJson(
        path: String,
        cookie: String,
        body: String,
        query: List<Pair<String, String>> = emptyList(),
    ): PanResult<QuarkRaw> = send(path, cookie, query, body = body)

    private suspend fun send(
        path: String,
        cookie: String,
        query: List<Pair<String, String>>,
        body: String?,
    ): PanResult<QuarkRaw> {
        val url: HttpUrl = buildUrl(path, query)
            ?: return PanResult.Failure(PanError.unknown("请求地址不合法", path))

        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", REFERER)
            .header("Cookie", cookie)
        if (body == null) {
            builder.get()
        } else {
            builder.post(body.toRequestBody(JSON_MEDIA))
        }

        return when (val result = engine.executeRaw(builder.build())) {
            is PanResult.Failure -> result
            is PanResult.Success -> result.value.use { response ->
                val text = try {
                    response.body.string()
                } catch (io: IOException) {
                    return@use PanResult.Failure(
                        PanError.network(io.message ?: "读取响应失败", io),
                    )
                }
                if (text.isBlank()) {
                    return@use PanResult.Failure(PanError.unknown("服务端返回空响应"))
                }
                check(text, renewedCookies(response.headers("Set-Cookie")))
            }
        }
    }

    /**
     * 往一个**绝对**地址发请求（预签名 URL 用），并把状态码与响应头原样带回来。
     *
     * 不能走 [send]：那条路假设响应是夸克的 JSON 信封，而 OSS 的 PUT/POST 返回的是
     * 空的 200 加一个 Etag 头，套上信封解析只会报「响应解析失败」。
     */
    suspend fun executeAbs(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: RequestBody? = null,
    ): PanResult<QuarkRawResponse> {
        val parsed: HttpUrl = try {
            url.toHttpUrl()
        } catch (e: IllegalArgumentException) {
            return PanResult.Failure(PanError.unknown("上传地址不合法", url, e))
        }
        val builder = Request.Builder().url(parsed)
        headers.forEach { (k, v) -> builder.header(k, v) }
        builder.method(method, body)

        return when (val result = engine.executeRaw(builder.build())) {
            is PanResult.Failure -> result
            is PanResult.Success -> result.value.use { response ->
                val text = try {
                    response.body.string()
                } catch (io: IOException) {
                    return@use PanResult.Failure(
                        PanError.network(io.message ?: "读取响应失败", io),
                    )
                }
                PanResult.Success(
                    QuarkRawResponse(
                        code = response.code,
                        text = text,
                        etag = response.header("Etag") ?: response.header("ETag"),
                    ),
                )
            }
        }
    }

    private fun buildUrl(path: String, query: List<Pair<String, String>>): HttpUrl? = try {
        ("$baseUrl$path").toHttpUrl().newBuilder().apply {
            // pr 与 fr 是所有端点都带的 PC 客户端标识，缺了会被风控拦截。
            addQueryParameter("pr", PR)
            addQueryParameter("fr", "pc")
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun renewedCookies(headers: List<String>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        headers.forEach { header ->
            val pair = header.substringBefore(';')
            val name = pair.substringBefore('=', "").trim()
            val value = pair.substringAfter('=', "").trim()
            if ((name == "__puus" || name == "__pus") && value.isNotEmpty()) {
                map[name] = value
            }
        }
        return map
    }

    /** 统一的成功判定与错误映射；成功返回原始 JSON 与响应树。 */
    fun check(raw: String, renewed: Map<String, String> = emptyMap()): PanResult<QuarkRaw> {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            ?: return PanResult.Failure(PanError.unknown("响应解析失败", raw.take(300)))
        val obj = root as? JsonObject
            ?: return PanResult.Failure(PanError.unknown("响应不是 JSON 对象", raw.take(300)))

        val status = obj["status"]?.jsonPrimitive?.intOrNull ?: 0
        val code = obj["code"]?.jsonPrimitive?.intOrNull ?: 0
        val message = obj["message"].asText()

        if (code == 0 && status < 400) {
            return PanResult.Success(QuarkRaw(raw, root, renewed))
        }
        return PanResult.Failure(errorOf(status, code, message))
    }

    fun <T> decode(raw: QuarkRaw, serializer: KSerializer<T>): PanResult<T> =
        runCatching { json.decodeFromString(serializer, raw.raw) }.fold(
            onSuccess = { PanResult.Success(it) },
            onFailure = { e ->
                PanResult.Failure(PanError.unknown("响应解析失败", raw.raw.take(300), e))
            },
        )

    private fun errorOf(status: Int, code: Int, message: String?): PanError = when {
        status == 401 || status == 403 || code == 31001 || code == 31023 -> PanError.auth()
        status == 404 -> PanError.notFound()
        status == 429 -> PanError.rateLimited()
        status >= 500 -> PanError(ErrorKind.SERVER, "服务器错误（$status）", retryable = true)
        else -> PanError.unknown(message ?: "请求失败（$code）")
    }

    companion object {
        const val BASE_URL = "https://drive.quark.cn/1/clouddrive"

        const val REFERER = "https://pan.quark.cn"

        const val PR = "ucpro"

        /** 官方 PC 客户端 UA，服务端按它下发风控策略。 */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 " +
                "Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"

        /** 夸克的根目录是 "0"。 */
        const val ROOT_ID = "0"

        const val PATH_FILE_SORT = "/file/sort"
        const val PATH_FILE = "/file"
        const val PATH_FILE_RENAME = "/file/rename"
        const val PATH_FILE_MOVE = "/file/move"
        const val PATH_FILE_DELETE = "/file/delete"
        const val PATH_FILE_DOWNLOAD = "/file/download"
        const val PATH_MEMBER = "/member"
        const val PATH_CONFIG = "/config"
        const val PATH_UPLOAD_PRE = "/file/upload/pre"
        const val PATH_UPLOAD_HASH = "/file/update/hash"
        const val PATH_UPLOAD_AUTH = "/file/upload/auth"
        const val PATH_UPLOAD_FINISH = "/file/upload/finish"

        const val PATH_SHARE = "/share"
        const val PATH_SHARE_PASSWORD = "/share/password"
        const val PATH_SHARE_DELETE = "/share/delete"
        const val PATH_SHARE_MYPAGE = "/share/mypage/detail"
        const val PATH_TASK = "/task"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        internal fun JsonElement?.asText(): String? = when (this) {
            null, is JsonNull -> null
            is JsonPrimitive -> content
            else -> toString()
        }
    }
}

/** 直发预签名地址的产物：状态码、响应体、Etag 头（分片上传时必须记下来）。 */
internal data class QuarkRawResponse(
    val code: Int,
    val text: String,
    val etag: String? = null,
) {
    val isSuccessful: Boolean get() = code in 200..299
}

/** 一次成功请求的产物：原始 JSON、已解析的响应树、可能轮换过的 Cookie。 */
internal data class QuarkRaw(
    val raw: String,
    val root: JsonElement,
    val renewed: Map<String, String> = emptyMap(),
)
