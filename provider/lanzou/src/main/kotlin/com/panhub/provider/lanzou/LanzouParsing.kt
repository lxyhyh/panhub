package com.panhub.provider.lanzou

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 蓝奏云返回的字段全是「给人看的字符串」，不是数字。
 *
 * 文件大小是 "1.2 M"，时间是 "2024-01-02"。把这些解析逻辑单独放一个
 * 对象里，一是让 provider 主体只剩「调接口 + 映射模型」，二是这些字段线上
 * 一定会有畸形个案，必须有集中的兜底策略，不能各自 try/catch。
 */
internal object LanzouParsing {

    /** 蓝奏云只面向国内用户，返回的时间没有时区信息，按东八区解释。 */
    private val ZONE: ZoneOffset = ZoneOffset.ofHours(8)

    private val SIZE_PATTERN =
        Regex("""^([0-9]+(?:\.[0-9]+)?)\s*([KMGTP]?)B?$""", RegexOption.IGNORE_CASE)

    private val MULTIPLIERS: Map<String, Long> = mapOf(
        "" to 1L,
        "K" to 1024L,
        "M" to 1024L * 1024,
        "G" to 1024L * 1024 * 1024,
        "T" to 1024L * 1024 * 1024 * 1024,
        "P" to 1024L * 1024 * 1024 * 1024 * 1024,
    )

    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val SECOND: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * 把 1.2 M / 23.5 K / 1,024 解析成字节数。
     * 解析不出来返回 0 而不是抛异常：列表里一个坏条目不该毁掉整页。
     */
    fun parseSize(raw: String?): Long {
        val text = raw?.trim()?.replace(",", "")?.replace("，", "") ?: return 0L
        if (text.isEmpty()) return 0L
        text.toLongOrNull()?.let { return it }
        val match = SIZE_PATTERN.matchEntire(text) ?: return 0L
        val number = match.groupValues[1].toDoubleOrNull() ?: return 0L
        val multiplier = MULTIPLIERS[match.groupValues[2].uppercase()] ?: return 0L
        return (number * multiplier).toLong()
    }

    /** 解析 2024-01-02 / 2024-01-02 03:04 / 2024-01-02 03:04:05；失败返回 null。 */
    fun parseTime(raw: String?): Long? {
        val text = raw?.trim() ?: return null
        if (text.isEmpty()) return null
        runCatching { LocalDateTime.parse(text, SECOND) }.getOrNull()
            ?.let { return it.toInstant(ZONE).toEpochMilli() }
        runCatching { LocalDateTime.parse(text, MINUTE) }.getOrNull()
            ?.let { return it.toInstant(ZONE).toEpochMilli() }
        runCatching { LocalDate.parse(text, DAY) }.getOrNull()
            ?.let { return it.atStartOfDay().toInstant(ZONE).toEpochMilli() }
        return null
    }

    /**
     * 文件夹名清洗。
     *
     * 蓝奏云对文件夹名有一批禁止字符，命中时服务端只回「失败」，不说是哪个
     * 字符。所以客户端先按网页版同一套规则清洗：空格换下划线，去掉禁止字符。
     */
    private val ILLEGAL_CHARS: Set<Char> = buildSet {
        "$%^!*<>)(+=/:;,?".forEach { add(it) }
        add('\u0060')   // 反引号
        add('\'')
        add('"')
    }

    fun sanitizeFolderName(raw: String): String =
        raw.trim()
            .replace(' ', '_')
            .filterNot { it in ILLEGAL_CHARS }

    /** 从 ylogin=123456; phpdisk_info=... 里取出 uid。 */
    fun uidFromCookie(cookie: String): String? =
        Regex("""ylogin=(\d+)""").find(cookie)?.groupValues?.get(1)
}
