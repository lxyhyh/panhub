package com.panhub.provider.baidu

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
import com.panhub.core.model.ShareId
import com.panhub.core.model.ShareLink
import com.panhub.core.model.ShareOptions
import com.panhub.core.model.SharePage
import com.panhub.core.network.HttpEngine
import com.panhub.provider.api.DownloadTarget
import com.panhub.provider.api.PanProvider
import com.panhub.provider.api.ProviderCredentials
import com.panhub.provider.api.ProviderSession
import com.panhub.provider.api.UploadSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * 百度网盘实现（网页接口 + Cookie）。
 *
 * 不接官方开放平台：xpan 要求 client_id/client_secret，个人项目申请不到。
 * 网页接口用 BDUSS/STOKEN 这组 Cookie 即可，与网页版行为一致。
 *
 * **节点 id 就是路径**。百度网盘是路径寻址的（更名、移动、分享全要路径），
 * fs_id 只在列表里出现且不能反查父目录。用路径当 NodeId 让 rename/move 直接可用，
 * 代价是移动后 id 会变——但这正是百度的语义，UI 刷新后拿到新 id 就是对的。
 *
 * 不声明 copy 之外的部分：分享端点存在（/share/set 返回 errno 2 而不是 404），
 * 但实测被手机号绑定拦截，本实现只保留创建分享并原样回传服务端的报错。
 */
class BaiduProvider(
    private val engine: HttpEngine = HttpEngine(),
) : PanProvider {

    private val api = BaiduApi(engine)

    private val uploader = BaiduUploader(engine, api)

    override val descriptor = ProviderDescriptor(
        id = ProviderId(ID),
        displayName = "百度网盘",
        authMethod = AuthMethod.TOKEN,
        authHint = "浏览器登录 pan.baidu.com 后，把整段 Cookie（含 BDUSS、STOKEN）粘贴进来",
        capabilities = ProviderCapabilities(
            list = true,
            quota = true,
            download = true,
            rename = true,
            move = true,
            delete = true,
            createFolder = true,
            share = true,
            shareList = true,
            search = true,
            directLink = true,
            upload = true,
            uploadChunked = true,
        ),
    )

    override suspend fun login(credentials: ProviderCredentials): PanResult<ProviderSession> {
        val cookie = when (credentials) {
            is ProviderCredentials.Token -> credentials.accessToken
            is ProviderCredentials.Password ->
                return PanResult.Failure(PanError.auth("百度网盘只支持粘贴 Cookie 登录"))
        }.trim()

        if (cookie.isEmpty()) {
            return PanResult.Failure(PanError.auth("Cookie 不能为空"))
        }
        if (!cookie.contains("BDUSS", ignoreCase = true)) {
            return PanResult.Failure(PanError.auth("Cookie 里缺少 BDUSS，请确认复制的是完整 Cookie"))
        }

        return when (val quota = api.get(BaiduApi.PATH_QUOTA, cookie)) {
            is PanResult.Failure -> PanResult.Failure(
                if (quota.error.kind == com.panhub.core.common.ErrorKind.AUTH) quota.error
                else PanError.auth("Cookie 无效或已过期"),
            )
            is PanResult.Success -> PanResult.Success(
                ProviderSession(
                    accountId = AccountId("$ID:" + cookie.hashCode().toString()),
                    credentials = ProviderCredentials.Token(accessToken = cookie),
                    displayName = "百度网盘",
                ),
            )
        }
    }

    override suspend fun verify(session: ProviderSession): PanResult<Unit> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        return when (val raw = api.get(BaiduApi.PATH_QUOTA, cookie)) {
            is PanResult.Failure -> raw
            is PanResult.Success -> PanResult.Success(Unit)
        }
    }

    override suspend fun list(
        session: ProviderSession,
        parentId: NodeId?,
        page: PageRequest,
    ): PanResult<NodePage> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        val dir = parentId?.value ?: BaiduApi.ROOT_PATH
        val pageIndex = page.cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val pageSize = if (page.limit in 1..MAX_PAGE_SIZE) page.limit else MAX_PAGE_SIZE

        val raw = api.get(
            BaiduApi.PATH_LIST,
            cookie,
            listOf(
                "dir" to dir,
                "order" to "name",
                "desc" to "0",
                "showempty" to "0",
                "page" to pageIndex.toString(),
                "num" to pageSize.toString(),
            ),
        )

        return when (raw) {
            is PanResult.Failure -> raw
            is PanResult.Success -> when (
                val resp = decode(raw.value, BaiduListResp.serializer())
            ) {
                is PanResult.Failure -> PanResult.Failure(resp.error)
                is PanResult.Success -> {
                    val items = resp.value.list.orEmpty().mapNotNull { dto ->
                        val path = dto.path ?: return@mapNotNull null
                        RemoteNode(
                            id = NodeId(path),
                            parentId = NodeId(dir),
                            name = dto.serverFilename ?: path.substringAfterLast('/'),
                            kind = if (dto.isDir) NodeKind.DIR else NodeKind.FILE,
                            size = dto.size,
                            modifiedAt = dto.serverMtime?.takeIf { it > 0 }?.times(1000),
                            createdAt = dto.serverCtime?.takeIf { it > 0 }?.times(1000),
                            thumbnailUrl = dto.thumbs?.url3,
                        )
                    }
                    PanResult.Success(
                        NodePage(
                            items = items,
                            // 百度不返回总数，只能按「本页是否满」判断还有没有下一页。
                            nextCursor = if (items.size >= pageSize) (pageIndex + 1).toString() else null,
                        ),
                    )
                }
            }
        }
    }

    override suspend fun stat(session: ProviderSession, nodeId: NodeId): PanResult<RemoteNode> =
        PanResult.Failure(PanError.notSupported("百度网盘暂不支持查询单个文件详情"))

    override suspend fun quota(session: ProviderSession): PanResult<Quota> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        val raw = api.get(
            BaiduApi.PATH_QUOTA,
            cookie,
            listOf("checkfree" to "1", "checkexpire" to "1"),
        )
        return when (raw) {
            is PanResult.Failure -> raw
            is PanResult.Success -> when (val resp = decode(raw.value, BaiduQuotaResp.serializer())) {
                is PanResult.Failure -> PanResult.Failure(resp.error)
                is PanResult.Success -> PanResult.Success(
                    Quota(used = resp.value.used, total = resp.value.total),
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
        val parent = parentId?.value ?: BaiduApi.ROOT_PATH
        val path = joinPath(parent, name)

        val raw = api.postForm(
            BaiduApi.PATH_CREATE,
            cookie,
            listOf(
                "a" to "commit",
                "path" to path,
                "isdir" to "1",
                "block_list" to "[]",
            ),
            listOf("method" to "create"),
        )
        return when (raw) {
            is PanResult.Failure -> raw
            is PanResult.Success -> when (val resp = decode(raw.value, BaiduCreateResp.serializer())) {
                is PanResult.Failure -> PanResult.Failure(resp.error)
                is PanResult.Success -> PanResult.Success(
                    RemoteNode(
                        id = NodeId(resp.value.path ?: path),
                        parentId = NodeId(parent),
                        name = name,
                        kind = NodeKind.DIR,
                    ),
                )
            }
        }
    }

    override suspend fun rename(
        session: ProviderSession,
        nodeId: NodeId,
        newName: String,
    ): PanResult<RemoteNode> = when (
        val result = manage(
            session,
            "rename",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("path", JsonPrimitive(nodeId.value))
                        put("newname", JsonPrimitive(newName))
                    },
                )
            },
        )
    ) {
        is PanResult.Failure -> result
        is PanResult.Success -> PanResult.Success(
            RemoteNode(
                id = NodeId(joinPath(parentOf(nodeId.value), newName)),
                parentId = null,
                name = newName,
                kind = if (nodeId.value.endsWith("/")) NodeKind.DIR else NodeKind.FILE,
            ),
        )
    }

    override suspend fun move(
        session: ProviderSession,
        nodeId: NodeId,
        newParentId: NodeId?,
    ): PanResult<Unit> = manage(
        session,
        "move",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("path", JsonPrimitive(nodeId.value))
                    put("dest", JsonPrimitive(newParentId?.value ?: BaiduApi.ROOT_PATH))
                    put("newname", JsonPrimitive(nameOf(nodeId.value)))
                },
            )
        },
    )

    override suspend fun delete(session: ProviderSession, nodeId: NodeId): PanResult<Unit> = manage(
        session,
        "delete",
        buildJsonArray { add(JsonPrimitive(nodeId.value)) },
    )

    /** 移动 / 复制 / 删除 / 改名共用 /rest/2.0/xpan/file?method=filemanager。 */
    private suspend fun manage(
        session: ProviderSession,
        opera: String,
        fileList: JsonArray,
    ): PanResult<Unit> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        val raw = api.postForm(
            BaiduApi.PATH_MANAGE,
            cookie,
            listOf(
                "async" to "0",
                "filelist" to fileList.toString(),
                "ondup" to "fail",
            ),
            listOf("method" to "filemanager", "opera" to opera),
        )
        return when (raw) {
            is PanResult.Failure -> raw
            is PanResult.Success -> PanResult.Success(Unit)
        }
    }

    override suspend fun downloadUrl(
        session: ProviderSession,
        nodeId: NodeId,
    ): PanResult<DownloadTarget> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        val path = nodeId.value

        val raw = api.get(
            BaiduApi.PATH_FILEMETAS,
            cookie,
            listOf("target" to buildJsonArray { add(JsonPrimitive(path)) }.toString(), "dlink" to "1"),
        )
        return when (raw) {
            is PanResult.Failure -> raw
            is PanResult.Success -> {
                val link = baiduMetasDlink(raw.value)
                if (link.isNullOrBlank()) {
                    PanResult.Failure(PanError.unknown("没有拿到下载地址"))
                } else {
                    PanResult.Success(
                        DownloadTarget(
                            url = link,
                            // 百度直链必须带这个 UA，否则返回 403。
                            headers = mapOf("User-Agent" to BaiduApi.DOWNLOAD_UA),
                            fileName = nameOf(path),
                        ),
                    )
                }
            }
        }
    }

    /** /api/filemetas 的 data 是 list，元素里 dlink 才是直链。 */
    private fun baiduMetasDlink(raw: String): String? {
        val root = runCatching { api.json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return null
        val list = root["list"] as? JsonArray
        val first = list?.firstOrNull() as? JsonObject
        return (first?.get("dlink") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }

    override suspend fun upload(
        session: ProviderSession,
        parentId: NodeId?,
        source: UploadSource,
        listener: PanProvider.UploadProgress,
    ): PanResult<RemoteNode> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        return uploader.upload(cookie, parentId?.value ?: BaiduApi.ROOT_PATH, source, listener)
    }

    override suspend fun createShare(
        session: ProviderSession,
        nodeIds: List<NodeId>,
        options: ShareOptions,
    ): PanResult<ShareLink> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        val paths = buildJsonArray { nodeIds.forEach { add(JsonPrimitive(it.value)) } }
        val raw = api.postForm(
            BaiduApi.PATH_SHARE_SET,
            cookie,
            listOf(
                "period" to (options.expireDays?.toString() ?: "0"),
                "pwd" to (options.password ?: ""),
                "eflag" to "0",
                "schannel" to "4",
                "channel" to "chunlei",
                "clienttype" to "0",
                "web" to "1",
                "fid_list" to paths.toString(),
            ),
        )
        return when (raw) {
            is PanResult.Failure -> raw
            is PanResult.Success -> when (val resp = decode(raw.value, BaiduShareResp.serializer())) {
                is PanResult.Failure -> PanResult.Failure(resp.error)
                is PanResult.Success -> {
                    val data = resp.value
                    val url = data.shortUrl ?: data.link
                    if (url.isNullOrBlank()) {
                        PanResult.Failure(PanError.unknown("没有拿到分享链接"))
                    } else {
                        PanResult.Success(
                            ShareLink(
                                id = ShareId(data.shareId?.toString() ?: url),
                                url = url,
                                password = data.pwd,
                                nodeIds = nodeIds,
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun cancelShare(session: ProviderSession, shareId: String): PanResult<Unit> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        // 参数名是复数 shareid_list，值是 JSON 数组字符串。发单数 shareid 服务端
        // 不报错也不处理，界面就会以为取消成功。
        val raw = api.postForm(
            BaiduApi.PATH_SHARE_CANCEL,
            cookie,
            listOf("shareid_list" to "[" + shareId + "]"),
        )
        return when (raw) {
            is PanResult.Failure -> raw
            is PanResult.Success -> PanResult.Success(Unit)
        }
    }

    override suspend fun search(
        session: ProviderSession,
        query: String,
        page: PageRequest,
    ): PanResult<NodePage> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        val keyword = query.trim()
        if (keyword.isEmpty()) return PanResult.Failure(PanError.unknown("搜索关键字不能为空"))
        return api.search(cookie, keyword, page)
    }

    override suspend fun listShares(
        session: ProviderSession,
        page: PageRequest,
    ): PanResult<SharePage> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        // 百度的分页是页码不是游标，这里把游标当页码用；响应给 nextpage，
        // 非 0 才继续，避免最后一页之后还反复拉同一个空页。
        val pageIndex = page.cursor?.toIntOrNull() ?: 1
        val raw = api.get(
            BaiduApi.PATH_SHARE_RECORD,
            cookie,
            listOf(
                "page" to pageIndex.toString(),
                "num" to page.limit.toString(),
                "order" to "time",
                "desc" to "1",
            ),
        )
        return when (raw) {
            is PanResult.Failure -> raw
            is PanResult.Success -> when (val resp = decode(raw.value, BaiduShareRecordResp.serializer())) {
                is PanResult.Failure -> PanResult.Failure(resp.error)
                is PanResult.Success -> {
                    val data = resp.value
                    val items = data.list.shareRecords().map { it.toShareLink() }
                    val next = data.nextpage
                    PanResult.Success(
                        SharePage(
                            items = items,
                            nextCursor = if (next > pageIndex) next.toString() else null,
                        ),
                    )
                }
            }
        }
    }

    /** 过期时间用毫秒时间戳，0 表示永久，界面上不显示到期日。 */
    private fun BaiduShareRecord.toShareLink(): ShareLink {
        val url = shareUrlOf(this)
        return ShareLink(
            id = ShareId(shareId.toString()),
            url = url ?: "https://pan.baidu.com/s/$shareId",
            password = normalizeSharePwd(passwd),
            expiresAt = expiredTime.takeIf { it > 0L }?.let { it * 1000L },
            title = typicalPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() },
            // status 非 0 是服务商自己判定的失效，比按时间推算可靠。
            expired = status != 0,
        )
    }

    private fun <T> decode(raw: String, serializer: kotlinx.serialization.KSerializer<T>): PanResult<T> =
        runCatching { api.json.decodeFromString(serializer, raw) }.fold(
            onSuccess = { PanResult.Success(it) },
            onFailure = { e -> PanResult.Failure(PanError.unknown("响应解析失败", raw.take(300), e)) },
        )

    private fun cookieOf(session: ProviderSession): String? =
        (session.credentials as? ProviderCredentials.Token)?.accessToken

    companion object {
        const val ID = "baidu"

        private const val MAX_PAGE_SIZE = 1000

        /** 拼出百度风格的路径：父目录 + 文件名，保证只有一个斜杠。 */
        internal fun joinPath(parent: String, name: String): String {
            val base = parent.trimEnd('/')
            return if (base.isEmpty()) "/$name" else "$base/$name"
        }

        internal fun parentOf(path: String): String {
            val trimmed = path.trimEnd('/')
            val index = trimmed.lastIndexOf('/')
            return if (index <= 0) "/" else trimmed.substring(0, index)
        }

        internal fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')
    }
}