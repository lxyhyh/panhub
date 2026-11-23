package com.panhub.app

import android.app.Application
import com.panhub.app.di.PanHubContainer
import com.panhub.app.transfer.TransferNotifications
import com.panhub.app.transfer.TransferServiceBridge

class PanHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 传输通知渠道在进程启动时建好：等到第一次传输再建，
        // 系统可能把「刚创建渠道又立刻发通知」当成骚扰而静默掉。
        TransferNotifications.ensureChannel(this)
        // 队列一有活就起前台服务，退到后台也继续传。
        TransferServiceBridge.attach(this, PanHubContainer.transferEngine(this))
    }
}
