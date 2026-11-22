package com.panhub.provider.lanzou

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
import com.panhub.core.network.HttpEngine
import com.panhub.provider.api.DownloadTarget
import com.panhub.provider.api.PanProvider
import com.panhub.provider.api.ProviderCredentials
import com.panhub.provider.api.ProviderSession
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 蓝奏云实现。
 *
 * 与其它网盘不同的四点，决定了这个类的结构：
 * 1. 登录态是网页 Cookie 而不是 token，所以登录方式是「粘贴 Cookie」，
 *    用 AuthMethod.TOKEN 声明，让 UI 自动显示成令牌输入框。
 * 2. 文件夹与文件是两个 task（47 / 5），列表要合并；文件夹不分页。
 * 3. 所有写操作只回 zt == "1"，没有错误码，所以每个动作后面都要显式
 *    判 zt，否则失败会被当成成功。
 * 4. 没有配额、没有回收站、没有「创建分享」：文件天生带分享页，分享
 *    就是取那个地址；删除是永久删除。
 *
 * 客户端本身是有状态的（Cookie 挂在实例上），所以每个入口都要先
 * restore() 把会话里的 Cookie 装回去。
 */
class LanzouProvider(
    private val engine: HttpEngine = HttpEngine(),
) : PanProvider {

    private val cookie = LanzouCookie()
    private val api = LanzouApi(engine, cookie)

    override val descriptor = ProviderDescriptor(
        id = ProviderId(ID),
        displayName = "蓝奏云",
        authMethod = AuthMethod.TOKEN,
        authHint = "浏览器登录 pc.woozooo.com 后，把整段 Cookie 粘贴进来",
        capabilities = ProviderCapabilities(
            list = true,
            quota = false,
            upload = true,
            uploadChunked = false,
            download = true,
            rename = true,
            move = true,
            copy = false,
            trash = false,
            delete = true,
            createFolder = true,
            search = false,
            share = true,
            sharePassword = true,
            shareExpiry = false,
            directLink = true,
            offlineDownload = false,
        ),
    )

    override suspend fun login(credentials: ProviderCredentials): PanResult<ProviderSession> {
        val raw = when (credentials) {
            is ProviderCredentials.Token -> credentials.accessToken
            is ProviderCredentials.Password -> return PanResult.Failure(
                PanError.auth("蓝奏云只支持粘贴 Cookie 登录"),
            )
        }
        val uid = LanzouParsing.uidFromCookie(raw)
            ?: return PanResult.Failure(PanError.auth("Cookie 里没有 ylogin，请确认复制的是完整 Cookie"))
        cookie.clear()
        loadCookie(raw)
        return when (val probe = probeRoot()) {
            is PanResult.Failure -> probe
            is PanResult.Success -> if (!probe.value) {
                PanResult.Failure(PanError.auth("Cookie 无效或已过期，请重新登录网页版"))
            } else {
                PanResult.Success(
                    ProviderSession(
                        accountId = AccountId(ID + ":" + uid),
                        credentials = ProviderCredentials.Token(accessToken = cookie.export()),
                        displayName = "蓝奏云 " + uid,
                        extra = mapOf("uid" to uid),
                    ),
                )
            }
        }
    }

    override suspend fun verify(session: ProviderSession): PanResult<Unit> {
        restore(session)
        return when (val probe = probeRoot()) {
            is PanResult.Failure -> probe
            is PanResult.Success -> if (probe.value) PanResult.Success(Unit) else PanResult.Failure(PanError.auth())
        }
    }

    /** 用最小的只读请求探活：根目录第 1 页文件列表。 */
    private suspend fun probeRoot(): PanResult<Boolean> =
        when (val result = api.task(LanzouApi.TASK_FILE_LIST, listOf("folder_id" to ROOT_ID, "pg" to "1"))) {
            is PanResult.Failure -> result
            is PanResult.Success -> PanResult.Success(ztOf(result.value) == "1")
        }

    override suspend fun list(
        session: ProviderSession,
        parentId: NodeId?,
        page: PageRequest,
    ): PanResult<NodePage> {
        restore(session)
        val folderId = parentId?.value ?: ROOT_ID
        // 文件夹不分页，只在第一页取一次，后续翻页不重复请求。
        val folders = if (page.cursor == null) {
            when (val result = folderList(folderId)) {
                is PanResult.Failure -> return result
                is PanResult.Success -> result.value
            }
        } else {
            emptyList()
        }
        val pageIndex = page.cursor?.toIntOrNull() ?: 1
        return when (
            val result = api.task(
                LanzouApi.TASK_FILE_LIST,
                listOf("folder_id" to folderId, "pg" to pageIndex.toString()),
            )
        ) {
            is PanResult.Failure -> result
            is PanResult.Success -> parseFilePage(result.value, folderId, folders, pageIndex)
        }
    }

    /**
     * 「还有下一页」的判据是 info（本页条数）不为 0。
     * 不能按「本页是否满页」判断：蓝奏云每页条数并不固定。
     */
    private fun parseFilePage(
        raw: String,
        folderId: String,
        folders: List<RemoteNode>,
        pageIndex: Int,
    ): PanResult<NodePage> {
        val root = objectOf(raw)
            ?: return PanResult.Failure(PanError.unknown("文件列表解析失败", raw.take(200)))
        if (ztOf(root) != "1") {
            return PanResult.Failure(PanError.auth("登录状态已失效，请重新登录"))
        }
        val items = root["text"]?.let { element ->
            runCatching { element.jsonArray }.getOrNull()?.mapNotNull { node ->
                runCatching { node.jsonObject }.getOrNull()?.let { fileNode(it, folderId) }
            }
        }.orEmpty()
        val count = root["info"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        return PanResult.Success(
            NodePage(
                items = if (pageIndex == 1) folders + items else items,
                nextCursor = if (count > 0) (pageIndex + 1).toString() else null,
            ),
        )
    }

    private fun fileNode(obj: JsonObject, folderId: String): RemoteNode? {
        val id = obj["id"]?.jsonPrimitive?.content ?: return null
        val name = obj["name_all"]?.jsonPrimitive?.content
            ?: obj["name"]?.jsonPrimitive?.content
            ?: return null
        return RemoteNode(
            id = NodeId(id),
            parentId = NodeId(folderId),
            name = name.replace("&amp;", "&").replace("&#039;", "'"),
            kind = NodeKind.FILE,
            size = LanzouParsing.parseSize(obj["size"]?.jsonPrimitive?.content),
            modifiedAt = LanzouParsing.parseTime(obj["time"]?.jsonPrimitive?.content),
        )
    }

    private suspend fun folderList(folderId: String): PanResult<List<RemoteNode>> {
        // task 47 的 uid 必须带在 query 上，否则服务端只回空列表。
        val result = api.task(
            LanzouApi.TASK_FOLDER_LIST,
            listOf("folder_id" to folderId),
            uid = cookie.uid(),
        )
        return when (result) {
            is PanResult.Failure -> result
            is PanResult.Success -> {
                val root = objectOf(result.value)
                    ?: return PanResult.Failure(PanError.unknown("文件夹列表解析失败", result.value.take(200)))
                val folders = root["text"]?.let { element ->
                    runCatching { element.jsonArray }.getOrNull()?.mapNotNull { node ->
                        val obj = runCatching { node.jsonObject }.getOrNull() ?: return@mapNotNull null
                        val id = obj["fol_id"]?.jsonPrimitive?.content
                            ?: obj["id"]?.jsonPrimitive?.content
                            ?: return@mapNotNull null
                        RemoteNode(
                            id = NodeId(id),
                            parentId = NodeId(folderId),
                            name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                            kind = NodeKind.DIR,
                        )
                    }
                }.orEmpty()
                PanResult.Success(folders)
            }
        }
    }

    override suspend fun stat(session: ProviderSession, nodeId: NodeId): PanResult<RemoteNode> {
        restore(session)
        // 没有按 id 单查的接口：task 12 只给名称与简介，大小与时间要回列表里找。
        return when (val result = api.task(LanzouApi.TASK_FILE_INFO, listOf("file_id" to nodeId.value))) {
            is PanResult.Failure -> result
            is PanResult.Success -> {
                val name = objectOf(result.value)?.get("text")?.jsonPrimitive?.content
                if (name == null) {
                    PanResult.Failure(PanError.notFound("文件不存在或已被删除"))
                } else {
                    PanResult.Success(RemoteNode(id = nodeId, parentId = null, name = name, kind = NodeKind.FILE))
                }
            }
        }
    }

    override suspend fun quota(session: ProviderSession): PanResult<Quota> =
        PanResult.Failure(PanError.notSupported("蓝奏云不提供容量查询接口"))

    override suspend fun createFolder(
        session: ProviderSession,
        parentId: NodeId?,
        name: String,
    ): PanResult<RemoteNode> {
        restore(session)
        val parent = parentId?.value ?: ROOT_ID
        // 服务端不接受空格与一批符号，且失败时只说「失败」，不指明是哪个字符。
        val safe = LanzouParsing.sanitizeFolderName(name)
        if (safe.isEmpty()) {
            return PanResult.Failure(PanError.unknown("文件夹名里没有可用字符"))
        }
        // 创建接口不回 id，只能建完再查列表。既然要查，就先查一次做去重。
        when (val existing = folderList(parent)) {
            is PanResult.Failure -> return existing
            is PanResult.Success -> existing.value.firstOrNull { it.name == safe }?.let { return PanResult.Success(it) }
        }
        val created = api.task(
            LanzouApi.TASK_MKDIR,
            listOf("parent_id" to parent, "folder_name" to safe, "folder_description" to ""),
        )
        return when (created) {
            is PanResult.Failure -> created
            is PanResult.Success ->
                if (ztOf(created.value) != "1") {
                    PanResult.Failure(PanError.unknown("新建文件夹失败"))
                } else {
                    when (val after = folderList(parent)) {
                        is PanResult.Failure -> after
                        is PanResult.Success -> {
                            val node = after.value.firstOrNull { it.name == safe }
                            if (node != null) {
                                PanResult.Success(node)
                            } else {
                                PanResult.Failure(PanError.unknown("文件夹已创建，但列表里没找到"))
                            }
                        }
                    }
                }
        }
    }

    override suspend fun rename(
        session: ProviderSession,
        nodeId: NodeId,
        newName: String,
    ): PanResult<RemoteNode> {
        restore(session)
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return PanResult.Failure(PanError.unknown("名称不能为空"))
        val result = api.task(
            LanzouApi.TASK_RENAME_FILE,
            listOf("file_id" to nodeId.value, "file_name" to trimmed, "type" to "2"),
        )
        return when (result) {
            is PanResult.Failure -> result
            is PanResult.Success ->
                // 重命名只对会员开放，免费账号服务端统一回失败。
                if (ztOf(result.value) == "1") {
                    PanResult.Success(RemoteNode(id = nodeId, parentId = null, name = trimmed, kind = NodeKind.FILE))
                } else {
                    PanResult.Failure(PanError.notSupported("重命名需要蓝奏云会员"))
                }
        }
    }

    override suspend fun move(
        session: ProviderSession,
        nodeId: NodeId,
        newParentId: NodeId?,
    ): PanResult<Unit> {
        restore(session)
        val result = api.task(
            LanzouApi.TASK_MOVE_FILE,
            listOf("file_id" to nodeId.value, "folder_id" to (newParentId?.value ?: ROOT_ID)),
        )
        return when (result) {
            is PanResult.Failure -> result
            is PanResult.Success ->
                if (ztOf(result.value) == "1") PanResult.Success(Unit)
                else PanResult.Failure(PanError.unknown("移动失败"))
        }
    }

    override suspend fun delete(session: ProviderSession, nodeId: NodeId): PanResult<Unit> {
        restore(session)
        // 蓝奏云没有回收站，删掉就是永久删除。
        val result = api.task(LanzouApi.TASK_DELETE_FILE, listOf("file_id" to nodeId.value))
        return when (result) {
            is PanResult.Failure -> result
            is PanResult.Success ->
                if (ztOf(result.value) == "1") PanResult.Success(Unit)
                else PanResult.Failure(PanError.unknown("删除失败"))
        }
    }

    override suspend fun downloadUrl(session: ProviderSession, nodeId: NodeId): PanResult<DownloadTarget> {
        restore(session)
        return when (val share = api.task(LanzouApi.TASK_SHARE_INFO, listOf("file_id" to nodeId.value))) {
            is PanResult.Failure -> share
            is PanResult.Success -> resolveDirect(share.value)
        }
    }

    /**
     * 直链解析：分享页里藏着 sign，拿 sign 换 /file/ 路径，再拼上 CDN 域名。
     *
     * 页面模板有两代并存：新版把 sign 写成 var isngis='...'，旧版写成
     * 'sign':...,；而且首页可能只是个壳，真正的参数在内嵌 iframe 里。
     * 所以两处都要找，且都找到时优先 iframe。
     */
    private suspend fun resolveDirect(raw: String): PanResult<DownloadTarget> {
        val info = objectOf(raw)?.let { root ->
            runCatching { root["info"]?.jsonObject }.getOrNull()
        } ?: return PanResult.Failure(PanError.unknown("该文件没有可用的分享地址"))
        val domain = info["is_newd"]?.jsonPrimitive?.content
            ?: return PanResult.Failure(PanError.unknown("分享地址缺少域名"))
        val fileId = info["f_id"]?.jsonPrimitive?.content
            ?: return PanResult.Failure(PanError.unknown("分享地址缺少文件编号"))
        val password = if (info["onof"]?.jsonPrimitive?.content == "1") {
            info["pwd"]?.jsonPrimitive?.content
        } else {
            null
        }
        val pageUrl = domain + "/" + fileId

        val html = when (val result = api.page(pageUrl)) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }
        val iframe = IFRAME.find(html)?.groupValues?.get(1)
        val iframeHtml = if (iframe != null) {
            val absolute = if (iframe.startsWith("http")) iframe else domain + "/" + iframe.trimStart('/')
            (api.page(absolute, referer = pageUrl) as? PanResult.Success)?.value
        } else {
            null
        }

        val sign = extractSign(iframeHtml ?: html)
            ?: return PanResult.Failure(PanError.unknown("下载参数解析失败，页面结构可能已改变"))
        val base = Regex("""(https?://[^/]+)""").find(pageUrl)?.groupValues?.get(1) ?: LanzouApi.BASE_URL

        return when (val result = api.downProcess(base, sign, password, pageUrl)) {
            is PanResult.Failure -> result
            is PanResult.Success -> {
                val dto = result.value
                val path = dto.url
                if (path.isNullOrEmpty() || dto.dom.isNullOrEmpty()) {
                    PanResult.Failure(PanError.unknown(dto.inf ?: "未获取到直链"))
                } else {
                    PanResult.Success(
                        DownloadTarget(
                            url = if (path.startsWith("http")) path else dto.dom + "/file/" + path,
                            headers = mapOf("User-Agent" to LanzouApi.USER_AGENT, "Referer" to pageUrl),
                            fileName = dto.inf?.replace("*", "_"),
                        ),
                    )
                }
            }
        }
    }

    /** 从页面里找下载参数。新旧两版模板字段名不同，按优先级依次尝试。 */
    private fun extractSign(html: String): String? {
        NEW_SIGN.find(html)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }?.let {
            return resolveJsValue(html, it)
        }
        val raw = OLD_SIGN.find(html)?.groupValues?.get(1)?.trim()
        if (!raw.isNullOrEmpty()) {
            val stripped = raw.replace("'", "").trim()
            return resolveJsValue(html, stripped)
        }
        return null
    }

    /** 页面里可能把 sign 存成变量再引用，这里把变量名还原成字面量。 */
    private fun resolveJsValue(html: String, token: String): String {
        QUOTED.find(token.trim())?.let { return it.groupValues[1] }
        if (!JS_IDENT.matches(token)) return token
        val definition = varDefinition(token).find(html) ?: return token
        return definition.groupValues[1].trim().trim { it == '"' || it == '\'' }
    }

    private fun varDefinition(name: String): Regex =
        Regex("var[ \t]+" + Regex.escape(name) + "[ \t]*=[ \t]*([^;]+);")

    override suspend fun createShare(
        session: ProviderSession,
        nodeIds: List<NodeId>,
        options: ShareOptions,
    ): PanResult<ShareLink> {
        restore(session)
        // 蓝奏云没有「创建分享」：每个文件天生就有分享页，取地址即可。
        val first = nodeIds.firstOrNull() ?: return PanResult.Failure(PanError.unknown("没有选择文件"))
        return when (val result = api.task(LanzouApi.TASK_SHARE_INFO, listOf("file_id" to first.value))) {
            is PanResult.Failure -> result
            is PanResult.Success -> {
                val info = objectOf(result.value)?.let { root ->
                    runCatching { root["info"]?.jsonObject }.getOrNull()
                }
                val domain = info?.get("is_newd")?.jsonPrimitive?.content
                val fileId = info?.get("f_id")?.jsonPrimitive?.content
                if (domain.isNullOrEmpty() || fileId.isNullOrEmpty() || fileId == "i") {
                    PanResult.Failure(PanError.unknown("无法获取分享地址"))
                } else {
                    val password = if (info["onof"]?.jsonPrimitive?.content == "1") {
                        info["pwd"]?.jsonPrimitive?.content
                    } else {
                        null
                    }
                    PanResult.Success(
                        ShareLink(
                            id = ShareId(fileId),
                            url = domain + "/" + fileId,
                            password = password,
                            nodeIds = listOf(first),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun cancelShare(session: ProviderSession, shareId: String): PanResult<Unit> =
        PanResult.Failure(PanError.notSupported("蓝奏云无法取消分享，只能删除文件"))

    /**
     * 上传。
     *
     * 失败重试策略：蓝奏云对上传有体积上限（免费账号约 100MB），
     * 超限时服务端返回 zt != 1 且 info 是一句中文提示，这里原样透传给用户，
     * 比统一成「上传失败」有用得多。
     */
    override suspend fun upload(
        session: ProviderSession,
        parentId: NodeId?,
        source: com.panhub.provider.api.UploadSource,
        listener: PanProvider.UploadProgress,
    ): PanResult<RemoteNode> {
        restore(session)
        val folder = parentId?.value ?: ROOT_ID
        val name = LanzouParsing.sanitizeFolderName(source.name).ifBlank { source.name }
        val total = source.size

        val channel = kotlinx.coroutines.channels.Channel<Long>(
            kotlinx.coroutines.channels.Channel.CONFLATED,
        )
        return kotlinx.coroutines.coroutineScope {
            val reporter = launch {
                for (sent in channel) listener.onProgress(sent.coerceAtMost(total), total)
            }
            try {
                when (val result = api.upload(folder, name, total, { source.openStream() }) { sent ->
                    channel.trySend(sent)
                }) {
                    is PanResult.Failure -> result
                    is PanResult.Success -> PanResult.Success<RemoteNode>(
                        RemoteNode(
                            id = NodeId("uploaded:" + name),
                            parentId = parentId,
                            name = name,
                            kind = NodeKind.FILE,
                            size = total,
                            mimeType = source.mimeType,
                        ),
                    )
                }
            } finally {
                channel.close()
                reporter.join()
            }
        }
    }

    private fun objectOf(raw: String): JsonObject? =
        runCatching { api.json.parseToJsonElement(raw).jsonObject }.getOrNull()

    private fun ztOf(raw: String): String? = objectOf(raw)?.get("zt")?.jsonPrimitive?.content

    private fun ztOf(root: JsonObject): String? = root["zt"]?.jsonPrimitive?.content

    /** 把会话里的 Cookie 装回内存实例。 */
    private fun restore(session: ProviderSession) {
        cookie.clear()
        val token = when (val credentials = session.credentials) {
            is ProviderCredentials.Token -> credentials.accessToken
            is ProviderCredentials.Password -> ""
        }
        loadCookie(token)
    }

    private fun loadCookie(header: String) {
        header.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { pair ->
            val index = pair.indexOf('=')
            if (index > 0) {
                val name = pair.substring(0, index).trim()
                // 按域名生效的人机校验值不能混进登录态，会污染所有请求。
                if (name != LanzouChallenge.MARKER) {
                    cookie.put(name, pair.substring(index + 1).trim())
                }
            }
        }
    }

    companion object {
        const val ID = "lanzou"

        /** 根目录在蓝奏云里是 -1，不是 0，也不是空串。 */
        const val ROOT_ID = "-1"

        private val IFRAME = Regex("""<iframe[^>]*?src=["']([^"']+)["']""")
        private val NEW_SIGN = Regex("""var[ \t]+isngis[ \t]*=[ \t]*'([^']*)'""")
        private val OLD_SIGN = Regex("""'sign'[ \t]*:[ \t]*([^,]+),""")
        private val QUOTED = Regex("""^['"](.*)['"]$""")
        private val JS_IDENT = Regex("""^[A-Za-z_$][\w$]*$""")
    }
}
