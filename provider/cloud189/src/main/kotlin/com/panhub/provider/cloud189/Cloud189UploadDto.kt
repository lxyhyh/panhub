package com.panhub.provider.cloud189

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * upload.cloud.189.cn 的响应外壳。
 *
 * 注意它和 open 接口不是一套信封：这里是 code == "SUCCESS"，
 * 那边是 res_code == 0。混用会把成功当失败。
 */
@Serializable
internal data class C189UploadEnvelope(
    @SerialName("code") val code: String? = null,
    @SerialName("msg") val msg: String? = null,
    @SerialName("data") val data: C189UploadData? = null,
    @SerialName("uploadUrls") val uploadUrls: Map<String, C189UploadUrl>? = null,
)

@Serializable
internal data class C189UploadData(
    @SerialName("uploadFileId") val uploadFileId: String? = null,
    /** 1 表示服务端已有同内容文件，可以直接提交，不用传分片。 */
    @SerialName("fileDataExists") val fileDataExists: Int? = null,
)

@Serializable
internal data class C189UploadUrl(
    @SerialName("requestURL") val requestUrl: String? = null,
    /** 形如 Content-Type=...&x-amz-...，整体做过一次 URL 编码。 */
    @SerialName("requestHeader") val requestHeader: String? = null,
)

/** generateRsaKey.action 的响应。 */
@Serializable
internal data class C189RsaKeyDto(
    @SerialName("pubKey") val pubKey: String? = null,
    @SerialName("pkId") val pkId: String? = null,
    @SerialName("expire") val expire: Long? = null,
)
