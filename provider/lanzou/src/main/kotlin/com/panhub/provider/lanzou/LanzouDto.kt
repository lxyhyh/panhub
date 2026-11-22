package com.panhub.provider.lanzou

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 蓝奏云（`pc.woozooo.com`）的响应全部是「一个 zt 状态位 + 若干平铺字段」，
 * 而且同一个 `doupload.php` 端点靠 `task` 参数区分动作，字段名随 task 变化。
 *
 * 因此这里不写「一个 task 一个 DTO」，而是用宽松的 envelope：需要哪些字段
 * 就在 `decode` 里按名字取。线上任何一个字段缺失都不应该让整页崩掉。
 */
@Serializable
internal data class LanzouEnvelope(
    /** "1" 表示成功；其他值表示失败。 */
    val zt: String? = null,
    val info: String? = null,
    val text: List<LanzouFileDto>? = null,
)

/**
 * 文件条目（task 5 的 `text` 数组元素）。
 *
 * [id] 是字符串而不是数字：蓝奏云会用 `-1` 表示被屏蔽的条目，也会直接
 * 返回 `http...` 开头的值。用数字会解析失败并把整页打掉。
 */
@Serializable
internal data class LanzouFileDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name_all") val nameAll: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("size") val size: String? = null,
    @SerialName("time") val time: String? = null,
    @SerialName("downs") val downs: String? = null,
    @SerialName("onof") val onof: String? = null,
)

/**
 * 文件夹条目。task 47 的 `text` 数组里是文件夹，字段名与文件不同
 * （`fol_id` / `folder_des`），所以单独一个 DTO 而不是复用上面的。
 */
@Serializable
internal data class LanzouFolderDto(
    @SerialName("fol_id") val folId: String? = null,
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("folder_des") val folderDes: String? = null,
)

/** 文件夹列表响应。`info` 在这个 task 里是路径数组而不是字符串，不能复用上面的 envelope。 */
@Serializable
internal data class LanzouFolderEnvelope(
    val zt: String? = null,
    val info: List<LanzouPathDto>? = null,
    val text: List<LanzouFolderDto>? = null,
)

/** 面包屑的一节。 */
@Serializable
internal data class LanzouPathDto(
    @SerialName("folderid") val folderId: String? = null,
    val name: String? = null,
)

/** `ajaxm.php` / `ajaxfile.php` 返回的下载分段信息。 */
@Serializable
internal data class LanzouDownloadDto(
    val zt: String? = null,
    val dom: String? = null,
    val url: String? = null,
    val inf: String? = null,
)

/** task 22（文件分享信息）的 `info` 对象。 */
@Serializable
internal data class LanzouShareInfoDto(
    @SerialName("is_newd") val isNewd: String? = null,
    @SerialName("f_id") val fId: String? = null,
    val onof: String? = null,
    val pwd: String? = null,
    val name: String? = null,
)
