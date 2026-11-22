package com.panhub.provider.webdav

import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.common.map
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodePage
import com.panhub.core.model.PageRequest
import com.panhub.core.model.ProviderCapabilities
import com.panhub.core.model.ProviderDescriptor
import com.panhub.core.model.ProviderId
import com.panhub.core.model.Quota
import com.panhub.core.model.RemoteNode
import com.panhub.core.model.ShareLink
import com.panhub.core.model.ShareOptions
import com.panhub.core.network.HttpEngine
import com.panhub.provider.api.DownloadTarget
import com.panhub.provider.api.PanProvider
import com.panhub.provider.api.ProviderCredentials
import com.panhub.provider.api.ProviderSession

/**
 * 坚果云：官方 WebDAV 接入。
 *
 * 为什么先做它：协议标准、不需要逆向、不会随官方 App 更新而失效。
 * 分享功能坚果云 WebDAV 不提供，所以 capabilities.share = false，
 * UI 会自动隐藏分享按钮。
 */
class NutstoreProvider(
    private val engine: HttpEngine = HttpEngine(),
    private val baseUrl: String = DEFAULT_BASE_URL,
) : PanProvider {

    override val descriptor = ProviderDescriptor(
        id = ProviderId(ID),
        displayName = "坚果云",
        capabilities = ProviderCapabilities(
            list = true,
            quota = true,
            upload = true,
            download = true,
            rename = true,
            move = true,
            delete = true,
            createFolder = true,
            share = false,
        ),
    )

    override suspend fun login(credentials: ProviderCredentials): PanResult<ProviderSession> {
        val password = credentials as? ProviderCredentials.Password
            ?: return PanResult.Failure(PanError.notSupported("坚果云请使用邮箱 + 应用密码登录"))

        val client = WebDavClient(engine, baseUrl, password.username, password.password)
        return when (val probe = client.propfind("", depth = "0")) {
            is PanResult.Failure -> PanResult.Failure(probe.error)
            is PanResult.Success -> PanResult.Success(
                ProviderSession(
                    accountId = AccountId("${ID}:${password.username}"),
                    credentials = password,
                    displayName = password.username,
                )
            )
        }
    }

    override suspend fun verify(session: ProviderSession): PanResult<Unit> {
        val client = clientFor(session) ?: return PanResult.Failure(PanError.auth())
        return client.propfind("", depth = "0").map { }
    }

    override suspend fun list(
        session: ProviderSession,
        parentId: NodeId?,
        page: PageRequest,
    ): PanResult<NodePage> {
        val client = clientFor(session) ?: return PanResult.Failure(PanError.auth())
        val path = parentId?.value.orEmpty()
        return client.propfind(path, depth = "1").map { xml ->
            // WebDAV 没有分页，一次返回全部；nextCursor 永远为 null。
            NodePage(items = WebDavXml.parseList(xml, selfHref = path.ifBlank { "" }), nextCursor = null)
        }
    }

    override suspend fun stat(session: ProviderSession, nodeId: NodeId): PanResult<RemoteNode> {
        val client = clientFor(session) ?: return PanResult.Failure(PanError.auth())
        return client.propfind(nodeId.value, depth = "0").map { xml ->
            WebDavXml.parseList(xml, selfHref = null).firstOrNull()
                ?: throw IllegalStateException("empty propfind response")
        }.let { result ->
            when (result) {
                is PanResult.Failure -> PanResult.Failure(
                    if (result.error.kind == com.panhub.core.common.ErrorKind.UNKNOWN) {
                        PanError.notFound()
                    } else result.error
                )
                is PanResult.Success -> result
            }
        }
    }

    override suspend fun quota(session: ProviderSession): PanResult<Quota> {
        val client = clientFor(session) ?: return PanResult.Failure(PanError.auth())
        return client.propfind("", depth = "0").map { xml ->
            WebDavXml.parseQuota(xml) ?: Quota(used = 0L, total = null)
        }
    }

    override suspend fun createFolder(
        session: ProviderSession,
        parentId: NodeId?,
        name: String,
    ): PanResult<RemoteNode> {
        val client = clientFor(session) ?: return PanResult.Failure(PanError.auth())
        val path = join(parentId?.value, name)
        return client.mkcol(path).map {
            RemoteNode(
                id = NodeId("/$path"),
                parentId = parentId,
                name = name,
                kind = com.panhub.core.model.NodeKind.DIR,
                providerPath = "/$path",
            )
        }
    }

    override suspend fun rename(
        session: ProviderSession,
        nodeId: NodeId,
        newName: String,
    ): PanResult<RemoteNode> {
        val client = clientFor(session) ?: return PanResult.Failure(PanError.auth())
        val source = nodeId.value
        val parent = source.substringBeforeLast('/', "")
        val target = join(parent, newName)
        return client.move(source, target).map {
            RemoteNode(
                id = NodeId("/$target"),
                parentId = null,
                name = newName,
                kind = com.panhub.core.model.NodeKind.FILE,
                providerPath = "/$target",
            )
        }
    }

    override suspend fun move(
        session: ProviderSession,
        nodeId: NodeId,
        newParentId: NodeId?,
    ): PanResult<Unit> {
        val client = clientFor(session) ?: return PanResult.Failure(PanError.auth())
        val name = nodeId.value.substringAfterLast('/')
        return client.move(nodeId.value, join(newParentId?.value, name))
    }

    override suspend fun delete(session: ProviderSession, nodeId: NodeId): PanResult<Unit> {
        val client = clientFor(session) ?: return PanResult.Failure(PanError.auth())
        return client.delete(nodeId.value)
    }

    override suspend fun downloadUrl(
        session: ProviderSession,
        nodeId: NodeId,
    ): PanResult<DownloadTarget> {
        val password = session.credentials as? ProviderCredentials.Password
            ?: return PanResult.Failure(PanError.auth())
        val path = nodeId.value
        val encoded = path.split('/').filter { it.isNotBlank() }
            .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val url = baseUrl.trimEnd('/') + "/" + encoded
        return PanResult.Success(
            DownloadTarget(
                url = url,
                headers = mapOf(
                    "Authorization" to okhttp3.Credentials.basic(
                        password.username,
                        password.password,
                        Charsets.UTF_8,
                    ),
                ),
                fileName = path.substringAfterLast('/'),
            )
        )
    }

    override suspend fun createShare(
        session: ProviderSession,
        nodeIds: List<NodeId>,
        options: ShareOptions,
    ): PanResult<ShareLink> =
        PanResult.Failure(PanError.notSupported("坚果云 WebDAV 不提供分享接口"))

    override suspend fun cancelShare(session: ProviderSession, shareId: String): PanResult<Unit> =
        PanResult.Failure(PanError.notSupported("坚果云 WebDAV 不提供分享接口"))

    private fun clientFor(session: ProviderSession): WebDavClient? {
        val password = session.credentials as? ProviderCredentials.Password ?: return null
        return WebDavClient(engine, baseUrl, password.username, password.password)
    }

    private fun join(parent: String?, name: String): String {
        val cleanParent = parent.orEmpty().trim('/')
        return if (cleanParent.isEmpty()) name else "$cleanParent/$name"
    }

    companion object {
        const val ID = "nutstore"
        const val DEFAULT_BASE_URL = "https://dav.jianguoyun.com/dav/"
    }
}