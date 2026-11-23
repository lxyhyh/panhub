package com.panhub.provider.baidu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 预上传（precreate）的响应。
 *
 * return_type 决定接下来做什么：
 * - 1：正常走分片上传，返回 uploadid 与 block_list（每片的状态，0 表示还没传）；
 * - 2：服务端已有同内容文件，秒传成功，info 里直接是新文件的元数据。
 */
@Serializable
internal data class BaiduPrecreateResp(
    @SerialName("errno") val errno: Int = 0,
    @SerialName("errmsg") val errmsg: String? = null,
    @SerialName("show_msg") val showMsg: String? = null,
    @SerialName("return_type") val returnType: Int = 0,
    @SerialName("uploadid") val uploadId: String? = null,
    @SerialName("block_list") val blockList: List<Int>? = null,
    @SerialName("path") val path: String? = null,
    @SerialName("info") val info: BaiduUploadFile? = null,
)

/** 秒传与 create 都返回这个结构。 */
@Serializable
internal data class BaiduUploadFile(
    @SerialName("fs_id") val fsId: Long? = null,
    @SerialName("path") val path: String? = null,
    @SerialName("server_filename") val serverFilename: String? = null,
    @SerialName("size") val size: Long = 0L,
    @SerialName("isdir") val isdir: Int = 0,
    @SerialName("md5") val md5: String? = null,
    @SerialName("ctime") val ctime: Long? = null,
    @SerialName("mtime") val mtime: Long? = null,
)

/** 上传域名接口。不传 Cookie 也能拿到，只是域名可能不是最优节点。 */
@Serializable
internal data class BaiduLocateUploadResp(
    @SerialName("error_code") val errorCode: Int = 0,
    @SerialName("error_msg") val errorMsg: String? = null,
    @SerialName("host") val host: String? = null,
    @SerialName("servers") val servers: List<BaiduUploadServer>? = null,
    @SerialName("bak_servers") val bakServers: List<BaiduUploadServer>? = null,
    @SerialName("expire") val expire: Int? = null,
)

@Serializable
internal data class BaiduUploadServer(
    @SerialName("server") val server: String? = null,
)

/** 分片上传的响应：成功时两个字段都是 0，失败时可能只出现其中一个。 */
@Serializable
internal data class BaiduSliceResp(
    @SerialName("error_code") val errorCode: Int = 0,
    @SerialName("error_msg") val errorMsg: String? = null,
    @SerialName("errno") val errno: Int = 0,
    @SerialName("md5") val md5: String? = null,
)
