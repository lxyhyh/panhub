package com.panhub.provider.cloud189

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 官方客户端通道（api.cloud.189.cn）独有的两个响应结构。
 *
 * 列目录、容量、批量任务、取下载地址这几个接口的响应外壳与网页 open 接口
 * 完全一样（res_code 加 fileListAO / cloudCapacityInfo / taskId / fileDownloadUrl），
 * 所以直接复用 [C189Envelope]，这里只补 App 通道独有的两条：
 * 换会话的 /getSessionForPC.action 与刷新 token 的 refreshToken.do。
 */
@Serializable
internal data class C189AppSessionDto(
    @SerialName("res_code") val resCode: JsonElement? = null,
    @SerialName("res_message") val resMessage: String? = null,
    @SerialName("loginName") val loginName: String? = null,
    @SerialName("keepAlive") val keepAlive: Int? = null,
    @SerialName("sessionKey") val sessionKey: String? = null,
    @SerialName("sessionSecret") val sessionSecret: String? = null,
    @SerialName("familySessionKey") val familySessionKey: String? = null,
    @SerialName("familySessionSecret") val familySessionSecret: String? = null,
    @SerialName("accessToken") val accessToken: String? = null,
    @SerialName("refreshToken") val refreshToken: String? = null,
)

/**
 * 刷新 token 的响应。
 *
 * 这个接口失败时也回 HTTP 200，只在 body 里给 result 与 msg，
 * 所以判据是 result != 0 或 accessToken 为空，不能看状态码。
 */
@Serializable
internal data class C189AppRefreshDto(
    @SerialName("accessToken") val accessToken: String? = null,
    @SerialName("refreshToken") val refreshToken: String? = null,
    @SerialName("expiresIn") val expiresIn: Int? = null,
    @SerialName("result") val result: Int? = null,
    @SerialName("msg") val msg: String? = null,
)
