package com.panhub.provider.webdav

import com.panhub.core.model.NodeKind
import com.panhub.core.model.Quota
import com.panhub.core.model.RemoteNode
import com.panhub.core.model.NodeId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 一个症状极小的 WebDAV multistatus 解析器。
 *
 * 故意不引入 XML 库：响应结构固定，用正则按 <response> 切块足够，
 * 而且避免了 Android 上 XmlPullParser 的命名空间坑。
 * 所有标签都做「带不带 D: 前缀」双匹配。
 */
object WebDavXml {

    private val responseRegex = Regex(
        "<(?:[A-Za-z0-9]+:)?response[^>]*>(.*?)</(?:[A-Za-z0-9]+:)?response>",
        RegexOption.DOT_MATCHES_ALL,
    )

    private val hrefRegex = Regex(
        "<(?:[A-Za-z0-9]+:)?href[^>]*>(.*?)</(?:[A-Za-z0-9]+:)?href>",
        RegexOption.DOT_MATCHES_ALL,
    )

    private val collectionRegex = Regex(
        "<(?:[A-Za-z0-9]+:)?collection\s*/>|<(?:[A-Za-z0-9]+:)?collection\s*>",
    )

    fun tagValues(xml: String, tag: String): String? {
        val re = Regex(
            "<(?:[A-Za-z0-9]+:)?$tag(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9]+:)?$tag>",
            RegexOption.DOT_MATCHES_ALL,
        )
        return re.find(xml)?.groupValues?.get(1)?.trim()
    }

    private fun decodeHref(href: String): String {
        val withoutScheme = href.substringAfter("://", href)
        val pathStart = withoutScheme.indexOf('/')
        val raw = if (pathStart >= 0) withoutScheme.substring(pathStart) else withoutScheme
        return java.net.URLDecoder.decode(raw, "UTF-8").trimEnd('/')
    }

    /**
     * 解析目录列表。第一项通常是目录自己，用 [selfHref] 过滤掉。
     */
    fun parseList(xml: String, selfHref: String?): List<RemoteNode> {
        val self = selfHref?.trimEnd('/')
        return responseRegex.findAll(xml).mapNotNull { match ->
            val block = match.groupValues[1]
            val href = hrefRegex.find(block)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val decoded = decodeHref(href)
            if (decoded.isBlank() || decoded == self) return@mapNotNull null

            val name = decoded.substringAfterLast('/')
            if (name.isBlank()) return@mapNotNull null

            val isDir = collectionRegex.containsMatchIn(block)
            val size = tagValues(block, "getcontentlength")?.toLongOrNull() ?: 0L
            val modified = parseDate(tagValues(block, "getlastmodified"))
            val mime = tagValues(block, "getcontenttype")

            RemoteNode(
                id = NodeId(decoded),
                parentId = null,
                name = name,
                kind = if (isDir) NodeKind.DIR else NodeKind.FILE,
                size = size,
                modifiedAt = modified,
                mimeType = if (isDir) null else mime,
                providerPath = decoded,
            )
        }.toList()
    }

    /** 从根目录 PROPFIND 里读配额；坚果云可能在根响应里带 quota 字段。 */
    fun parseQuota(xml: String): Quota? {
        val used = tagValues(xml, "quota-used-bytes")?.toLongOrNull() ?: return null
        val available = tagValues(xml, "quota-available-bytes")?.toLongOrNull()
        return Quota(used = used, total = available?.let { it + used })
    }

    fun parseDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
