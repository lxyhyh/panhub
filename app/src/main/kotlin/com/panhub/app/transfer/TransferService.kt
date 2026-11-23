package com.panhub.app.transfer

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.panhub.app.di.PanHubContainer
import com.panhub.core.transfer.TransferDigest
import com.panhub.core.transfer.TransferEngine
import com.panhub.core.transfer.TransferRunTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 让传输在退到后台后继续跑。
 *
 * 为什么需要它：手机上的后台进程随时可能被系统回收，几 GB 的下载传到一半被杀掉，
 * 用户回来看到的是「队列还在，但一动不动」。前台服务把进程优先级提到「用户可见」，
 * 只要通知栏那条进度还在，系统就不会随手把它清掉。
 *
 * 只在有进行中的任务时启动，队列一空就停：常驻通知不是目的，
 * 让用户知道「还在传」才是。
 */
class TransferService : Service() {

    private var scope: CoroutineScope? = null
    private var watching: Job? = null

    /** 上一次采样时队列里还有没有活，用来判断「这一轮刚结束」。 */
    private val runs = TransferRunTracker()

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TransferNotifications.ensureChannel(this)
        // Android 14 起必须显式声明前台服务类型，否则 startForeground 会抛
        // MissingForegroundServiceTypeException。dataSync 正是「本地与云端传数据」。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                TransferNotifications.NOTIFICATION_ID,
                placeholderNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(TransferNotifications.NOTIFICATION_ID, placeholderNotification())
        }
        watch(PanHubContainer.transferEngine(this))
        return START_STICKY
    }

    /**
     * 订阅队列并持续刷新通知。
     *
     * 用 collect 而不是只读一次：进度是连续变化的，通知要跟着走；
     * 队列空了就撤通知并停掉服务，不在后台留一个空壳。
     */
    private fun watch(engine: TransferEngine) {
        if (watching?.isActive == true) return
        val manager = getSystemService(NotificationManager::class.java)
        watching = scope?.launch {
            engine.tasks.collect { tasks ->
                val digest = TransferDigest.of(tasks)
                // 队列从「有活」跳到「没活」的那一刻就是一轮结束，给用户一条成绩单。
                // 放在停服务之前发：停了之后这个服务可能要过一会儿才被真正销毁，
                // 但通知必须马上出现。
                runs.sample(tasks)?.let { report ->
                    TransferNotifications.notifyFinished(this@TransferService, report)
                }
                if (!digest.hasActive) {
                    // 全部结束：撤掉常驻通知，然后停服务。
                    // 先 stopSelf 再 cancel 通知，避免系统在停服务时把通知也一并清掉后
                    // 我们又补发一条。
                    stopSelf()
                    return@collect
                }
                val notification = TransferNotifications.build(this@TransferService, digest)
                if (notification != null) {
                    manager?.notify(TransferNotifications.NOTIFICATION_ID, notification)
                }
            }
        }
    }

    override fun onDestroy() {
        watching = null
        scope?.cancel()
        scope = null
        TransferNotifications.cancel(this)
        super.onDestroy()
    }

    /** 服务刚起来时还没有读到队列，先给一条「正在准备」占位通知。 */
    private fun placeholderNotification() =
        TransferNotifications.build(this, TransferDigest(active = 1, queued = 1))
            ?: error("占位通知不应为空")

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /**
         * 有任务入队时调用；已经在前台就不重复启动。
         *
         * Android 12 起后台不能启动前台服务，系统会抛
         * ForegroundServiceStartNotAllowedException。队列恢复恰好发生在
         * 冷启动那一瞬间，来不及判断当前是否可见，所以这里兜住异常：
         * 起不来顶多是退到后台可能被回收，不该让 App 直接崩掉。
         */
        fun start(context: Context) {
            val intent = Intent(context, TransferService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TransferService::class.java)) }
        }
    }
}
