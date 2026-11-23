package com.panhub.provider.pan123

import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.PageRequest
import com.panhub.core.model.ShareEntry
import com.panhub.core.model.ShareEntryPage
import com.panhub.core.model.ShareSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 打开别人的 123 云盘分享，并把它转存到自己的网盘。
 *
 * 三个动作串成一条链：
 * 1. GET  /api/share/get  列目录（顺带验证分享是否可用、提取码对不对）；
 * 2. GET  /api/share/get  带 ParentFileId 进入子目录，翻页靠 Page 递增；
 * 3. POST /api/restful/goapi/v1/file/copy/save 建转存任务，
 *    再轮询 GET /restful/goapi/v1/file/copy/save/get?taskID= 等它完成。
 *
 * 为什么把三步放在同一个对象里：提取码在打开分享时校验一次就够了，
 * 但列目录与转存都必须带着它；如果让上层持有再透传，
 * 重构时最容易被漏掉，而漏掉的表现只是「转存失败」四个字。
 *
 * 事实来源：APK 内 interface_config.json 的 shareFileDetails / copySaveFiles /
 * copySaveTask 三条端点；参数形态由分享页 bundle 与公开实现交叉核对，
 * 差异记在 docs/recon/pan123-share.md。
 */
internal class Pan123ShareImporter(
    private val api: Pan123Api,
    private val json: kotlinx.serialization.json.Json = api.json,
    private val pollAttempts: Int = DEFAULT_POLL_ATTEMPTS,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {

    /**
     * 用分享链接换一个可以浏览的会话。
     *
     * 分享本身没有 stoken 这种票据，[ShareSource.token] 里放的是提取码 ——
     * 后续列目录与转存都要带它。空串表示这条分享没有密码。
     */
    suspend fun open(token: String?, shareUrl: String, passcode: String?): PanResult<ShareSource> {
        val parsed = Pan123ShareLink.parse(shareUrl)
            ?: return PanResult.Failure(PanError.unknown("这不是 123 云盘的分享链接"))
        // 链接里带的提取码优先：用户是从别处复制过来的，通常已经带了。
        val code = passcode?.trim()?.takeIf { it.isNotEmpty() } ?: parsed.passcode

        // 用根目录第一页验证分享可用性：分享不存在、失效、提取码不对
        // 都会在这一步就返回明确错误码，比等到转存时才失败好得多。
        val first = when (val result = fetchPage(token, parsed.shareKey, code.orEmpty(), null, 1)) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }
        if (first.expired) {
            return PanResult.Failure(PanError.unknown("分享已失效"))
        }
        val title = first.shareName?.takeIf { it.isNotBlank() }
            ?: first.infoList.singleOrNull()?.fileName?.takeIf { it.isNotBlank() }
        return PanResult.Success(
            ShareSource(linkId = parsed.shareKey, token = code.orEmpty(), title = title),
        )
    }

    /** 列分享里的一个目录；[parentId] 为 null 表示分享根目录。 */
    suspend fun list(
        token: String?,
        source: ShareSource,
        parentId: NodeId?,
        page: PageRequest,
    ): PanResult<ShareEntryPage> {
        val pageIndex = page.cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val data = when (
            val result = fetchPage(token, source.linkId, source.token, parentId, pageIndex)
        ) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }
        if (data.expired) {
            return PanResult.Failure(PanError.unknown("分享已失效"))
        }

        val items = data.infoList.mapNotNull { dto ->
            val id = dto.fileId.takeIf { it > 0 } ?: return@mapNotNull null
            ShareEntry(
                id = NodeId(id.toString()),
                parentId = parentId,
                name = dto.fileName.orEmpty().ifBlank { id.toString() },
                kind = if (dto.isDir) NodeKind.DIR else NodeKind.FILE,
                // 转存要 S3KeyFlag + Etag + StorageNode 三样，用竖线拼成一个票据，
                // 免得在 ShareEntry 上开三个只有 123 认识的字段。
                ticket = ticketOf(dto),
                size = if (dto.isDir) 0L else dto.size,
                modifiedAt = parsePan123Time(dto.updateAt),
            )
        }

        // Next 的终止值是字符串 "-1"；空串表示「还有下一页」，
        // 所以不能用 isNotBlank 判断，只能显式排除 "-1"。
        val next = data.next?.takeIf { it != NO_MORE_PAGES }
            ?.let { pageIndex + 1 }
            ?.takeIf { items.isNotEmpty() }
        return PanResult.Success(ShareEntryPage(items = items, nextCursor = next?.toString()))
    }

    /**
     * 把选中的条目转存到 [targetParentId]（null 表示网盘根目录）。
     *
     * 服务端只回一个任务号，落盘是异步的，所以这里要轮询到完成为止。
     * 返回值是「提交成功的条目数」：服务端没有逐条回执，
     * 谎报比失败更糟，所以只报我们确实提交上去的数量。
     */
    suspend fun save(
        token: String?,
        source: ShareSource,
        entries: List<ShareEntry>,
        targetParentId: NodeId?,
    ): PanResult<Int> {
        if (entries.isEmpty()) {
            return PanResult.Failure(PanError.unknown("请先选择要转存的内容"))
        }
        val target = targetParentId?.value?.toLongOrNull() ?: 0L
        val body = buildJsonObject {
            put(
                "fileList",
                buildJsonArray {
                    entries.forEach { entry ->
                        val parts = (entry.ticket ?: "").split('|')
                        addJsonObject {
                            putEntry(entry, target, parts)
                        }
                    }
                },
            )
            put("shareKey", source.linkId)
            // 没有提取码要发空串，不能发 null：服务端会把它当成「参数缺失」。
            put("sharePwd", source.token)
            put("currentLevel", 1)
            put("superAdmin", JsonNull)
        }.toString()

        val created = when (
            val result = api.post(
                path = Pan123Api.PATH_COPY_SAVE,
                token = token,
                body = body,
                decode = { raw ->
                    json.decodeFromString(
                        ApiEnvelope.serializer(CopySaveData.serializer()),
                        raw,
                    )
                },
            )
        ) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }

        val taskId = created.taskId?.takeIf { it.isNotBlank() }
            ?: return PanResult.Failure(PanError.unknown("服务端没有下发转存任务号"))
        return awaitTask(token, taskId, entries.size)
    }

    /** 轮询转存任务直到完成或失败。 */
    private suspend fun awaitTask(
        token: String?,
        taskId: String,
        submitted: Int,
    ): PanResult<Int> {
        repeat(pollAttempts) {
            currentCoroutineContext().ensureActive()
            val task = when (
                val result = api.get(
                    path = Pan123Api.PATH_COPY_SAVE_GET + "?taskID=" + taskId,
                    token = token,
                    decode = { raw ->
                        json.decodeFromString(
                            ApiEnvelope.serializer(CopySaveTaskData.serializer()),
                            raw,
                        )
                    },
                )
            ) {
                is PanResult.Failure -> return result
                is PanResult.Success -> result.value
            }
            if (task.done) return PanResult.Success(submitted)
            delay(pollIntervalMs)
        }
        // 任务没在窗口内完成不算失败：文件通常已经在往目标目录搬了，
        // 说「失败」会让用户重试，反而多存一份。
        return PanResult.Failure(
            PanError(ErrorKind.SERVER, "转存还在进行，稍后到自己的网盘里看看", retryable = true),
        )
    }

    /** 单页列表请求。 */
    private suspend fun fetchPage(
        token: String?,
        shareKey: String,
        sharePwd: String,
        parentId: NodeId?,
        page: Int,
    ): PanResult<ShareFilesData> {
        val query = buildString {
            append(Pan123Api.PATH_SHARE_GET)
            append("?limit=").append(PAGE_SIZE)
            // next 固定发 "0"：分享接口的翻页靠 Page 递增，next 只是游标占位。
            append("&next=0")
            append("&orderBy=file_name")
            append("&orderDirection=asc")
            append("&shareKey=").append(encode(shareKey))
            append("&ParentFileId=").append(parentId?.value?.takeIf { it.isNotBlank() } ?: ROOT_PARENT)
            append("&Page=").append(page)
            // 无提取码时不能带空 SharePwd：服务端会回 400「请输入Next」。
            if (sharePwd.isNotBlank()) append("&SharePwd=").append(encode(sharePwd))
        }
        return api.get(
            path = query,
            token = token,
            decode = { raw ->
                json.decodeFromString(ApiEnvelope.serializer(ShareFilesData.serializer()), raw)
            },
        )
    }

    /**
     * 转存请求里的一条记录。
     *
     * 服务端对同一份数据同时接受大小写两套字段名（不同网关不同版本），
     * 两套都带上才稳妥；缺 s3keyFlag 会被判「文件不存在」。
     */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putEntry(
        entry: ShareEntry,
        targetParent: Long,
        parts: List<String>,
    ) {
        val flag = parts.getOrNull(0).orEmpty()
        val etag = parts.getOrNull(1).orEmpty()
        val node = parts.getOrNull(2).orEmpty()
        val id = entry.id.value.toLongOrNull() ?: 0L
        put("fileID", id)
        put("fileId", id)
        put("size", entry.size)
        put("etag", etag)
        put("type", if (entry.isDir) 1 else 0)
        put("parentFileID", targetParent)
        put("parentFileId", targetParent)
        put("fileName", entry.name)
        put("driveID", 0)
        put("driveId", 0)
        put("s3keyFlag", flag)
        put("S3KeyFlag", flag)
        put("StorageNode", node)
    }

    /** 把取件凭据拼成一个票据串。 */
    private fun ticketOf(dto: ShareFileItem): String? {
        val flag = dto.s3KeyFlag.orEmpty()
        if (flag.isBlank()) return null
        return flag + "|" + dto.etag.orEmpty() + "|" + dto.storageNode.orEmpty()
    }

    /** 手写 URL 编码：只放行 ASCII 未保留字符，其余按 UTF-8 百分号编码。 */
    private fun encode(value: String): String {
        val out = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val ch = byte.toInt() and 0xFF
            val c = ch.toChar()
            if (c.isLetterOrDigit() && ch < 0x80 || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append(c)
            } else {
                out.append('%')
                out.append(HEX[ch shr 4])
                out.append(HEX[ch and 0x0F])
            }
        }
        return out.toString()
    }

    companion object {
        const val DEFAULT_POLL_ATTEMPTS = 20

        const val DEFAULT_POLL_INTERVAL_MS = 500L

        /** 分享根目录的父 id，与网页端 / 公开实现一致。 */
        const val ROOT_PARENT = "0"

        /** Next 的终止值。 */
        private const val NO_MORE_PAGES = "-1"

        private const val PAGE_SIZE = 100

        private val HEX = "0123456789ABCDEF".toCharArray()
    }
}
