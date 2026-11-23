package com.panhub.feature.transfers

import com.panhub.core.design.formatBytes
import com.panhub.core.transfer.TransferDirection
import com.panhub.core.transfer.TransferEstimate
import com.panhub.core.transfer.TransferState
import com.panhub.core.transfer.TransferTask

/**
 * 传输卡片上的速率文案。
 *
 * 单独拎出来是为了能测：这里全是「什么情况下该显示什么」的判断，
 * 和排版无关，却最容易写错（比如把排队中的任务也标上 0 B/s）。
 */
object TransferStatusText {

    /**
     * 运行中的速率与剩余时间；不该显示时返回空串。
     *
     * 只给「正在跑」的任务加：排队中的任务没有速度可言，结束的任务速度是历史数据，
     * 挂在那里只会让已经传完的一行显得还没完。
     *
     * 速率还没测出来（刚起步、只有一次采样）时什么都不显示——
     * 写「0 B/s」会让人以为卡住了，而它其实正在飞快地传。
     */
    fun speedNote(task: TransferTask, speed: Long?): String {
        if (task.state != TransferState.RUNNING) return ""
        if (speed == null) return ""
        // 整理型任务不传字节，它们的「速率」是列目录的速度，说出来没意义。
        if (task.direction == TransferDirection.DOWNLOAD_FOLDER ||
            task.direction == TransferDirection.CLOUD_COPY_FOLDER ||
            task.direction == TransferDirection.UPLOAD_FOLDER
        ) {
            return ""
        }
        val head = " · " + formatBytes(speed) + "/s"
        val remaining = TransferEstimate.remainingMillis(task, speed) ?: return head
        return head + " · 还剩 " + TransferEstimate.describe(remaining)
    }
}
