package com.panhub.provider.quark

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 夸克网盘的统一信封。
 *
 * 成功判据是 code == 0 && status < 400（OpenList drivers/quark_uc 的 request() 原文：
 * if e.Status >= 400 || e.Code != 0 { return err }）。
 */
@Serializable
internal data class QuarkEnvelope<T>(
    @SerialName("status") val status: Int = 0,
    @SerialName("code") val code: Int = 0,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: T? = null,
    @SerialName("metadata") val metadata: QuarkMetadata? = null,
)

@Serializable
internal data class QuarkMetadata(
    @SerialName("_size") val size: Int? = null,
    @SerialName("_page") val page: Int? = null,
    @SerialName("_count") val count: Int? = null,
    @SerialName("_total") val total: Int? = null,
)

@Serializable
internal data class QuarkFileListData(
    @SerialName("list") val list: List<QuarkFileDto>? = null,
)

/**
 * 一个文件或文件夹。
 *
 * [file] 为 true 表示它是**文件**（注意和直觉相反），判断目录要看 !file。
 * 文件名字段可能含 HTML 实体（&amp; 之类），展示前要反转义，见 QuarkParsing.unescape。
 */
@Serializable
internal data class QuarkFileDto(
    @SerialName("fid") val fid: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("size") val size: Long? = null,
    @SerialName("file") val file: Boolean = true,
    @SerialName("category") val category: Int = 0,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
)

@Serializable
internal data class QuarkMemberData(
    @SerialName("use_capacity") val useCapacity: Long? = null,
    @SerialName("total_capacity") val totalCapacity: Long? = null,
    @SerialName("member_type") val memberType: String? = null,
)

/** /file/download 的 data 是数组，元素里的 download_url 才是真链接。 */
@Serializable
internal data class QuarkDownloadItem(
    @SerialName("fid") val fid: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
)

@Serializable
internal data class QuarkCreateDirData(
    @SerialName("fid") val fid: String? = null,
)
