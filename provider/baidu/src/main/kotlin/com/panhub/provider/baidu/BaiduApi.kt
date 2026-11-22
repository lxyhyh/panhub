package com.panhub.provider.baidu

import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.network.HttpEngine
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 百度网盘网页接口的 HTTP 层。
 *
 * 为什么用网页接口（pan.baidu.com/api + /rest/2.0/xpan）而不是官方开放平台：
 * 开放平台要求申请 client_id/client_secret 并审核，个人项目拿不到；
 * 网页接口用 Cookie（BDUSS + STOKEN）就能用，且与网页版行为一致。
 *
 * 没有统一信封：几乎所有响应都是 errno + 业务字段，errno == 0 才算成功。
 * 所以这一层只负责发请求、抽 errno、把常见的错误码翻译成 PanError，
 * 具体反序列化留给上层。
 */
internal class BaiduApi(
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
        extraHeaders: Map<String, String> = emptyMap(),
    ): PanResult<String> {
        val builder = Request.Builder()
            .url(url(path, query) ?: return invalidUrl(path))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", REFERER)
            .header("Cookie", cookie)
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        return send(builder.get().build())
    }

    suspend fun postForm(
        path: String,
        cookie: String,
        form: List<Pair<String, String>>,
        query: List<Pair<String, String>> = emptyList(),
    ): PanResult<String> {
        val body = form.joinToString("&") { (k, v) -> encode(k) + "=" + encode(v) }
            .toRequestBody(FORM_MEDIA)
        val request = Request.Builder()
            .url(url(path, query) ?: return invalidUrl(path))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", REFERER)
            .header("Cookie", cookie)
            .post(body)
            .build()
        return send(request)
    }

    private fun url(path: String, query: List<Pair<String, String>>) = try {
        ("$baseUrl$path").toHttpUrl().newBuilder().apply {
            // 这几个是网页版固定带的参数，缺了部分接口会判非法请求。
            addQueryParameter("channel", "chunlei")
            addQueryParameter("app_id", APP_ID)
            addQueryParameter("web", "1")
            addQueryParameter("clienttype", "0")
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun invalidUrl(path: String): PanResult<String> =
        PanResult.Failure(PanError.unknown("请求地址不合法", path))

    private suspend fun send(request: Request): PanResult<String> =
        when (val result = engine.executeRaw(request)) {
            is PanResult.Failure -> result
            is PanResult.Success -> result.value.use { response ->
                val text = try {
                    response.body.string()
                } catch (io: IOException) {
                    return@use PanResult.Failure(
                        PanError.network(io.message ?: "读取响应失败", io),
                    )
                }
                if (text.isBlank()) return@use PanResult.Failure(PanError.unknown("服务端返回空响应"))
                checkErrno(text)
            }
        }

    /** 统一的 errno 判定。errno 缺失按成功处理（有些接口直接返回业务体）。 */
    fun checkErrno(raw: String): PanResult<String> {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            ?: return PanResult.Failure(PanError.unknown("响应解析失败", raw.take(300)))
        // isLenient 会把 "<html>" 也当成字符串原语解析出来，所以这里必须排除非对象，
        // 否则 HTML 错误页会被当成成功响应往下传。
        val obj = root as? kotlinx.serialization.json.JsonObject
            ?: return PanResult.Failure(PanError.unknown("响应不是 JSON 对象", raw.take(300)))
        val errno = (obj["errno"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toIntOrNull() ?: 0
        if (errno == 0) return PanResult.Success(raw)

        val message = (obj["show_msg"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: (obj["errmsg"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        return PanResult.Failure(errorOf(errno, message))
    }

    private fun errorOf(errno: Int, message: String?): PanError = when (errno) {
        // -6 = 未登录 / BDUSS 失效，111 = 登录过期，20016/20017 = token 无效
        -6, 111, 20016, 20017 -> PanError.auth(message ?: "登录态已失效，请重新粘贴 Cookie")
        -7 -> PanError.auth("文件名非法或已存在同名文件")
        -8 -> PanError.notFound("文件或目录不存在")
        -9 -> PanError.notFound("文件或目录不存在")
        -10 -> PanError.unknown("云盘容量不足")
        -19 -> PanError.auth("该账号已被限制访问")
        2 -> PanError.unknown(message ?: "操作被拒绝，可能需要完成手机号绑定或实名")
        12 -> PanError.notFound("目录不存在")
        31023 -> PanError.auth("分享或下载需要更高权限")
        31066 -> PanError.notSupported("该文件不支持此操作")
        else -> PanError.unknown(message ?: "请求失败（errno $errno）")
    }

    companion object {
        const val BASE_URL = "https://pan.baidu.com"

        const val APP_ID = "250528"

        const val REFERER = "https://pan.baidu.com/disk/home"

        /** 网页版 UA。 */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** 下载链接要求带这个 UA 才放行。 */
        const val DOWNLOAD_UA = "pan.baidu.com"

        /** 根目录路径是 "/"，不是 id。百度网盘是路径寻址的。 */
        const val ROOT_PATH = "/"

        const val PATH_LIST = "/api/list"
        const val PATH_QUOTA = "/api/quota"
        const val PATH_FILEMETAS = "/api/filemetas"
        const val PATH_CREATE = "/api/create"
        const val PATH_MANAGE = "/rest/2.0/xpan/file"
        const val PATH_XPAN_FILEMETAS = "/rest/2.0/xpan/multimedia"
        const val PATH_TEMPLATE = "/api/gettemplatevariable"
        const val PATH_UINFO = "/rest/2.0/xpan/nas"
        const val PATH_SHARE_SET = "/share/set"
        const val PATH_SHARE_RECORD = "/share/record"
        const val PATH_SHARE_CANCEL = "/share/cancel"

        internal fun encode(value: String): String {
            val out = StringBuilder(value.length * 3)
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xFF
                val ch = c.toChar()
                val safe = (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') ||
                    ch == '-' || ch == '_' || ch == '.' || ch == '~'
                if (safe) out.append(ch) else out.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
            }
            return out.toString()
        }

        private const val HEX = "0123456789ABCDEF"

        private val FORM_MEDIA = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
    }
}
