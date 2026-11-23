package com.panhub.provider.baidu

import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.PageRequest
import com.panhub.core.model.ShareEntry
import com.panhub.core.model.ShareEntryPage
import com.panhub.core.model.ShareSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * 打开别人的百度网盘分享，并把它转存到自己的网盘。
 *
 * 链路：
 * 1. `GET /api/shorturlinfo` 短链换 shareid / uk（匿名可调）；
 * 2. `POST /share/verify` 校验提取码，拿 randsk（没有密码就跳过）；
 * 3. `GET /share/list` 列目录（根目录用 root=1，子目录用 dir=<绝对路径>）；
 * 4. `POST /share/transfer` 转存（fsidlist + 目标绝对路径）。
 *
 * 三个只在真机上暴露的坑，都写进注释了：
 * - 短链接口要**带**首位 1 的 surl，提取码接口要**去掉**首位 1 的 surl；
 * - 提取码校验必须带分享页的 Referer，否则一律 errno 2；
 * - 分享列表的字段类型在根目录与子目录之间会从字符串变成数字。
 */
internal class BaiduShareImporter(
    private val api: BaiduApi,
    private val json: Json = api.json,
) {

    /**
     * 用分享链接换一个可以浏览的会话。
     *
     * [ShareSource.linkId] 放 shareid，[ShareSource.token] 放 `<uk>|<randsk>`
     * —— 百度没有 stoken 这种票据，但列目录要 uk、转存要 uk + sekey，
     * 两个都必须跟着分享走到最后，所以拼进 token 里，避免在 provider 上挂一层易失的缓存。
     */
    suspend fun open(cookie: String, shareUrl: String, passcode: String?): PanResult<ShareSource> {
        val parsed = BaiduShareLink.parse(shareUrl)
            ?: return PanResult.Failure(PanError.unknown("这不是百度网盘的分享链接"))
        val code = passcode?.trim()?.takeIf { it.isNotEmpty() } ?: parsed.passcode

        val info = when (val result = fetchShareInfo(cookie, parsed.surl)) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }
        if (info.shareid <= 0L || info.uk <= 0L) {
            return PanResult.Failure(PanError.unknown("没能从链接里解析出分享信息"))
        }

        // -9 表示「这条分享要提取码」，不是失败：shareid / uk 照样给全了。
        val needsCode = info.errno == NEED_CODE
        if (needsCode && code == null) {
            return PanResult.Failure(PanError.unknown("该分享需要提取码"))
        }
        var randsk = ""
        if (code != null) {
            randsk = when (val result = verify(cookie, parsed.surl, code)) {
                is PanResult.Failure -> return result
                is PanResult.Success -> result.value
            }
        }

        // 再列一次根目录确认真的能打开：提取码对了但分享被取消的样本很常见。
        val first = when (
            val result = fetchPage(cookie, info.shareid, info.uk, randsk, null, 1, FIRST_PAGE_SIZE)
        ) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }
        val title = first.title?.takeIf { it.isNotBlank() } ?: info.title?.takeIf { it.isNotBlank() }
        return PanResult.Success(
            ShareSource(
                linkId = info.shareid.toString(),
                token = ticketOf(info.uk, randsk),
                title = title,
            ),
        )
    }

    /** 列分享里的一个目录；[parentId] 为 null 表示分享根目录。 */
    suspend fun list(
        cookie: String,
        source: ShareSource,
        parentId: NodeId?,
        page: PageRequest,
    ): PanResult<ShareEntryPage> {
        val ticket = ticketOf(source.token)
            ?: return PanResult.Failure(PanError.unknown("分享会话已失效，请重新打开分享"))
        val pageIndex = page.cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val limit = page.limit.coerceIn(1, MAX_PAGE_SIZE)
        val data = when (
            val result = fetchPage(cookie, source.linkId.toLongOrNull() ?: 0L, ticket.uk, ticket.randsk, parentId, pageIndex, limit)
        ) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }

        val items = data.list.shareEntries().map { dto ->
            val path = dto.path?.takeIf { it.isNotBlank() }
                ?: BaiduProvider.joinPath(parentId?.value ?: "/", dto.name)
            ShareEntry(
                // 百度是路径寻址的，id 直接用分享内的绝对路径：进子目录时把它当 dir= 传回去。
                id = NodeId(path),
                parentId = parentId,
                name = dto.name,
                kind = if (dto.isDir) NodeKind.DIR else NodeKind.FILE,
                // 转存要的是 fs_id，不是路径，所以票据里放它。
                ticket = dto.fsId.toString(),
                size = dto.size,
                modifiedAt = dto.modifiedAt,
            )
        }
        // 分享列表没有 has_more，只能按「这一页拿满了就还有下一页」判断。
        val next = if (items.size >= limit) (pageIndex + 1).toString() else null
        return PanResult.Success(ShareEntryPage(items = items, nextCursor = next))
    }

    /**
     * 把选中的条目转存到 [targetParentId]（null 表示网盘根目录）。
     *
     * 返回真正存进去的条目数：服务端会在 info[] 里逐项回执，
     * 有一部分失败时既不能报全成功，也不能把成功的那部分一起丢掉。
     */
    suspend fun save(
        cookie: String,
        source: ShareSource,
        entries: List<ShareEntry>,
        targetParentId: NodeId?,
    ): PanResult<Int> {
        if (entries.isEmpty()) {
            return PanResult.Failure(PanError.unknown("请先选择要转存的内容"))
        }
        val ticket = ticketOf(source.token)
            ?: return PanResult.Failure(PanError.unknown("分享会话已失效，请重新打开分享"))
        val fsIds = entries.mapNotNull { it.ticket?.toLongOrNull() }
        if (fsIds.isEmpty()) {
            return PanResult.Failure(PanError.unknown("没拿到要转存的文件标识，请重新打开分享"))
        }
        val bdstoken = when (val result = api.fetchBdstoken(cookie)) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }

        val query = mutableListOf(
            "from" to ticket.uk.toString(),
            "shareid" to (source.linkId.toLongOrNull() ?: 0L).toString(),
            "bdstoken" to bdstoken,
            "ondup" to ONDUP,
        )
        // 带提取码的分享要在转存时回传同一个 randsk，否则服务端认为你没被授权。
        if (ticket.randsk.isNotBlank()) query += "sekey" to ticket.randsk

        val body = listOf(
            "fsidlist" to buildJsonArray { fsIds.forEach { add(JsonPrimitive(it)) } }.toString(),
            "path" to (targetParentId?.value?.takeIf { it.isNotBlank() } ?: BaiduApi.ROOT_PATH),
        )
        val raw = api.postFormRaw(
            path = BaiduApi.PATH_SHARE_TRANSFER,
            cookie = cookieWithRandsk(cookie, ticket.randsk),
            form = body,
            query = query,
        )
        val text = when (raw) {
            is PanResult.Failure -> return raw
            is PanResult.Success -> raw.value
        }
        val resp = runCatching { json.decodeFromString(BaiduShareTransferResp.serializer(), text) }
            .getOrElse {
                return PanResult.Failure(PanError.unknown("无法解析转存响应", text.take(300)))
            }
        if (resp.errno != 0) {
            return PanResult.Failure(transferError(resp.errno, resp.showMsg ?: resp.errmsg))
        }
        val saved = countSaved(resp.info)
        if (saved == 0) {
            return PanResult.Failure(PanError.unknown(firstInfoError(resp.info) ?: "转存失败"))
        }
        return PanResult.Success(saved)
    }

    /** 短链 -> shareid / uk。匿名可调，不需要登录态。 */
    private suspend fun fetchShareInfo(cookie: String, surl: String): PanResult<BaiduShortUrlInfoResp> {
        val raw = api.getRaw(
            path = BaiduApi.PATH_SHORTURL_INFO,
            cookie = cookie,
            query = listOf("shorturl" to BaiduShareLink.normalize(surl), "root" to "1"),
        )
        val text = when (raw) {
            is PanResult.Failure -> return raw
            is PanResult.Success -> raw.value
        }
        val resp = runCatching { json.decodeFromString(BaiduShortUrlInfoResp.serializer(), text) }
            .getOrElse {
                return PanResult.Failure(PanError.unknown("无法解析分享链接信息", text.take(300)))
            }
        return when (resp.errno) {
            // -9 = 需要提取码，但 shareid / uk 已经给全，继续往下走。
            0, NEED_CODE -> PanResult.Success(resp)
            CANCELLED -> PanResult.Failure(PanError.notFound("这条分享已被取消"))
            BAD_LINK -> PanResult.Failure(PanError.unknown("分享链接不正确"))
            else -> PanResult.Failure(
                PanError.unknown(resp.showMsg ?: resp.errmsg ?: "分享链接不可用（errno " + resp.errno + "）"),
            )
        }
    }

    /** 校验提取码，返回 randsk。 */
    private suspend fun verify(cookie: String, surl: String, passcode: String): PanResult<String> {
        val raw = api.postFormRaw(
            path = BaiduApi.PATH_SHARE_VERIFY,
            cookie = cookie,
            form = listOf("pwd" to passcode, "vcode" to "", "vcode_str" to ""),
            // 提取码校验不带分享页 Referer 一律 errno 2，实测过。
            query = listOf("surl" to BaiduShareLink.shortForm(surl)),
            extraHeaders = mapOf("Referer" to BaiduApi.sharePageReferer(BaiduShareLink.normalize(surl))),
        )
        val text = when (raw) {
            is PanResult.Failure -> return raw
            is PanResult.Success -> raw.value
        }
        val resp = runCatching { json.decodeFromString(BaiduShareVerifyResp.serializer(), text) }
            .getOrElse {
                return PanResult.Failure(PanError.unknown("无法解析提取码校验结果", text.take(300)))
            }
        return when (resp.errno) {
            0 -> PanResult.Success(resp.randsk.orEmpty())
            // -12 = 这条分享本来就没有密码，用户多填了；当作通过。
            NO_PASSWORD -> PanResult.Success("")
            -9 -> PanResult.Failure(PanError.auth("提取码不正确"))
            else -> PanResult.Failure(
                PanError.unknown(resp.showMsg ?: resp.errmsg ?: "提取码校验失败（errno " + resp.errno + "）"),
            )
        }
    }

    /** 单页列表。根目录用 root=1，子目录用 dir=<绝对路径>（dir=/ 会 errno 2）。 */
    private suspend fun fetchPage(
        cookie: String,
        shareId: Long,
        uk: Long,
        randsk: String,
        parentId: NodeId?,
        page: Int,
        limit: Int,
    ): PanResult<BaiduShareListResp> {
        val query = mutableListOf(
            "shareid" to shareId.toString(),
            "uk" to uk.toString(),
            "page" to page.toString(),
            "num" to limit.toString(),
        )
        val parent = parentId?.value?.takeIf { it.isNotBlank() }
        if (parent == null) query += "root" to "1" else query += "dir" to parent

        val raw = api.getRaw(
            path = BaiduApi.PATH_SHARE_LIST,
            cookie = cookieWithRandsk(cookie, randsk),
            query = query,
        )
        val text = when (raw) {
            is PanResult.Failure -> return raw
            is PanResult.Success -> raw.value
        }
        val resp = runCatching { json.decodeFromString(BaiduShareListResp.serializer(), text) }
            .getOrElse {
                return PanResult.Failure(PanError.unknown("无法解析分享目录", text.take(300)))
            }
        return when (resp.errno) {
            0 -> PanResult.Success(resp)
            CANCELLED -> PanResult.Failure(PanError.notFound("这条分享已被取消"))
            NEED_VERIFY_CODE -> PanResult.Failure(PanError.auth("提取码已失效，请重新输入"))
            else -> PanResult.Failure(
                PanError.unknown(resp.showMsg ?: resp.errmsg ?: "分享目录打开失败（errno " + resp.errno + "）"),
            )
        }
    }

    /** 转存的 cookie 要额外带上 randsk，服务端靠它确认「你验过提取码」。 */
    private fun cookieWithRandsk(cookie: String, randsk: String): String =
        if (randsk.isBlank()) cookie else cookie + "; BDCLND=" + randsk

    private fun ticketOf(uk: Long, randsk: String): String = uk.toString() + "|" + randsk

    private fun ticketOf(token: String): Ticket? {
        if (token.isBlank()) return null
        val uk = token.substringBefore('|').toLongOrNull() ?: return null
        if (uk <= 0L) return null
        return Ticket(uk = uk, randsk = token.substringAfter('|', ""))
    }

    private fun countSaved(info: kotlinx.serialization.json.JsonElement?): Int {
        val array = info as? JsonArray ?: return 0
        return array.count { element ->
            val obj = element as? JsonObject ?: return@count false
            ((obj["errno"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0) == 0
        }
    }

    private fun firstInfoError(info: kotlinx.serialization.json.JsonElement?): String? {
        val array = info as? JsonArray ?: return null
        array.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val errno = (obj["errno"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            if (errno != 0) {
                val message = (obj["errmsg"] as? JsonPrimitive)?.content
                    ?: (obj["show_msg"] as? JsonPrimitive)?.content
                return message ?: "转存失败（errno " + errno + "）"
            }
        }
        return null
    }

    private fun transferError(errno: Int, message: String?): PanError = when (errno) {
        CANCELLED -> PanError.notFound("这条分享已被取消")
        -6, 111 -> PanError.auth("登录态已失效，请重新粘贴 Cookie")
        -9 -> PanError.auth("提取码已失效，请重新打开分享")
        -10 -> PanError.unknown("云盘容量不足")
        12 -> PanError.notFound("目标目录不存在")
        else -> PanError.unknown(message ?: "转存失败（errno " + errno + "）")
    }

    private data class Ticket(val uk: Long, val randsk: String)

    companion object {
        /** 转存遇到同名文件时新建副本，绝不覆盖用户已有文件。 */
        private const val ONDUP = "newcopy"

        private const val NEED_CODE = -9

        private const val CANCELLED = -21

        private const val BAD_LINK = 140

        private const val NO_PASSWORD = -12

        /** 提取码校验失败。 */
        private const val NEED_VERIFY_CODE = -9

        private const val MAX_PAGE_SIZE = 1000

        private const val FIRST_PAGE_SIZE = 100
    }
}
