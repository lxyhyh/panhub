package com.panhub.provider.cloud189

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * 天翼云盘上传接口用到的密码学零件。
 *
 * 上传走的是另一条加密通道（upload.cloud.189.cn），和列目录那套 open 接口
 * 完全不是一回事：每个请求先把表单拼成一个字符串，用随机密钥做 AES-ECB 加密，
 * 密文的十六进制串就是 query 里的 params；再用同一个随机串做 HMAC-SHA1 签名；
 * 随机串本身用服务端下发的 RSA 公钥加密后放进 EncryptionText 头。
 *
 * 这里只放纯函数，方便逐条核对；网络与缓存都在 [Cloud189Uploader] 里。
 */
internal object Cloud189UploadCrypto {

    /** 随机串最短 16 位：前 16 位要当 AES 密钥用。 */
    const val SECRET_MIN_LENGTH = 16

    /** 随机串最长 31 位（参考实现是 16 + rand(16)）。 */
    const val SECRET_MAX_LENGTH = 31

    /** nextInt 的上界，等价于参考实现的 16 + int(16 * rand)。 */
    const val SECRET_SPAN = SECRET_MAX_LENGTH - SECRET_MIN_LENGTH + 1

    private const val HEX = "0123456789abcdef"

    private const val UUID_TEMPLATE = "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx"

    /** AES-128-ECB + PKCS7（Java 里 PKCS5Padding 对 AES 就是 PKCS7）。 */
    fun aesEcbEncrypt(plain: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(plain)
    }

    fun aesEcbHex(plain: String, key: String): String =
        aesEcbEncrypt(plain.toByteArray(Charsets.UTF_8), key.toByteArray(Charsets.UTF_8)).toLowerHex()

    fun hmacSha1Hex(data: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).toLowerHex()
    }

    /**
     * 用服务端下发的公钥做 RSA/PKCS1 加密。
     *
     * 服务端给的是 PEM 正文（不带 BEGIN/END 那两行），也就是 DER 的 Base64，
     * 所以这里直接按 X509 SubjectPublicKeyInfo 还原。
     */
    fun rsaEncryptBase64(plain: ByteArray, publicKeyBase64: String): String? = runCatching {
        val der = Base64.getDecoder().decode(publicKeyBase64.filterNot { it.isWhitespace() })
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        Base64.getEncoder().encodeToString(cipher.doFinal(plain))
    }.getOrNull()

    /** 随机小写十六进制串，长度由调用方按 SECRET_MIN_LENGTH..SECRET_MAX_LENGTH 决定。 */
    fun randomToken(random: Random, length: Int): String = buildString(length) {
        repeat(length) { append(HEX[random.nextInt(HEX.length)]) }
    }

    /** 形如 8-4-4-4-12 的请求号，版本位固定 4，变体位落在 8..b。 */
    fun randomRequestId(random: Random): String = buildString(UUID_TEMPLATE.length) {
        for (ch in UUID_TEMPLATE) {
            when (ch) {
                'x' -> append(HEX[random.nextInt(HEX.length)])
                'y' -> append(HEX[8 + random.nextInt(4)])
                else -> append(ch)
            }
        }
    }

    /**
     * 表单拼串。参考实现按 map 的遍历顺序拼，服务端验签时用的是解密出来的
     * 那一份原文，所以顺序只要前后一致即可，这里固定成调用方给的顺序。
     */
    fun paramString(params: List<Pair<String, String>>): String =
        params.joinToString("&") { (name, value) -> name + "=" + value }

    /** 签名串：SessionKey / Operate / RequestURI / Date / params 五段，顺序不能变。 */
    fun signature(
        sessionKey: String,
        uri: String,
        date: String,
        paramsHex: String,
        secret: String,
    ): String = hmacSha1Hex(
        "SessionKey=" + sessionKey +
            "&Operate=GET&RequestURI=" + uri +
            "&Date=" + date +
            "&params=" + paramsHex,
        secret,
    )

    /**
     * 与 Go 的 url.QueryEscape 对齐：空格变加号，其余非 unreserved 字符按 UTF-8 百分号编码。
     *
     * 文件名要在这里编码一次，因为它会被塞进待加密的表单串里，
     * 服务端解密后自己再解一次码。
     */
    fun queryEscape(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            val ch = code.toChar()
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' -> append(ch)
                ch == '-' || ch == '_' || ch == '.' || ch == '~' -> append(ch)
                ch == ' ' -> append('+')
                else -> {
                    append('%')
                    append(HEX[code shr 4].uppercaseChar())
                    append(HEX[code and 0x0F].uppercaseChar())
                }
            }
        }
    }

    /**
     * 百分号解码，但不像 URLDecoder 那样把加号当空格。
     *
     * 分片上传头里的 Content-MD5 之类的值是 Base64，加号是合法字符，
     * 用 URLDecoder 会把它吃掉。
     */
    fun percentDecode(value: String): String {
        if (value.indexOf('%') < 0) return value
        val out = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch == '%' && index + 2 < value.length) {
                val code = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (code != null) {
                    out.write(code)
                    index += 3
                    continue
                }
            }
            out.write(ch.toString().toByteArray(Charsets.UTF_8))
            index++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    fun md5Bytes(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)
}

/** 小写十六进制。上传接口里所有摘要都以小写十六进制出现（分片摘要除外）。 */
internal fun ByteArray.toLowerHex(): String {
    val chars = CharArray(size * 2)
    for (i in indices) {
        val value = this[i].toInt() and 0xFF
        chars[i * 2] = HEX_DIGITS[value ushr 4]
        chars[i * 2 + 1] = HEX_DIGITS[value and 0x0F]
    }
    return String(chars)
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()
