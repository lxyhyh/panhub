package com.panhub.provider.quark

import com.panhub.provider.api.FileUploadSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OSS 签名串是「少一个换行就失败」的字符串，必须逐字锁死。
 * 期望值直接照 OpenList drivers/quark_uc/util.go 的格式手写，不是从实现里抄的。
 */
class QuarkOssTest {

    @Test
    fun partAuthMetaMatchesOssV1Layout() {
        val meta = QuarkOss.partAuthMeta(
            mime = "text/plain",
            timeStr = "Tue, 04 Nov 2025 10:00:00 GMT",
            bucket = "quark-oss",
            objKey = "file/abc",
            partNumber = 2,
            uploadId = "UP-1",
        )
        val expected = "PUT\n" +
            "\n" +
            "text/plain\n" +
            "Tue, 04 Nov 2025 10:00:00 GMT\n" +
            "x-oss-date:Tue, 04 Nov 2025 10:00:00 GMT\n" +
            "x-oss-user-agent:" + QuarkOss.USER_AGENT + "\n" +
            "/quark-oss/file/abc?partNumber=2&uploadId=UP-1"
        assertEquals(expected, meta)
    }

    @Test
    fun commitAuthMetaCarriesMd5AndCallback() {
        val meta = QuarkOss.commitAuthMeta(
            contentMd5 = "Zm9v",
            timeStr = "Tue, 04 Nov 2025 10:00:00 GMT",
            callbackBase64 = "Y2I=",
            bucket = "quark-oss",
            objKey = "file/abc",
            uploadId = "UP-1",
        )
        val expected = "POST\n" +
            "Zm9v\n" +
            "application/xml\n" +
            "Tue, 04 Nov 2025 10:00:00 GMT\n" +
            "x-oss-callback:Y2I=\n" +
            "x-oss-date:Tue, 04 Nov 2025 10:00:00 GMT\n" +
            "x-oss-user-agent:" + QuarkOss.USER_AGENT + "\n" +
            "/quark-oss/file/abc?uploadId=UP-1"
        assertEquals(expected, meta)
    }

    @Test
    fun commitXmlKeepsOnePartPerRequest() {
        val xml = QuarkOss.commitXml(listOf("\"etag-a\"", "\"etag-b\""))
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(xml.contains("<PartNumber>1</PartNumber>\n<ETag>\"etag-a\"</ETag>"))
        assertTrue(xml.contains("<PartNumber>2</PartNumber>\n<ETag>\"etag-b\"</ETag>"))
        assertTrue(xml.endsWith("</CompleteMultipartUpload>"))
    }

    @Test
    fun singlePartUploadStillProducesValidXml() {
        val xml = QuarkOss.commitXml(listOf("\"only\""))
        assertEquals(1, Regex("<Part>").findAll(xml).count())
        assertTrue(xml.contains("<PartNumber>1</PartNumber>"))
    }

    @Test
    fun hostStripsAnyScheme() {
        assertEquals("oss-cn-hangzhou.aliyuncs.com", QuarkOss.host("http://oss-cn-hangzhou.aliyuncs.com"))
        assertEquals("oss-cn-hangzhou.aliyuncs.com", QuarkOss.host("https://oss-cn-hangzhou.aliyuncs.com"))
        assertEquals("oss-cn-hangzhou.aliyuncs.com", QuarkOss.host("oss-cn-hangzhou.aliyuncs.com/"))
        assertNull(QuarkOss.host(""))
        assertNull(QuarkOss.host(null))
    }

    @Test
    fun partUrlCarriesPartNumberAndUploadId() {
        val url = QuarkOss.url(
            host = "oss-cn-hangzhou.aliyuncs.com",
            bucket = "quark-oss",
            objKey = "file/abc",
            partNumber = 3,
            uploadId = "UP-1",
        )
        assertEquals(
            "https://quark-oss.oss-cn-hangzhou.aliyuncs.com/file/abc?partNumber=3&uploadId=UP-1",
            url,
        )
    }

    @Test
    fun commitUrlOmitsPartNumber() {
        val url = QuarkOss.url(
            host = "oss-cn-hangzhou.aliyuncs.com",
            bucket = "quark-oss",
            objKey = "file/abc",
            partNumber = null,
            uploadId = "UP-1",
        )
        assertEquals("https://quark-oss.oss-cn-hangzhou.aliyuncs.com/file/abc?uploadId=UP-1", url)
    }

    @Test
    fun mimeFallsBackToExtensionThenOctetStream() {
        val uploader = QuarkUploader(QuarkApi(com.panhub.core.network.HttpEngine()))
        val temp = File.createTempFile("panhub-quark-mime", ".png")
        try {
            assertEquals("image/png", uploader.mimeOf(FileUploadSource(temp)))
        } finally {
            temp.delete()
        }
        val unknown = File.createTempFile("panhub-quark-mime", ".weird")
        try {
            assertEquals("application/octet-stream", uploader.mimeOf(FileUploadSource(unknown)))
        } finally {
            unknown.delete()
        }
    }

    @Test
    fun httpDateIsRfc1123InGmt() {
        val uploader = QuarkUploader(QuarkApi(com.panhub.core.network.HttpEngine()))
        assertTrue(Regex("^[A-Z][a-z]{2}, \\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT$").matches(uploader.httpDate()))
    }
}
