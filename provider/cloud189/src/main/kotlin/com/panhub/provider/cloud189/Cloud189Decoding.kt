package com.panhub.provider.cloud189

import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 天翼云盘所有通道共用的 JSON 解析与错误归一化。
 *
 * 之所以单独抽出来：网页 open 接口（cloud.189.cn）、官方客户端通道
 * （api.cloud.189.cn）与上传通道（upload.cloud.189.cn）的响应外壳字段名
 * 与判定规则完全一样，只是基址与签名不同。判定规则写一份，三处共用，
 * 避免哪天改了一处漏掉另一处。
 */
internal object Cloud189Decoding {

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
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
        val error = resCodeError(envelope.resCode, envelope.resMessage)
        return if (error == null) PanResult.Success(envelope) else PanResult.Failure(error)
    }

    /**
     * res_code 判定。天翼云盘有时回数字 0、有时回字符串错误码，
     * 所以先归一化成字符串再比。
     */
    fun resCodeError(resCode: JsonElement?, resMessage: String?): PanError? {
        val code = textOf(resCode)
        if (code.isNullOrBlank() || code == "0") return null
        if (code == INVALID_SESSION_KEY) return PanError.auth()
        return PanError.unknown(resMessage ?: "天翼云盘返回错误（" + code + "）", code)
    }

    /** 把响应体当成 JSON 对象看；不是对象（HTML、空体）返回 null。 */
    fun objectOf(raw: String): JsonObject? =
        runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()

    /** 直接按对象取 res_code，用于那些成功时返回业务对象、失败时返回外壳的接口。 */
    fun objectError(raw: String): PanError? {
        val obj = objectOf(raw) ?: return null
        val code = textOf(obj.get("res_code")) ?: textOf(obj.get("errorCode"))
        return resCodeError(JsonPrimitive(code ?: ""), textOf(obj.get("res_message")))
    }

    /** 从 JsonElement 里安全地取字符串，数字也转成字符串。 */
    fun textOf(element: JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.content
    }

    /** HTTP 状态码到错误的映射，三条通道共用。 */
    fun httpError(code: Int): PanError = when (code) {
        401, 403 -> PanError.auth()
        404 -> PanError.notFound()
        429 -> PanError.rateLimited()
        in 500..599 -> PanError(ErrorKind.SERVER, "服务器错误（" + code + "）", retryable = true)
        else -> PanError.unknown("请求失败（" + code + "）")
    }

    /** 会话失效的统一错误码，三条通道都用它。 */
    const val INVALID_SESSION_KEY = "InvalidSessionKey"
}
