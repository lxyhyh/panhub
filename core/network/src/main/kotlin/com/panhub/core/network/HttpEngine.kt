package com.panhub.core.network

import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 全 App 唯一的 HTTP 出口。所有 provider 都通过它发请求，
 * 好处是：超时、User-Agent、重试、Cookie、日志只在这里配置一次。
 */
class HttpEngine(
    client: OkHttpClient = defaultClient(),
) {
    val client: OkHttpClient = client

    /** 给某个服务商派生一个带专属 CookieJar / 拦截器的 client。 */
    fun child(configure: OkHttpClient.Builder.() -> Unit): HttpEngine =
        HttpEngine(client.newBuilder().apply(configure).build())

    suspend fun execute(request: Request): PanResult<Response> =
        try {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                response.use {
                    PanResult.Failure(httpError(it.code, it.message))
                }
            } else {
                PanResult.Success(response)
            }
        } catch (io: IOException) {
            PanResult.Failure(PanError.network(io.message ?: "网络请求失败", io))
        }

    suspend fun executeRaw(request: Request): PanResult<Response> =
        try {
            PanResult.Success(client.newCall(request).await())
        } catch (io: IOException) {
            PanResult.Failure(PanError.network(io.message ?: "网络请求失败", io))
        }

    /**
     * 发请求，把响应体读完、关掉，只把文本带回来。
     *
     * 读 body 这一步是**真的在读 socket**，所以固定放到 IO 线程上做。
     * [execute] / [executeRaw] 只保证连上并收到响应头（enqueue 在 OkHttp 自己的
     * 线程池里），body 是调用方读的——在 ViewModel 的 Main 协程里读会抛
     * NetworkOnMainThreadException。凡是只要文本的地方都应该走这里。
     */
    suspend fun fetchText(request: Request): PanResult<HttpText> = try {
        PanResult.Success(client.newCall(request).await().readText())
    } catch (io: IOException) {
        PanResult.Failure(PanError.network(io.message ?: "网络请求失败", io))
    }

    /**
     * 与 [fetchText] 相同，只是把非 2xx 当成失败。
     *
     * 错误响应里的解释不往 [PanError] 里塞：多数调用方拿它判断「登录是否还有效」，
     * 服务端返回的整页 HTML 只会污染提示语。
     */
    suspend fun fetchTextOk(request: Request): PanResult<HttpText> =
        when (val result = fetchText(request)) {
            is PanResult.Failure -> result
            is PanResult.Success -> {
                val text = result.value
                if (text.code in 200..299) {
                    result
                } else {
                    PanResult.Failure(httpError(text.code, text.message))
                }
            }
        }

    private fun httpError(code: Int, message: String): PanError = when (code) {
        401, 403 -> PanError.auth()
        404 -> PanError.notFound()
        429 -> PanError.rateLimited()
        in 500..599 -> PanError(
            kind = ErrorKind.SERVER,
            message = "服务器错误（$code）",
            retryable = true,
        )
        else -> PanError.unknown("请求失败（$code $message）")
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** 一次请求的文本结果。body 已经在 IO 线程上读完并关闭。 */
data class HttpText(
    val code: Int,
    val message: String,
    val body: String,
    val headers: Headers,
)

/**
 * 读完并关闭响应。
 *
 * 单独抽出来是为了让「碰 socket 的这一步」只有一个出口：
 * 调用方无论跑在哪个线程上，读字节这件事都发生在这里的 IO 线程。
 */
private suspend fun Response.readText(): HttpText = withContext(Dispatchers.IO) {
    use {
        HttpText(
            code = it.code,
            message = it.message,
            body = it.body?.string().orEmpty(),
            headers = it.headers,
        )
    }
}

/** OkHttp 的 enqueue 桥接成挂起函数，随协程取消一起取消。 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) return
            cont.resumeWithException(e)
        }
    })
}

/** 小工具：把键值对拼成 form body，避免每个 provider 重复写。 */
fun formBody(pairs: List<Pair<String, String>>): RequestBody =
    okhttp3.FormBody.Builder().apply {
        pairs.forEach { (k, v) -> add(k, v) }
    }.build()

fun urlBuilder(base: String, block: HttpUrl.Builder.() -> Unit): HttpUrl =
    base.toHttpUrl().newBuilder().apply(block).build()