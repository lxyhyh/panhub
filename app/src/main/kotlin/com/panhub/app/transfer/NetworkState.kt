package com.panhub.app.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.panhub.core.transfer.TransferEngine

/** 现在是不是 Wi-Fi（有线也算，它同样不计流量）。 */
internal fun isOnWifi(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val active = manager.activeNetwork ?: return false
    val caps = manager.getNetworkCapabilities(active) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}

/**
 * 网络一变就让队列重新看一眼能不能走。
 *
 * 没有这一步，「仅 Wi-Fi」关上的门会一直关着：门只在 [TransferEngine.pump]
 * 里检查，而 pump 只在有任务变化时才被调用，网络恢复不属于任务变化。
 */
object TransferGateBridge {

    fun attach(context: Context, engine: TransferEngine) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        engine.reevaluateGate()
                    }

                    override fun onLost(network: Network) {
                        // 断网时不必做什么：已经开跑的任务自己会失败并进入退避，
                        // 这里的门只在「开始新的」那一刻才有意义。
                    }
                },
            )
        }
    }
}
