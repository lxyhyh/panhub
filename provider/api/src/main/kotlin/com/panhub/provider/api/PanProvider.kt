package com.panhub.provider.api

import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodePage
import com.panhub.core.model.PageRequest
import com.panhub.core.model.ProviderDescriptor
import com.panhub.core.model.Quota
import com.panhub.core.model.RemoteNode
import com.panhub.core.model.ShareLink
import com.panhub.core.model.SharePage
import com.panhub.core.model.ShareOptions

/**
 * 一个网盘服务商的全部能力。
 *
 * 设计约束（深模块化的关键）：
 * 1. 只暴露这一层接口，任何 HTTP/DTO/签名细节都不许泄漏到 feature 层；
 * 2. 所有方法返回 [PanResult]，不抛业务异常；
 * 3. 不支持的操作返回 [com.panhub.core.common.ErrorKind.UNSUPPORTED]，
 *    调用方可以先用 [ProviderCapabilities] 判断，避免无谓的调用；
 * 4. 新增服务商 = 实现这个接口 + 注册进 ProviderRegistry，不改动 UI。
 */
interface PanProvider {

    /** 静态描述，用于列表展示与能力判断。 */
    val descriptor: ProviderDescriptor

    /**
     * 用一组凭据登录并返回可持久化的会话。
     * 对于 WebDAV 这类无状态服务，直接校验一次 PROPFIND 即可。
     */
    suspend fun login(credentials: ProviderCredentials): PanResult<ProviderSession>

    /** 用已保存的会话做一次轻量校验，判断是否还有效。 */
    suspend fun verify(session: ProviderSession): PanResult<Unit>

    /** 列出某个目录下的节点；[parentId] 为 null 表示根目录。 */
    suspend fun list(
        session: ProviderSession,
        parentId: NodeId?,
        page: PageRequest = PageRequest(),
    ): PanResult<NodePage>

    /** 取单个节点的详细信息。 */
    suspend fun stat(session: ProviderSession, nodeId: NodeId): PanResult<RemoteNode>

    /** 容量信息；不支持时返回 UNSUPPORTED。 */
    suspend fun quota(session: ProviderSession): PanResult<Quota>

    /** 新建文件夹，返回新节点。 */
    suspend fun createFolder(
        session: ProviderSession,
        parentId: NodeId?,
        name: String,
    ): PanResult<RemoteNode>

    /** 重命名。 */
    suspend fun rename(
        session: ProviderSession,
        nodeId: NodeId,
        newName: String,
    ): PanResult<RemoteNode>

    /** 移动到新的父目录。 */
    suspend fun move(
        session: ProviderSession,
        nodeId: NodeId,
        newParentId: NodeId?,
    ): PanResult<Unit>

    /** 删除（进回收站或直接删除，由实现决定，见 capabilities.trash）。 */
    suspend fun delete(session: ProviderSession, nodeId: NodeId): PanResult<Unit>

    /** 拿到一个可下载的 URL 及其必需的请求头。 */
    suspend fun downloadUrl(session: ProviderSession, nodeId: NodeId): PanResult<DownloadTarget>

    /**
     * 上传一个文件到 [parentId] 目录。
     *
     * 默认实现返回 UNSUPPORTED，这样已有的 provider 不用改一行代码，
     * 未实现上传的服务商也不会因为漏写方法而编译失败。
     */
    suspend fun upload(
        session: ProviderSession,
        parentId: NodeId?,
        source: UploadSource,
        listener: UploadProgress = UploadProgress.NONE,
    ): PanResult<RemoteNode> = PanResult.Failure(PanError.notSupported())

    /** 上传进度回调。默认空实现，调用方可以不给。 */
    fun interface UploadProgress {
        /** [sent] 已发送字节，[total] 总字节。 */
        suspend fun onProgress(sent: Long, total: Long)

        companion object {
            val NONE = UploadProgress { _, _ -> }
        }
    }

    /** 创建分享链接。 */
    suspend fun createShare(
        session: ProviderSession,
        nodeIds: List<NodeId>,
        options: ShareOptions = ShareOptions(),
    ): PanResult<ShareLink>

    /** 取消分享。 */
    suspend fun cancelShare(session: ProviderSession, shareId: String): PanResult<Unit>

    /**
     * 列出这个会话已经创建过的分享。
     *
     * 默认返回 UNSUPPORTED：只有少数服务商提供类似接口，
     * 让未实现的服务商保持沉默，而不是让调用方先猜能力再调用。
     */
    suspend fun listShares(
        session: ProviderSession,
        page: PageRequest = PageRequest(),
    ): PanResult<SharePage> = PanResult.Failure(PanError.notSupported("该网盘不支持查看分享列表"))
}

/** 一次下载所需的一切：URL + 必需的头（很多网盘校验 UA/Referer）。 */
data class DownloadTarget(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val fileName: String? = null,
    val size: Long? = null,
)

/** 登录凭据。用密封接口，每种登录方式一个子类。 */
sealed interface ProviderCredentials {
    /** 用户名 + 密码（WebDAV、部分网盘）。 */
    data class Password(val username: String, val password: String) : ProviderCredentials

    /** 直接给一个已经拿到的令牌（Cookie / access_token / refresh_token）。 */
    data class Token(
        val accessToken: String,
        val refreshToken: String? = null,
        val extra: Map<String, String> = emptyMap(),
    ) : ProviderCredentials
}

/** 已建立的会话，必须可序列化以便持久化。 */
data class ProviderSession(
    val accountId: AccountId,
    val credentials: ProviderCredentials,
    val displayName: String? = null,
    val extra: Map<String, String> = emptyMap(),
)