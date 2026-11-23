package com.panhub.provider.baidu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 短链换 shareid / uk 的响应。
 *
 * 注意 errno 的语义：**-9 不是失败**。带提取码的分享会回 -9，
 * 但 shareid 与 uk 照样给全（服务端只是顺手告诉你「要验证」）。
 * 已取消的分享回 -21，链接写错回 140。
 */
@Serializable
internal data class BaiduShortUrlInfoResp(
    val errno: Int = 0,
    val errmsg: String? = null,
    @SerialName("show_msg") val showMsg: String? = null,
    val shareid: Long = 0,
    val uk: Long = 0,
    val title: String? = null,
    val dir: String? = null,
    val type: Int = 0,
    val root: Int = 0,
    val public: Int? = null,
)

/** 提取码校验的响应。成功后 randsk 要原样写进 Cookie `BDCLND`。 */
@Serializable
internal data class BaiduShareVerifyResp(
    val errno: Int = 0,
    val errmsg: String? = null,
    @SerialName("show_msg") val showMsg: String? = null,
    val randsk: String? = null,
)

/**
 * 分享内的目录列表。
 *
 * 条目字段类型**不稳定**：根目录返回字符串（`"fs_id":"123"`），
 * 子目录返回数字。用强类型 DTO 会在一半场景里解析失败，
 * 所以 list 收成 JsonElement，逐项用 [shareEntries] 手读。
 */
@Serializable
internal data class BaiduShareListResp(
    val errno: Int = 0,
    val errmsg: String? = null,
    @SerialName("show_msg") val showMsg: String? = null,
    val list: JsonElement? = null,
    @SerialName("cur_total") val curTotal: Int = 0,
    val title: String? = null,
    @SerialName("share_id") val shareId: Long = 0,
    val uk: Long = 0,
    @SerialName("expired_type") val expiredType: Int = 0,
)

/** 分享里的一个条目。 */
internal data class BaiduShareEntryDto(
    val fsId: Long,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val path: String?,
    val modifiedAt: Long?,
)

/** 转存的响应。`info` 里每项带自己的 errno，非 0 表示那一项没存进去。 */
@Serializable
internal data class BaiduShareTransferResp(
    val errno: Int = 0,
    val errmsg: String? = null,
    @SerialName("show_msg") val showMsg: String? = null,
    @SerialName("extra") val extra: JsonElement? = null,
    val info: JsonElement? = null,
    val taskid: Long = 0,
)

/** 把 list 字段解析成条目；字段类型不稳定，所以每个值都按「字符串或数字」读。 */
internal fun JsonElement?.shareEntries(): List<BaiduShareEntryDto> {
    val array = this as? JsonArray ?: return emptyList()
    return array.mapNotNull { element -> (element as? JsonObject)?.toShareEntry() }
}

private fun JsonObject.toShareEntry(): BaiduShareEntryDto? {
    val fsId = long("fs_id") ?: return null
    val name = text("server_filename")?.takeIf { it.isNotBlank() } ?: return null
    val isDir = (long("isdir") ?: 0L) != 0L
    return BaiduShareEntryDto(
        fsId = fsId,
        name = name,
        isDir = isDir,
        size = if (isDir) 0L else long("size") ?: 0L,
        path = text("path"),
        // server_mtime 是秒，界面上要毫秒；0 表示服务端没给。
        modifiedAt = long("server_mtime")?.takeIf { it > 0L }?.let { it * 1000L },
    )
}

/** 字符串或数字都接受：同一个接口的两种返回形态混用，写死类型必然炸一半。 */
private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.trim()?.toLongOrNull()

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.content
