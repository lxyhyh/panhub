package com.panhub.core.kernel

/** 对外暴露内核是否可用、版本多少，用于"关于"页和排障。 */
object KernelInfo {

    /** 当前是否在用 Rust 内核。没有 NDK 产物时会是 false。 */
    val usingRust: Boolean get() = NativeKernel.available

    /** 内核版本；不可用时返回 null。 */
    val version: String? get() = if (NativeKernel.available) NativeKernel.kernelVersion() else null

    /** 一行人类可读的描述。 */
    fun summary(): String = if (usingRust) "Rust 内核 ${version}" else "纯 Kotlin 兜底"
}
