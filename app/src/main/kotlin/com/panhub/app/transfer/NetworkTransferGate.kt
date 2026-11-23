package com.panhub.app.transfer

import com.panhub.core.common.PanError
import com.panhub.core.transfer.TransferGate

/**
 * 「仅 Wi-Fi 传输」的实现。
 *
 * 判断只做两件事：用户开没开这个开关、当前是不是 Wi-Fi。
 * 网络类型的判断从外面传进来（[onWifi]），这样这一层是纯 Kotlin，
 * 可以在单元测试里把「Wi-Fi / 移动网络」两种情形都摆出来。
 */
class NetworkTransferGate(
    private val wifiOnly: () -> Boolean,
    private val onWifi: () -> Boolean,
) : TransferGate {

    override fun blockedReason(): PanError? {
        if (!wifiOnly()) return null
        if (onWifi()) return null
        return PanError.network(BLOCKED_MESSAGE)
    }

    companion object {
        /**
         * 挡住的文案。
         *
         * 写明是哪个开关造成的：只说「网络不可用」会让人去检查信号，
         * 而真正的原因是他自己在设置里勾了这个。
         */
        const val BLOCKED_MESSAGE = "已开启「仅 Wi-Fi 传输」，连上 Wi-Fi 后会自己接着传"
    }
}
