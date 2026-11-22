package com.panhub.provider.lanzou

import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.network.HttpEngine
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.Request

/**
 * 蓝奏云 HTTP 层。
 *
 * 三件必须集中处理的事，所以单独抽出来而不是散在 provider 里：
 * 1. 所有动作都打同一个 `doupload.php` 端点，靠 `task` 数字区分；
 * 2. 服务端有风控，请求之间要留间隔（网页版同样如此）；
 * 3. 人机校验（acw_sc__v2）要算出 Cookie 再重试一次。
 */
internal class LanzouApi(
    private val engine: HttpEngine,
    private val cookie: LanzouCookie,
) {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** 上一次请求时刻，用于限速。 */
    private var lastRequestAt = 0L

    private suspend fun throttle() {
        val elapsed = System.currentTimeMillis() - lastRequestAt
        if (lastRequestAt != 0L && elapsed < MIN_INTERVAL_MS) {
            delay(MIN_INTERVAL_MS - elapsed)
        }
        lastRequestAt = System.currentTimeMillis()
    }

    /** 调 `doupload.php` 的某个 task。[uid] 部分 task（47）必须带在 query 上。 */
    suspend fun task(
        task: Int,
        fields: List<Pair<String, String>> = emptyList(),
        uid: String? = null,
    ): PanResult<String> {
        throttle()
        val url = buildString {
            append(BASE_URL).append(PATH_DOUPLOAD)
            if (uid != null) append("?uid=").append(uid)
        }
        val body = FormBody.Builder()
            .add("task", task.toString())
            .apply { fields.forEach { (k, v) -> add(k, v) } }
            .build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE_URL + "/mydisk.php")
            .apply { cookie.header(BASE_HOST)?.let { header("Cookie", it) } }
            .build()
        return when (val result = engine.execute(request)) {
            is PanResult.Failure -> result
            is PanResult.Success -> result.value.use { response ->
                cookie.save(response.headers)
                PanResult.Success(response.body.string())
            }
        }
    }

    /** GET 一个网页并自动处理人机校验，返回正文 HTML。 */
    suspend fun page(url: String, referer: String? = null): PanResult<String> {
        val first = getRaw(url, referer)
        val html = when (first) {
            is PanResult.Failure -> return first
            is PanResult.Success -> first.value
        }
        if (!LanzouChallenge.contains(html)) return PanResult.Success(html)
        val token = LanzouChallenge.compute(html) ?: return PanResult.Success(html)
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return PanResult.Success(html)
        cookie.putForHost(host, LanzouChallenge.MARKER, token)
        // 校验通过后带 Cookie 再请求一次，这次拿到的才是正文。
        return getRaw(url, referer)
    }

    private suspend fun getRaw(url: String, referer: String?): PanResult<String> {
        throttle()
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer ?: BASE_URL + "/mydisk.php")
            .apply { cookie.header(host)?.let { header("Cookie", it) } }
            .build()
        return when (val result = engine.execute(request)) {
            is PanResult.Failure -> result
            is PanResult.Success -> result.value.use { response ->
                cookie.save(response.headers)
                PanResult.Success(response.body.string())
            }
        }
    }

    /** 用 sign 换取下载地址。[password] 只有带提取码的分享才需要。 */
    suspend fun downProcess(
        base: String,
        sign: String,
        password: String? = null,
        referer: String,
        extra: List<Pair<String, String>> = emptyList(),
    ): PanResult<LanzouDownloadDto> {
        throttle()
        val body = FormBody.Builder()
            .add("action", "downprocess")
            .add("sign", sign)
            .add("ves", "1")
            .apply {
                if (password != null) add("p", password)
                extra.forEach { (k, v) -> add(k, v) }
            }
            .build()
        val request = Request.Builder()
            .url(base + "/" + AJAXM)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .apply { cookie.header(BASE_HOST)?.let { header("Cookie", it) } }
            .build()
        return when (val result = engine.execute(request)) {
            is PanResult.Failure -> result
            is PanResult.Success -> result.value.use { response ->
                cookie.save(response.headers)
                decodeDownload(response.body.string())
            }
        }
    }

    private fun decodeDownload(raw: String): PanResult<LanzouDownloadDto> {
        val dto = runCatching {
            json.decodeFromString(LanzouDownloadDto.serializer(), raw)
        }.getOrNull() ?: return PanResult.Failure(PanError.unknown("下载地址解析失败", raw.take(200)))
        if (dto.zt != "1") {
            return PanResult.Failure(PanError.unknown(dto.inf ?: "获取下载地址失败"))
        }
        return PanResult.Success(dto)
    }

    /**
     * 把响应体解析成 JSON 对象。
     *
     * 蓝奏云在撞上风控时返回的是 HTML 而不是 JSON，这种情况要归类成
     * 「可重试的限流」而不是「数据损坏」，否则界面会提示用户去检查网络。
     */
    fun decodeObject(raw: String): PanResult<JsonObject> {
        val element = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return PanResult.Failure(
                PanError(
                    kind = ErrorKind.RATE_LIMIT,
                    message = "请求被拦截，请稍后再试",
                    detail = raw.take(200),
                    retryable = true,
                ),
            )
        return PanResult.Success(element)
    }

    companion object {
        const val BASE_URL = "https://pc.woozooo.com"
        const val BASE_HOST = "pc.woozooo.com"
        const val PATH_DOUPLOAD = "/doupload.php"
        const val AJAXM = "ajaxm.php"
        const val UPLOAD_PATH = "html5up.php"

        /**
         * 网页版 UA。蓝奏云按 UA 返回不同模板：桌面版分享页里有简介表格，
         * 移动版没有。拿桌面 UA 能少一次额外请求。
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** 请求间隔。太快会触发风控，返回空 body 而不是明确错误码。 */
        const val MIN_INTERVAL_MS = 300L

        // 蓝奏云没有 REST 路径，全部靠这些 task 号区分动作。
        const val TASK_FILE_LIST = 5
        const val TASK_FOLDER_LIST = 47
        const val TASK_MKDIR = 2
        const val TASK_RENAME_FOLDER = 4
        const val TASK_DELETE_FILE = 6
        const val TASK_DELETE_FOLDER = 3
        const val TASK_MOVE_FILE = 20
        const val TASK_FILE_INFO = 12
        const val TASK_SHARE_INFO = 22
        const val TASK_RENAME_FILE = 46
    }
}
