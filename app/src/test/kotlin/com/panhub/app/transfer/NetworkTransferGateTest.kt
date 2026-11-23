package com.panhub.app.transfer

import com.panhub.core.common.PanError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「仅 Wi-Fi 传输」的判断本身。
 *
 * 这条规则很短，但写错的后果是用户以为在传、实际一点没动，所以三种组合都钉住。
 */
class NetworkTransferGateTest {

    private fun gate(wifiOnly: Boolean, onWifi: Boolean) =
        NetworkTransferGate(wifiOnly = { wifiOnly }, onWifi = { onWifi })

    @Test
    fun switchOffLetsAnythingThrough() {
        assertNull(gate(wifiOnly = false, onWifi = false).blockedReason())
        assertNull(gate(wifiOnly = false, onWifi = true).blockedReason())
    }

    @Test
    fun switchOnAndWifiLetsItThrough() {
        assertNull(gate(wifiOnly = true, onWifi = true).blockedReason())
    }

    @Test
    fun switchOnAndMobileDataBlocks() {
        val reason = gate(wifiOnly = true, onWifi = false).blockedReason()

        assertEquals(PanError.network(NetworkTransferGate.BLOCKED_MESSAGE), reason)
    }

    @Test
    fun blockedMessageNamesTheSwitchNotTheNetwork() {
        // 只说「网络不可用」会让人去检查信号，而真正的原因是他自己勾了这个开关。
        assertEquals(
            "已开启「仅 Wi-Fi 传输」，连上 Wi-Fi 后会自己接着传",
            NetworkTransferGate.BLOCKED_MESSAGE,
        )
    }

    @Test
    fun theSwitchIsReadEveryTimeNotCached() {
        var enabled = false
        val gate = NetworkTransferGate(wifiOnly = { enabled }, onWifi = { false })

        assertNull(gate.blockedReason())
        enabled = true
        assertEquals(PanError.network(NetworkTransferGate.BLOCKED_MESSAGE), gate.blockedReason())
    }
}
