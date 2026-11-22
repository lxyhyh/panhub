package com.panhub.provider.pan123

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 123 云盘的线上 DTO。
 *
 * 所有字段名都与接口一字不差（PascalCase 是服务端风格）。
 * 只有这一处知道服务端长什么样；上层只看到 core:model 的 RemoteNode。
 */
@Serializable
data class ApiEnvelope<T>(
    val code: Int = 0,
    val message: String? = null,
    val data: T? = null,
) {
    val isOk: Boolean get() = code == 0 || code == 200
}

@Serializable
data class LoginRequest(
    val passport: String,
    val password: String,
    val remember: Boolean = true,
)

@Serializable
data class LoginData(
    val token: String? = null,
)

@Serializable
data class UserInfoData(
    @SerialName("Nickname") val nickname: String? = null,
    @SerialName("Passport") val passport: String? = null,
    @SerialName("SpaceUsed") val spaceUsed: Long? = null,
    @SerialName("SpacePermanent") val spacePermanent: Long? = null,
    @SerialName("SpaceTemp") val spaceTemp: Long? = null,
)

@Serializable
data class FileListData(
    @SerialName("Next") val next: String? = null,
    @SerialName("InfoList") val infoList: List<FileItem>? = null,
)

@Serializable
data class FileItem(
    @SerialName("FileId") val fileId: Long = 0,
    @SerialName("FileName") val fileName: String = "",
    /** 0 = 文件，1 = 文件夹（推断，已用接口文档样例核对过顺序）。 */
    @SerialName("Type") val type: Int = 0,
    @SerialName("Size") val size: Long = 0,
    @SerialName("ParentFileId") val parentFileId: Long = 0,
    @SerialName("Etag") val etag: String? = null,
    @SerialName("S3KeyFlag") val s3KeyFlag: String? = null,
    @SerialName("CreateAt") val createAt: String? = null,
    @SerialName("UpdateAt") val updateAt: String? = null,
    @SerialName("DownloadUrl") val downloadUrl: String? = null,
) {
    val isDir: Boolean get() = type == 1
}

@Serializable
data class DownloadInfoData(
    @SerialName("DownloadUrl") val downloadUrl: String? = null,
)

@Serializable
data class CreateFolderData(
    @SerialName("Info") val info: FileItem? = null,
    @SerialName("FileId") val fileId: Long? = null,
)

@Serializable
data class UploadRequest(
    val driveId: Int = 0,
    /** 2 = 覆盖同名，1 = 自动重命名，0 = 服务端默认。 */
    val duplicate: Int = 2,
    val etag: String,
    val fileName: String,
    val parentFileId: String,
    val size: Long,
    /** 0 = 文件，1 = 文件夹。 */
    val type: Int = 0,
)

/**
 * upload_request 的响应。
 *
 * 三种形态（见 docs/recon/pan123.md）：
 * - reuse 为 true：秒传，文件已在服务器上，无需上传；
 * - accessKeyId 非空：服务端下发临时 S3 凭据，需用 S3 SDK 直传；
 * - 其余：走预签名 URL 分片上传。
 */
@Serializable
data class UploadTicket(
    @SerialName("AccessKeyId") val accessKeyId: String? = null,
    @SerialName("SecretAccessKey") val secretAccessKey: String? = null,
    @SerialName("SessionToken") val sessionToken: String? = null,
    @SerialName("Bucket") val bucket: String? = null,
    @SerialName("Key") val key: String? = null,
    @SerialName("FileId") val fileId: Long = 0,
    @SerialName("Reuse") val reuse: Boolean = false,
    @SerialName("EndPoint") val endPoint: String? = null,
    @SerialName("StorageNode") val storageNode: String? = null,
    @SerialName("UploadId") val uploadId: String? = null,
)

@Serializable
data class S3PartRequest(
    @SerialName("StorageNode") val storageNode: String? = null,
    @SerialName("bucket") val bucket: String? = null,
    @SerialName("key") val key: String? = null,
    @SerialName("partNumberStart") val partNumberStart: Int,
    @SerialName("partNumberEnd") val partNumberEnd: Int,
    @SerialName("uploadId") val uploadId: String? = null,
)

@Serializable
data class S3PartData(
    @SerialName("presignedUrls") val presignedUrls: Map<String, String> = emptyMap(),
)

@Serializable
data class UploadCompleteRequest(
    @SerialName("StorageNode") val storageNode: String? = null,
    val bucket: String? = null,
    val fileId: Long = 0,
    val fileSize: Long = 0,
    val isMultipart: Boolean = false,
    val key: String? = null,
    val uploadId: String? = null,
)
