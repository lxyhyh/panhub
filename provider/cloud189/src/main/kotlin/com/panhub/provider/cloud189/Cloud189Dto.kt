package com.panhub.provider.cloud189

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 天翼云盘 open 接口的响应外壳。
 *
 * 线上 res_code 有时是数字 0、有时是字符串错误码（例如 InvalidSessionKey），
 * 所以用 [JsonElement] 接住再统一归一化，避免字段类型差异把整页打掉。
 */
@Serializable
internal data class C189Envelope(
    @SerialName("res_code") val resCode: JsonElement? = null,
    @SerialName("res_message") val resMessage: String? = null,
    @SerialName("errorCode") val errorCode: String? = null,
    @SerialName("errorMsg") val errorMsg: String? = null,
    @SerialName("fileListAO") val fileListAo: C189FileListAo? = null,
    @SerialName("fileDownloadUrl") val fileDownloadUrl: String? = null,
    @SerialName("downloadUrl") val downloadUrl: String? = null,
    @SerialName("cloudCapacityInfo") val cloudCapacity: C189Capacity? = null,
    @SerialName("familyCapacityInfo") val familyCapacity: C189Capacity? = null,
    @SerialName("sessionKey") val sessionKey: String? = null,
    @SerialName("sessionSecret") val sessionSecret: String? = null,
    @SerialName("loginName") val loginName: String? = null,
)

/**
 * 列表结果。天翼云盘把文件与文件夹分成两个数组返回，
 * 而不是用 isFolder 混排，这是它和其它网盘最不同的地方。
 */
@Serializable
internal data class C189FileListAo(
    @SerialName("count") val count: Int? = null,
    @SerialName("fileList") val fileList: List<C189FileDto>? = null,
    @SerialName("folderList") val folderList: List<C189FolderDto>? = null,
)

@Serializable
internal data class C189FileDto(
    @SerialName("id") val id: JsonElement? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("size") val size: Long? = null,
    @SerialName("lastOpTime") val lastOpTime: String? = null,
    @SerialName("icon") val icon: C189IconDto? = null,
)

@Serializable
internal data class C189FolderDto(
    @SerialName("id") val id: JsonElement? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("lastOpTime") val lastOpTime: String? = null,
)

@Serializable
internal data class C189IconDto(
    @SerialName("smallUrl") val smallUrl: String? = null,
)

@Serializable
internal data class C189Capacity(
    @SerialName("usedSize") val usedSize: Long? = null,
    @SerialName("totalSize") val totalSize: Long? = null,
    @SerialName("freeSize") val freeSize: Long? = null,
)
