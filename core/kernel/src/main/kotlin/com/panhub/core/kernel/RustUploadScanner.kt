package com.panhub.core.kernel

import com.panhub.core.common.UploadScan
import com.panhub.core.common.UploadScanner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 用 Rust 内核做上传摘要扫描。
 *
 * 为什么值得单独一条路：上传前的摘要计算是纯 IO 密集的活，几个 GB 的文件
 * 在 ART 上要跑十几秒，Rust 侧同样的流式扫描能快一个量级，而且内核只读一遍盘
 * 就能同时给出整文件 MD5、SHA-1、前缀 MD5 和逐分片 MD5。
 *
 * 内核不可用时 [scan] 返回 null，调用方（provider）会退回自己的 Kotlin 实现，
 * 所以这个类不需要任何降级逻辑。
 */
internal object RustUploadScanner : UploadScanner {

    private val json = Json { ignoreUnknownKeys = true }

    override fun scan(file: File, sliceSize: Long, prefixLimit: Long): UploadScan? {
        if (!NativeKernel.available) return null
        val raw = runCatching {
            NativeKernel.scanFile(file.absolutePath, sliceSize, prefixLimit)
        }.getOrNull() ?: return null
        return runCatching { decode(raw) }.getOrNull()
    }

    private fun decode(raw: String): UploadScan {
        val obj = json.parseToJsonElement(raw).jsonObject
        val slices: JsonArray = obj.getValue("sliceMd5s").jsonArray
        return UploadScan(
            md5 = obj.getValue("md5").jsonPrimitive.content,
            sha1 = obj.getValue("sha1").jsonPrimitive.content,
            size = obj.getValue("size").jsonPrimitive.content.toLong(),
            prefixMd5 = obj.getValue("prefixMd5").jsonPrimitive.content,
            sliceMd5s = slices.map { it.jsonPrimitive.content },
        )
    }

    /** 供设置页显示：内核到底有没有生效。 */
    fun available(): Boolean = NativeKernel.available
}
