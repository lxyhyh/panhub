package com.panhub.provider.cloud189

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 天翼云盘的时间格式是 "2024-01-02 12:34:56"，没有时区，按东八区解释。
 * 部分接口只回日期（"2024-01-02"），按当天零点处理。
 */
internal object Cloud189Parsing {

    private val ZONE: ZoneOffset = ZoneOffset.ofHours(8)

    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private val DATE_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 解析失败返回 null，不抛异常——展示层对时间缺失是容忍的。 */
    fun parseTime(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        runCatching { LocalDateTime.parse(text, DATE_TIME) }
            .getOrNull()
            ?.let { return it.toInstant(ZONE).toEpochMilli() }

        // 纯日期不能用 LocalDateTime 解析，必须走 LocalDate。
        runCatching { LocalDate.parse(text, DATE_ONLY) }
            .getOrNull()
            ?.let { return it.atStartOfDay().toInstant(ZONE).toEpochMilli() }

        return null
    }

    /**
     * 天翼云盘的文件与文件夹是两套 id 空间，数值可能重复，
     * 而改名/批量任务必须知道类型，所以节点 id 带上类型前缀。
     */
    fun folderNodeId(raw: String): String = PREFIX_FOLDER + raw

    fun fileNodeId(raw: String): String = PREFIX_FILE + raw

    fun isFolder(nodeId: String): Boolean = nodeId.startsWith(PREFIX_FOLDER)

    fun rawId(nodeId: String): String = nodeId.substringAfter(':')

    const val PREFIX_FOLDER = "folder:"
    const val PREFIX_FILE = "file:"
}
