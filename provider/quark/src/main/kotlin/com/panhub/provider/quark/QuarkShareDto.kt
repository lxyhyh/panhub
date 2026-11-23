package com.panhub.provider.quark

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 分享相关的响应体。
 *
 * 字段名照抄服务端（下划线命名），这一层不做业务判断：
 * 哪些字段能缺、缺了怎么兜底，统一交给 [QuarkShareParsing]。
 *
 * 事实来源：qrypt 的 pkg/drivers/quark/share.go（Go 实现，用真实账号跑通）与
 * QuarkPanTool 的 quark.py。两者字段一致，差异只在一个多传 support_error_code。
 */
@Serializable
internal data class QuarkShareCreateData(
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("task_sync") val taskSync: Boolean? = null,
    @SerialName("task_resp") val taskResp: QuarkShareTaskResp? = null,
)

@Serializable
internal data class QuarkShareTaskResp(
    @SerialName("data") val data: QuarkShareTask? = null,
)

/**
 * 异步任务的当前状态。
 *
 * [status] 的取值：2 表示完成，3 表示失败，其余表示还在排队或处理中。
 * 分享是异步生成的，创建接口只返回 task_id，真正的 share_id 要轮询到这里才有。
 */
@Serializable
internal data class QuarkShareTask(
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("status") val status: Int? = null,
    @SerialName("share_id") val shareId: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
)

/** /share/password 的返回：这里才有真正能发出去的链接与提取码。 */
@Serializable
internal data class QuarkSharePasswordData(
    @SerialName("share_id") val shareId: String? = null,
    @SerialName("pwd_id") val pwdId: String? = null,
    @SerialName("share_url") val shareUrl: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("url_type") val urlType: Int? = null,
    @SerialName("passcode") val passcode: String? = null,
    @SerialName("expired_at") val expiredAt: Long? = null,
    @SerialName("file_num") val fileNum: Int? = null,
)

@Serializable
internal data class QuarkShareListData(
    @SerialName("list") val list: List<QuarkShareItem>? = null,
)

/**
 * 分享列表里的一条。
 *
 * [status] 保留但**不参与失效判断**：没有找到可靠证据说明它的取值含义，
 * 猜一个「非 0 即失效」会把正常分享标成失效。失效只看 [expiredAt]。
 */
@Serializable
internal data class QuarkShareItem(
    @SerialName("share_id") val shareId: String? = null,
    @SerialName("pwd_id") val pwdId: String? = null,
    @SerialName("share_url") val shareUrl: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("url_type") val urlType: Int? = null,
    @SerialName("passcode") val passcode: String? = null,
    @SerialName("expired_at") val expiredAt: Long? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("file_num") val fileNum: Int? = null,
    @SerialName("status") val status: Int? = null,
)
