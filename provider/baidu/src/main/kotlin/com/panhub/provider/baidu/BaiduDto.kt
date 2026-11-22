package com.panhub.provider.baidu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 百度网盘网页接口的响应。
 *
 * 这些接口没有统一的信封，几乎都是「errno + 业务字段」：errno == 0 才是成功，
 * 非 0 时 errmsg / show_msg 里是给人看的原因。所以这里不做泛型信封，
 * 每种响应各自声明，判成功统一在 BaiduApi.checkErrno 里。
 */
@Serializable
internal data class BaiduListResp(
    @SerialName("errno") val errno: Int = 0,
    @SerialName("errmsg") val errmsg: String? = null,
    @SerialName("show_msg") val showMsg: String? = null,
    @SerialName("list") val list: List<BaiduFileDto>? = null,
    @SerialName("request_id") val requestId: String? = null,
)

@Serializable
internal data class BaiduFileDto(
    @SerialName("fs_id") val fsId: Long? = null,
    @SerialName("path") val path: String? = null,
    @SerialName("server_filename") val serverFilename: String? = null,
    @SerialName("size") val size: Long = 0L,
    @SerialName("isdir") val isdir: Int = 0,
    @SerialName("server_mtime") val serverMtime: Long? = null,
    @SerialName("server_ctime") val serverCtime: Long? = null,
    @SerialName("category") val category: Int = 0,
    @SerialName("md5") val md5: String? = null,
    @SerialName("thumbs") val thumbs: BaiduThumbsDto? = null,
) {
    val isDir: Boolean get() = isdir == 1
}

@Serializable
internal data class BaiduThumbsDto(
    @SerialName("url3") val url3: String? = null,
)

@Serializable
internal data class BaiduQuotaResp(
    @SerialName("errno") val errno: Int = 0,
    @SerialName("errmsg") val errmsg: String? = null,
    @SerialName("total") val total: Long = 0L,
    @SerialName("used") val used: Long = 0L,
    @SerialName("free") val free: Long = 0L,
)

@Serializable
internal data class BaiduFileMetasResp(
    @SerialName("errno") val errno: Int = 0,
    @SerialName("errmsg") val errmsg: String? = null,
    @SerialName("info") val info: List<BaiduMetaDto>? = null,
)

@Serializable
internal data class BaiduMetaDto(
    @SerialName("fs_id") val fsId: Long? = null,
    @SerialName("path") val path: String? = null,
    @SerialName("dlink") val dlink: String? = null,
    @SerialName("size") val size: Long = 0L,
)

@Serializable
internal data class BaiduTemplateResp(
    @SerialName("errno") val errno: Int = 0,
    @SerialName("result") val result: List<BaiduTemplateVar>? = null,
)

@Serializable
internal data class BaiduTemplateVar(
    @SerialName("bdstoken") val bdstoken: String? = null,
)

@Serializable
internal data class BaiduCreateResp(
    @SerialName("errno") val errno: Int = 0,
    @SerialName("errmsg") val errmsg: String? = null,
    @SerialName("path") val path: String? = null,
    @SerialName("fs_id") val fsId: Long? = null,
    @SerialName("isdir") val isdir: Int = 0,
)

@Serializable
internal data class BaiduShareResp(
    @SerialName("errno") val errno: Int = 0,
    @SerialName("errmsg") val errmsg: String? = null,
    @SerialName("show_msg") val showMsg: String? = null,
    @SerialName("link") val link: String? = null,
    @SerialName("shorturl") val shortUrl: String? = null,
    @SerialName("pwd") val pwd: String? = null,
    @SerialName("shareid") val shareId: Long? = null,
)

/** 登录态探活：/api/quota 能通说明 Cookie 有效。 */
@Serializable
internal data class BaiduUserInfoResp(
    @SerialName("errno") val errno: Int = 0,
    @SerialName("errmsg") val errmsg: String? = null,
    @SerialName("baidu_name") val baiduName: String? = null,
    @SerialName("netdisk_name") val netdiskName: String? = null,
    @SerialName("uk") val uk: Long? = null,
)
