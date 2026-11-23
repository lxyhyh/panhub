package com.panhub.provider.quark

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 「打开别人的分享并转存」用到的响应体。
 *
 * 与 [QuarkShareCreateData] 那一套分开：创建分享是自己往外发，
 * 这里是往里收，字段与流程都不重合，混在一个文件里只会让人以为它们是同一件事。
 *
 * 事实来源：qrypt pkg/drivers/quark/share.go 的 OpenShare / ListShareEntries /
 * SaveShareEntries（Go 实现，用真实账号跑通）。
 */
@Serializable
internal data class QuarkShareTokenData(
    @SerialName("stoken") val stoken: String? = null,
    @SerialName("url_type") val urlType: Int? = null,
    @SerialName("expired_at") val expiredAt: Long? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("share_type") val shareType: Int? = null,
    @SerialName("author") val author: QuarkShareAuthor? = null,
)

@Serializable
internal data class QuarkShareAuthor(
    @SerialName("nick_name") val nickName: String? = null,
    @SerialName("member_type") val memberType: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
internal data class QuarkShareDetailData(
    @SerialName("is_owner") val isOwner: Boolean? = null,
    @SerialName("share") val share: QuarkShareTokenData? = null,
    @SerialName("list") val list: List<QuarkShareEntryDto>? = null,
)

/**
 * 分享里的一个条目。
 *
 * [shareFidToken] 是这一项在分享内的取件凭据，转存时必须原样回传。
 * 它跟 fid 不是一回事：fid 是分享方网盘里的 id，你拿它去调自己的接口只会 404。
 */
@Serializable
internal data class QuarkShareEntryDto(
    @SerialName("fid") val fid: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("pdir_fid") val pdirFid: String? = null,
    @SerialName("dir") val dir: Boolean? = null,
    @SerialName("size") val size: Long? = null,
    @SerialName("format_type") val formatType: String? = null,
    @SerialName("share_fid_token") val shareFidToken: String? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
    @SerialName("file_type") val fileType: Int? = null,
)

@Serializable
internal data class QuarkShareSaveData(
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("task_sync") val taskSync: Boolean? = null,
    @SerialName("task_resp") val taskResp: QuarkShareSaveTaskResp? = null,
)

@Serializable
internal data class QuarkShareSaveTaskResp(
    @SerialName("data") val data: QuarkShareSaveTask? = null,
)
/**
 * 转存任务的返回。
 *
 * 与 [QuarkShareTask] 分开：创建分享的任务只关心 share_id，
 * 转存的任务只关心 save_as 里的目标 fid 列表，两个字段集几乎不重叠。
 */
@Serializable
internal data class QuarkShareSaveTask(
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("status") val status: Int? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("save_as") val saveAs: QuarkShareSaveAs? = null,
)

/**
 * 转存完成后的落点信息。
 *
 * [topFids] 是新生成在目标目录里的 fid，最多 100 个；转存数量超过 100 时它会被截断，
 * 所以计数只能用「用户选了几项」或 [sumNum]，不能拿它的大小当成功数。
 */
@Serializable
internal data class QuarkShareSaveAs(
    @SerialName("save_as_top_fids") val topFids: List<String>? = null,
    @SerialName("save_as_select_top_fids") val selectTopFids: List<String>? = null,
    @SerialName("to_pdir_fid") val toPdirFid: String? = null,
    @SerialName("to_pdir_name") val toPdirName: String? = null,
    @SerialName("save_as_sum_num") val sumNum: Int? = null,
)
