package com.panhub.core.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.panhub.core.storage.SecretCipher
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 直接用 Keystore 里不可导出的密钥做 AES/GCM。
 *
 * 为什么不复用 [com.panhub.core.storage.AesGcmCipher]：那个类通过
 * [com.panhub.core.storage.SecretKeySource.keyBytes] 拿密钥字节，
 * 而 Keystore 里的密钥设计上就不允许导出（getEncoded() 返回 null）。
 * 唯一可行的路径是拿到 [SecretKey] 句柄直接给 Cipher 用。
 *
 * 密文格式与 core:storage 的实现保持一致：Base64(IV || 密文 || tag)，
 * 这样以后换实现不会让老数据读不出来。
 */
class KeystoreCipher(
    private val alias: String = DEFAULT_ALIAS,
) : SecretCipher {

    override fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
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
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun base64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun unBase64(text: String): ByteArray =
        android.util.Base64.decode(text, android.util.Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "panhub.account.cipher"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
