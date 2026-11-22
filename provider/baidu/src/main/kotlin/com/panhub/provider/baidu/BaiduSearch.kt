package com.panhub.provider.baidu

import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.NodePage
import com.panhub.core.model.PageRequest
import com.panhub.core.model.RemoteNode
import com.panhub.provider.api.ProviderSession
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 搜索响应。
 *
 * 与列表接口不同，搜索的结果里没有 parent 目录字段——它是全盘递归的，
 * 所以这里只能从 path 反推父目录（见 [BaiduSearchItem.toRemoteNode]）。
 */
@Serializable
internal data class BaiduSearchResp(
    @SerialName("errno") val errno: Int = 0,
    @SerialName("errmsg") val errmsg: String? = null,
    @SerialName("show_msg") val showMsg: String? = null,
    @SerialName("list") val list: List<BaiduSearchItem>? = null,
    @SerialName("has_more") val hasMore: Int = 0,
)

/** 与列表共用同一套字段，但服务端也可能省略 thumbs/md5，所以全部可选。 */
@Serializable
internal data class BaiduSearchItem(
    @SerialName("fs_id") val fsId: Long? = null,
    @SerialName("path") val path: String? = null,
    @SerialName("server_filename") val serverFilename: String? = null,
    @SerialName("size") val size: Long = 0L,
    @SerialName("isdir") val isdir: Int = 0,
    @SerialName("server_mtime") val serverMtime: Long? = null,
    @SerialName("server_ctime") val serverCtime: Long? = null,
    @SerialName("category") val category: Int? = null,
)

/**
 * 搜索结果 -> 领域节点。
 *
 * 父目录从路径里切出来而不是留空：界面要靠它显示「在哪个目录里」，
 * 而且从搜索结果直接进目录时需要一个能用的 parent。根目录仍用 "/" 表示。
 */
internal fun BaiduSearchItem.toRemoteNode(): RemoteNode? {
    val full = path ?: return null
    val parentPath = full.substringBeforeLast('/', "")
    return RemoteNode(
        id = NodeId(full),
        parentId = NodeId(if (parentPath.isEmpty()) BaiduApi.ROOT_PATH else parentPath),
        name = serverFilename ?: full.substringAfterLast('/'),
        kind = if (isdir == 1) NodeKind.DIR else NodeKind.FILE,
        size = size,
        modifiedAt = serverMtime?.takeIf { it > 0 }?.times(1000),
        createdAt = serverCtime?.takeIf { it > 0 }?.times(1000),
    )
}

/**
 * 搜索一次。
 *
 * 两个已知约束：
 * - 必须带 bdstoken，网页版用它防 CSRF；缺了会返回 errno -6，与「未登录」同一个码，
 *   所以要先把 token 拿回来再判断登录态。
 * - page 与 num 是页码与每页条数，没有游标；has_more == 0 就是最后一页。
 */
internal suspend fun BaiduApi.search(
    cookie: String,
    keyword: String,
    page: PageRequest,
): PanResult<NodePage> {
    val token = when (val template = fetchBdstoken(cookie)) {
        is PanResult.Failure -> return template
        is PanResult.Success -> template.value
    }
    val pageIndex = page.cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val pageSize = if (page.limit in 1..1000) page.limit else 100
    val raw = get(
        path = BaiduApi.PATH_SEARCH,
        cookie = cookie,
        query = listOf(
            "key" to keyword,
            "recursion" to "1",
            "order" to "time",
            "desc" to "1",
            "showempty" to "0",
            "page" to pageIndex.toString(),
            "num" to pageSize.toString(),
            "bdstoken" to token,
        ),
    )
    return when (raw) {
        is PanResult.Failure -> raw
        is PanResult.Success -> when (val resp = decodeSearch(raw.value)) {
            is PanResult.Failure -> resp
            is PanResult.Success -> {
                val data = resp.value
                val items = data.list.orEmpty().mapNotNull { it.toRemoteNode() }
                PanResult.Success(
                    NodePage(
                        items = items,
                        nextCursor = if (data.hasMore == 1) (pageIndex + 1).toString() else null,
                    ),
                )
            }
        }
    }
}

private fun BaiduApi.decodeSearch(raw: String): PanResult<BaiduSearchResp> =
    runCatching { json.decodeFromString(BaiduSearchResp.serializer(), raw) }
        .fold(
            onSuccess = { PanResult.Success(it) },
            onFailure = { PanResult.Failure(PanError.unknown("无法解析搜索响应", raw.take(300))) },
        )

/** bdstoken 会随登录态变化，每次搜索现取，不在会话里缓存过期值。 */
private suspend fun BaiduApi.fetchBdstoken(cookie: String): PanResult<String> {
    val raw = get(
        path = BaiduApi.PATH_TEMPLATE,
        cookie = cookie,
        query = listOf("fields" to """["bdstoken"]"""),
    )
    return when (raw) {
        is PanResult.Failure -> raw
        is PanResult.Success -> runCatching {
            json.decodeFromString(BaiduTemplateResp.serializer(), raw.value)
        }.fold(
            onSuccess = { resp ->
                val token = resp.result?.firstOrNull()?.bdstoken
                if (token.isNullOrBlank()) {
                    PanResult.Failure(PanError.auth("没能取到 bdstoken，Cookie 可能已失效"))
                } else {
                    PanResult.Success(token)
                }
            },
            onFailure = {
                PanResult.Failure(PanError.unknown("无法解析 bdstoken 响应", raw.value.take(300)))
            },
        )
    }
}