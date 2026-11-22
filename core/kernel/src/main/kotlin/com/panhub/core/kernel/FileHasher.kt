package com.panhub.core.kernel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 上层唯一需要知道的哈希能力。
 *
 * 为什么会有一个接口 + 一个默认实现：Rust 内核在单元测试、无 NDK 的机器上
 * 可能加载不成功，这时必须能退回 Kotlin 实现；上层代码不该关心用的是哪个。
 */
interface FileHasher {

    /** 计算整个文件的全部摘要。大文件是流式读取，不会整块进内存。 */
    suspend fun hashFile(file: File): FileHashes

    /** 计算内存中一段字节的摘要。 */
    suspend fun hashBytes(bytes: ByteArray): FileHashes

    /** 只算某个分片的 MD5（分片上传用）。 */
    suspend fun sliceMd5(file: File, offset: Long, length: Long): String

    companion object {
        /** 优先用 Rust 内核，不可用时退回纯 Kotlin。 */
        fun create(): FileHasher =
            if (NativeKernel.available) RustFileHasher else KotlinFileHasher
    }
}

/**
 * Rust 实现：一次读盘算出四种摘要，几十兆的文件在手机上几乎无感。
 *
 * JSON 解析放在这里而不是 NativeKernel，是为了让 JNI 层保持"只传字符串"，
 * 这样以后换编码方式不用动 FFI 签名。
 */
internal object RustFileHasher : FileHasher {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun hashFile(file: File): FileHashes =
        decode(NativeKernel.hashFile(file.absolutePath), file.length())

    override suspend fun hashBytes(bytes: ByteArray): FileHashes =
        decode(NativeKernel.hashBytes(bytes), bytes.size.toLong())

    override suspend fun sliceMd5(file: File, offset: Long, length: Long): String =
        NativeKernel.sliceMd5(file.absolutePath, offset, length)

    private fun decode(raw: String, size: Long): FileHashes {
        val obj = json.parseToJsonElement(raw).jsonObject
        return FileHashes(
            md5 = obj.getValue("md5").jsonPrimitive.content,
            sha1 = obj.getValue("sha1").jsonPrimitive.content,
            sha256 = obj.getValue("sha256").jsonPrimitive.content,
            crc32 = obj.getValue("crc32").jsonPrimitive.content.toLong(),
            size = size,
        )
    }
}

/**
 * 纯 Kotlin 兜底实现。
 *
 * 只保证正确性，不追求速度：它会把整个文件读进内存，因此只该在
 * 内核不可用（例如没有 NDK 产物的调试构建）时跑到小文件上。
 */
internal object KotlinFileHasher : FileHasher {

    override suspend fun hashFile(file: File): FileHashes = hashBytes(file.readBytes())

    override suspend fun hashBytes(bytes: ByteArray): FileHashes {
        val md5 = digest("MD5", bytes)
        val sha1 = digest("SHA-1", bytes)
        val sha256 = digest("SHA-256", bytes)
        val crc = java.util.zip.CRC32().apply { update(bytes) }.value
        return FileHashes(md5 = md5, sha1 = sha1, sha256 = sha256, crc32 = crc, size = bytes.size.toLong())
    }

    override suspend fun sliceMd5(file: File, offset: Long, length: Long): String {
        val raf = java.io.RandomAccessFile(file, "r")
        return raf.use {
            it.seek(offset)
            val buf = ByteArray(length.toInt())
            it.readFully(buf)
            digest("MD5", buf)
        }
    }

    private fun digest(algorithm: String, bytes: ByteArray): String =
        java.security.MessageDigest.getInstance(algorithm)
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
