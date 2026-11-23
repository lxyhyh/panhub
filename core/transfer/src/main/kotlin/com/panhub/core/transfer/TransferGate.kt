package com.panhub.core.transfer

import com.panhub.core.common.PanError

/**
 * 现在能不能开始新的传输。
 *
 * 抽成接口而不是让引擎直接问「是不是 Wi-Fi」：判断网络类型是 Android 的事，
 * 传输队列是纯 JVM 模块，让它认识 ConnectivityManager 就等于把一个纯逻辑
 * 模块拖进平台层，测试也得跟着起 Robolectric。
 */
fun interface TransferGate {

    /**
     * 返回 null 表示可以开始；返回错误表示此刻不宜开始，错误文案会显示在传输页。
     *
     * 只挡「开始」，不打断已经在跑的：跑到一半从 Wi-Fi 掉到移动网络时，
     * 硬砍正在传的文件会留下半截残片，而且用户往往是刚刚才切换网络。
     */
    fun blockedReason(): PanError?

    companion object {
        /** 不看网络，永远放行。测试与不需要门控的调用方用。 */
        val AlwaysOpen: TransferGate = TransferGate { null }
    }
}
