package com.panhub.provider.baidu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import java.security.MessageDigest

/**
 * 分享记录列表。
 *
 * list 字段服务端会按情况给数组或空对象（没有分享时是 `"list":{}`），
 * 所以这里收成 JsonElement 自己判形态，直接声明成 List 会在空列表时解析失败。
 */
@Serializable
internal data class BaiduShareRecordResp(
    val errno: Int = 0,
    val errmsg: String? = null,
    @SerialName("show_msg") val showMsg: String? = null,
    val count: Int = 0,
    val nextpage: Int = 0,
    val list: JsonElement? = null,
)

/** 一条分享记录。passwd 官方已废弃（json 里被忽略），密码要另取。 */
@Serializable
internal data class BaiduShareRecord(
    val shareId: Long = 0,
    val fsIds: List<Long> = emptyList(),
    val shortlink: String? = null,
    val status: Int = 0,
    val public: Int = 0,
    @SerialName("typicalPath") val typicalPath: String? = null,
    @SerialName("expiredType") val expiredType: Int = 0,
    @SerialName("expiredTime") val expiredTime: Long = 0,
    @SerialName("vCnt") val viewCount: Int = 0,
    /** 少数历史响应里还带着 passwd，有就直接用，省一次请求。 */
    val passwd: String? = null,
)

/** 分享的提取码查询结果。pwd 为 "0" 表示没有密码。 */
@Serializable
internal data class BaiduShareSurlResp(
    val errno: Int = 0,
    val errmsg: String? = null,
    val pwd: String? = null,
    val shorturl: String? = null,
)

/** 把 list 字段的两种形态（数组 / 空对象）都归一成列表。 */
internal fun JsonElement?.shareRecords(): List<BaiduShareRecord> {
    val array = this as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        runCatching {
            SHARE_JSON.decodeFromJsonElement(BaiduShareRecord.serializer(), element)
        }.getOrNull()
    }
}

/**
 * 查询提取码的签名。
 *
 * 算法来自公开实现：md5(shareId + "_sharesurlinfo!@#") 的小写十六进制。
 * 少了这个 sign，接口只回空密码，看不出是「没有密码」还是「签名没通过」。
 */
internal fun shareSurlSign(shareId: String): String {
    val digest = MessageDigest.getInstance("MD5")
    digest.update(shareId.toByteArray(Charsets.UTF_8))
    digest.update("_sharesurlinfo!@#".toByteArray(Charsets.UTF_8))
    val bytes = digest.digest()
    val hex = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        hex.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return hex.toString()
}

private const val HEX = "0123456789abcdef"

/** 服务端把无密码记成 "0"。 */
internal fun normalizeSharePwd(raw: String?): String? =
    raw?.trim()?.takeIf { it.isNotEmpty() && it != "0" }

/** 分享链接的两种历史形态：短链优先，其次长链。 */
internal fun shareUrlOf(record: BaiduShareRecord): String? =
    record.shortlink?.takeIf { it.isNotBlank() }
/** 只给这个文件用，配置与 BaiduApi 内部的一致，免得为解析元素再去要一个实例。 */
private val SHARE_JSON = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
    coerceInputValues = true
}