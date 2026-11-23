package com.panhub.app.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.panhub.core.transfer.TransferDigest
import com.panhub.core.transfer.TransferReport
import com.panhub.core.transfer.TransferDirection
import com.panhub.core.transfer.TransferState
import com.panhub.core.transfer.TransferTask
import com.panhub.core.design.formatBytes

/**
 * 传输通知。
 *
 * 只有一条常驻通知：它跟着 [TransferDigest] 走，说的永远是「现在在传什么、
 * 还剩多少」。不给每个任务单独发通知——手机上并排十几条进度通知既吵又没用，
 * 用户真正需要的是「还在跑」和「跑完了」两件事。
 *
 * 文案与传输页标题用的是同一份汇总，避免通知说「正在下载」而列表其实已经空了。
 */
internal object TransferNotifications {

    const val CHANNEL_ID = "panhub_transfers"

    /** 常驻通知的固定 id：同一条通知更新，不会在通知栏堆叠。 */
    const val NOTIFICATION_ID = 4201

    /**
     * 结束通知的 id。
     *
     * 与常驻那条分开：常驻通知在队列空了之后会被撤掉，而「这一轮传完了」
     * 需要留在通知栏里等用户点掉，两者生命周期不同。
     */
    const val FINISHED_NOTIFICATION_ID = 4202

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "传输",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "上传、下载与跨网盘转存的进度"
            // 进度类通知不该响铃震动，IMPORTANCE_LOW 已经压住声音，
            // 这里再明确关掉角标，免得桌面图标上一直挂一个数字。
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** 有进行中的任务时构建进度通知；没有则返回 null。 */
    fun build(context: Context, digest: TransferDigest): Notification? {
        if (!digest.hasActive) return null
        val running = digest.running
        val title = titleOf(digest)
        val text = running?.let { describe(it) } ?: "等待开始"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context))

        val percent = digest.percent
        if (percent != null) {
            builder.setProgress(100, percent, false)
        } else {
            // 大小未知时用不确定进度条，比显示 0% 诚实。
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    /** 队列清空后要显式撤掉通知，否则它会一直挂在通知栏。 */
    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** 标题与传输页保持一致：有百分比就用「传输 42%（3）」。 */
    fun titleOf(digest: TransferDigest): String {
        val percent = digest.percent
        return when {
            percent != null && digest.active > 1 -> "传输 " + percent + "%（" + digest.active + "）"
            percent != null -> "传输 " + percent + "%"
            digest.active > 1 -> "传输（" + digest.active + "）"
            else -> "传输"
        }
    }

    /**
     * 一行正文：任务名加方向，带字节数。
     *
     * 名字可能很长（尤其是整目录里的深层文件），超过 40 个字符就截断，
     * 通知栏一行放不下，截断比让系统随机切掉一半更可读。
     */
    fun describe(task: TransferTask): String {
        val action = when (task.direction) {
            TransferDirection.DOWNLOAD -> "下载"
            TransferDirection.UPLOAD -> "上传"
            TransferDirection.CLOUD_COPY -> "转存"
            TransferDirection.DOWNLOAD_FOLDER -> "整理文件"
            TransferDirection.CLOUD_COPY_FOLDER -> "整理文件"
            TransferDirection.UPLOAD_FOLDER -> "整理文件"
        }
        // 跨模块的属性不能智能转换，先取到局部变量再判空。
        val total = task.totalBytes
        val size = when {
            total != null && total > 0L ->
                formatBytes(task.bytes) + " / " + formatBytes(total)
            task.state == TransferState.RUNNING -> formatBytes(task.bytes)
            else -> ""
        }
        val head = action + "：" + shorten(task.name)
        return if (size.isEmpty()) head else head + " · " + size
    }

    private fun shorten(name: String): String =
        if (name.length <= NAME_LIMIT) name else name.take(NAME_LIMIT - 1) + "…"

    private fun openApp(context: Context): PendingIntent? {
        // 通知点开回到 App 本身，不深链到传输页：启动参数的组合会随导航变化，
        // 而「回到 App」这件事永远是用户想要的。
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 一轮传输结束时的通知。
     *
     * 为什么要发：用户把 App 退到后台之后，唯一的反馈就是这个通知栏。
     * 不告诉他一轮已经结束，他只能反复切回来确认——那正是前台服务想避免的事。
     *
     * 只有一件事是真的失败时标题就直接说失败，别让用户点进来才发现；
     * 字节数只算成功的那些，失败的任务中途停下的部分不是成绩。
     */
    fun buildFinished(context: Context, report: TransferReport): Notification? {
        if (report.isEmpty) return null
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(finishedTitle(report))
            .setContentText(finishedText(report))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context))
            .build()
    }

    /**
     * 结束通知的标题。
     *
     * 全都成功时说「完成」，全都失败时说「失败」——中间那种最含糊的情况
     * 才把失败数写进标题，因为那才是用户需要立刻知道的事。
     */
    fun finishedTitle(report: TransferReport): String = when {
        report.failed == 0 -> "传输完成"
        report.completed == 0 -> "传输失败"
        else -> "传输结束，有 " + report.failed + " 项失败"
    }

    /**
     * 结束通知的正文：完成几项、失败几项、一共搬了多少。
     *
     * 字节数只算成功的：失败的任务中途停下的部分不是成绩，
     * 把它算进去会让「失败」那条通知显得像传完了。
     */
    fun finishedText(report: TransferReport): String {
        val parts = mutableListOf<String>()
        if (report.completed > 0) parts += report.completed.toString() + " 项完成"
        if (report.failed > 0) parts += report.failed.toString() + " 项失败"
        if (report.bytes > 0L) parts += formatBytes(report.bytes)
        return parts.joinToString(" · ")
    }

    /** 发出结束通知；没有成绩时什么都不做。 */
    fun notifyFinished(context: Context, report: TransferReport) {
        val notification = buildFinished(context, report) ?: return
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(FINISHED_NOTIFICATION_ID, notification)
        }
    }

    /** 通知栏一行的实际可用宽度大约这么多字符。 */
    private const val NAME_LIMIT = 40
}
