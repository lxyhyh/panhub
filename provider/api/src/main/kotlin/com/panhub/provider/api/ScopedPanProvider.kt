package com.panhub.provider.api

import com.panhub.core.common.PanResult
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodePage
import com.panhub.core.model.PageRequest
import com.panhub.core.model.Quota
import com.panhub.core.model.RemoteNode
import com.panhub.core.model.ShareLink
import com.panhub.core.model.SharePage
import com.panhub.core.model.ShareOptions

/**
 * 把「服务商 + 会话」绑定在一起的便捷视图。
 *
 * feature 层拿到的就是这个，不需要每次都传 session，
 * 也不会误拿到别的账号的会话。
 */
class ScopedPanProvider(
    private val provider: PanProvider,
    private val session: ProviderSession,
) {
    val descriptor get() = provider.descriptor

    suspend fun list(parentId: NodeId?, page: PageRequest = PageRequest()): PanResult<NodePage> =
        provider.list(session, parentId, page)

    suspend fun stat(nodeId: NodeId): PanResult<RemoteNode> = provider.stat(session, nodeId)

    suspend fun quota(): PanResult<Quota> = provider.quota(session)

    suspend fun createFolder(parentId: NodeId?, name: String): PanResult<RemoteNode> =
        provider.createFolder(session, parentId, name)

    suspend fun rename(nodeId: NodeId, newName: String): PanResult<RemoteNode> =
        provider.rename(session, nodeId, newName)

    suspend fun move(nodeId: NodeId, newParentId: NodeId?): PanResult<Unit> =
        provider.move(session, nodeId, newParentId)

    suspend fun delete(nodeId: NodeId): PanResult<Unit> = provider.delete(session, nodeId)

    suspend fun downloadUrl(nodeId: NodeId): PanResult<DownloadTarget> =
        provider.downloadUrl(session, nodeId)

    suspend fun upload(
        parentId: NodeId?,
        source: UploadSource,
        listener: PanProvider.UploadProgress = PanProvider.UploadProgress.NONE,
    ): PanResult<RemoteNode> = provider.upload(session, parentId, source, listener)

    suspend fun createShare(
        nodeIds: List<NodeId>,
        options: ShareOptions = ShareOptions(),
    ): PanResult<ShareLink> = provider.createShare(session, nodeIds, options)

    suspend fun cancelShare(shareId: String): PanResult<Unit> =
        provider.cancelShare(session, shareId)

    suspend fun search(query: String, page: PageRequest = PageRequest()): PanResult<NodePage> =
        provider.search(session, query, page)

    suspend fun listShares(page: PageRequest = PageRequest()): PanResult<SharePage> =
        provider.listShares(session, page)
}