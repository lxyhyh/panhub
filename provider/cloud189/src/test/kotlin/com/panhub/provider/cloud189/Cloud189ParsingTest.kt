package com.panhub.provider.cloud189

import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanResult
import com.panhub.core.network.HttpEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class Cloud189ParsingTest {

    @Test
    fun parseTimeWithSeconds() {
        val expected = LocalDateTime.of(2024, 1, 2, 12, 34, 56)
            .toInstant(ZoneOffset.ofHours(8)).toEpochMilli()
        assertEquals(expected, Cloud189Parsing.parseTime("2024-01-02 12:34:56"))
    }

    @Test
    fun parseDateOnlyIsMidnight() {
        val expected = LocalDateTime.of(2024, 1, 2, 0, 0, 0)
            .toInstant(ZoneOffset.ofHours(8)).toEpochMilli()
        assertEquals(expected, Cloud189Parsing.parseTime("2024-01-02"))
    }

    @Test
    fun parseTimeRejectsGarbage() {
        assertNull(Cloud189Parsing.parseTime(null))
        assertNull(Cloud189Parsing.parseTime(""))
        assertNull(Cloud189Parsing.parseTime("not a date"))
    }

    @Test
    fun nodeIdPrefixDistinguishesKinds() {
        val folder = Cloud189Parsing.folderNodeId("123")
        val file = Cloud189Parsing.fileNodeId("123")
        assertTrue(Cloud189Parsing.isFolder(folder))
        assertTrue(!Cloud189Parsing.isFolder(file))
        assertEquals("123", Cloud189Parsing.rawId(folder))
        assertEquals("123", Cloud189Parsing.rawId(file))
    }

    @Test
    fun urlEncodingEscapesReserved() {
        assertEquals("abc-._~", Cloud189Api.encode("abc-._~"))
        assertEquals("a%20b", Cloud189Api.encode("a b"))
        assertEquals("%E4%B8%AD", Cloud189Api.encode("中"))
        assertEquals("a%26b%3Dc", Cloud189Api.encode("a&b=c"))
    }

    @Test
    fun envelopeTreatsZeroAsSuccess() {
        val api = Cloud189Api(HttpEngine())
        val result = api.decode("{\"res_code\":0,\"res_message\":\"success\"}")
        assertTrue(result is PanResult.Success)
    }

    @Test
    fun envelopeTreatsInvalidSessionAsAuthError() {
        val api = Cloud189Api(HttpEngine())
        val result = api.decode("{\"res_code\":\"InvalidSessionKey\",\"res_message\":\"login\"}")
        assertTrue(result is PanResult.Failure)
        assertEquals(ErrorKind.AUTH, (result as PanResult.Failure).error.kind)
    }

    @Test
    fun fileListSplitsFoldersAndFiles() {
        val api = Cloud189Api(HttpEngine())
        val raw = "{\"res_code\":0,\"fileListAO\":{\"count\":2," +
            "\"folderList\":[{\"id\":11,\"name\":\"docs\",\"lastOpTime\":\"2024-01-02 12:34:56\"}]," +
            "\"fileList\":[{\"id\":22,\"name\":\"a.txt\",\"size\":1024," +
            "\"lastOpTime\":\"2024-01-03 01:02:03\"," +
            "\"icon\":{\"smallUrl\":\"https://example.com/i.png\"}}]}}"
        val envelope = (api.decode(raw) as PanResult.Success).value
        val ao = envelope.fileListAo
        assertEquals(2, ao?.count)
        assertEquals("docs", ao?.folderList?.first()?.name)
        assertEquals("a.txt", ao?.fileList?.first()?.name)
        assertEquals(1024L, ao?.fileList?.first()?.size)
        assertEquals("https://example.com/i.png", ao?.fileList?.first()?.icon?.smallUrl)
    }

    @Test
    fun capacityEnvelopeParsesSizes() {
        val api = Cloud189Api(HttpEngine())
        val raw = "{\"res_code\":0,\"cloudCapacityInfo\":{\"usedSize\":100,\"totalSize\":1000}}"
        val envelope = (api.decode(raw) as PanResult.Success).value
        assertEquals(100L, envelope.cloudCapacity?.usedSize)
        assertEquals(1000L, envelope.cloudCapacity?.totalSize)
    }

    @Test
    fun unknownEnvelopeFieldsAreIgnored() {
        val api = Cloud189Api(HttpEngine())
        val raw = "{\"res_code\":0,\"totallyUnknown\":{\"a\":1},\"res_message\":\"ok\"}"
        assertTrue(api.decode(raw) is PanResult.Success)
    }
}
