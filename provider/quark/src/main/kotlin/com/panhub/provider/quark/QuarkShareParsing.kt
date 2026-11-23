package com.panhub.provider.quark

import com.panhub.core.model.NodeId
import com.panhub.core.model.ShareId
import com.panhub.core.model.ShareLink

/**
 * 夸克分享的纯解析逻辑（不碰网络，便于单测）。
 *
 * 这里集中了三个容易写错、又都能单独验证的换算：
 * 有效期档位、链接形态、以及「永久」的哨兵时间戳。
 */
internal object QuarkShareParsing {

    /** 公开分享（谁都能打开）。 */
    const val URL_TYPE_PUBLIC = 1

    /** 带提取码的分享。 */
    const val URL_TYPE_PRIVATE = 2

    /** 任务完成。 */
    const val TASK_STATUS_DONE = 2

    /** 任务失败。 */
    const val TASK_STATUS_FAILED = 3

    /** 服务端用这个时刻表示「永久有效」，本地要当成没有到期时间。 */
    const val PERMANENT_EXPIRED_AT = 4_102_416_000_000L

    /** 默认站点根（夸克）；UC 用 [normalizeUrl] 的 shareHost 参数传自己的。 */
    private const val SHARE_HOST = "https://pan.quark.cn"

    /**
     * 把「多少天后过期」换算成夸克的档位。
     *
     * 服务端只有 1/2/3/4 四档，对应永久/一天/一周/一月，没有任意天数。
     * 用户选 3 天只能落到「一周」档，所以返回档位后还要把实际到期时刻写回
     * [ShareLink.expiresAt]，否则会出现「显示 3 天、实际 7 天」的偏差。
     *
     * 传 0 与传 null 都表示永久：0 在 [com.panhub.core.model.ShareOptions] 里
     * 没有定义，但上游（界面）把「永久」渲染成 0 是常见做法，这里一并接住。
     */
    fun expiredTypeOf(expireDays: Int?): Int = when {
        expireDays == null || expireDays <= 0 -> 1
        expireDays <= 1 -> 2
        expireDays <= 7 -> 3
        else -> 4
    }

    /** 档位对应的天数，用来算 [ShareLink.expiresAt]；永久返回 null。 */
    fun daysOfExpiredType(expiredType: Int): Int? = when (expiredType) {
        2 -> 1
        3 -> 7
        4 -> 30
        else -> null
    }

    /**
     * 归一化分享链接。
     *
     * 服务端返回的可能是完整 URL，也可能只有路径或一个裸 id：
     * 只给路径时必须补 host，只给 id 时必须补 /s/ 前缀，否则复制出去是一串
     * 打不开的字符，用户以为分享坏了。
     */
    fun normalizeUrl(raw: String?, shareHost: String = SHARE_HOST): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("/") -> shareHost + trimmed
            else -> shareHost + "/s/" + trimmed
        }
    }

    /**
     * 把提取码拼到链接后面。
     *
     * 已有查询串时用 &，否则用 ?；提取码为空就原样返回，
     * 不要拼出一个尾随的 ?pwd= 让用户手动删。
     */
    fun withPasscode(url: String, passcode: String?): String {
        val code = passcode?.trim().orEmpty()
        if (code.isEmpty()) return url
        val separator = if (url.contains('?')) '&' else '?'
        return url + separator + "pwd=" + code
    }

    /**
     * 服务端到期时间转本地时间戳。
     *
     * 0、负数、以及「永久」哨兵都当成没有到期时间（返回 null），
     * 而不是当成 1970 年或 2100 年 —— 界面按 null 显示「永久」。
     */
    fun expiresAtOf(millis: Long?): Long? {
        val value = millis ?: return null
        if (value <= 0L || value >= PERMANENT_EXPIRED_AT) return null
        return value
    }

    /** 列表项转成统一的分享模型。缺 share_id 的条目直接丢掉（没法取消、没法打开）。 */
    fun toShareLink(item: QuarkShareItem, shareHost: String = SHARE_HOST): ShareLink? {
        val id = item.shareId?.takeIf { it.isNotBlank() } ?: return null
        val url = normalizeUrl(item.shareUrl ?: item.pwdId, shareHost) ?: return null
        val passcode = item.passcode?.takeIf { it.isNotBlank() }
        return ShareLink(
            id = ShareId(id),
            url = withPasscode(url, passcode),
            password = passcode,
            createdAt = item.createdAt?.takeIf { it > 0L },
            expiresAt = expiresAtOf(item.expiredAt),
            title = item.title?.takeIf { it.isNotBlank() },
        )
    }

    /** 创建成功后组装分享链接。 */
    fun toShareLink(
        data: QuarkSharePasswordData,
        nodeIds: List<NodeId>,
        shareHost: String = SHARE_HOST,
    ): ShareLink? {
        val id = data.shareId?.takeIf { it.isNotBlank() } ?: return null
        val url = normalizeUrl(data.shareUrl ?: data.pwdId, shareHost) ?: return null
        val passcode = data.passcode?.takeIf { it.isNotBlank() }
        return ShareLink(
            id = ShareId(id),
            url = withPasscode(url, passcode),
            password = passcode,
            expiresAt = expiresAtOf(data.expiredAt),
            nodeIds = nodeIds,
            title = data.title?.takeIf { it.isNotBlank() },
        )
    }
}
