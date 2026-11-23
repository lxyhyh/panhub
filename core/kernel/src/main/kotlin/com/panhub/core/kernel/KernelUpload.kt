package com.panhub.core.kernel

import com.panhub.core.common.UploadScanner

/**
 * 内核提供的能力入口。
 *
 * 上层只认 [UploadScanner] 这个接口：内核编出来时拿到的是一条走 Rust 的
 * 快路，没编出来（本机调试、没有 NDK 产物的构建）时拿到的是一个什么都
 * 不做的空实现，调用方自然退回自己的 Kotlin 实现。
 */
object KernelUpload {

    /** 默认扫描器：有内核走内核，没有就返回 [UploadScanner.None]。 */
    fun scanner(): UploadScanner = if (NativeKernel.available) RustUploadScanner else UploadScanner.None
}
