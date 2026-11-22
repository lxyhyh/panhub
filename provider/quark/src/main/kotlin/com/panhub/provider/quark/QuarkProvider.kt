package com.panhub.provider.quark

import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.AuthMethod
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 夸克网盘实现。
 *
 * 端点事实来源：OpenList 的 drivers/quark_uc（not a copy of its code, just the
 * request shapes），本项目只接夸克品牌，UC 那套 host/UA/pr 差异不实现。
 *
 * 鉴权是网页会话 Cookie（__pus / __puus 等），没有可直传的 access token 接口，
 * 所以登录方式是粘贴 Cookie。服务端会在响应里轮换 __puus，轮换后的值缓存在
 * 进程内的 [renewedCookies] 里，否则会话会很快失效。
 *
 * 不声明分享能力：OpenList 里没有任何 share 相关实现，没有可信事实来源的路径不猜。
 */
class QuarkProvider(
    private val engine: HttpEngine = HttpEngine(),
) : PanProvider {

    private val api = QuarkApi(engine)

    /**
     * accountId -> 服务端轮换过的 Cookie 字段。
     *
     * 只存被轮换的那几个键（__puus / __pus），取用时合到会话里存的完整 Cookie 串上，
     * 这样用户下次还是要用完整 Cookie，而进程内一直是新鲜的。
     * 进程内有效，重启后靠服务端重新下发。
     */
    private val renewedCookies = ConcurrentHashMap<String, Map<String, String>>()

    override val descriptor = ProviderDescriptor(
        id = ProviderId(ID),
        displayName = "夸克网盘",
        authMethod = AuthMethod.TOKEN,
        authHint = "浏览器登录 pan.quark.cn 后，把整段 Cookie 粘贴进来",
        capabilities = ProviderCapabilities(
            list = true,
            quota = true,
            download = true,
            rename = true,
            move = true,
            delete = true,
            createFolder = true,
            directLink = true,
        ),
    )

    override suspend fun login(credentials: ProviderCredentials): PanResult<ProviderSession> {
        val cookie = when (credentials) {
            is ProviderCredentials.Token -> credentials.accessToken
            is ProviderCredentials.Password ->
                return PanResult.Failure(PanError.auth("夸克网盘只支持粘贴 Cookie 登录"))
        }.trim()

        if (cookie.isEmpty()) {
            return PanResult.Failure(PanError.auth("Cookie 不能为空"))
        }

        val accountId = AccountId("$ID:" + cookie.hashCode().toString())
        return when (val member = fetchMember(cookie)) {
            is PanResult.Failure -> PanResult.Failure(
                if (member.error.kind == com.panhub.core.common.ErrorKind.AUTH) member.error
                else PanError.auth("Cookie 无效或已过期"),
            )
            is PanResult.Success -> {
                rememberRenewed(accountId.value, member.value.second)
                PanResult.Success(
                    ProviderSession(
                        accountId = accountId,
                        credentials = ProviderCredentials.Token(accessToken = cookie),
                        displayName = "夸克网盘",
                    ),
                )
            }
        }
    }

    override suspend fun verify(session: ProviderSession): PanResult<Unit> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        return when (val member = fetchMember(cookie)) {
            is PanResult.Failure -> PanResult.Failure(member.error)
            is PanResult.Success -> {
                rememberRenewed(session.accountId.value, member.value.second)
                PanResult.Success(Unit)
            }
        }
    }

    /** 取 /member：既用来探活，也用来读容量。返回 (数据, 轮换后的 Cookie)。 */
    private suspend fun fetchMember(cookie: String): PanResult<Pair<QuarkMemberData?, Map<String, String>>> {
        val raw = api.get(
            QuarkApi.PATH_MEMBER,
            cookie,
            listOf(
                "fetch_subscribe" to "false",
                "_ch" to "home",
                "fetch_identity" to "false",
            ),
        )
        return when (raw) {
            is PanResult.Failure -> raw
            is PanResult.Success -> when (
                val envelope = api.decode(raw.value, QuarkEnvelope.serializer(QuarkMemberData.serializer()))
            ) {
                is PanResult.Failure -> PanResult.Failure(envelope.error)
                is PanResult.Success -> PanResult.Success(envelope.value.data to raw.value.renewed)
            }
        }
    }

    override suspend fun list(
        session: ProviderSession,
        parentId: NodeId?,
        page: PageRequest,
    ): PanResult<NodePage> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        val pageIndex = page.cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val pageSize = if (page.limit in 1..MAX_PAGE_SIZE) page.limit else MAX_PAGE_SIZE
        val dirId = parentId?.value ?: QuarkApi.ROOT_ID

        val raw = api.get(
            QuarkApi.PATH_FILE_SORT,
            cookie,
            listOf(
                "pdir_fid" to dirId,
                "_page" to pageIndex.toString(),
                "_size" to pageSize.toString(),
                "_fetch_total" to "1",
                "fetch_all_file" to "1",
                "fetch_risk_file_name" to "1",
                "_sort" to "file_type:asc,file_name:asc",
            ),
        )

        return when (raw) {
            is PanResult.Failure -> raw
            is PanResult.Success -> {
                rememberRenewed(session.accountId.value, raw.value.renewed)
                when (
                    val envelope = api.decode(
                        raw.value,
                        QuarkEnvelope.serializer(QuarkFileListData.serializer()),
                    )
                ) {
                    is PanResult.Failure -> PanResult.Failure(envelope.error)
                    is PanResult.Success -> {
                        val items = envelope.value.data?.list.orEmpty().mapNotNull { dto ->
                            val fid = dto.fid ?: return@mapNotNull null
                            RemoteNode(
                                id = NodeId(fid),
                                parentId = NodeId(dirId),
                                name = QuarkParsing.displayName(dto),
                                kind = if (dto.file) NodeKind.FILE else NodeKind.DIR,
                                size = dto.size ?: 0L,
                                modifiedAt = dto.updatedAt,
                                createdAt = dto.createdAt,
                            )
                        }
                        val total = envelope.value.metadata?.total
                        val next = if (total != null) {
                            if (pageIndex * pageSize < total) (pageIndex + 1).toString() else null
                        } else {
                            // 没有总数时只能靠「本页是否满」判断，宁可多请求一页也不丢数据。
                            if (items.size >= pageSize) (pageIndex + 1).toString() else null
                        }
                        PanResult.Success(NodePage(items = items, nextCursor = next))
                    }
                }
            }
        }
    }

    override suspend fun stat(session: ProviderSession, nodeId: NodeId): PanResult<RemoteNode> =
        PanResult.Failure(PanError.notSupported("夸克网盘暂不支持查询单个文件详情"))

    override suspend fun quota(session: ProviderSession): PanResult<Quota> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        return when (val member = fetchMember(cookie)) {
            is PanResult.Failure -> member
            is PanResult.Success -> {
                rememberRenewed(session.accountId.value, member.value.second)
                val data = member.value.first
                PanResult.Success(
                    Quota(
                        used = data?.useCapacity ?: 0L,
                        total = data?.totalCapacity,
                    ),
                )
            }
        }
    }

    override suspend fun createFolder(
        session: ProviderSession,
        parentId: NodeId?,
        name: String,
    ): PanResult<RemoteNode> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        val dirId = parentId?.value ?: QuarkApi.ROOT_ID
        val body = buildJsonObject {
            put("dir_init_lock", JsonPrimitive(false))
            put("dir_path", JsonPrimitive(""))
            put("file_name", JsonPrimitive(name))
            put("pdir_fid", JsonPrimitive(dirId))
        }.toString()

        val raw = api.postJson(QuarkApi.PATH_FILE, cookie, body)
        return when (raw) {
            is PanResult.Failure -> raw
            is PanResult.Success -> {
                rememberRenewed(session.accountId.value, raw.value.renewed)
                when (
                    val envelope = api.decode(
                        raw.value,
                        QuarkEnvelope.serializer(QuarkCreateDirData.serializer()),
                    )
                ) {
                    is PanResult.Failure -> PanResult.Failure(envelope.error)
                    is PanResult.Success -> PanResult.Success(
                        RemoteNode(
                            id = NodeId(envelope.value.data?.fid ?: name),
                            parentId = NodeId(dirId),
                            name = name,
                            kind = NodeKind.DIR,
                        ),
                    )
                }
            }
        }
    }

    override suspend fun rename(
        session: ProviderSession,
        nodeId: NodeId,
        newName: String,
    ): PanResult<RemoteNode> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        val body = buildJsonObject {
            put("fid", JsonPrimitive(nodeId.value))
            put("file_name", JsonPrimitive(newName))
        }.toString()

        val raw = api.postJson(QuarkApi.PATH_FILE_RENAME, cookie, body)
        return when (raw) {
            is PanResult.Failure -> raw
            is PanResult.Success -> {
                rememberRenewed(session.accountId.value, raw.value.renewed)
                PanResult.Success(
                    RemoteNode(
                        id = nodeId,
                        parentId = null,
                        name = newName,
                        kind = NodeKind.FILE,
                    ),
                )
            }
        }
    }

    override suspend fun move(
        session: ProviderSession,
        nodeId: NodeId,
        newParentId: NodeId?,
    ): PanResult<Unit> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        val target = newParentId?.value ?: QuarkApi.ROOT_ID
        val body = buildJsonObject {
            put("action_type", JsonPrimitive(1))
            put("exclude_fids", buildJsonArray { })
            put("filelist", buildJsonArray { add(JsonPrimitive(nodeId.value)) })
            put("to_pdir_fid", JsonPrimitive(target))
        }.toString()
        return simplePost(QuarkApi.PATH_FILE_MOVE, cookie, body, session.accountId.value)
    }

    override suspend fun delete(session: ProviderSession, nodeId: NodeId): PanResult<Unit> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        val body = buildJsonObject {
            put("action_type", JsonPrimitive(1))
            put("exclude_fids", buildJsonArray { })
            put("filelist", buildJsonArray { add(JsonPrimitive(nodeId.value)) })
        }.toString()
        return simplePost(QuarkApi.PATH_FILE_DELETE, cookie, body, session.accountId.value)
    }

    /** 只关心成败、不关心返回体的那些 POST。 */
    private suspend fun simplePost(
        path: String,
        cookie: String,
        body: String,
        accountId: String,
    ): PanResult<Unit> = when (val raw = api.postJson(path, cookie, body)) {
        is PanResult.Failure -> raw
        is PanResult.Success -> {
            rememberRenewed(accountId, raw.value.renewed)
            PanResult.Success(Unit)
        }
    }

    override suspend fun downloadUrl(
        session: ProviderSession,
        nodeId: NodeId,
    ): PanResult<DownloadTarget> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        val body = buildJsonObject {
            put("fids", buildJsonArray { add(JsonPrimitive(nodeId.value)) })
        }.toString()

        val raw = api.postJson(QuarkApi.PATH_FILE_DOWNLOAD, cookie, body)
        return when (raw) {
            is PanResult.Failure -> raw
            is PanResult.Success -> {
                rememberRenewed(session.accountId.value, raw.value.renewed)
                val url = firstDownloadUrl(raw.value.root)
                if (url.isNullOrBlank()) {
                    PanResult.Failure(PanError.unknown("没有拿到下载地址"))
                } else {
                    PanResult.Success(
                        DownloadTarget(
                            url = url,
                            // 夸克的下载链接校验 Cookie / Referer / UA，三个都得带。
                            headers = mapOf(
                                "Cookie" to cookie,
                                "Referer" to QuarkApi.REFERER + "/",
                                "User-Agent" to QuarkApi.USER_AGENT,
                            ),
                        ),
                    )
                }
            }
        }
    }

    /** data 是数组，取第一个元素的 download_url。 */
    private fun firstDownloadUrl(root: kotlinx.serialization.json.JsonElement): String? {
        val obj = root as? JsonObject ?: return null
        val array = obj["data"] as? kotlinx.serialization.json.JsonArray ?: return null
        val first = array.firstOrNull() as? JsonObject ?: return null
        val value = first["download_url"] as? JsonPrimitive ?: return null
        return value.content.takeIf { it.isNotBlank() }
    }

    override suspend fun createShare(
        session: ProviderSession,
        nodeIds: List<NodeId>,
        options: ShareOptions,
    ): PanResult<ShareLink> =
        PanResult.Failure(PanError.notSupported("夸克网盘暂不支持创建分享"))

    override suspend fun cancelShare(session: ProviderSession, shareId: String): PanResult<Unit> =
        PanResult.Failure(PanError.notSupported("夸克网盘暂不支持取消分享"))

    /** 优先用服务端轮换过的最新 Cookie，没有就用会话里的。 */
    private fun cookieOf(session: ProviderSession): String? {
        val stored = (session.credentials as? ProviderCredentials.Token)?.accessToken ?: return null
        val renewed = renewedCookies[session.accountId.value] ?: return stored
        return mergeCookie(stored, renewed)
    }

    private fun rememberRenewed(accountId: String, renewed: Map<String, String>) {
        if (renewed.isEmpty()) return
        val merged = LinkedHashMap(renewedCookies[accountId].orEmpty())
        merged.putAll(renewed)
        renewedCookies[accountId] = merged
    }

    /** 把新值覆盖到旧 Cookie 串上，保留其余字段。 */
    private fun mergeCookie(base: String?, updates: Map<String, String>): String {
        val pairs = LinkedHashMap<String, String>()
        base?.split(';')?.forEach { chunk ->
            val item = chunk.trim()
            if (item.isEmpty()) return@forEach
            val key = item.substringBefore('=', "").trim()
            if (key.isEmpty()) return@forEach
            pairs[key] = item.substringAfter('=', "").trim()
        }
        updates.forEach { (k, v) -> pairs[k] = v }
        return pairs.entries.joinToString("; ") { (k, v) -> k + "=" + v }
    }

    companion object {
        const val ID = "quark"

        private const val MAX_PAGE_SIZE = 100
    }
}
