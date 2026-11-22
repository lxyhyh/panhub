package com.panhub.provider.cloud189

import com.panhub.core.common.ErrorKind
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * 天翼云盘实现。
 *
 * 端点事实来源：OpenList 的 drivers/189（util.go、driver.go、types.go）。
 * 鉴权用网页会话 Cookie——天翼云盘的 open 接口没有可直传的 access token，
 * 必须带 cloud.189.cn 域的登录 Cookie 才会返回业务数据。
 *
 * 节点 id 带 folder:/file: 前缀：天翼云盘的文件和文件夹是两套 id 空间，
 * 数值可能重复，而改名与批量任务需要知道类型，前缀是唯一可靠的类型来源。
 *
 * 不声明分享能力：官方客户端 APK 里搜不到分享创建端点，
 * OpenList 也没有实现，没有可信事实来源的路径宁可不猜。
 */
class Cloud189Provider(
    private val engine: HttpEngine = HttpEngine(),
) : PanProvider {

    private val api = Cloud189Api(engine)

    override val descriptor = ProviderDescriptor(
        id = ProviderId(ID),
        displayName = "天翼云盘",
        authMethod = AuthMethod.TOKEN,
        authHint = "浏览器登录 cloud.189.cn 后，把整段 Cookie 粘贴进来",
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
                return PanResult.Failure(PanError.auth("天翼云盘只支持粘贴 Cookie 登录"))
        }.trim()

        if (cookie.isEmpty()) {
            return PanResult.Failure(PanError.auth("Cookie 不能为空"))
        }

        return when (val probe = api.get(Cloud189Api.PATH_USER_SIZE, cookie)) {
            is PanResult.Failure -> PanResult.Failure(
                if (probe.error.kind == ErrorKind.AUTH) probe.error
                else PanError.auth("Cookie 无效或已过期"),
            )
            is PanResult.Success -> when (val envelope = api.decode(probe.value)) {
                is PanResult.Failure -> PanResult.Failure(
                    if (envelope.error.kind == ErrorKind.AUTH) envelope.error
                    else PanError.auth("Cookie 无效或已过期"),
                )
                is PanResult.Success -> PanResult.Success(
                    ProviderSession(
                        accountId = AccountId("$ID:" + cookie.hashCode().toString()),
                        credentials = ProviderCredentials.Token(accessToken = cookie),
                        displayName = displayNameOf(cookie) ?: "天翼云盘",
                    ),
                )
            }
        }
    }

    /** 拿登录名做展示名；失败不影响登录，退回固定文案。 */
    private suspend fun displayNameOf(cookie: String): String? {
        val raw = api.get(Cloud189Api.PATH_USER_BRIEF, cookie)
        if (raw !is PanResult.Success) return null
        val element = runCatching { api.json.parseToJsonElement(raw.value) }.getOrNull()
        val name = Cloud189Api.textOf(
            (element as? kotlinx.serialization.json.JsonObject)?.get("loginName"),
        )
        return name?.takeIf { it.isNotBlank() }
    }

    override suspend fun verify(session: ProviderSession): PanResult<Unit> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        return when (val raw = api.get(Cloud189Api.PATH_USER_SIZE, cookie)) {
            is PanResult.Failure -> raw
            is PanResult.Success -> when (val envelope = api.decode(raw.value)) {
                is PanResult.Failure -> PanResult.Failure(envelope.error)
                is PanResult.Success -> PanResult.Success(Unit)
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
        val pageNum = page.cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val folderId = parentId?.let { Cloud189Parsing.rawId(it.value) }
            ?: Cloud189Api.ROOT_FOLDER_ID

        val raw = api.get(
            Cloud189Api.PATH_FILE_LIST,
            cookie,
            listOf(
                "pageSize" to Cloud189Api.PAGE_SIZE.toString(),
                "pageNum" to pageNum.toString(),
                "mediaType" to "0",
                "folderId" to folderId,
                "iconOption" to "5",
                "orderBy" to "lastOpTime",
                "descending" to "true",
            ),
        )

        return when (raw) {
            is PanResult.Failure -> raw
            is PanResult.Success -> when (val envelope = api.decode(raw.value)) {
                is PanResult.Failure -> PanResult.Failure(envelope.error)
                is PanResult.Success -> {
                    val ao = envelope.value.fileListAo
                    val folders = ao?.folderList.orEmpty().mapNotNull { folder ->
                        val rawId = Cloud189Api.textOf(folder.id) ?: return@mapNotNull null
                        RemoteNode(
                            id = NodeId(Cloud189Parsing.folderNodeId(rawId)),
                            parentId = NodeId(folderId),
                            name = folder.name.orEmpty(),
                            kind = NodeKind.DIR,
                            modifiedAt = Cloud189Parsing.parseTime(folder.lastOpTime),
                        )
                    }
                    val files = ao?.fileList.orEmpty().mapNotNull { file ->
                        val rawId = Cloud189Api.textOf(file.id) ?: return@mapNotNull null
                        RemoteNode(
                            id = NodeId(Cloud189Parsing.fileNodeId(rawId)),
                            parentId = NodeId(folderId),
                            name = file.name.orEmpty(),
                            kind = NodeKind.FILE,
                            size = file.size ?: 0L,
                            modifiedAt = Cloud189Parsing.parseTime(file.lastOpTime),
                            thumbnailUrl = file.icon?.smallUrl,
                        )
                    }
                    val count = folders.size + files.size
                    PanResult.Success(
                        NodePage(
                            items = folders + files,
                            nextCursor = if (count >= Cloud189Api.PAGE_SIZE) {
                                (pageNum + 1).toString()
                            } else {
                                null
                            },
                        ),
                    )
                }
            }
        }
    }

    override suspend fun stat(session: ProviderSession, nodeId: NodeId): PanResult<RemoteNode> =
        PanResult.Failure(PanError.notSupported("天翼云盘暂不支持查询单个文件详情"))

    override suspend fun quota(session: ProviderSession): PanResult<Quota> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        val raw = api.get(Cloud189Api.PATH_USER_SIZE, cookie)
        return when (raw) {
            is PanResult.Failure -> raw
            is PanResult.Success -> when (val envelope = api.decode(raw.value)) {
                is PanResult.Failure -> PanResult.Failure(envelope.error)
                is PanResult.Success -> {
                    val capacity = envelope.value.cloudCapacity ?: envelope.value.familyCapacity
                    PanResult.Success(
                        Quota(
                            used = capacity?.usedSize ?: 0L,
                            total = capacity?.totalSize,
                        ),
                    )
                }
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
        val folderId = parentId?.let { Cloud189Parsing.rawId(it.value) }
            ?: Cloud189Api.ROOT_FOLDER_ID

        val raw = api.postForm(
            Cloud189Api.PATH_CREATE_FOLDER,
            cookie,
            listOf(
                "parentFolderId" to folderId,
                "folderName" to name,
            ),
        )

        return when (raw) {
            is PanResult.Failure -> raw
            is PanResult.Success -> when (val envelope = api.decode(raw.value)) {
                is PanResult.Failure -> PanResult.Failure(envelope.error)
                // 建目录接口不回新 id，只能拿名字占位；列表刷新后会被真实 id 覆盖。
                is PanResult.Success -> PanResult.Success(
                    RemoteNode(
                        id = NodeId(Cloud189Parsing.folderNodeId(name)),
                        parentId = NodeId(folderId),
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
    ): PanResult<RemoteNode> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))

        // 文件与文件夹用两个不同的改名端点，字段名也不一样。
        val isFolder = Cloud189Parsing.isFolder(nodeId.value)
        val rawId = Cloud189Parsing.rawId(nodeId.value)
        val path = if (isFolder) {
            Cloud189Api.PATH_RENAME_FOLDER
        } else {
            Cloud189Api.PATH_RENAME_FILE
        }
        val fields = if (isFolder) {
            listOf("folderId" to rawId, "destFolderName" to newName)
        } else {
            listOf("fileId" to rawId, "destFileName" to newName)
        }

        val raw = api.postForm(path, cookie, fields)
        return when (raw) {
            is PanResult.Failure -> raw
            is PanResult.Success -> when (val envelope = api.decode(raw.value)) {
                is PanResult.Failure -> PanResult.Failure(envelope.error)
                is PanResult.Success -> PanResult.Success(
                    RemoteNode(
                        id = nodeId,
                        parentId = null,
                        name = newName,
                        kind = if (isFolder) NodeKind.DIR else NodeKind.FILE,
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
        val target = newParentId?.let { Cloud189Parsing.rawId(it.value) }
            ?: Cloud189Api.ROOT_FOLDER_ID
        return batchTask(cookie, "MOVE", target, nodeId)
    }

    override suspend fun delete(session: ProviderSession, nodeId: NodeId): PanResult<Unit> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        return batchTask(cookie, "DELETE", "", nodeId)
    }

    /** 移动 / 复制 / 删除共用一个批量任务端点，靠 type 区分。 */
    private suspend fun batchTask(
        cookie: String,
        type: String,
        targetFolderId: String,
        nodeId: NodeId,
    ): PanResult<Unit> {
        val rawId = Cloud189Parsing.rawId(nodeId.value)
        val isFolder = Cloud189Parsing.isFolder(nodeId.value)
        val taskInfos = buildJsonArray {
            add(
                buildJsonObject {
                    put("fileId", JsonPrimitive(rawId))
                    put("fileName", JsonPrimitive(nodeId.value))
                    put("isFolder", JsonPrimitive(if (isFolder) 1 else 0))
                },
            )
        }.toString()

        val raw = api.postForm(
            Cloud189Api.PATH_BATCH_TASK,
            cookie,
            listOf(
                "type" to type,
                "targetFolderId" to targetFolderId,
                "taskInfos" to taskInfos,
            ),
        )

        return when (raw) {
            is PanResult.Failure -> raw
            is PanResult.Success -> when (val envelope = api.decode(raw.value)) {
                is PanResult.Failure -> PanResult.Failure(envelope.error)
                is PanResult.Success -> PanResult.Success(Unit)
            }
        }
    }

    override suspend fun downloadUrl(
        session: ProviderSession,
        nodeId: NodeId,
    ): PanResult<DownloadTarget> {
        val cookie = cookieOf(session)
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        val rawId = Cloud189Parsing.rawId(nodeId.value)

        val raw = api.get(
            Cloud189Api.PATH_FILE_INFO,
            cookie,
            listOf("fileId" to rawId),
        )

        return when (raw) {
            is PanResult.Failure -> raw
            is PanResult.Success -> when (val envelope = api.decode(raw.value)) {
                is PanResult.Failure -> PanResult.Failure(envelope.error)
                is PanResult.Success -> {
                    val url = envelope.value.fileDownloadUrl ?: envelope.value.downloadUrl
                    if (url.isNullOrBlank()) {
                        PanResult.Failure(PanError.unknown("没有拿到下载地址"))
                    } else {
                        PanResult.Success(
                            DownloadTarget(
                                url = if (url.startsWith("http")) url else "https:$url",
                                headers = mapOf(
                                    "Cookie" to cookie,
                                    "Referer" to Cloud189Api.BASE_URL + "/",
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun createShare(
        session: ProviderSession,
        nodeIds: List<NodeId>,
        options: ShareOptions,
    ): PanResult<ShareLink> =
        PanResult.Failure(PanError.notSupported("天翼云盘暂不支持创建分享"))

    override suspend fun cancelShare(session: ProviderSession, shareId: String): PanResult<Unit> =
        PanResult.Failure(PanError.notSupported("天翼云盘暂不支持取消分享"))

    private fun cookieOf(session: ProviderSession): String? =
        (session.credentials as? ProviderCredentials.Token)?.accessToken

    companion object {
        const val ID = "cloud189"
    }
}
