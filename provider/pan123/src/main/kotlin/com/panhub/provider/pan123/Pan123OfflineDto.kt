package com.panhub.provider.pan123

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 离线解析的响应。
 *
 * 字段名全部照抄 APK 内网页 bundle（main.bba88759.js）里读写的那几个键：
 * 服务端在 data.list[0] 上给一份「这个链接里有什么」的清单。
 */
@Serializable
internal data class OfflineResolveData(
    val list: List<OfflineResolveItem>? = null,
)

/**
 * 解析出的单个资源。
 *
 * [errCode] 是**资源级**的错误码：外层 code 是 0 也可能因为链接失效、
 * 需要会员等原因在这一层报错，两层都要看。
 */
@Serializable
internal data class OfflineResolveItem(
    val id: JsonElement? = null,
    val name: String? = null,
    @SerialName("file_nums") val fileNums: Int? = null,
    val url: String? = null,
    val hash: String? = null,
    @SerialName("err_code") val errCode: JsonElement? = null,
    @SerialName("err_msg") val errMsg: String? = null,
    val files: List<OfflineFileDto>? = null,
)

/**
 * 资源里的一个条目。
 *
 * [fileType] 是**图标分类**（图片/视频/音频…），不是「是不是目录」，
 * 所以这里不拿它推断目录——bundle 里它只被塞进文件列表的 type 字段。
 */
@Serializable
internal data class OfflineFileDto(
    val id: JsonElement? = null,
    val name: String? = null,
    val size: Long? = null,
    @SerialName("file_type") val fileType: JsonElement? = null,
    val category: JsonElement? = null,
    val format: String? = null,
)


/** 任务列表的外壳：data.list + data.total。 */
@Serializable
internal data class OfflineTaskListData(
    @SerialName("list") val list: List<OfflineTaskDto>? = null,
    @SerialName("total") val total: Int? = null,
)

/**
 * 一条离线任务。字段名小写驼峰来自 OpenList drivers/123 的 offlineTask；
 * progress 服务端已经算成 0..100 的浮点。
 */
@Serializable
internal data class OfflineTaskDto(
    @SerialName("task_id") val taskId: JsonElement? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("status") val status: Int? = null,
    @SerialName("size") val size: Long? = null,
    @SerialName("downloaded") val downloaded: Long? = null,
    @SerialName("progress") val progress: Double? = null,
    @SerialName("speed") val speed: Long? = null,
)