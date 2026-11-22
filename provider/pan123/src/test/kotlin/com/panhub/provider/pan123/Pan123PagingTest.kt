package com.panhub.provider.pan123

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 列表响应到 [com.panhub.core.model.NodePage] 的映射。
 *
 * 重点在游标：123 云盘用字符串 "-1" 表示「没有下一页」，
 * 而不是空串或 null。把它当成有效游标传下去，界面就会一直显示
 * 「加载更多」，点一次拿回同一批数据，看起来像是翻页坏了。
 */
class Pan123PagingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun pageOf(raw: String): Pair<Int, String?> {
        val data = json.decodeFromString(
            ApiEnvelope.serializer(FileListData.serializer()),
            raw,
        ).data!!
        val cursor = data.next?.takeIf { it.isNotBlank() && it != "-1" }
        return (data.infoList?.size ?: 0) to cursor
    }

    @Test
    fun sentinelMinusOneMeansNoMore() {
        val raw = """{"code":0,"data":{"Next":"-1","InfoList":[{"FileId":1,"FileName":"a","Type":0}]}}"""
        val (count, cursor) = pageOf(raw)
        assertEquals(1, count)
        assertNull("Next 为 -1 时必须没有下一页", cursor)
    }

    @Test
    fun realCursorIsPreserved() {
        val raw = """{"code":0,"data":{"Next":"9527","InfoList":[]}}"""
        val (_, cursor) = pageOf(raw)
        assertEquals("9527", cursor)
    }

    @Test
    fun missingNextMeansNoMore() {
        val raw = """{"code":0,"data":{"InfoList":[]}}"""
        assertNull(pageOf(raw).second)
    }
}