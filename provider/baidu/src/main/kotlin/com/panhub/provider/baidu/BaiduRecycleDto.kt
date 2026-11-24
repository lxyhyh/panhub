package com.panhub.provider.baidu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 回收站接口的响应。
 *
 * 列表条目与文件列表长得几乎一样（同一个 path 寻址体系），多的两处是
 * `leftTime`（还剩多久自动清理，秒数；0 表示不会自动清理）与 `delete_type`。
 * 时间戳有些条目回秒、有些回毫秒（老数据），统一在映射处按量级归一。
 */
@Serializable
internal data class BaiduRecycleListResp(
    @SerialName("errno") val errno: Int = 0,
    @SerialName("errmsg") val errmsg: String? = null,
    @SerialName("show_msg") val showMsg: String? = null,
    @SerialName("list") val list: List<BaiduRecycleItemDto>? = null,
)

@Serializable
internal data class BaiduRecycleItemDto(
    @SerialName("fs_id") val fsId: Long? = null,
    @SerialName("path") val path: String? = null,
    @SerialName("server_filename") val serverFilename: String? = null,
    @SerialName("size") val size: Long = 0L,
    @SerialName("isdir") val isdir: Int = 0,
    @SerialName("server_mtime") val serverMtime: Long? = null,
    @SerialName("server_ctime") val serverCtime: Long? = null,
    @SerialName("leftTime") val leftTime: Long = 0L,
    @SerialName("delete_type") val deleteType: Int = 0,
) {
    val isDir: Boolean get() = isdir == 1
}

/**
 * 还原 / 清空的响应。errno == 0 时 `faillist` 里列出没成功的 fs_id——
 * 服务端把「部分失败」也算成功，这里必须自己检查。
 */
@Serializable
internal data class BaiduRecycleOperaResp(
    @SerialName("errno") val errno: Int = 0,
    @SerialName("errmsg") val errmsg: String? = null,
    @SerialName("show_msg") val showMsg: String? = null,
    @SerialName("faillist") val failList: List<Long>? = null,
    @SerialName("taskid") val taskId: Long? = null,
)