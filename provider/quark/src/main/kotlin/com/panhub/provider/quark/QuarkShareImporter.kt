package com.panhub.provider.quark

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 打开别人的夸克分享，并把它转存到自己的网盘。
 *
 * 三个动作串成一条链：
 * 1. POST /share/sharepage/token 用「分享 id + 提取码」换 stoken；
 * 2. GET  /share/sharepage/detail 用 stoken 列目录（每一项带自己的 share_fid_token）；
 * 3. POST /share/sharepage/save 用 fid + 票据换一次转存，返回 task_id 再轮询。
 *
 * 为什么把三步放在同一个对象里：stoken 只有这里能拿到，而列目录与转存都必须带它，
 * 拆开就要让上层持有并透传一个它完全不理解的字符串 —— 那正是最容易泄漏
 * 服务商细节、也最容易在重构时被漏掉的地方。
 *
 * 事实来源：qrypt pkg/drivers/quark/share.go 的 OpenShare / ListShareEntries /
 * SaveShareEntries（Go 实现，用真实账号跑通）。
 */
internal class QuarkShareImporter(
    private val api: QuarkApi,
    /** 只用来在报错里说清是哪家的分享。 */
    private val brand: CloudDriveBrand = CloudDriveBrand.Quark,
    private val pollAttempts: Int = DEFAULT_POLL_ATTEMPTS,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {

    /** 用分享链接换会话票据。 */
    suspend fun open(
        cookie: String,
        shareUrl: String,
        passcode: String?,
        onRenewed: (Map<String, String>) -> Unit,
    ): PanResult<ShareSource> {
        val parsed = QuarkShareLink.parse(shareUrl)
            ?: return PanResult.Failure(PanError.unknown("这不是" + brand.displayName + "的分享链接"))
        // 链接里带的提取码优先：用户是从别处复制过来的，通常已经带了。
        val code = passcode?.trim()?.takeIf { it.isNotEmpty() } ?: parsed.passcode

        val body = buildJsonObject {
            put("pwd_id", parsed.linkId)
            put("passcode", code.orEmpty())
            put("support_visit_limit_private_share", true)
        }.toString()

        val raw = when (
            val result = api.postJson(QuarkApi.PATH_SHARE_PAGE_TOKEN, cookie, body)
        ) {
            is PanResult.Failure -> return result
            is PanResult.Success -> {
                onRenewed(result.value.renewed)
                result.value
            }
        }

        val data = when (
            val envelope = api.decode(raw, QuarkEnvelope.serializer(QuarkShareTokenData.serializer()))
        ) {
            is PanResult.Failure -> return envelope
            is PanResult.Success -> envelope.value.data
        }

        val stoken = data?.stoken?.takeIf { it.isNotBlank() }
            ?: return PanResult.Failure(PanError.unknown("分享打不开，可能链接已失效或提取码不对"))
        return PanResult.Success(
            ShareSource(
                linkId = parsed.linkId,
                token = stoken,
                title = data.title?.takeIf { it.isNotBlank() },
                ownerName = data.author?.nickName?.takeIf { it.isNotBlank() },
                expiresAt = QuarkShareParsing.expiresAtOf(data.expiredAt),
            ),
        )
    }

    /** 列分享里的一个目录；[parentId] 为 null 表示分享根目录。 */
    suspend fun list(
        cookie: String,
        source: ShareSource,
        parentId: NodeId?,
        page: PageRequest,
        onRenewed: (Map<String, String>) -> Unit,
    ): PanResult<ShareEntryPage> {
        val pdir = parentId?.value?.takeIf { it.isNotBlank() } ?: QuarkApi.ROOT_ID
        val pageIndex = page.cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val pageSize = if (page.limit in 1..MAX_PAGE_SIZE) page.limit else MAX_PAGE_SIZE

        val query = mutableListOf(
            "pwd_id" to source.linkId,
            "stoken" to source.token,
            "pdir_fid" to pdir,
            "force" to "0",
            "_page" to pageIndex.toString(),
            "_size" to pageSize.toString(),
            "ver" to "2",
            "_fetch_total" to "1",
            "_sort" to "file_type:asc,file_name:asc",
            "fetch_update_flag" to "1",
        )
        // 根目录要多带两个参数，服务端才会下发分享标题与横幅；子目录带了反而多余。
        if (pdir == QuarkApi.ROOT_ID) {
            query += "_fetch_banner" to "1"
            query += "_fetch_share" to "1"
        }

        val raw = when (
            val result = api.get(QuarkApi.PATH_SHARE_PAGE_DETAIL, cookie, query)
        ) {
            is PanResult.Failure -> return result
            is PanResult.Success -> {
                onRenewed(result.value.renewed)
                result.value
            }
        }

        val envelope = when (
            val decoded = api.decode(
                raw,
                QuarkEnvelope.serializer(QuarkShareDetailData.serializer()),
            )
        ) {
            is PanResult.Failure -> return decoded
            is PanResult.Success -> decoded.value
        }

        val items = envelope.data?.list.orEmpty().mapNotNull { dto ->
            val fid = dto.fid?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ShareEntry(
                id = NodeId(fid),
                parentId = NodeId(pdir),
                name = QuarkParsing.unescape(dto.fileName.orEmpty()).ifBlank { fid },
                kind = if (dto.dir == true) NodeKind.DIR else NodeKind.FILE,
                ticket = dto.shareFidToken?.takeIf { it.isNotBlank() },
                size = dto.size ?: 0L,
                modifiedAt = dto.updatedAt?.takeIf { it > 0L },
            )
        }

        val total = envelope.metadata?.total
        val next = if (total != null) {
            if (pageIndex * pageSize < total) (pageIndex + 1).toString() else null
        } else {
            if (items.size >= pageSize) (pageIndex + 1).toString() else null
        }
        return PanResult.Success(ShareEntryPage(items = items, nextCursor = next))
    }

    /**
     * 把选中的条目转存到 [targetParentId]。
     *
     * 返回值是「真正落进目标目录的条目数」：服务端可能只成功一部分，
     * 这时谎报全部成功比失败更糟 —— 用户会以为文件在，回头找不到。
     */
    suspend fun save(
        cookie: String,
        source: ShareSource,
        entries: List<ShareEntry>,
        targetParentId: NodeId?,
        onRenewed: (Map<String, String>) -> Unit,
    ): PanResult<Int> {
        if (entries.isEmpty()) {
            return PanResult.Failure(PanError.unknown("请先选择要转存的内容"))
        }
        val tickets = entries.map { it.ticket?.takeIf { t -> t.isNotBlank() } }
        if (tickets.any { it == null }) {
            return PanResult.Failure(PanError.unknown("分享里的条目缺少取件凭据，请重新打开分享"))
        }
        // pdir_fid 必须是条目所在的分享目录，服务端据此判断这些 fid 出自哪里。
        val pdir = entries.firstNotNullOfOrNull { it.parentId?.value?.takeIf { v -> v.isNotBlank() } }
            ?: QuarkApi.ROOT_ID

        val body = buildJsonObject {
            put("fid_list", buildJsonArray { entries.forEach { add(JsonPrimitive(it.id.value)) } })
            put("fid_token_list", buildJsonArray { tickets.forEach { add(JsonPrimitive(it.orEmpty())) } })
            put("to_pdir_fid", targetParentId?.value?.takeIf { it.isNotBlank() } ?: QuarkApi.ROOT_ID)
            put("pwd_id", source.linkId)
            put("stoken", source.token)
            put("pdir_fid", pdir)
            put("scene", "link")
            put("exclude_fids", buildJsonArray { })
        }.toString()

        val raw = when (
            val result = api.postJson(QuarkApi.PATH_SHARE_PAGE_SAVE, cookie, body)
        ) {
            is PanResult.Failure -> return result
            is PanResult.Success -> {
                onRenewed(result.value.renewed)
                result.value
            }
        }

        val created = when (
            val envelope = api.decode(raw, QuarkEnvelope.serializer(QuarkShareSaveData.serializer()))
        ) {
            is PanResult.Failure -> return envelope
            is PanResult.Success -> envelope.value.data
        }

        val inline = created?.taskResp?.data
        if (inline?.status == QuarkShareParsing.TASK_STATUS_DONE) {
            return PanResult.Success(savedCount(inline, entries.size))
        }
        val taskId = created?.taskId?.takeIf { it.isNotBlank() }
            ?: return PanResult.Failure(PanError.unknown("服务端没有下发转存任务号"))

        return awaitTask(cookie, taskId, entries.size, onRenewed)
    }

    /** 轮询转存任务直到完成或失败。 */
    private suspend fun awaitTask(
        cookie: String,
        taskId: String,
        requested: Int,
        onRenewed: (Map<String, String>) -> Unit,
    ): PanResult<Int> {
        var retry = 0
        repeat(pollAttempts) {
            currentCoroutineContext().ensureActive()
            val raw = when (
                val result = api.get(
                    QuarkApi.PATH_TASK,
                    cookie,
                    listOf("task_id" to taskId, "retry_index" to retry.toString()),
                )
            ) {
                is PanResult.Failure -> return result
                is PanResult.Success -> {
                    onRenewed(result.value.renewed)
                    result.value
                }
            }
            val task = when (
                val envelope = api.decode(
                    raw,
                    QuarkEnvelope.serializer(QuarkShareSaveTask.serializer()),
                )
            ) {
                is PanResult.Failure -> return envelope
                is PanResult.Success -> envelope.value.data
            }
            when (task?.status) {
                QuarkShareParsing.TASK_STATUS_DONE -> return PanResult.Success(savedCount(task, requested))
                QuarkShareParsing.TASK_STATUS_FAILED -> return PanResult.Failure(
                    PanError.unknown(task.errorMessage ?: "转存被服务端拒绝"),
                )
                else -> {
                    retry++
                    delay(pollIntervalMs)
                }
            }
        }
        return PanResult.Failure(
            PanError(ErrorKind.SERVER, "转存还在进行，稍后到自己的网盘里看看", retryable = true),
        )
    }

    /**
     * 成功条目数。
     *
     * 优先用服务端给的 save_as_sum_num；没有就数 top_fids，但它最多 100 条，
     * 所以数量对不上时按「用户选了几项」算 —— 宁可说成功全部，也不要说成功 0 项。
     */
    private fun savedCount(task: QuarkShareSaveTask, requested: Int): Int {
        val reported = task.saveAs?.sumNum ?: 0
        if (reported > 0) return reported
        val top = task.saveAs?.topFids.orEmpty().size
        if (top > 0) return if (requested > top) requested else top
        return requested
    }

    companion object {
        const val DEFAULT_POLL_ATTEMPTS = 20

        const val DEFAULT_POLL_INTERVAL_MS = 500L

        private const val MAX_PAGE_SIZE = 100
    }
}
