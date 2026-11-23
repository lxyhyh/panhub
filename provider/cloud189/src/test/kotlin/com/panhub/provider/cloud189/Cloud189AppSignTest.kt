package com.panhub.provider.cloud189

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * App 通道的签名零件。
 *
 * 这里的期望值不是从实现里抄的，是用 python 的 cryptography 与 hmac/sha1
 * 独立算出来后钉进来的（脚本 /tmp/vec189b.py）。签名串少一段、段序错、
 * 大小写反了，服务端只会回一个 InvalidSignature，看不出是哪一步，
 * 所以每个字段都必须逐字对得上。
 */
class Cloud189AppSignTest {

    private val date = "Mon, 02 Jan 2006 15:04:05 GMT"

    @Test
    fun aesEcbOutputsUpperCaseHex() {
        // 与上传通道正好相反：那边小写，这边大写。
        assertEquals(
            "8B78193314D7D2398C70B8CCD78EDD51",
            Cloud189AppSign.aesEcbUpperHex("a=1&b=2", "0123456789abcdef"),
        )
    }

    @Test
    fun encryptedParamsUsesOnlyFirstSixteenSecretChars() {
        // 会话密钥长度不固定，AES-128 只取前 16 位。
        val secret = "abcdef0123456789XYZ"
        assertEquals(19, secret.length)
        assertEquals(
            "EAF58B8664D2C36E0F28A707BD901D60A77C324F41BF079122D7CBCBA69C6B94A735E720D85017B2144F4D14379AA194",
            Cloud189AppSign.encryptedParams("folderId=-11&pageNum=1&pageSize=60", secret),
        )
    }

    @Test
    fun encryptedParamsRejectsShortSecret() {
        // 不足 16 位说明会话本身异常，返回 null 让调用方显式失败，而不是抛异常。
        assertNull(Cloud189AppSign.encryptedParams("a=1", "short"))
    }

    @Test
    fun signatureWithoutParamsIsFourSegments() {
        assertEquals(
            "3E6A91A01983AF3ADEA92BCA04A2D9C7E2808482",
            Cloud189AppSign.signature(
                sessionSecret = "0123456789abcdef",
                sessionKey = "SK-1",
                method = "GET",
                path = "/file/listFiles.action",
                date = date,
            ),
        )
    }

    @Test
    fun signatureWithParamsAppendsFifthSegment() {
        assertEquals(
            "45AFB2E1373AD20B066C000259C6A15B3D6EDDD1",
            Cloud189AppSign.signature(
                sessionSecret = "0123456789abcdef",
                sessionKey = "SK-1",
                method = "POST",
                path = "/createFolder.action",
                date = date,
                paramsHex = "ABCDEF0123",
            ),
        )
    }

    @Test
    fun signatureUsesWholeSecretNotJustFirstBlock() {
        // 加密只取前 16 位，签名用的是完整密钥，这两件事不能混。
        assertEquals(
            "96B4826A5CF08782EE69A1A80B80AF2819234CD6",
            Cloud189AppSign.signature(
                sessionSecret = "abcdef0123456789XYZ",
                sessionKey = "SK-2",
                method = "GET",
                path = "/family/file/listFiles.action",
                date = date,
            ),
        )
    }

    @Test
    fun httpDateIsGmtFormatted() {
        // 2006-01-02T15:04:05Z 的毫秒值。
        assertEquals(date, Cloud189AppSign.httpDate(1136214245000L))
    }

    @Test
    fun pathOfDropsHostAndQuery() {
        assertEquals(
            "/file/listFiles.action",
            Cloud189AppSign.pathOf("https://api.cloud.189.cn/file/listFiles.action?folderId=-11"),
        )
        // 带端口也要能取对。
        assertEquals(
            "/createFolder.action",
            Cloud189AppSign.pathOf("http://127.0.0.1:8080/createFolder.action"),
        )
        // 参考实现的正则要求路径非空，裸主机取到空串；实际请求的 path 都以 / 开头，
        // 所以这里保持与参考实现一致，而不是自作主张补一个 /。
        assertEquals("", Cloud189AppSign.pathOf("https://api.cloud.189.cn"))
    }

    @Test
    fun paramStringSortsByKey() {
        // 参考实现按 key 排序后拼，服务端解密出来的是同一份原文。
        assertEquals(
            "a=1&b=2&c=3",
            Cloud189AppSign.paramString(listOf("c" to "3", "a" to "1", "b" to "2")),
        )
    }

    @Test
    fun clientSuffixCarriesFourClientFields() {
        val suffix = Cloud189AppSign.clientSuffix(Random(7)).toMap()
        assertEquals(Cloud189AppSign.PC, suffix["clientType"])
        assertEquals(Cloud189AppSign.VERSION, suffix["version"])
        assertEquals(Cloud189AppSign.CHANNEL_ID, suffix["channelId"])
        val rand = suffix.getValue("rand")
        assertTrue("rand 应是 两段数字", Regex("[0-9]+_[0-9]+").matches(rand))
    }

    @Test
    fun requestIdLooksLikeUuid() {
        val id = Cloud189AppSign.requestId(Random(11))
        assertEquals(36, id.length)
        assertEquals('4', id[14])
        assertTrue(id[19] in "89ab")
    }
}
