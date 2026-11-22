package com.panhub.provider.quark

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarkParsingTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test
    fun unescapeHandlesNamedEntities() {
        assertEquals("a&b", QuarkParsing.unescape("a&amp;b"))
        assertEquals("<x>", QuarkParsing.unescape("&lt;x&gt;"))
        assertEquals("it's", QuarkParsing.unescape("it&apos;s"))
    }

    @Test
    fun unescapeHandlesNumericEntities() {
        assertEquals("中", QuarkParsing.unescape("&#20013;"))
        assertEquals("A", QuarkParsing.unescape("&#x41;"))
    }

    @Test
    fun unescapeLeavesPlainTextAlone() {
        assertEquals("普通 文件.txt", QuarkParsing.unescape("普通 文件.txt"))
        // 没有分号的 & 不是实体，不能被吃掉。
        assertEquals("a & b", QuarkParsing.unescape("a & b"))
    }

    @Test
    fun displayNameFallsBackToFid() {
        val dto = QuarkFileDto(fid = "abc", fileName = "  ")
        assertEquals("abc", QuarkParsing.displayName(dto))
    }

    @Test
    fun displayNameUnescapes() {
        val dto = QuarkFileDto(fid = "abc", fileName = "报告&amp;附件.pdf")
        assertEquals("报告&附件.pdf", QuarkParsing.displayName(dto))
    }

    @Test
    fun envelopeIsOkOnlyWhenCodeZeroAndStatusSmall() {
        val ok = json.decodeFromString(
            QuarkEnvelope.serializer(QuarkFileListData.serializer()),
            """{"status":200,"code":0,"data":{"list":[]}}""",
        )
        assertTrue(ok.code == 0 && ok.status < 400)

        val bad = json.decodeFromString(
            QuarkEnvelope.serializer(QuarkFileListData.serializer()),
            """{"status":400,"code":23001,"message":"file is doloading[同名冲突]"}""",
        )
        assertFalse(bad.code == 0 && bad.status < 400)
        assertEquals("file is doloading[同名冲突]", bad.message)
    }

    @Test
    fun fileListParsesFileFlagAndMetadata() {
        val raw = """
            {"status":200,"code":0,"data":{"list":[
              {"fid":"f1","file_name":"a.txt","size":12,"file":true,"updated_at":1700000000000},
              {"fid":"d1","file_name":"docs","file":false}
            ]},"metadata":{"_page":1,"_size":100,"_total":2}}
        """.trimIndent()
        val envelope = json.decodeFromString(
            QuarkEnvelope.serializer(QuarkFileListData.serializer()),
            raw,
        )
        val list = envelope.data?.list.orEmpty()
        assertEquals(2, list.size)
        assertTrue(list[0].file)
        assertFalse(list[1].file)
        assertEquals(2, envelope.metadata?.total)
        assertEquals(12L, list[0].size)
    }

    @Test
    fun downloadDataIsArray() {
        val raw = """{"status":200,"code":0,"data":[{"fid":"f1","download_url":"https://x/y"}]}"""
        val envelope = json.decodeFromString(
            QuarkEnvelope.serializer(
                kotlinx.serialization.builtins.ListSerializer(QuarkDownloadItem.serializer()),
            ),
            raw,
        )
        assertEquals("https://x/y", envelope.data?.firstOrNull()?.downloadUrl)
    }

    @Test
    fun memberDataKeepsCapacity() {
        val raw = """{"status":200,"code":0,"data":{"use_capacity":100,"total_capacity":1000,"member_type":"NORMAL"}}"""
        val envelope = json.decodeFromString(
            QuarkEnvelope.serializer(QuarkMemberData.serializer()),
            raw,
        )
        assertEquals(100L, envelope.data?.useCapacity)
        assertEquals(1000L, envelope.data?.totalCapacity)
        assertEquals("NORMAL", envelope.data?.memberType)
    }

    @Test
    fun rootIdIsZero() {
        assertEquals("0", QuarkApi.ROOT_ID)
    }

    @Test
    fun quotaFreeIsNullWhenTotalMissing() {
        val data = QuarkMemberData(useCapacity = 5, totalCapacity = null)
        assertNull(data.totalCapacity)
    }
}
