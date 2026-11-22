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
    val Nickname: String? = null,
    val Passport: String? = null,
    val SpaceUsed: Long? = null,
    @SerialName("SpacePermanent") val spacePermanent: Long? = null,
    val SpaceTemp: Long? = null,
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
data class ShareCreateRequest(
    val driveId: Int = 0,
    val fileIds: List<Long>,
    val shareName: String? = null,
    /** 0 = 永久，其他为天数（推断）。 */
    val expiration: Int = 0,
    val sharePwd: String? = null,
)

@Serializable
data class ShareCreateData(
    @SerialName("ShareKey") val shareKey: String? = null,
    @SerialName("ShareUrl") val shareUrl: String? = null,
)
