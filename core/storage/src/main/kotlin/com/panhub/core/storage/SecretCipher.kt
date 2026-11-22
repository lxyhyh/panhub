package com.panhub.core.storage

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 账号密钥的加解密。
 *
 * 为什么需要它：账号记录里存的是 token 或应用密码，明文落盘等于把账号
 * 送给任何能读到应用私有目录的人（root、adb backup、恶意备份工具）。
 *
 * 密钥从哪来由 [SecretKeySource] 决定：Android 上应该用 Keystore 生成的
 * 不可导出密钥，单元测试里用固定密钥。这一层不关心，只做 AES-GCM。
 */
interface SecretCipher {
    fun encrypt(plain: String): String
    fun decrypt(encoded: String): String?
}

/** 提供一个 16/24/32 字节的 AES 密钥。 */
fun interface SecretKeySource {
    fun keyBytes(): ByteArray
}

/**
 * AES/GCM/NoPadding 实现。
 *
 * 每次加密用新的随机 IV，把 IV 和密文一起 Base64 存下来（IV 不是秘密）。
 * GCM 自带完整性校验，密文被改动会解密失败，返回 null 而不是抛异常。
 */
class AesGcmCipher(private val keySource: SecretKeySource) : SecretCipher {

    override fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return base64(iv + encrypted)
    }

    override fun decrypt(encoded: String): String? {
        if (encoded.isEmpty()) return ""
        return runCatching {
            val raw = unBase64(encoded)
            if (raw.size <= IV_LENGTH) return null
            val iv = raw.copyOfRange(0, IV_LENGTH)
            val cipherText = raw.copyOfRange(IV_LENGTH, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keySpec(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun keySpec() = SecretKeySpec(keySource.keyBytes(), "AES")

    private fun base64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)

    private fun unBase64(text: String): ByteArray =
        java.util.Base64.getDecoder().decode(text)

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}

/**
 * 不加密的实现，**只给测试用**。
 *
 * 名字故意起得刺眼，避免有人顺手把它接到正式代码里。
 */
object InsecurePlainCipher : SecretCipher {
    override fun encrypt(plain: String): String = "plain:$plain"
    override fun decrypt(encoded: String): String? = encoded.removePrefix("plain:").takeIf { encoded.startsWith("plain:") }
}
