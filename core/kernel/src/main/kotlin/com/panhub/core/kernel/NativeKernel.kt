package com.panhub.core.kernel

/**
 * 与 Rust 内核的**唯一**衔接点。
 *
 * 约定：
 *  - 这里的每个 external 声明都对应 core/kernel/src/jni_bridge.rs 里的一个
 *    `Java_com_panhub_core_kernel_NativeKernel_<name>` 函数，两边必须同名同参；
 *  - 除了这个对象，没有任何 Kotlin 代码直接接触 JNI；
 *  - 库加载失败不抛异常，而是让 [available] 为 false，上层退回纯 Kotlin 实现。
 *
 * 之所以要"能退回"：CI 或单元测试环境不一定有 NDK 交叉编译产物，
 * 上层不应该因为少了一个 .so 就整个崩掉。
 */
internal object NativeKernel {

    /** .so 是否加载成功。第一次访问时求值一次，之后复用。 */
    val available: Boolean by lazy { runCatching { System.loadLibrary("panhub_kernel") }.isSuccess }

    /** 描述某个文件或一段字节的全套摘要，返回一行 JSON。 */
    external fun hashFile(path: String): String

    external fun hashBytes(data: ByteArray): String

    /** 对文件 [offset] 起 [length] 字节算 MD5，用于分片上传。 */
    external fun sliceMd5(path: String, offset: Long, length: Long): String

    /**
     * 一遍读盘算出上传需要的全部摘要，返回一行 JSON：
     * {"md5","sha1","size","prefixMd5","sliceMd5s":[...]}。
     *
     * [sliceSize] 传 0 表示不算分片，[prefixLimit] 传 0 表示不算前缀摘要。
     */
    external fun scanFile(path: String, sliceSize: Long, prefixLimit: Long): String

    external fun kernelVersion(): String
}
