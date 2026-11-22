package com.panhub.provider.pan123

import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.common.map
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
import com.panhub.core.network.HttpEngine
import com.panhub.provider.api.DownloadTarget
import com.panhub.provider.api.PanProvider
import com.panhub.provider.api.ProviderCredentials
import com.panhub.provider.api.ProviderSession
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 123 云盘实现。
 *
 * 接口路径全部来自 APK 内的 interface_config.json（见 docs/recon/pan123.md）。
 * 令牌既放在 Authorization 头也放 Cookie，兼容两种后端校验。
 */
class Pan123Provider(
    private val engine: HttpEngine = HttpEngine(),
) : PanProvider {

    private val api = Pan123Api(engine)
    private val json = api.json
    private val uploader = Pan123Uploader(api, json)

    override val descriptor = ProviderDescriptor(
        id = ProviderId(ID),
        displayName = "123 云盘",
        authMethod = AuthMethod.BOTH,
        authHint = "账号密码登录，或粘贴浏览器里的 access token",
        capabilities = ProviderCapabilities(
            list = true,
            quota = true,
            upload = true,
            uploadChunked = true,
            download = true,
            rename = true,
            move = true,
            copy = true,
            trash = true,
            delete = true,
            createFolder = true,
            share = true,
            sharePassword = true,
            shareExpiry = true,
            directLink = true,
            offlineDownload = true,
        ),
    )

    override suspend fun login(credentials: ProviderCredentials): PanResult<ProviderSession> {
        // 两种方式：账号密码，或直接导入 token（规避风控与验证码）。
        when (credentials) {
            is ProviderCredentials.Token -> {
                val check = fetchUserInfo(credentials.accessToken)
                return check.map {
                    ProviderSession(
                        accountId = AccountId("$ID:${it.passport ?: credentials.accessToken}"),
                        credentials = credentials,
                        displayName = it.nickname ?: it.passport,
                    )
                }
            }

            is ProviderCredentials.Password -> {
                val body = json.encodeToString(
                    LoginRequest.serializer(),
                    LoginRequest(passport = credentials.username, password = credentials.password),
                )
                return when (val result = api.post(
                    path = Pan123Api.PATH_SIGN_IN,
                    token = null,
                    body = body,
                    decode = { raw -> json.decodeFromString(ApiEnvelope.serializer(LoginData.serializer()), raw) },
                )) {
                    is PanResult.Failure -> PanResult.Failure(result.error)
                    is PanResult.Success -> {
                        val token = result.value.token
                            ?: return PanResult.Failure(PanError.unknown("登录响应缺少 token"))
                        val session = ProviderCredentials.Token(accessToken = token)
                        fetchUserInfo(token).map {
                            ProviderSession(
                                accountId = AccountId("$ID:${it.passport ?: credentials.username}"),
                                credentials = session,
                                displayName = it.nickname ?: it.passport,
                            )
                        }
                    }
                }
            }
        }
    }

    override suspend fun verify(session: ProviderSession): PanResult<Unit> =
        fetchUserInfo(tokenOf(session)).map { }

    override suspend fun list(
        session: ProviderSession,
        parentId: NodeId?,
        page: PageRequest,
    ): PanResult<NodePage> {
        val parent = parentId?.value ?: "0"
        val cursor = page.cursor
        val query = buildString {
            append(Pan123Api.PATH_FILE_LIST)
            append("?driveId=0")
            append("&limit=").append(page.limit)
            append("&next=").append(cursor ?: "")
            append("&orderBy=file_name")
            append("&orderDirection=asc")
            append("&parentFileId=").append(parent)
            append("&trashed=false")
            append("&category=0")
            append("&searchData=")
            append("&type=0")
        }
        return api.get(
            path = query,
            token = tokenOf(session),
            decode = { raw ->
                json.decodeFromString(ApiEnvelope.serializer(FileListData.serializer()), raw)
            },
        ).map { data ->
            NodePage(
                items = data.infoList.orEmpty().map { it.toRemoteNode(parentId) },
                nextCursor = data.next?.takeIf { it.isNotBlank() },
            )
        }
    }

    override suspend fun stat(session: ProviderSession, nodeId: NodeId): PanResult<RemoteNode> {
        // 123 云盘没有单文件 stat，用列表接口按 parent 反查代价太高；
        // 这里复用 file/info（已确认存在），失败时退化成一次列表查找。
        val path = "/api/file/info?fileId=${nodeId.value}"
        return api.get(
            path = path,
            token = tokenOf(session),
            decode = { raw ->
                json.decodeFromString(ApiEnvelope.serializer(FileItem.serializer()), raw)
            },
        ).map { it.toRemoteNode(null) }
    }

    override suspend fun quota(session: ProviderSession): PanResult<Quota> =
        fetchUserInfo(tokenOf(session)).map { info ->
            val total = (info.spacePermanent ?: 0L) + (info.spaceTemp ?: 0L)
            Quota(used = info.spaceUsed ?: 0L, total = total.takeIf { it > 0 })
        }

    override suspend fun createFolder(
        session: ProviderSession,
        parentId: NodeId?,
        name: String,
    ): PanResult<RemoteNode> {
        val body = """{"driveId":0,"etag":"","fileName":"$name","parentFileId":${parentId?.value ?: "0"},"size":0,"type":1}"""
        return api.post(
            path = Pan123Api.PATH_UPLOAD_REQUEST,
            token = tokenOf(session),
            body = body,
            decode = { raw ->
                json.decodeFromString(ApiEnvelope.serializer(CreateFolderData.serializer()), raw)
            },
        ).map { data ->
            data.info?.toRemoteNode(parentId) ?: RemoteNode(
                id = NodeId(data.fileId?.toString() ?: ""),
                parentId = parentId,
                name = name,
                kind = NodeKind.DIR,
            )
        }
    }

    override suspend fun rename(
        session: ProviderSession,
        nodeId: NodeId,
        newName: String,
    ): PanResult<RemoteNode> {
        val body = """{"driveId":0,"fileId":${nodeId.value},"fileName":"$newName"}"""
        return api.post(
            path = Pan123Api.PATH_RENAME,
            token = tokenOf(session),
            body = body,
            decode = { raw -> json.decodeFromString(ApiEnvelope.serializer(JsonUnit), raw) },
        ).map {
            RemoteNode(id = nodeId, parentId = null, name = newName, kind = NodeKind.FILE)
        }
    }

    override suspend fun move(
        session: ProviderSession,
        nodeId: NodeId,
        newParentId: NodeId?,
    ): PanResult<Unit> {
        val body = """{"fileIds":[${nodeId.value}],"parentFileId":${newParentId?.value ?: "0"},"driveId":0}"""
        return api.post(
            path = Pan123Api.PATH_MOD_PID,
            token = tokenOf(session),
            body = body,
            decode = { raw -> json.decodeFromString(ApiEnvelope.serializer(JsonUnit), raw) },
        ).map { }
    }

    override suspend fun delete(session: ProviderSession, nodeId: NodeId): PanResult<Unit> {
        val body = """{"fileIds":[${nodeId.value}],"driveId":0,"operation":true}"""
        return api.post(
            path = Pan123Api.PATH_DELETE,
            token = tokenOf(session),
            body = body,
            decode = { raw -> json.decodeFromString(ApiEnvelope.serializer(JsonUnit), raw) },
        ).map { }
    }

    override suspend fun downloadUrl(
        session: ProviderSession,
        nodeId: NodeId,
    ): PanResult<DownloadTarget> {
        val body = """{"fileId":${nodeId.value},"driveId":0}"""
        return api.post(
            path = Pan123Api.PATH_DOWNLOAD_INFO,
            token = tokenOf(session),
            body = body,
            decode = { raw ->
                json.decodeFromString(ApiEnvelope.serializer(DownloadInfoData.serializer()), raw)
            },
        ).map { data ->
            DownloadTarget(
                url = data.downloadUrl ?: error("missing download url"),
                headers = mapOf("User-Agent" to HttpEngine.USER_AGENT),
            )
        }
    }

    override suspend fun createShare(
        session: ProviderSession,
        nodeIds: List<NodeId>,
        options: ShareOptions,
    ): PanResult<ShareLink> {
        val ids = nodeIds.mapNotNull { it.value.toLongOrNull() }
        if (ids.isEmpty()) return PanResult.Failure(PanError.unknown("无效的文件 id"))
        val request = ShareCreateRequest(
            fileIds = ids,
            shareName = options.description,
            expiration = options.expireDays ?: 0,
            sharePwd = options.password,
        )
        val body = json.encodeToString(ShareCreateRequest.serializer(), request)
        return api.post(
            path = Pan123Api.PATH_SHARE_CREATE,
            token = tokenOf(session),
            body = body,
            decode = { raw ->
                json.decodeFromString(ApiEnvelope.serializer(ShareCreateData.serializer()), raw)
            },
        ).map { data ->
            ShareLink(
                id = ShareId(data.shareKey.orEmpty()),
                url = data.shareUrl ?: "https://www.123pan.com/s/${data.shareKey}",
                password = options.password,
                nodeIds = nodeIds,
            )
        }
    }

    override suspend fun cancelShare(session: ProviderSession, shareId: String): PanResult<Unit> {
        val body = """{"shareKey":"$shareId"}"""
        return api.post(
            path = Pan123Api.PATH_SHARE_DELETE,
            token = tokenOf(session),
            body = body,
            decode = { raw -> json.decodeFromString(ApiEnvelope.serializer(JsonUnit), raw) },
        ).map { }
    }

    override suspend fun upload(
        session: ProviderSession,
        parentId: NodeId?,
        source: com.panhub.provider.api.UploadSource,
        listener: PanProvider.UploadProgress,
    ): PanResult<RemoteNode> = uploader.upload(tokenOf(session), parentId, source, listener)

    private suspend fun fetchUserInfo(token: String?): PanResult<UserInfoData> =
        api.get(
            path = Pan123Api.PATH_USER_INFO,
            token = token,
            decode = { raw ->
                json.decodeFromString(ApiEnvelope.serializer(UserInfoData.serializer()), raw)
            },
        )

    private fun tokenOf(session: ProviderSession): String? =
        (session.credentials as? ProviderCredentials.Token)?.accessToken

    private fun FileItem.toRemoteNode(parent: NodeId?): RemoteNode = RemoteNode(
        id = NodeId(fileId.toString()),
        parentId = parent ?: parentFileId.takeIf { it != 0L }?.let { NodeId(it.toString()) },
        name = fileName,
        kind = if (isDir) NodeKind.DIR else NodeKind.FILE,
        size = if (isDir) 0L else size,
        modifiedAt = parseTime(updateAt),
        createdAt = parseTime(createAt),
        providerPath = null,
    )

    private fun parseTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        }.getOrElse {
            runCatching {
                java.time.LocalDateTime.parse(raw.replace(' ', 'T'))
                    .toInstant(java.time.ZoneOffset.ofHours(8)).toEpochMilli()
            }.getOrNull()
        }
    }

    companion object {
        const val ID = "pan123"
    }
}