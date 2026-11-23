package com.panhub.provider.cloud189

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

class Cloud189UploadCryptoTest {

    @Test
    fun hmacSha1MatchesPublishedVector() {
        // RFC 2202 用例 2：key = "key"，data = "The quick brown fox jumps over the lazy dog"。
        assertEquals(
            "de7c9b85b8b78aa6bc8a7a36f70a90701c9db4d9",
            Cloud189UploadCrypto.hmacSha1Hex(
                "The quick brown fox jumps over the lazy dog",
                "key",
            ),
        )
    }

    @Test
    fun aesEcbIsDeterministicAndPadded() {
        // ECB 同样的输入必然得到同样的密文，这是服务端能解出来的前提。
        val first = Cloud189UploadCrypto.aesEcbHex("a=1&b=2", "0123456789abcdef")
        val second = Cloud189UploadCrypto.aesEcbHex("a=1&b=2", "0123456789abcdef")
        assertEquals(first, second)
        // "a=1&b=2" 是 7 字节，补满一块后密文 16 字节 = 32 个十六进制字符。
        assertEquals(32, first.length)
        assertNotEquals(
            first,
            Cloud189UploadCrypto.aesEcbHex("a=1&b=3", "0123456789abcdef"),
        )
    }

    @Test
    fun aesEcbFullBlockGetsWholePaddingBlock() {
        // PKCS7 在正好整块时会再补一整块，这是标准行为，不是 bug。
        // 16 字节正好一块，PKCS7 仍要再补一整块 → 32 字节密文 = 64 个十六进制字符。
        val plain = "0123456789abcdef"
        assertEquals(64, Cloud189UploadCrypto.aesEcbHex(plain, "0123456789abcdef").length)
        // 32 字节同理补成 48 字节 → 96 个十六进制字符。
        val longer = Cloud189UploadCrypto.aesEcbHex(plain + plain, "0123456789abcdef")
        assertEquals(96, longer.length)
    }

    @Test
    fun signatureIsFiveSegmentsInFixedOrder() {
        // 签名串的段序错了服务端必然验签失败，所以这里逐字钉住。
        val expected = Cloud189UploadCrypto.hmacSha1Hex(
            "SessionKey=k&Operate=GET&RequestURI=/person/initMultiUpload" +
                "&Date=1700000000000&params=abcd",
            "secret",
        )
        assertEquals(
            expected,
            Cloud189UploadCrypto.signature(
                sessionKey = "k",
                uri = "/person/initMultiUpload",
                date = "1700000000000",
                paramsHex = "abcd",
                secret = "secret",
            ),
        )
    }

    @Test
    fun paramStringKeepsGivenOrder() {
        assertEquals(
            "fileName=a%20b&fileSize=10",
            Cloud189UploadCrypto.paramString(
                listOf("fileName" to "a%20b", "fileSize" to "10"),
            ),
        )
    }

    @Test
    fun queryEscapeMatchesGoStyle() {
        assertEquals("a+b", Cloud189UploadCrypto.queryEscape("a b"))
        assertEquals("a-b_c.d~e", Cloud189UploadCrypto.queryEscape("a-b_c.d~e"))
        assertEquals("%E4%B8%AD", Cloud189UploadCrypto.queryEscape("中"))
        assertEquals("%2B", Cloud189UploadCrypto.queryEscape("+"))
        assertEquals("%26", Cloud189UploadCrypto.queryEscape("&"))
    }

    @Test
    fun percentDecodeKeepsPlusSign() {
        // requestHeader 里的 Content-MD5 是 Base64，加号是内容的一部分，不能被当空格吃掉。
        assertEquals("a+b", Cloud189UploadCrypto.percentDecode("a+b"))
        assertEquals("a b", Cloud189UploadCrypto.percentDecode("a%20b"))
        assertEquals("%", Cloud189UploadCrypto.percentDecode("%"))
        assertEquals("Content-Type", Cloud189UploadCrypto.percentDecode("Content-Type"))
    }

    @Test
    fun randomTokenStaysInsideSecretRange() {
        val random = Random(7)
        repeat(200) {
            val length = Cloud189UploadCrypto.SECRET_MIN_LENGTH +
                random.nextInt(Cloud189UploadCrypto.SECRET_SPAN)
            val token = Cloud189UploadCrypto.randomToken(random, length)
            assertEquals(length, token.length)
            assertTrue(length in Cloud189UploadCrypto.SECRET_MIN_LENGTH..Cloud189UploadCrypto.SECRET_MAX_LENGTH)
            assertTrue(token.all { it in "0123456789abcdef" })
        }
    }

    @Test
    fun requestIdLooksLikeUuidV4() {
        val random = Random(11)
        val id = Cloud189UploadCrypto.randomRequestId(random)
        assertEquals(36, id.length)
        assertEquals('-', id[8])
        assertEquals('-', id[13])
        assertEquals('-', id[18])
        assertEquals('-', id[23])
        assertEquals('4', id[14])
        assertTrue(id[19] in "89ab")
    }

    @Test
    fun paramStringOfEmptyFormIsEmpty() {
        assertEquals("", Cloud189UploadCrypto.paramString(emptyList()))
    }
}

class Cloud189UploadDigestTest {

    private fun digestOf(bytes: ByteArray, size: Long = bytes.size.toLong()) =
        Cloud189UploadDigest.compute(ByteArrayInputStream(bytes), size)

    @Test
    fun smallFileUsesFileMd5AsSliceMd5() {
        val bytes = "abc".toByteArray()
        val digests = digestOf(bytes)
        // MD5("abc") 是公开测试向量。
        assertEquals("900150983cd24fb0d6963f7d28e17f72", digests.fileMd5)
        assertEquals(digests.fileMd5, digests.sliceMd5)
        assertEquals(listOf("900150983CD24FB0D6963F7D28E17F72"), digests.sliceMd5s)
    }

    @Test
    fun emptyFileStillReportsOneSlice() {
        val digests = digestOf(ByteArray(0), 0)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", digests.fileMd5)
        assertEquals(digests.fileMd5, digests.sliceMd5)
        assertEquals(1, digests.sliceMd5s.size)
        assertEquals("D41D8CD98F00B204E9800998ECF8427E", digests.sliceMd5s.first())
    }

    @Test
    fun sliceMd5JoinsUpperHexWithNewline() {
        // 用 4 字节分片模拟：abcd / efgh / ij 三段。
        // 期望值由 python 独立算出：
        //   E2FC714C4727EE9395F324CD2E7F331F
        //   1F7690EBDD9B4CAF8FAB49CA1757BF27
        //   7BED657A775C37C2570786D0CBEEFD88
        //   三行用 \n 连起来再 MD5 = c59513269c6a0d6ca9c538c670d73416
        val digests = Cloud189UploadDigest.compute(
            ByteArrayInputStream("abcdefghij".toByteArray()),
            size = 10L,
            sliceSize = 4L,
        )
        assertEquals("a925576942e94b2ef57a066101b48876", digests.fileMd5)
        assertEquals(
            listOf(
                "E2FC714C4727EE9395F324CD2E7F331F",
                "1F7690EBDD9B4CAF8FAB49CA1757BF27",
                "7BED657A775C37C2570786D0CBEEFD88",
            ),
            digests.sliceMd5s,
        )
        assertEquals("c59513269c6a0d6ca9c538c670d73416", digests.sliceMd5)
    }

    @Test
    fun exactMultipleOfSliceSizeHasNoTrailingSlice() {
        val bytes = ByteArray(8) { it.toByte() }
        val digests = Cloud189UploadDigest.compute(
            ByteArrayInputStream(bytes),
            size = 8L,
            sliceSize = 4L,
        )
        assertEquals(2, digests.sliceMd5s.size)
    }

    @Test
    fun progressReportsTotalBytes() {
        val seen = ArrayList<Long>()
        Cloud189UploadDigest.compute(
            ByteArrayInputStream(ByteArray(1000) { 1 }),
            size = 1000L,
        ) { read -> seen += read }
        assertEquals(1000L, seen.last())
        assertTrue(seen.size >= 1)
    }
}
