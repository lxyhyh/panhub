package com.panhub.provider.cloud189

import com.panhub.core.common.Clock
import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.network.HttpEngine
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.random.Random

/**
 * 天翼云盘官方客户端通道（api.cloud.189.cn）的请求层。
 *
 * 这是「预留接口」：现在的 [Cloud189Provider] 走的是网页 open 接口
 * （cloud.189.cn/api/open/...）加 Cookie，能跑通但没有官方客户端那条通道稳。
 * 这条通道按官方客户端的做法签名——先用 accessToken 换出 sessionKey 与
 * sessionSecret，之后每个请求带 Date / SessionKey / X-Request-ID / Signature
 * 四个头，签名是 HMAC-SHA1(sessionSecret, 四段串)。
 *
 * 为什么现在只预留不切换：换会话与刷新 token 都需要真实的 accessToken 才能验证，
 * 手上没有可用账号，贸然切过去只会把能用的功能弄坏。所以这一层把
 * 「换会话 / 刷新 token / 签名请求 / 失效自动重试一次」四件事都实现好、
 * 用脚本化响应测住，等有账号时把 Provider 的调用点换过来即可。
 *
 * 端点与参数名在官方 APK 的 lib/arm64-v8a/libapp.so 里能逐条对上
 * （/file/listFiles.action、/createFolder.action、/renameFile.action 等）；
 * 签名算法与三个 token 端点来自 OpenList drivers/189pc，APK 里没有明文。
 */
internal class Cloud189AppChannel(
    private val engine: HttpEngine,
    private val baseUrl: String = API_URL,
    private val authUrl: String = AUTH_URL,
    private val clock: Clock = Clock.Default,
    private val random: Random = Random.Default,
) {
    /** 当前会话。换会话成功后才有值；签名请求必须先有它。 */
    @Volatile
    var session: Cloud189AppSession? = null
        private set

    /**
     * 用 accessToken 换会话。官方客户端在启动、失效、保活失败时都调它。
     *
     * 返回的 sessionKey / sessionSecret 是后续签名的全部依据，
     * accessToken 与 refreshToken 原样带回，方便调用方落盘。
     */
    suspend fun openSession(
        accessToken: String,
        refreshToken: String? = null,
    ): PanResult<Cloud189AppSession> {
        val query = Cloud189AppSign.clientSuffix(random) +
            listOf(
                "returnType" to "JSON",
                "appId" to Cloud189AppSign.APP_ID,
                "accessToken" to accessToken,
            )
        val request = Request.Builder()
            .url(buildUrl(PATH_SESSION, query))
            .header("User-Agent", HttpEngine.USER_AGENT)
            .header(Cloud189AppSign.HEADER_REQUEST_ID, Cloud189AppSign.requestId(random))
            .get()
            .build()

        return when (val raw = send(request)) {
            is PanResult.Failure -> raw
            is PanResult.Success -> parseSession(raw.value, accessToken, refreshToken)
        }
    }

    /**
     * 用 refreshToken 换一对新 token，换到之后再换一次会话。
     *
     * 这个接口失败时也回 HTTP 200，只在 body 里给 result 与 msg，
     * 所以判据必须是 result != 0 或 accessToken 为空，不能看状态码。
     */
    suspend fun refreshToken(refreshToken: String): PanResult<Cloud189AppSession> {
        val body = listOf(
            "clientId" to Cloud189AppSign.APP_ID,
            "refreshToken" to refreshToken,
            "grantType" to "refresh_token",
            "format" to "json",
        ).joinToString("&") { (k, v) -> k + "=" + Cloud189Api.encode(v) }
            .toRequestBody(FORM_MEDIA)

        val request = Request.Builder()
            .url(authUrl + PATH_REFRESH_TOKEN)
            .header("User-Agent", HttpEngine.USER_AGENT)
            .header("Accept", "application/json;charset=UTF-8")
            .post(body)
            .build()

        return when (val raw = send(request)) {
            is PanResult.Failure -> raw
            is PanResult.Success -> {
                val dto = runCatching {
                    Cloud189Decoding.json.decodeFromString(
                        C189AppRefreshDto.serializer(),
                        raw.value,
                    )
                }.getOrElse { e ->
                    return PanResult.Failure(
                        PanError.unknown("刷新 token 响应解析失败", raw.value.take(300), e),
                    )
                }
                val access = dto.accessToken.orEmpty()
                if ((dto.result ?: 0) != 0 || access.isEmpty()) {
                    return PanResult.Failure(
                        PanError.auth(dto.msg?.takeIf { it.isNotBlank() } ?: "登录已过期，请重新登录"),
                    )
                }
                openSession(access).let { result ->
                    if (result is PanResult.Success) {
                        session = result.value.copy(refreshToken = dto.refreshToken ?: refreshToken)
                        PanResult.Success(session!!)
                    } else {
                        result
                    }
                }
            }
        }
    }

    /** 保活。官方客户端定时调，失败时通常会重新换会话。 */
    suspend fun keepAlive(): PanResult<Unit> = when (val raw = get(PATH_KEEP_ALIVE)) {
        is PanResult.Failure -> raw
        is PanResult.Success -> PanResult.Success(Unit)
    }

    /** 签名的 GET。path 以 / 开头，query 会自动拼上客户端标识。 */
    suspend fun get(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
    ): PanResult<String> = signedRequest(method = "GET", path = path, query = query)

    /** 签名的 POST，参数放 form body。 */
    suspend fun postForm(
        path: String,
        fields: List<Pair<String, String>> = emptyList(),
        query: List<Pair<String, String>> = emptyList(),
    ): PanResult<String> = signedRequest(method = "POST", path = path, query = query, fields = fields)

    /**
     * 一次签名请求，含「会话失效就刷新一次再重放」。
     *
     * 官方客户端靠响应体里是否出现 InvalidSessionKey / userSessionBO is null 判断，
     * 这两个字符串在 HTTP 200 的 body 里出现，所以状态码不能当依据。
     * 重试只做一次，避免刷新也失败时无限循环。
     */
    private suspend fun signedRequest(
        method: String,
        path: String,
        query: List<Pair<String, String>>,
        fields: List<Pair<String, String>> = emptyList(),
        retried: Boolean = false,
    ): PanResult<String> {
        val current = session
            ?: return PanResult.Failure(PanError.auth("天翼云盘客户端通道尚未建立会话"))

        val fullQuery = Cloud189AppSign.clientSuffix(random) + query
        val url = buildUrl(path, fullQuery)
        val date = Cloud189AppSign.httpDate(clock.nowMillis())
        val signature = Cloud189AppSign.signature(
            sessionSecret = current.sessionSecret,
            sessionKey = current.sessionKey,
            method = method,
            path = Cloud189AppSign.pathOf(url.toString()),
            date = date,
        )

        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", HttpEngine.USER_AGENT)
            .header("Accept", "application/json;charset=UTF-8")
            .header("Referer", WEB_URL + "/")
        Cloud189AppSign.headers(
            sessionKey = current.sessionKey,
            signature = signature,
            date = date,
            requestId = Cloud189AppSign.requestId(random),
        ).forEach { (name, value) -> builder.header(name, value) }

        val request = when (method) {
            "POST" -> builder.post(
                fields.joinToString("&") { (k, v) -> k + "=" + Cloud189Api.encode(v) }
                    .toRequestBody(FORM_MEDIA),
            )
            else -> builder.get()
        }.build()

        val raw = when (val result = send(request)) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }

        if (!retried && isSessionExpired(raw)) {
            val refresh = current.refreshToken
                ?: return PanResult.Failure(PanError.auth())
            return when (val renewed = refreshToken(refresh)) {
                is PanResult.Failure -> renewed
                is PanResult.Success -> signedRequest(
                    method = method,
                    path = path,
                    query = query,
                    fields = fields,
                    retried = true,
                )
            }
        }
        return PanResult.Success(raw)
    }

    /** 会话失效的两种官方判定写法，任一出现即需要重新换会话。 */
    private fun isSessionExpired(raw: String): Boolean =
        raw.contains(Cloud189Decoding.INVALID_SESSION_KEY) || raw.contains("userSessionBO is null")

    private fun parseSession(
        raw: String,
        accessToken: String,
        refreshToken: String?,
    ): PanResult<Cloud189AppSession> {
        val dto = runCatching {
            Cloud189Decoding.json.decodeFromString(C189AppSessionDto.serializer(), raw)
        }.getOrElse { e ->
            return PanResult.Failure(PanError.unknown("换会话响应解析失败", raw.take(300), e))
        }
        Cloud189Decoding.resCodeError(dto.resCode, dto.resMessage)?.let { error ->
            return PanResult.Failure(error)
        }
        val key = dto.sessionKey?.takeIf { it.isNotBlank() }
        val secret = dto.sessionSecret?.takeIf { it.isNotBlank() }
        if (key == null || secret == null) {
            return PanResult.Failure(PanError.auth("换会话失败：服务端没有下发会话密钥"))
        }
        val established = Cloud189AppSession(
            sessionKey = key,
            sessionSecret = secret,
            accessToken = dto.accessToken?.takeIf { it.isNotBlank() } ?: accessToken,
            refreshToken = dto.refreshToken?.takeIf { it.isNotBlank() } ?: refreshToken,
            loginName = dto.loginName?.takeIf { it.isNotBlank() },
        )
        session = established
        return PanResult.Success(established)
    }

    private fun buildUrl(path: String, query: List<Pair<String, String>>) =
        (baseUrl + path).toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    private suspend fun send(request: Request): PanResult<String> =
        when (val result = engine.executeRaw(request)) {
            is PanResult.Failure -> result
            is PanResult.Success -> result.value.use { response -> readBody(response) }
        }

    /**
     * 读 body 并把 HTTP 层的失败翻译成 PanError。
     *
     * 注意会话失效不在这里判：它在 200 的 body 里，由 [isSessionExpired] 处理，
     * 这样「先刷新再重放」只有一处逻辑。
     */
    private fun readBody(response: Response): PanResult<String> {
        val raw = try {
            response.body.string()
        } catch (io: IOException) {
            return PanResult.Failure(PanError.network(io.message ?: "读取响应失败", io))
        }
        if (raw.isBlank()) {
            return PanResult.Failure(PanError.unknown("服务端返回空响应"))
        }
        if (!response.isSuccessful) {
            return PanResult.Failure(Cloud189Decoding.httpError(response.code))
        }
        return PanResult.Success(raw)
    }

    /** 把响应体当 JSON 对象解出来，方便调用方取字段；不是对象返回 null。 */
    fun objectOf(raw: String): JsonObject? = Cloud189Decoding.objectOf(raw)

    companion object {
        /** 官方客户端接口基址，APK 的 libapp.so 里明文出现 3 次。 */
        const val API_URL = "https://api.cloud.189.cn"

        /** 登录与刷新 token 在另一个域上。 */
        const val AUTH_URL = "https://open.e.189.cn"

        const val WEB_URL = "https://cloud.189.cn"

        const val PATH_SESSION = "/getSessionForPC.action"
        const val PATH_KEEP_ALIVE = "/keepUserSession.action"
        const val PATH_REFRESH_TOKEN = "/api/oauth2/refreshToken.do"

        private val FORM_MEDIA =
            "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
    }
}
