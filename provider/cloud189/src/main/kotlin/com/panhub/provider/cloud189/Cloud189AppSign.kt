package com.panhub.provider.cloud189

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * 天翼云盘官方客户端通道（api.cloud.189.cn）的签名零件。
 *
 * 这条通道和现有的网页 open 接口（cloud.189.cn/api/open/...）不是一回事：
 * 它靠一对会话密钥做 HMAC-SHA1 签名，密钥由 /getSessionForPC.action 下发。
 * 每个已登录请求带四个头：Date、SessionKey、X-Request-ID、Signature；
 * 签名串是 SessionKey / Operate / RequestURI / Date 四段，
 * 有加密参数时再追加 params 一段。
 *
 * 事实来源：OpenList drivers/189pc 的 help.go 与 utils.go。
 * 端点与参数名在天翼 APK 的 lib/arm64-v8a/libapp.so 里能逐条对上
 * （/file/listFiles.action、/createFolder.action、/renameFile.action 等，
 * 以及 X-Request-ID、SessionKey、sessionSecret 三个字符串）。
 *
 * 与上传通道（upload.cloud.189.cn）的区别：那边每次请求现生成随机密钥，
 * 用 RSA 公钥加密后放进 EncryptionText 头，params 与签名都是小写十六进制、
 * Date 是毫秒时间戳；这边用会话固定的 sessionSecret，全部大写十六进制、
 * Date 是 GMT 字符串。两套不能混用。
 */
internal object Cloud189AppSign {

    /** 官方 PC 客户端的 appId，换会话与刷新 token 必须全程一致。 */
    const val APP_ID = "9317140619"

    /** 密码登录用的 clientType，与扫码登录的 1 不同。 */
    const val CLIENT_TYPE = "10020"

    const val VERSION = "7.2.4.0"

    const val CHANNEL_ID = "web_cloud.189.cn"

    /** clientSuffix 里的 clientType 取值。 */
    const val PC = "TELEPC"

    const val HEADER_DATE = "Date"
    const val HEADER_SESSION_KEY = "SessionKey"
    const val HEADER_REQUEST_ID = "X-Request-ID"
    const val HEADER_SIGNATURE = "Signature"

    /** 加密表单在 query 里的参数名。 */
    const val PARAM_NAME = "params"

    /** rand 的两段上界，对齐参考实现的 Int63n(1e5) 与 Int63n(1e10)。 */
    const val RAND_HEAD_BOUND = 100_000
    const val RAND_TAIL_BOUND = 10_000_000_000L

    /** AES-128 的密钥长度：会话密钥只取前 16 个字符。 */
    const val AES_KEY_LENGTH = 16

    private val DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC)

    /**
     * 参考实现用正则从完整 URL 里取 path：
     * 协议与主机之后、第一个空白或 ? # 之前的那一段，query 不参与签名。
     */
    private val PATH_PATTERN = Regex("://[^/]+((/[^/\\s?#]+)*)")

    /** GMT 格式的时间头，例如 "Mon, 02 Jan 2006 15:04:05 GMT"。 */
    fun httpDate(nowMillis: Long): String = DATE_FORMAT.format(Instant.ofEpochMilli(nowMillis))

    /** 从完整 URL 取签名用的 path；取不到返回空串，让调用方显式失败。 */
    fun pathOf(fullUrl: String): String =
        PATH_PATTERN.find(fullUrl)?.groupValues?.getOrNull(1) ?: ""

    /**
     * 每个请求都要带的客户端标识。
     * 参考实现用的是 map，遍历顺序不固定，所以这里也不承诺顺序。
     */
    fun clientSuffix(random: Random): List<Pair<String, String>> = listOf(
        "clientType" to PC,
        "version" to VERSION,
        "channelId" to CHANNEL_ID,
        "rand" to randomToken(random),
    )

    /** 形如 "12345_6789012345" 的两段随机数，服务端只拿它防缓存。 */
    fun randomToken(random: Random): String =
        random.nextInt(RAND_HEAD_BOUND).toString() + "_" + random.nextLong(RAND_TAIL_BOUND)

    /** 形如 8-4-4-4-12 的请求号，与上传通道同构，直接复用那边的实现。 */
    fun requestId(random: Random): String = Cloud189UploadCrypto.randomRequestId(random)

    /**
     * 表单拼串：参考实现按 key 排序后拼 k=v，值不做 URL 编码——
     * 调用方自己编码，因为服务端解密后拿到的是编码过的原文。
     */
    fun paramString(params: List<Pair<String, String>>): String =
        params.sortedBy { it.first }.joinToString("&") { it.first + "=" + it.second }

    /** AES-128-ECB + PKCS7，输出大写十六进制。 */
    fun aesEcbUpperHex(plain: String, key: String): String = Cloud189UploadCrypto.aesEcbEncrypt(
        plain.toByteArray(Charsets.UTF_8),
        key.toByteArray(Charsets.UTF_8),
    ).toUpperHex()

    /**
     * 用会话密钥前 16 位加密表单，得到 query 里的 params。
     * 会话密钥不足 16 位说明会话本身异常，返回 null 让调用方失败而不是抛异常。
     */
    fun encryptedParams(plain: String, sessionSecret: String): String? {
        if (sessionSecret.length < AES_KEY_LENGTH) return null
        return aesEcbUpperHex(plain, sessionSecret.substring(0, AES_KEY_LENGTH))
    }

    /**
     * 签名串五段，顺序不能变：SessionKey / Operate / RequestURI / Date，
     * params 只在有加密表单时出现。输出大写十六进制。
     */
    fun signature(
        sessionSecret: String,
        sessionKey: String,
        method: String,
        path: String,
        date: String,
        paramsHex: String = "",
    ): String {
        var data = "SessionKey=" + sessionKey +
            "&Operate=" + method +
            "&RequestURI=" + path +
            "&Date=" + date
        if (paramsHex.isNotEmpty()) data = data + "&params=" + paramsHex
        return hmacSha1Upper(data, sessionSecret)
    }

    /** 四个签名头，顺序无关，服务端按名字取。 */
    fun headers(
        sessionKey: String,
        signature: String,
        date: String,
        requestId: String,
    ): List<Pair<String, String>> = listOf(
        HEADER_DATE to date,
        HEADER_SESSION_KEY to sessionKey,
        HEADER_REQUEST_ID to requestId,
        HEADER_SIGNATURE to signature,
    )

    private fun hmacSha1Upper(data: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).toUpperHex()
    }
}

/** 大写十六进制。App 通道的 params 与签名都是大写，和上传通道正好相反。 */
internal fun ByteArray.toUpperHex(): String {
    val chars = CharArray(size * 2)
    for (i in indices) {
        val value = this[i].toInt() and 0xFF
        chars[i * 2] = HEX_DIGITS_UPPER[value ushr 4]
        chars[i * 2 + 1] = HEX_DIGITS_UPPER[value and 0x0F]
    }
    return String(chars)
}

private val HEX_DIGITS_UPPER = "0123456789ABCDEF".toCharArray()
