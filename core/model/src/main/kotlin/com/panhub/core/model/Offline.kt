package com.panhub.core.model

/**
 * 离线下载的两种来源。
 *
 * LINK 是普通网址（http/https），BT 是从磁力链接里摘出来的 info hash。
 * 服务端对两者的入参完全不同（urls / info_hash），所以类型要在模型层定死，
 * 不能让每个 provider 各判一次字符串前缀。
 */
enum class OfflineKind { LINK, BT }

/**
 * 用户贴进来的一条离线下载地址。
 *
 * 故意丢掉 magnet 链接里除 info hash 之外的参数：服务端只需要 hash，
 * 而 hash 之外的 trackers 传过去只会让请求变大。
 */
data class OfflineLink(val kind: OfflineKind, val value: String)

/** 把用户输入的一段文字变成可提交的离线地址。 */
object OfflineLinks {
    private const val MAGNET_PREFIX = "magnet:?"
    private const val BTIH_PREFIX = "urn:btih:"

    /** Base32 形式的 hash 是 32 位，十六进制是 40 位；放宽到 64 兼容 v2。 */
    private const val MIN_HASH_LENGTH = 32
    private const val MAX_HASH_LENGTH = 64

    /**
     * 识别一条离线地址。
     *
     * 认不出来就返回 null——宁可让界面说「这不像一个下载链接」，
     * 也不要把用户随手粘的一段话发到服务端换回一个看不懂的错误码。
     */
    fun parse(raw: String?): OfflineLink? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.startsWith(MAGNET_PREFIX, ignoreCase = true)) return parseMagnet(text)
        val lower = text.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        return OfflineLink(OfflineKind.LINK, text)
    }

    private fun parseMagnet(text: String): OfflineLink? {
        val query = text.substringAfter('?', "")
        if (query.isEmpty()) return null
        for (part in query.split('&')) {
            // 磁力链接里 hash 长这样：xt=urn:btih:<hash>，所以先按 = 切开再认前缀。
            val value = part.substringAfter('=', part)
            if (!value.startsWith(BTIH_PREFIX, ignoreCase = true)) continue
            val hash = value.substring(BTIH_PREFIX.length).trim()
            if (hash.length !in MIN_HASH_LENGTH..MAX_HASH_LENGTH) return null
            // 大写十六进制也能被接受，统一成小写省得服务端认为它们是两个资源。
            return OfflineLink(OfflineKind.BT, hash.lowercase())
        }
        return null
    }
}

/** 离线资源里的一个文件（还没进网盘，所以不是 RemoteNode）。 */
data class OfflineFile(
    val id: String,
    val name: String,
    val size: Long = 0L,
    val isDir: Boolean = false,
)

/**
 * 解析出来的离线资源。
 *
 * [id] 是提交时要回传的 resource_id，[files] 是里面可以被勾选保存的条目；
 * 选哪些文件是用户的事，所以这里只给默认全选。
 */
data class OfflineResource(
    val id: String,
    val name: String,
    val kind: OfflineKind,
    val files: List<OfflineFile> = emptyList(),
) {
    val isEmpty: Boolean get() = files.isEmpty()

    val defaultSelection: Set<String> get() = files.map { it.id }.toSet()

    val totalBytes: Long get() = files.filterNot { it.isDir }.sumOf { it.size }
}
