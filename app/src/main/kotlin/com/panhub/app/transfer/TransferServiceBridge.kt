package com.panhub.app.transfer

import android.content.Context
import com.panhub.core.transfer.TransferDigest
import com.panhub.core.transfer.TransferEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 把队列状态接到前台服务上。
 *
 * 为什么放在 app 而不是 core:transfer：core:transfer 是纯 JVM 模块，
 * 里面连 android.content 都编不过；「什么时候该起前台服务」是平台决定，
 * 属于装配层的事。
 *
 * 只在「从没有进行中任务变成有」的那一刻启动服务，之后由服务自己订阅
 * 队列刷新通知、队列空了就停。这样不会出现每次进度变化都去 startService
 * 的浪费，也不会在服务已经停了之后还留着过期的「已启动」印象。
 */
internal object TransferServiceBridge {

    @Volatile
    private var attached = false

    fun attach(context: Context, engine: TransferEngine) {
        if (attached) return
        attached = true
        val app = context.applicationContext ?: context
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            engine.tasks
                .map { TransferDigest.of(it).hasActive }
                .distinctUntilChanged()
                .collect { active ->
                    if (active) {
                        TransferService.start(app)
                    } else {
                        // 队列空了：服务自己会停，这里再兜一次，
                        // 免得用户手动清空队列时服务还在后台多待一会儿。
                        TransferService.stop(app)
                    }
                }
        }
    }
}
