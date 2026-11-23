package com.panhub.provider.quark

import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.common.map
import com.panhub.core.model.NodeId
import com.panhub.core.model.PageRequest
import com.panhub.core.model.ShareId
import com.panhub.core.model.ShareLink
import com.panhub.core.model.ShareOptions
import com.panhub.core.model.SharePage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 夸克的分享流程。
 *
 * 与别的网盘最大的不同是**创建是异步的**：POST /share 只返回一个 task_id，
 * 真正的 share_id 要轮询 GET /task 拿到（status=2 完成、3 失败），
 * 最后还要 POST /share/password 才换到能发出去的链接与提取码。
 * 少任何一步都拿不到链接，所以三步都在这里串好，上层只看到一个 createShare。
 *
 * 事实来源：qrypt pkg/drivers/quark/share.go 与 QuarkPanTool quark.py
 * （两者用真实账号跑通，字段与流程一致）。
 */
internal class QuarkShareManager(
    private val api: QuarkApi,
    private val pollAttempts: Int = DEFAULT_POLL_ATTEMPTS,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {

    suspend fun create(
        cookie: String,
        nodeIds: List<NodeId>,
        options: ShareOptions,
        onRenewed: (Map<String, String>) -> Unit,
    ): PanResult<ShareLink> {
        if (nodeIds.isEmpty()) {
            return PanResult.Failure(PanError.unknown("请先选择要分享的文件"))
        }

        val passcode = options.password?.trim()?.takeIf { it.isNotEmpty() }
        val expiredType = QuarkShareParsing.expiredTypeOf(options.expireDays)
        val urlType = if (passcode == null) {
            QuarkShareParsing.URL_TYPE_PUBLIC
        } else {
            QuarkShareParsing.URL_TYPE_PRIVATE
        }

        val body = buildJsonObject {
            put("fid_list", buildJsonArray { nodeIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.value)) } })
            put("url_type", urlType)
            put("expired_type", expiredType)
            // 标题不能是空串：服务端会把它当成「用户起了一个空名字」而不是「没起名字」。
            options.description?.trim()?.takeIf { it.isNotEmpty() }?.let { put("title", it) }
            passcode?.let { put("passcode", it) }
        }

        val created = when (
            val result = api.postJson(QuarkApi.PATH_SHARE, cookie, body.toString())
        ) {
            is PanResult.Failure -> return result
            is PanResult.Success -> {
                onRenewed(result.value.renewed)
                when (
                    val envelope = api.decode(
                        result.value,
                        QuarkEnvelope.serializer(QuarkShareCreateData.serializer()),
                    )
                ) {
                    is PanResult.Failure -> return envelope
                    is PanResult.Success -> envelope.value.data
                }
            }
        }

        val shareId = when (val resolved = resolveShareId(cookie, created, onRenewed)) {
            is PanResult.Failure -> return resolved
            is PanResult.Success -> resolved.value
        }

        return password(cookie, shareId, nodeIds, onRenewed)
    }

    /**
     * 从创建响应里拿到 share_id：已经同步完成的直接用，否则轮询任务。
     *
     * 服务端有时把任务直接塞在 task_resp 里返回（task_sync=true），
     * 这时再轮询一次是多余的网络往返，也会让本来就慢的分享更慢。
     */
    private suspend fun resolveShareId(
        cookie: String,
        created: QuarkShareCreateData?,
        onRenewed: (Map<String, String>) -> Unit,
    ): PanResult<String> {
        val inline = created?.taskResp?.data
        if (inline?.status == QuarkShareParsing.TASK_STATUS_DONE) {
            val id = inline.shareId?.takeIf { it.isNotBlank() }
            if (id != null) return PanResult.Success(id)
        }
        val taskId = created?.taskId?.takeIf { it.isNotBlank() }
            ?: return PanResult.Failure(PanError.unknown("服务端没有下发分享任务号"))

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
                val envelope = api.decode(raw, QuarkEnvelope.serializer(QuarkShareTask.serializer()))
            ) {
                is PanResult.Failure -> return envelope
                is PanResult.Success -> envelope.value.data
            }
            when (task?.status) {
                QuarkShareParsing.TASK_STATUS_DONE -> {
                    val id = task.shareId?.takeIf { it.isNotBlank() }
                        ?: return PanResult.Failure(PanError.unknown("分享任务完成但没有返回分享 id"))
                    return PanResult.Success(id)
                }
                QuarkShareParsing.TASK_STATUS_FAILED -> {
                    return PanResult.Failure(
                        PanError.unknown(task.errorMessage ?: "服务端拒绝了这次分享"),
                    )
                }
                else -> {
                    retry++
                    delay(pollIntervalMs)
                }
            }
        }
        return PanResult.Failure(
            PanError(ErrorKind.SERVER, "分享生成超时，请稍后在分享列表里查看", retryable = true),
        )
    }

    /** 拿分享 id 换真正的链接与提取码。 */
    private suspend fun password(
        cookie: String,
        shareId: String,
        nodeIds: List<NodeId>,
        onRenewed: (Map<String, String>) -> Unit,
    ): PanResult<ShareLink> {
        val body = buildJsonObject { put("share_id", shareId) }
        return when (
            val result = api.postJson(QuarkApi.PATH_SHARE_PASSWORD, cookie, body.toString())
        ) {
            is PanResult.Failure -> result
            is PanResult.Success -> {
                onRenewed(result.value.renewed)
                when (
                    val envelope = api.decode(
                        result.value,
                        QuarkEnvelope.serializer(QuarkSharePasswordData.serializer()),
                    )
                ) {
                    is PanResult.Failure -> envelope
                    is PanResult.Success -> {
                        val data = envelope.value.data
                            ?: return PanResult.Failure(PanError.unknown("分享链接为空"))
                        QuarkShareParsing.toShareLink(data, nodeIds)
                            ?.let { PanResult.Success(it) }
                            ?: PanResult.Failure(PanError.unknown("分享链接为空"))
                    }
                }
            }
        }
    }

    suspend fun cancel(
        cookie: String,
        shareId: String,
        onRenewed: (Map<String, String>) -> Unit,
    ): PanResult<Unit> {
        if (shareId.isBlank()) {
            return PanResult.Failure(PanError.unknown("分享 id 为空，无法取消"))
        }
        val body = buildJsonObject {
            put(
                "share_ids",
                buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(shareId)) },
            )
        }
        return when (val result = api.postJson(QuarkApi.PATH_SHARE_DELETE, cookie, body.toString())) {
            is PanResult.Failure -> result
            is PanResult.Success -> {
                onRenewed(result.value.renewed)
                PanResult.Success(Unit)
            }
        }
    }

    suspend fun list(
        cookie: String,
        page: PageRequest,
        onRenewed: (Map<String, String>) -> Unit,
    ): PanResult<SharePage> {
        val pageIndex = page.cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val pageSize = if (page.limit in 1..MAX_PAGE_SIZE) page.limit else MAX_PAGE_SIZE
        val raw = when (
            val result = api.get(
                QuarkApi.PATH_SHARE_MYPAGE,
                cookie,
                listOf(
                    "_page" to pageIndex.toString(),
                    "_size" to pageSize.toString(),
                    "_sort" to "created_at:desc",
                    "_fetch_total" to "1",
                ),
            )
        ) {
            is PanResult.Failure -> return result
            is PanResult.Success -> {
                onRenewed(result.value.renewed)
                result.value
            }
        }
        return api.decode(raw, QuarkEnvelope.serializer(QuarkShareListData.serializer()))
            .map { envelope ->
                val items = envelope.data?.list.orEmpty().mapNotNull(QuarkShareParsing::toShareLink)
                val total = envelope.metadata?.total
                val next = if (total != null && pageIndex * pageSize < total) {
                    (pageIndex + 1).toString()
                } else {
                    // 没有总数时只能靠「本页是否满」判断，宁可多请求一页也不丢分享。
                    if (items.size >= pageSize) (pageIndex + 1).toString() else null
                }
                SharePage(items = items, nextCursor = next)
            }
    }

    /** 取消分享用不到，但列表里要能拿到 id 以便上层调用 cancel。 */
    fun shareIdOf(link: ShareLink): String = link.id.value

    companion object {
        const val DEFAULT_POLL_ATTEMPTS = 20

        const val DEFAULT_POLL_INTERVAL_MS = 500L

        private const val MAX_PAGE_SIZE = 100
    }
}
