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
/**
 * /file/upload/pre 的 data：一次上传的完整票据。
 *
 * [uploadUrl] 是 OSS 的 endpoint 主机（形如 xxx.oss-cn-xxx.aliyuncs.com 或带 scheme），
 * 真正 PUT 的地址要拼成 https://<bucket>.<uploadUrl 去掉 scheme>/<objKey>，见 QuarkUploader。
 */
@Serializable
internal data class QuarkUploadPre(
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("finish") val finish: Boolean = false,
    @SerialName("upload_id") val uploadId: String? = null,
    @SerialName("obj_key") val objKey: String? = null,
    @SerialName("upload_url") val uploadUrl: String? = null,
    @SerialName("fid") val fid: String? = null,
    @SerialName("bucket") val bucket: String? = null,
    @SerialName("callback") val callback: QuarkUploadCallback? = null,
    @SerialName("format_type") val formatType: String? = null,
    @SerialName("size") val size: Long? = null,
    @SerialName("auth_info") val authInfo: String? = null,
)

/** 收尾时要原样回传给 OSS 的回调描述。 */
@Serializable
internal data class QuarkUploadCallback(
    @SerialName("callbackUrl") val callbackUrl: String? = null,
    @SerialName("callbackBody") val callbackBody: String? = null,
)

/**
 * upload/pre 的信封。
 *
 * 不能复用 [QuarkEnvelope]：那一个的 metadata 是分页信息（_size/_page/_total），
 * 而上传这条接口的 metadata 装的是 part_size，同名不同义。
 */
@Serializable
internal data class QuarkUploadPreEnvelope(
    @SerialName("status") val status: Int = 0,
    @SerialName("code") val code: Int = 0,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: QuarkUploadPre? = null,
    @SerialName("metadata") val metadata: QuarkUploadPreMeta? = null,
)

/** 分片大小在 metadata 里，不在 data 里。 */
@Serializable
internal data class QuarkUploadPreMeta(
    @SerialName("part_size") val partSize: Long? = null,
    @SerialName("part_thread") val partThread: Int? = null,
)

/** /file/update/hash：finish=true 表示服务端已有同样内容，秒传完成。 */
@Serializable
internal data class QuarkHashData(
    @SerialName("finish") val finish: Boolean = false,
    @SerialName("fid") val fid: String? = null,
)

/** /file/upload/auth：auth_key 直接当 OSS 的 Authorization 头用。 */
@Serializable
internal data class QuarkUploadAuth(
    @SerialName("auth_key") val authKey: String? = null,
)
