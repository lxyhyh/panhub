package com.panhub.provider.api

import java.io.File
import java.io.InputStream

/**
 * 一个待上传的数据源。
 *
 * 为什么用接口而不是直接传 [File]：以后从相册或内容提供者上传时，
 * 拿到的是 Uri 而不是 File，只要换一个实现，provider 层一行都不用改。
 *
 * [size] 必须准确：多数网盘的分片协议要求预先声明总长度，
 * 声明错了会被服务端拒绝或者截断。
 */
interface UploadSource {
    /** 保存到网盘后的文件名（不含路径）。 */
    val name: String

    /** 字节数。 */
    val size: Long

    /** MIME 类型，未知时为 null。 */
    val mimeType: String?
        get() = null

    /** 每次调用都要返回一个从头开始的新流；实现方负责关闭。 */
    fun openStream(): InputStream
}

/** 磁盘上的文件。 */
class FileUploadSource(
    private val file: File,
    override val name: String = file.name,
    override val mimeType: String? = null,
) : UploadSource {
    override val size: Long get() = file.length()

    override fun openStream(): InputStream = file.inputStream()
}

/** 内存里的字节。只给测试与小文件用，大文件会把内存打满。 */
class BytesUploadSource(
    private val bytes: ByteArray,
    override val name: String,
    override val mimeType: String? = null,
) : UploadSource {
    override val size: Long get() = bytes.size.toLong()

    override fun openStream(): InputStream = bytes.inputStream()
}
