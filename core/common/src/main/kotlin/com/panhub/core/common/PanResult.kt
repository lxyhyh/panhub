package com.panhub.core.common

/**
 * 所有 provider 操作统一返回它，而不是抛业务异常。
 * 这样调用方对错误处理是强制的，也不会把 HTTP 细节泄露到 UI。
 */
sealed interface PanResult<out T> {
    data class Success<T>(val value: T) : PanResult<T>
    data class Failure(val error: PanError) : PanResult<Nothing>
}

inline fun <T, R> PanResult<T>.map(transform: (T) -> R): PanResult<R> = when (this) {
    is PanResult.Success -> PanResult.Success(transform(value))
    is PanResult.Failure -> this
}

inline fun <T> PanResult<T>.onSuccess(action: (T) -> Unit): PanResult<T> {
    if (this is PanResult.Success) action(value)
    return this
}

inline fun <T> PanResult<T>.onFailure(action: (PanError) -> Unit): PanResult<T> {
    if (this is PanResult.Failure) action(error)
    return this
}

fun <T> PanResult<T>.getOrNull(): T? = (this as? PanResult.Success)?.value

/** 归一化的错误。message 面向用户，detail 面向日志。 */
data class PanError(
    val kind: ErrorKind,
    val message: String,
    val detail: String? = null,
    val retryable: Boolean = false,
    val cause: Throwable? = null,
) {
    companion object {
        fun auth(message: String = "登录已过期，请重新登录") =
            PanError(ErrorKind.AUTH, message)

        fun network(message: String = "网络连接失败", cause: Throwable? = null) =
            PanError(ErrorKind.NETWORK, message, cause = cause, retryable = true)

        fun notFound(message: String = "文件不存在") = PanError(ErrorKind.NOT_FOUND, message)

        fun notSupported(message: String = "该网盘不支持此操作") =
            PanError(ErrorKind.UNSUPPORTED, message)

        fun rateLimited(message: String = "操作过于频繁，请稍后再试") =
            PanError(ErrorKind.RATE_LIMIT, message, retryable = true)

        fun unknown(message: String, detail: String? = null, cause: Throwable? = null) =
            PanError(ErrorKind.UNKNOWN, message, detail, retryable = false, cause = cause)
    }
}

enum class ErrorKind { AUTH, NETWORK, NOT_FOUND, UNSUPPORTED, RATE_LIMIT, SERVER, UNKNOWN }
