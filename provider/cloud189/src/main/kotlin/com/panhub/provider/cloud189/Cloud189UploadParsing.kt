package com.panhub.provider.cloud189

import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import kotlinx.serialization.json.Json

/**
 * 上传通道的响应解析。
 *
 * 单独拆出来是因为它和 open 接口的信封完全不同（code == "SUCCESS"），
 * 而且分片上传的请求头是一段整体 URL 编码过的 "k=v&k=v" 字符串，
 * 这两块都是纯字符串逻辑，能脱离网络单测。
 */
internal object Cloud189UploadParsing {

    /**
     * 解出上传响应信封。
     *
     * 判据只有 code == "SUCCESS"，不看 HTTP 状态码：服务端鉴权失败时
     * 也是 403 + 这段 JSON，靠状态码判断会把「登录过期」显示成「服务器错误」。
     */
    fun decode(json: Json, raw: String): PanResult<C189UploadEnvelope> {
        if (raw.isBlank()) {
            return PanResult.Failure(PanError.unknown("服务端返回空响应"))
        }
        val envelope = runCatching {
            json.decodeFromString(C189UploadEnvelope.serializer(), raw)
        }.getOrNull() ?: return PanResult.Failure(PanError.unknown("响应解析失败", raw.take(300)))

        if (envelope.code != SUCCESS) {
            val message = envelope.msg.orEmpty()
            // 线上原文：{"code":"InvalidSessionKey","msg":"sessionKey=null,invalid sessionKey,..."}
            // 登录失效既可能落在 code 也可能落在 msg，两处都要看。
            val sessionInvalid = envelope.code.orEmpty().contains("InvalidSessionKey") ||
                message.contains("InvalidSessionKey")
            return PanResult.Failure(
                if (sessionInvalid) {
                    PanError.auth("登录态已失效，请重新粘贴 Cookie")
                } else {
                    PanError.unknown(message.ifBlank { "上传请求被拒绝" }, envelope.code)
                },
            )
        }
        return PanResult.Success(envelope)
    }

    /**
     * 把 requestHeader 拆成一个个请求头。
     *
     * 服务端给的是 "Content-Type=...&x-amz-..." 整体做过一次 URL 编码的串，
     * 所以要先解码再按 & 切；值里可能再带 =（Base64 填充），只能按第一个 = 切。
     */
    fun parseHeaders(header: String?): List<Pair<String, String>> {
        if (header.isNullOrBlank()) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        Cloud189UploadCrypto.percentDecode(header).split("&").forEach { pair ->
            if (pair.isBlank()) return@forEach
            val split = pair.indexOf('=')
            if (split <= 0) return@forEach
            out += pair.substring(0, split) to pair.substring(split + 1)
        }
        return out
    }

    const val SUCCESS = "SUCCESS"
}
