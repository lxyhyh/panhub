package com.panhub.provider.pan123

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** 官方请求里 shareName 的缺省值，与网页端一致。 */
internal const val DEFAULT_SHARE_NAME = "123云盘分享"

/**
 * 创建分享的请求体。
 *
 * 字段形态与官网当前请求一字不差（见 docs/recon/pan123-share.md）：
 * fileIdList 是逗号分隔的字符串而不是数组，expiration 是带时区的 ISO 时间
 * 而不是天数。早期实现按直觉写成数组 + 天数，服务端会直接判参数非法。
 */
@Serializable
internal data class ShareCreateRequest(
	@SerialName("driveId") val driveId: Int = 0,
	/** 永久分享给一个远未来时间，服务端用 shareModality 表示真实时长。 */
	@SerialName("expiration") val expiration: String,
	@SerialName("fileIdList") val fileIdList: String,
	@SerialName("shareName") val shareName: String = DEFAULT_SHARE_NAME,
	/** 空串表示不设提取码，不能省略这个字段。 */
	@SerialName("sharePwd") val sharePwd: String = "",
	@SerialName("event") val event: String = "shareCreate",
	@SerialName("fileNum") val fileNum: Int,
	@SerialName("renameVisible") val renameVisible: Boolean = true,
	/** 1 = 1 天，2 = 7 天，3 = 30 天，4 = 永久。 */
	@SerialName("shareModality") val shareModality: Int = 4,
	@SerialName("operatePlace") val operatePlace: Int = 1,
	@SerialName("trafficLimitSwitch") val trafficLimitSwitch: Int = 1,
	@SerialName("trafficLimit") val trafficLimit: Long = 0,
	@SerialName("trafficSwitch") val trafficSwitch: Int = 1,
	@SerialName("fillPwdSwitch") val fillPwdSwitch: Int = 0,
)

/**
 * 创建分享的响应。
 *
 * 链接有三种历史形态（shareLinkList 里的 list 数组、裸数组、单字符串），
 * 旧网关还会把 shareKey/shareUrl 平铺在顶层；两套都要认，否则换个网关就解析不出链接。
 */
@Serializable
internal data class ShareCreateData(
	@SerialName("shareLinkList") val shareLinkList: JsonElement? = null,
	@SerialName("sharePwd") val sharePwdLower: String? = null,
	@SerialName("SharePwd") val sharePwdUpper: String? = null,
	@SerialName("shareKey") val shareKeyLower: String? = null,
	@SerialName("ShareKey") val shareKeyUpper: String? = null,
	@SerialName("shareUrl") val shareUrlLower: String? = null,
	@SerialName("ShareUrl") val shareUrlUpper: String? = null,
) {
	val sharePwd: String? get() = sharePwdLower ?: sharePwdUpper
	val shareKey: String? get() = shareKeyLower ?: shareKeyUpper
	val shareUrl: String? get() = shareUrlLower ?: shareUrlUpper
}

/** 分享列表的一页。[Next] 的终止值和文件列表一样是字符串 "-1"。 */
@Serializable
internal data class ShareListData(
	@SerialName("InfoList") val infoList: List<ShareListItem> = emptyList(),
	@SerialName("Next") val next: String? = null,
)

/**
 * 分享列表的一项。
 *
 * 服务端同时存在首字母大写与小写两套字段名（不同网关不同版本），
 * 所以每个字段声明两遍，取值时优先非空的那个。
 */
@Serializable
internal data class ShareListItem(
	@SerialName("shareId") val shareIdLower: Long = 0,
	@SerialName("ShareId") val shareIdUpper: Long = 0,
	@SerialName("shareKey") val shareKeyLower: String? = null,
	@SerialName("ShareKey") val shareKeyUpper: String? = null,
	@SerialName("shareName") val shareNameLower: String? = null,
	@SerialName("ShareName") val shareNameUpper: String? = null,
	@SerialName("shareUrl") val shareUrlLower: String? = null,
	@SerialName("ShareUrl") val shareUrlUpper: String? = null,
	@SerialName("sharePwd") val sharePwdLower: String? = null,
	@SerialName("SharePwd") val sharePwdUpper: String? = null,
	@SerialName("fileId") val fileIdLower: Long = 0,
	@SerialName("FileId") val fileIdUpper: Long = 0,
	@SerialName("expiration") val expirationLower: String? = null,
	@SerialName("Expiration") val expirationUpper: String? = null,
	@SerialName("shareStatus") val shareStatusLower: Int = 0,
	@SerialName("Status") val shareStatusUpper: Int = 0,
) {
	val shareId: Long get() = if (shareIdLower > 0) shareIdLower else shareIdUpper
	val shareKey: String? get() = shareKeyLower ?: shareKeyUpper
	val shareName: String? get() = shareNameLower ?: shareNameUpper
	val shareUrl: String? get() = shareUrlLower ?: shareUrlUpper
	val sharePwd: String? get() = sharePwdLower ?: sharePwdUpper
	val expiration: String? get() = expirationLower ?: expirationUpper
	val shareStatus: Int get() = if (shareStatusLower != 0) shareStatusLower else shareStatusUpper
}

/**
 * 取消分享。
 *
 * shareInfoList 里要的是 Long 型 shareId，不是 shareKey —— 传错会被服务端
 * 当成「分享不存在」，返回码看着正常但分享没被取消。
 */
@Serializable
internal data class ShareDeleteRequest(
	@SerialName("driveId") val driveId: Int = 0,
	@SerialName("shareInfoList") val shareInfoList: List<ShareIdWrapper>,
	@SerialName("isPayShare") val isPayShare: Int = 0,
	@SerialName("event") val event: String = "shareCancel",
	@SerialName("operatePlace") val operatePlace: Int = 2,
)

@Serializable
internal data class ShareIdWrapper(
	@SerialName("shareId") val shareId: Long,
)

/** 从三种历史形态里取出所有链接。 */
internal fun JsonElement?.shareLinks(): List<String> = when (val value = this) {
	is JsonObject -> (value["list"] as? JsonArray)?.mapNotNull { it.plainText() }.orEmpty()
	is JsonArray -> value.mapNotNull { it.plainText() }
	is JsonPrimitive -> plainText()?.let(::listOf).orEmpty()
	else -> emptyList()
}

private fun JsonElement.plainText(): String? = runCatching {
	jsonPrimitive.content
}.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
/**
 * 把服务端给的链接补成可点的完整地址。
 *
 * 服务端有时只回 shareKey，有时回 "/s/xxxx"，有时回完整 URL，
 * 三种都得能变成用户能直接打开的链接。
 */
internal fun normalizeShareUrl(value: String): String {
	val trimmed = value.trim()
	return when {
		trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
		trimmed.startsWith("/") -> "https://www.123pan.cn$trimmed"
		else -> "https://www.123pan.cn/s/$trimmed"
	}
}

/**
 * 分享有效期与 [ShareCreateRequest.expiration]/[ShareCreateRequest.shareModality] 的换算。
 *
 * 服务端同时要 ISO 时间与档位两个字段，且档位只有 1/7/30/永久四档：
 * 用户选 3 天只能落到 7 天档，所以本地要把实际到期的那个时刻写进 expiration，
 * 否则会出现「显示 3 天、实际 7 天」的偏差。
 */
internal fun shareExpirationIso(days: Int?): String {
	val d = days ?: 0
	if (d <= 0) return PERMANENT_EXPIRATION
	val date = java.time.LocalDate.now().plusDays(d.toLong()).atTime(23, 59, 59)
	return date.toString() + "+08:00"
}

internal fun shareModalityOf(days: Int?): Int {
	val d = days ?: 0
	return when {
		d <= 0 -> 4
		d <= 1 -> 1
		d <= 7 -> 2
		else -> 3
	}
}

internal const val PERMANENT_EXPIRATION = "2099-12-12T08:00:00+08:00"

/**
 * 分享里的一页文件。
 *
 * 123 云盘的历史网关对同一份数据给出大小写两套字段名（网页端用大写、
 * 部分新版接口用小写），所以每个字段声明两遍，取值时优先非空的那个。
 */
@Serializable
internal data class ShareFilesData(
	@SerialName("InfoList") val infoList: List<ShareFileItem> = emptyList(),
	@SerialName("Next") val next: String? = null,
	@SerialName("Expired") val expired: Boolean = false,
	@SerialName("ShareName") val shareNameUpper: String? = null,
	@SerialName("shareName") val shareNameLower: String? = null,
) {
	val shareName: String? get() = shareNameLower ?: shareNameUpper
}

@Serializable
internal data class ShareFileItem(
	@SerialName("FileId") val fileIdUpper: Long = 0,
	@SerialName("fileId") val fileIdLower: Long = 0,
	@SerialName("FileName") val fileNameUpper: String? = null,
	@SerialName("fileName") val fileNameLower: String? = null,
	@SerialName("Size") val sizeUpper: Long = 0,
	@SerialName("size") val sizeLower: Long = 0,
	@SerialName("Type") val type: Int = 0,
	@SerialName("ParentFileId") val parentFileIdUpper: Long = 0,
	@SerialName("parentFileId") val parentFileIdLower: Long = 0,
	@SerialName("S3KeyFlag") val s3KeyFlagUpper: String? = null,
	@SerialName("s3keyFlag") val s3KeyFlagLower: String? = null,
	@SerialName("Etag") val etagUpper: String? = null,
	@SerialName("etag") val etagLower: String? = null,
	@SerialName("StorageNode") val storageNode: String? = null,
	@SerialName("UpdateAt") val updateAtUpper: String? = null,
	@SerialName("updateAt") val updateAtLower: String? = null,
) {
	val fileId: Long get() = if (fileIdUpper > 0) fileIdUpper else fileIdLower
	val fileName: String? get() = fileNameUpper ?: fileNameLower
	val size: Long get() = if (sizeUpper > 0) sizeUpper else sizeLower
	val parentFileId: Long get() = if (parentFileIdUpper > 0) parentFileIdUpper else parentFileIdLower
	val s3KeyFlag: String? get() = s3KeyFlagUpper ?: s3KeyFlagLower
	val etag: String? get() = etagUpper ?: etagLower
	val updateAt: String? get() = updateAtUpper ?: updateAtLower

	/** Type == 1 才是文件夹；0 是普通文件。 */
	val isDir: Boolean get() = type == 1
}

/** 转存的响应：只给一个异步任务号。 */
@Serializable
internal data class CopySaveData(
	@SerialName("taskID") val taskIdUpper: String? = null,
	@SerialName("taskId") val taskIdLower: String? = null,
) {
	val taskId: String? get() = taskIdUpper ?: taskIdLower
}

/**
 * 转存任务的进度。
 *
 * 服务端没有统一的完成标志：有的网关给 finished，有的给 status=2/3，
 * 还有的只在下发 fileId 时才算落盘。三种都要认，否则会一直轮询到超时。
 */
@Serializable
internal data class CopySaveTaskData(
	@SerialName("finished") val finished: Boolean = false,
	@SerialName("status") val status: Int = 0,
	@SerialName("state") val state: String? = null,
	@SerialName("fileId") val fileIdLower: Long = 0,
	@SerialName("FileId") val fileIdUpper: Long = 0,
	@SerialName("newFileId") val newFileId: Long = 0,
	@SerialName("message") val message: String? = null,
) {
	val done: Boolean
		get() = finished || status == 2 || status == 3 ||
			state?.lowercase() in DONE_STATES ||
			fileIdLower > 0 || fileIdUpper > 0 || newFileId > 0

	internal companion object {
		val DONE_STATES = setOf("success", "done", "2", "3")
	}
}

/**
 * 把 123 云盘的时间串转成毫秒。
 *
 * 同一个字段在不同接口里有三种形态：带时区的 ISO、不带时区的本地时间
 * （固定 +08:00）、以及 "2024-01-02 03:04:05" 这种空格分隔写法。
 */
internal fun parsePan123Time(raw: String?): Long? {
	if (raw.isNullOrBlank()) return null
	return runCatching {
		java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
	}.getOrElse {
		runCatching {
			java.time.LocalDateTime.parse(raw.replace(' ', 'T'))
				.toInstant(java.time.ZoneOffset.ofHours(8)).toEpochMilli()
		}.getOrNull()
	}
}

