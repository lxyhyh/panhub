package com.panhub.provider.cloud189

import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Cloud189UploadParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    @Test
    fun successCodeIsRequired() {
        val raw = """{"code":"SUCCESS","data":{"uploadFileId":"u1","fileDataExists":1}}"""
        val result = Cloud189UploadParsing.decode(json, raw)
        assertTrue(result is PanResult.Success)
        val envelope = (result as PanResult.Success).value
        assertEquals("u1", envelope.data?.uploadFileId)
        assertEquals(1, envelope.data?.fileDataExists)
    }

    @Test
    fun invalidSessionBecomesAuthError() {
        // 线上原文：403 + {"code":"InvalidSessionKey","msg":"sessionKey=null,invalid sessionKey,..."}
        val raw = """{"code":"InvalidSessionKey","msg":"sessionKey=null,invalid sessionKey"}"""
        val result = Cloud189UploadParsing.decode(json, raw)
        assertTrue(result is PanResult.Failure)
        assertEquals(ErrorKind.AUTH, (result as PanResult.Failure).error.kind)
    }

    @Test
    fun otherCodesKeepServerMessage() {
        val raw = """{"code":"FILE_NAME_ILLEGAL","msg":"文件名包含非法字符"}"""
        val result = Cloud189UploadParsing.decode(json, raw)
        assertTrue(result is PanResult.Failure)
        assertEquals("文件名包含非法字符", (result as PanResult.Failure).error.message)
    }

    @Test
    fun blankBodyIsRejected() {
        assertTrue(Cloud189UploadParsing.decode(json, "") is PanResult.Failure)
        assertTrue(Cloud189UploadParsing.decode(json, "   ") is PanResult.Failure)
    }

    @Test
    fun nonJsonBodyIsRejected() {
        assertTrue(Cloud189UploadParsing.decode(json, "<html>502</html>") is PanResult.Failure)
    }

    @Test
    fun uploadUrlsAreParsedByPartNumber() {
        val raw = """{"code":"SUCCESS","uploadUrls":{"partNumber_1":""" +
            """{"requestURL":"https://hb02.upload.cloud.189.cn/x?y=1",""" +
            """"requestHeader":"Content-Type%3Dapplication%2Foctet-stream%26Content-MD5%3Dabc%3D"}}}"""
        val envelope = (Cloud189UploadParsing.decode(json, raw) as PanResult.Success).value
        val part = envelope.uploadUrls?.get("partNumber_1")
        assertEquals("https://hb02.upload.cloud.189.cn/x?y=1", part?.requestUrl)
        // 请求头是整体 URL 编码过的，解码后应该是一个正常的 k=v&k=v 串。
        assertEquals(
            "Content-Type=application/octet-stream&Content-MD5=abc=",
            Cloud189UploadCrypto.percentDecode(part?.requestHeader.orEmpty()),
        )
    }

    @Test
    fun headersSplitOnFirstEqualsOnly() {
        // Base64 值里的 = 不能被当成分隔符，否则 Content-MD5 会被截断。
        val headers = Cloud189UploadParsing.parseHeaders(
            "Content-MD5=YWJjZA%3D%3D&x-amz-date=20240101T000000Z",
        )
        assertEquals(2, headers.size)
        assertEquals("Content-MD5", headers[0].first)
        assertEquals("YWJjZA==", headers[0].second)
        assertEquals("20240101T000000Z", headers[1].second)
    }

    @Test
    fun emptyHeadersAreIgnored() {
        assertTrue(Cloud189UploadParsing.parseHeaders(null).isEmpty())
        assertTrue(Cloud189UploadParsing.parseHeaders("").isEmpty())
        assertTrue(Cloud189UploadParsing.parseHeaders("&&").isEmpty())
        assertTrue(Cloud189UploadParsing.parseHeaders("no-equals-sign").isEmpty())
    }
}
